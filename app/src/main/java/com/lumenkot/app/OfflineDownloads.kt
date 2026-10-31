package com.lumenkot.app

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One process-wide cache shared by HlsDownloader and offline playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object OfflineDownloads {
    private var instance: DownloadManager? = null
    private lateinit var cache: SimpleCache
    private lateinit var database: StandaloneDatabaseProvider
    private val executor = Executors.newFixedThreadPool(3)
    @Synchronized fun manager(context: Context): DownloadManager {
        instance?.let { return it }
        val app=context.applicationContext
        database=StandaloneDatabaseProvider(app)
        cache=SimpleCache(File(app.filesDir,"offline-hls"),NoOpCacheEvictor(),database)
        val sources=source(app,false)
        val factory=DownloaderFactory { request ->
            // Use the standard segment downloader, never save only the m3u8 playlist.
            if(request.mimeType==MimeTypes.APPLICATION_M3U8) HlsDownloader(request.toMediaItem(),sources,executor)
            else ProgressiveDownloader(request.toMediaItem(),sources,executor)
        }
        return DownloadManager(app,DefaultDownloadIndex(database),factory).also {
            it.maxParallelDownloads=2
            it.requirements=Requirements(Requirements.NETWORK)
            it.resumeDownloads()
            it.addListener(object: DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager,download: Download,exception: Exception?) {
                    if(download.state==Download.STATE_COMPLETED && JSONObject(download.request.data.toString(Charsets.UTF_8)).optString("tree").isNotBlank())
                        DownloadExportWorker.schedule(app,download.request.id)
                }
            })
            instance=it
        }
    }
    private fun source(context: Context,offline: Boolean): CacheDataSource.Factory {
        val api=RezkaApi(Settings(context))
        val factory=CacheDataSource.Factory().setCache(cache)
        if(offline) factory.setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        else factory.setUpstreamDataSourceFactory(OkHttpDataSource.Factory(api.client).setDefaultRequestProperties(api.headers()))
        return factory
    }
    fun playback(context: Context): CacheDataSource.Factory {
        manager(context)
        return source(context,true)
    }
    /** Called on an IO thread; URLs and track selections are preserved in the durable request. */
    fun request(context: Context,metadata: JSONObject): DownloadRequest {
        val url=metadata.getString("url")
        val hls=metadata.getString("format")=="HLS"
        val builder=DownloadRequest.Builder(java.util.UUID.randomUUID().toString(),Uri.parse(url))
            .setMimeType(if(hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .setData(metadata.toString().toByteArray())
        if(hls && !metadata.optBoolean("includeSubtitles",true)) {
            val api=RezkaApi(Settings(context))
            val request=okhttp3.Request.Builder().url(url);api.headers().forEach { (k,v) -> request.header(k,v) }
            api.client.newCall(request.build()).execute().use { response ->
                check(response.isSuccessful) { "HLS: HTTP ${response.code}" }
                val playlist=androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(Uri.parse(response.request.url.toString()),response.body!!.byteStream())
                if(playlist is androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist) {
                    builder.setStreamKeys(playlist.variants.indices.map { androidx.media3.common.StreamKey(0,it) }+
                        playlist.audios.indices.map { androidx.media3.common.StreamKey(1,it) })
                }
            }
        }
        return builder.build()
    }
    fun enqueue(context: Context,request: DownloadRequest) = DownloadService.sendAddDownload(context,HlsDownloadService::class.java,request,true)
    fun all(context: Context): List<Download> {
        val result=mutableListOf<Download>()
        manager(context).downloadIndex.getDownloads().use { cursor -> while(cursor.moveToNext()) {
            val download=cursor.download
            result.add(download)
            if(download.state==Download.STATE_COMPLETED && JSONObject(download.request.data.toString(Charsets.UTF_8)).optString("tree").isNotBlank()) {
                val state=DownloadExportWorker.state(context,download.request.id)
                if(!state.optBoolean("deleted") && state.optString("state") in listOf("","saving")) DownloadExportWorker.schedule(context,download.request.id)
            }
        } }
        return result
    }
    fun media(download: Download): JSONObject = JSONObject(download.request.data.toString(Charsets.UTF_8))
        .put("url",download.request.uri.toString()).put("offlineHls",download.request.mimeType==MimeTypes.APPLICATION_M3U8)
        .put("mime",download.request.mimeType)
    fun remove(context: Context,id: String) {
        DownloadExportWorker.cancel(context,id)
        val state=DownloadExportWorker.markDeleted(context,id)
        val folder=state.optString("folder")
        if(folder.isNotBlank()) executor.execute { androidx.documentfile.provider.DocumentFile.fromSingleUri(context,Uri.parse(folder))?.delete() }
        DownloadService.sendRemoveDownload(context,HlsDownloadService::class.java,id,true)
    }
    fun retry(context: Context,download: Download) = DownloadService.sendAddDownload(context,HlsDownloadService::class.java,download.request,true)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsDownloadService : DownloadService(7001,1000,"hls_downloads",R.string.download_channel,0) {
    override fun getDownloadManager(): DownloadManager = OfflineDownloads.manager(this)
    override fun getScheduler(): Scheduler = androidx.media3.exoplayer.scheduler.PlatformScheduler(this,7002)
    override fun getForegroundNotification(downloads: MutableList<Download>,notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this,"hls_downloads").buildProgressNotification(
            this,android.R.drawable.stat_sys_download,null,"Загрузка видео",downloads,notMetRequirements)
    override fun onTimeout(startId: Int,fgsType: Int) {
        downloadManager.pauseDownloads()
        stopSelf()
    }
}
