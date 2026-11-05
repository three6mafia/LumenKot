package com.lumenkot.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Persistent HLS index, non-evicting caches and background downloads; one instance per process. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsDownloads(context: Context,private val executor: Executor=Executors.newFixedThreadPool(3)) {
    companion object {
        const val STOP_REASON_USER=1
        @Volatile private var instance: HlsDownloads?=null
        fun get(context: Context): HlsDownloads=instance ?: synchronized(this) {instance ?: HlsDownloads(context.applicationContext).also {instance=it}}
        internal fun resetForTests() {DownloadService.clearDownloadManagerHelpers();instance?.release();instance=null}
        fun metadata(request: DownloadRequest)=JSONObject(String(request.data,Charsets.UTF_8))
        fun contentId(film: String,voice: String,season: String,episode: String,quality: String,selection: String=""): String {
            val identity=JSONArray(listOf(film,voice,season,episode,quality,selection)).toString()
            return "hls-"+MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        }
        fun shareIntent(url: String)=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,url)
    }
    private val app=context.applicationContext
    private val settings=Settings(app)
    private val api=RezkaApi(settings)
    private val client=api.client.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).build()
    private val database=StandaloneDatabaseProvider(app)
    val index=DefaultDownloadIndex(database,"hls")
    private val caches=mutableMapOf<String,SimpleCache>()
    private var createdManager: DownloadManager?=null
    val manager: DownloadManager get()=createdManager ?: DownloadManager(app,index,DownloaderFactory(::createDownloader)).apply {
        maxParallelDownloads=2
        requirements=Requirements(Requirements.NETWORK)
        addListener(object: DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager,download: Download,finalException: Exception?) {
                if(download.state==Download.STATE_FAILED) settings.prefs.edit().putString("hlsError:${download.request.id}",errorMessage(finalException)).apply()
                else settings.prefs.edit().remove("hlsError:${download.request.id}").apply()
            }
            override fun onDownloadRemoved(manager: DownloadManager,download: Download) {settings.prefs.edit().remove("hlsError:${download.request.id}").apply()}
        })
        createdManager=this
    }

    fun networkFactory(referer: String=""): DataSource.Factory=DataSource.Factory {
        OkHttpDataSource.Factory(client).setDefaultRequestProperties(api.headers()+if(referer.isBlank()) emptyMap() else mapOf("Referer" to referer)).createDataSource()
    }

    /** Runs on IO, before submitting work to the foreground service. */
    fun prepare(film: Film,voice: Voice,season: String,episode: String,stream: Stream,subtitles: List<Subtitle>,tree: Uri?=null,progressive: Boolean=false): DownloadRequest {
        check(stream.available) {PlaybackQuality.premiumMessage(stream.quality)}
        val locations=DownloadLocations(app)
        if(tree!=null) locations.requireAccess(tree)
        val root=locations.appRoot(tree)
        check(root.isDirectory || root.mkdirs()) {"Не удалось открыть папку загрузок"}
        val referer=api.absolute(film.card.url)
        val saveSubtitles=settings.prefs.getBoolean("downloadSubtitles",true)
        val keys=if(progressive) emptyList() else HlsDownloadPlan.prepare(stream.url,stream.quality,saveSubtitles,networkFactory(referer))
        val mime=if(progressive) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
        val id=contentId(film.card.id,voice.id,season,episode,stream.quality,if(tree==null) keys.joinToString() else "$mime|${keys.joinToString()}|$tree")
        val entries=if(saveSubtitles) subtitles.filter {it.url.startsWith("http://") || it.url.startsWith("https://")} else emptyList()
        val image=film.card.image.takeIf {settings.prefs.getBoolean("downloadPoster",true)}.orEmpty()
        val poster=when {image.isBlank()->"";image.startsWith("http")->image;image.startsWith("//")->"https:$image";else->Links.static+"/"+image.trimStart('/')}
        val data=JSONObject().put("title",film.card.title).put("film",film.card.id).put("filmUrl",referer).put("voice",voice.id).put("voiceTitle",voice.title)
            .put("season",season).put("episode",episode).put("quality",stream.quality).put("root",root.absolutePath).put("poster",poster)
            .put("subtitles",JSONArray(entries.map {JSONObject().put("title",it.title).put("url",it.url).put("language",it.language).put("default",it.default)}))
        if(tree!=null) data.put("treeUri",tree.toString()).put("folderName",TreeDownloadPackage.folderName(film.card.title+(if(episode.isNotBlank()) " S${season}E${episode}" else "")+" ${stream.quality}",id)).put("destination",locations.label(tree))
        return DownloadRequest.Builder(id,Uri.parse(stream.url)).setMimeType(mime).setStreamKeys(keys).setData(data.toString().toByteArray(Charsets.UTF_8)).build()
    }

    @Synchronized private fun cache(root: String): SimpleCache {
        val folder=File(root).canonicalFile
        check(app.getExternalFilesDirs("downloads").filterNotNull().any {it.canonicalFile==folder} && folder.isDirectory) {"Накопитель загрузки недоступен"}
        return caches.getOrPut(folder.path) {SimpleCache(File(folder,"hls-cache"),NoOpCacheEvictor(),database)}
    }
    private fun keyFactory(id: String)=CacheKeyFactory {spec -> "$id|${spec.key ?: spec.uri}"}
    fun cacheFactory(request: DownloadRequest,offline: Boolean): CacheDataSource.Factory {
        val data=metadata(request)
        return CacheDataSource.Factory().setCache(cache(data.getString("root"))).setCacheKeyFactory(keyFactory(request.id))
            .setUpstreamDataSourceFactory(if(offline) null else networkFactory(data.optString("filmUrl")))
            .apply {if(offline) setCacheWriteDataSinkFactory(null)}
    }
    fun createDownloader(request: DownloadRequest): Downloader {
        // DownloadManager creates its downloader on the index thread. Open removable storage on
        // the download thread so a missing/ejected card becomes a failed job, not a dead queue.
        return DeferredHlsDownloader {
            val target=metadata(request).optString("treeUri").takeIf {it.isNotBlank()}?.let {TreeDownloadPackage(app,request)}
            if(target!=null && target.committed()) return@DeferredHlsDownloader object: Downloader {
                override fun download(progressListener: Downloader.ProgressListener?) {purge(request);progressListener?.onProgress(C.LENGTH_UNSET.toLong(),0,100f)}
                override fun remove() {target.remove();purge(request)}
                override fun cancel() {}
            }
            val factory=cacheFactory(request,false)
            val video=HlsBundleDownloader(DefaultDownloaderFactory(factory,executor).createDownloader(request),factory,cache(metadata(request).getString("root")),request.id,assetUrls(request))
            if(target==null) video else object: Downloader {
                @Volatile private var canceled=false
                override fun download(progressListener: Downloader.ProgressListener?) {
                    var downloaded=0L
                    video.download {length,bytes,percent ->downloaded=bytes;progressListener?.onProgress(length,bytes,if(percent>=0) percent*.85f else percent)}
                    target.export(cache(metadata(request).getString("root")),cacheFactory(request,true),{copied,total ->
                        progressListener?.onProgress(C.LENGTH_UNSET.toLong(),downloaded,if(total>0) 85f+15f*copied/total else 100f)
                    }) {canceled}
                    purge(request)
                }
                override fun remove() {target.remove();video.remove()}
                override fun cancel() {canceled=true;video.cancel()}
            }
        }
    }
    private fun purge(request: DownloadRequest) {runCatching {val store=cache(metadata(request).getString("root"));store.keys.filter {it.startsWith("${request.id}|")}.forEach {store.removeResource(it)}}}
    fun offlineFactory(request: DownloadRequest): DataSource.Factory=if(metadata(request).optString("treeUri").isNotBlank()) TreeDownloadPackage(app,request).factory() else cacheFactory(request,true)
    fun completedCopy(request: DownloadRequest): DownloadRequest? {
        val existing=index.getDownload(request.id) ?: return null
        return existing.request.takeIf {existing.state==Download.STATE_COMPLETED && metadata(it).optString("treeUri").isNotBlank() && TreeDownloadPackage(app,it).committed()}
    }
    private fun assetUrls(request: DownloadRequest): List<String> {
        val data=metadata(request);val subtitles=data.optJSONArray("subtitles") ?: JSONArray()
        return ((0 until subtitles.length()).map {subtitles.getJSONObject(it).getString("url")}+data.optString("poster")).filter {it.isNotBlank()}.distinct()
    }
    fun isAssetComplete(request: DownloadRequest,url: String): Boolean {
        if(metadata(request).optString("treeUri").isNotBlank()) return TreeDownloadPackage(app,request).isComplete(url)
        val store=cache(metadata(request).getString("root"));val key=keyFactory(request.id).buildCacheKey(DataSpec(Uri.parse(url)))
        val size=ContentMetadata.getContentLength(store.getContentMetadata(key))
        return size>=0 && store.isCached(key,0,size)
    }
    fun missingAssetCount(request: DownloadRequest): Int {
        val resources=if(metadata(request).optString("treeUri").isNotBlank()) TreeDownloadPackage(app,request).readIndex().getJSONObject("resources") else null
        return assetUrls(request).count {if(resources!=null) resources.optJSONObject(it)?.optBoolean("complete")!=true else !isAssetComplete(request,it)}
    }
    fun list(): List<Download> = index.getDownloads().use {cursor ->buildList {while(cursor.moveToNext()) add(cursor.download)}}
    fun add(context: Context,request: DownloadRequest) {
        DownloadService.sendAddDownload(context,LumenDownloadService::class.java,request,true)
        settings.prefs.edit().putBoolean("hasHlsDownloads",true).apply()
    }
    fun retry(context: Context,request: DownloadRequest)=add(context,request)
    fun pause(context: Context,id: String)=DownloadService.sendSetStopReason(context,LumenDownloadService::class.java,id,STOP_REASON_USER,true)
    fun resume(context: Context,id: String)=DownloadService.sendSetStopReason(context,LumenDownloadService::class.java,id,Download.STOP_REASON_NONE,true)
    fun remove(context: Context,id: String) {
        val request=index.getDownload(id)?.request ?: return
        if(metadata(request).optString("treeUri").isNotBlank()) TreeDownloadPackage(app,request).requireRemovable() else cache(metadata(request).getString("root"))
        DownloadService.sendRemoveDownload(context,LumenDownloadService::class.java,id,true)
    }

    fun offlineRequest(id: String): DownloadRequest {
        val download=index.getDownload(id) ?: error("Загрузка не найдена")
        check(download.state==Download.STATE_COMPLETED) {"Дождитесь завершения загрузки"}
        if(metadata(download.request).optString("treeUri").isNotBlank()) TreeDownloadPackage(app,download.request).readIndex() else cache(metadata(download.request).getString("root"))
        return download.request
    }
    fun offlinePayload(request: DownloadRequest): JSONObject {
        val data=metadata(request);val subtitles=data.optJSONArray("subtitles") ?: JSONArray()
        val resources=if(data.optString("treeUri").isNotBlank()) TreeDownloadPackage(app,request).readIndex().getJSONObject("resources") else null
        val available=JSONArray((0 until subtitles.length()).map {subtitles.getJSONObject(it)}.filter {if(resources!=null) resources.optJSONObject(it.getString("url"))?.optBoolean("complete")==true else isAssetComplete(request,it.getString("url"))})
        return JSONObject().put("title",data.getString("title")).put("film",data.optString("film")).put("voice",data.optString("voice"))
            .put("season",data.optString("season")).put("episode",data.optString("episode")).put("quality",data.optString("quality"))
            .put("url",request.uri.toString()).put("offline",true).put("offlineHls",request.id).put("subtitles",available)
    }
    fun offlineMediaItem(request: DownloadRequest): MediaItem {
        val subtitles=offlinePayload(request).getJSONArray("subtitles")
        return request.toMediaItem().buildUpon().setSubtitleConfigurations((0 until subtitles.length()).map {i ->
            val sub=subtitles.getJSONObject(i);val uri=Uri.parse(sub.getString("url"))
            MediaItem.SubtitleConfiguration.Builder(uri).setLabel(sub.optString("title")).setLanguage(sub.optString("language"))
                .setMimeType(if(uri.path.orEmpty().endsWith(".srt",true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setSelectionFlags(if(sub.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0).build()
        }).build()
    }
    fun errorMessage(error: Exception?): String {
        val denied=generateSequence<Throwable>(error) {it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return if(denied?.responseCode in listOf(401,403)) "Сервис отказал в доступе. Проверьте вход и Premium на выбранном зеркале, затем выберите видео заново."
            else "Не удалось скачать видео. Проверьте подключение, накопитель и свободное место."
    }
    /** Used by deterministic storage tests; production owns this instance for the whole process. */
    fun release() {createdManager?.release();createdManager=null;synchronized(this) {caches.values.forEach {it.release()};caches.clear()};database.close();(executor as? ExecutorService)?.shutdownNow()}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class DeferredHlsDownloader(private val create: ()->Downloader): Downloader {
    @Volatile private var canceled=false
    @Volatile private var delegate: Downloader?=null
    private fun downloader(): Downloader {
        if(canceled) throw InterruptedException()
        val current=delegate ?: create().also {delegate=it}
        if(canceled) {current.cancel();throw InterruptedException()}
        return current
    }
    override fun download(progressListener: Downloader.ProgressListener?)=downloader().download(progressListener)
    override fun remove()=downloader().remove()
    override fun cancel() {canceled=true;delegate?.cancel()}
}

/** HlsDownloader persists playlists, init segments, keys and media; optional sidecars share its cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class HlsBundleDownloader(
    private val video: Downloader,private val factory: CacheDataSource.Factory,private val cache: SimpleCache,
    private val id: String,private val assets: List<String>
): Downloader {
    @Volatile private var canceled=false
    @Volatile private var writer: CacheWriter?=null
    override fun download(progressListener: Downloader.ProgressListener?) {
        var bytes=0L
        video.download {length,downloaded,percent ->bytes=downloaded;progressListener?.onProgress(length,downloaded,percent)}
        for(url in assets) {
            if(canceled || Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                var assetBytes=0L
                writer=CacheWriter(factory.createDataSourceForDownloading(),DataSpec(Uri.parse(url)),null) {_,cached,_ ->assetBytes=cached;progressListener?.onProgress(C.LENGTH_UNSET.toLong(),bytes+cached,C.PERCENTAGE_UNSET.toFloat())}
                writer!!.cache()
                bytes+=assetBytes
            } catch(e: IOException) {if(canceled || Thread.currentThread().isInterrupted) throw InterruptedException()} finally {writer=null}
        }
        if(canceled) throw InterruptedException()
        progressListener?.onProgress(C.LENGTH_UNSET.toLong(),bytes,100f)
    }
    override fun cancel() {canceled=true;video.cancel();writer?.cancel()}
    override fun remove() {video.remove();cache.keys.filter {it.startsWith("$id|")}.forEach {cache.removeResource(it)}}
}
