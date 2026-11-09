package com.lumenkot.app

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class Mp4DownloadsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val settings=Settings(context)
    private lateinit var store: HlsDownloads
    private lateinit var server: MockWebServer
    private var online=true
    private var attempts=0
    private var beforeRemux: ()->Unit={}
    private var failure: Exception?=null
    private val fake=object: Mp4Remuxer {
        override fun remux(video: File,audio: File?,durationMs: Long,output: File,canceled: ()->Boolean,progress: (Float)->Unit) {
            attempts++;beforeRemux();failure?.let {throw it}
            assertTrue(video.readText().contains("#EXT-X-ENDLIST"));assertTrue(durationMs>=2000)
            output.writeBytes("completed-test-mp4".toByteArray());progress(1f)
        }
    }
    @Before fun setup() {
        settings.prefs.edit().clear().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit()
        context.deleteDatabase("exoplayer_internal.db");context.getExternalFilesDir("downloads")!!.apply {deleteRecursively();mkdirs()}
        server=MockWebServer();server.start();settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if(!online) return MockResponse().setResponseCode(503)
                val path=request.requestUrl!!.encodedPath.removePrefix("/")
                val bytes=if(path=="video.mp4") "direct-mp4".toByteArray() else javaClass.classLoader!!.getResourceAsStream("mp4-fixture/$path")?.use {it.readBytes()} ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(bytes))
            }
        }
        store=HlsDownloads(context,Executor {it.run()},fake)
    }
    @After fun cleanup() {store.release();HlsDownloads.resetForTests();server.shutdown();settings.prefs.edit().clear().commit()}
    private fun request(path: String="master.m3u8",tree: Uri?=null,legacy: Boolean=false): DownloadRequest {
        val voice=Voice("1","Main")
        val film=Parser.film("""<div class="b-post__title"><h1>MP4 test</h1></div><li class="b-translator__item" data-translator_id="1">Main</li>""","/films/42.html")
        return store.prepare(film,voice,"1","2",Stream("720p",if(path.startsWith("http")) path else server.url("/$path").toString()),emptyList(),tree,path.endsWith("mp4"),!legacy)
    }
    private fun complete(request: DownloadRequest) {store.index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,C.LENGTH_UNSET.toLong(),0,0))}
    private fun tree(): Pair<Uri,FixtureDocumentsProvider> {
        val provider=FixtureDocumentsProvider()
        provider.attachInfo(context,ProviderInfo().apply {authority="com.lumenkot.fixture.documents";exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS"})
        provider.directory.deleteRecursively();provider.directory.mkdirs()
        ShadowContentResolver.registerProviderInternal("com.lumenkot.fixture.documents",provider)
        val uri=DocumentsContract.buildTreeDocumentUri("com.lumenkot.fixture.documents","root")
        DownloadLocations(context).remember(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return uri to provider
    }
    @Test fun completedMp4PlaysFromOrdinaryLocalFileAfterCacheIsPurgedAndRepositoryReopens() {
        val request=request();store.createDownloader(request).download(null);complete(request)
        val count=server.requestCount;online=false
        val payload=store.offlinePayload(store.offlineRequest(request.id))
        assertFalse(payload.has("offlineHls"));assertEquals("file",Uri.parse(payload.getString("url")).scheme)
        assertEquals("completed-test-mp4",File(Uri.parse(payload.getString("url")).path!!).readText())
        assertEquals(request.id,store.completedCopy(request)!!.id)
        assertTrue(runCatching {DataSourceInputStream(store.cacheFactory(request,true).createDataSource(),DataSpec(request.uri)).use {it.readBytes()}}.isFailure)
        store.release();store=HlsDownloads(context,Executor {it.run()},fake)
        assertEquals(payload.getString("url"),store.offlinePayload(store.offlineRequest(request.id)).getString("url"))
        store.createDownloader(request).download(null);assertEquals(1,attempts);assertEquals(count,server.requestCount)
        store.createDownloader(request).remove();assertFalse(File(Uri.parse(payload.getString("url")).path!!).exists())
    }
    @Test fun pauseDuringRemuxLeavesResumableSegmentsAndNeverExposesPartialMp4() {
        val request=request("encrypted/main.m3u8");val downloader=store.createDownloader(request)
        beforeRemux={downloader.cancel();throw InterruptedException()}
        assertTrue(runCatching {downloader.download(null)}.exceptionOrNull() is InterruptedException)
        assertFalse(Mp4DownloadPackage(context,request).committed());assertNull(store.phase(request.id))
        val count=server.requestCount;online=false;beforeRemux={}
        store.release();store=HlsDownloads(context,Executor {it.run()},fake)
        store.createDownloader(request).download(null)
        assertTrue(Mp4DownloadPackage(context,request).committed());assertEquals(count,server.requestCount);assertEquals(2,attempts)
    }
    @Test fun aRemuxFailureIsRetryableAndCannotBecomeCompleted() {
        val request=request();failure=IOException("fixture remux failure")
        assertTrue(runCatching {store.createDownloader(request).download(null)}.isFailure)
        assertFalse(Mp4DownloadPackage(context,request).committed())
        failure=null;online=false;val count=server.requestCount
        store.createDownloader(request).download(null)
        assertTrue(Mp4DownloadPackage(context,request).committed());assertEquals(count,server.requestCount)
    }
    @Test fun directMp4UsesTheSameResumableQueueAndProducesAStandaloneFileInSaf() {
        val (tree,provider)=tree();val sentinel=File(provider.directory,"keep.txt").apply {writeText("keep")}
        val request=request("video.mp4",tree);store.createDownloader(request).download(null);complete(request)
        val uri=Uri.parse(store.offlinePayload(store.offlineRequest(request.id)).getString("url"))
        online=false
        assertEquals("content",uri.scheme)
        assertEquals("direct-mp4",context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()})
        assertEquals(0,attempts);assertEquals(0,store.missingAssetCount(request))
        store.createDownloader(request).remove();assertTrue(sentinel.isFile)
    }
    @Test fun legacySafPackageCanBeConvertedWithTheNetworkOff() {
        val (tree,_)=tree();val legacy=request("encrypted/main.m3u8",tree,true)
        store.createDownloader(legacy).download(null);online=false;val count=server.requestCount
        val converted=store.mp4Request(legacy)
        store.createDownloader(converted).download(null);complete(converted)
        val payload=store.offlinePayload(store.offlineRequest(converted.id))
        assertEquals("content",Uri.parse(payload.getString("url")).scheme);assertFalse(payload.has("offlineHls"))
        assertEquals(count,server.requestCount);assertEquals(1,attempts)
    }
    @Test fun pausedDestinationCopyReusesTheFinishedRemuxAndDoesNotPublishPartialOutput() {
        val legacy=request("encrypted/main.m3u8",legacy=true)
        store.createDownloader(legacy).download(null)
        val request=store.mp4Request(legacy);val output=Mp4DownloadPackage(context,request)
        var canceled=false
        val source=store.cacheFactory(request,true)
        assertTrue(runCatching {
            output.publish(source,fake,{canceled},{if(it=="Сохранение в выбранную папку") canceled=true},{})
        }.exceptionOrNull() is InterruptedException)
        assertFalse(output.committed());assertEquals(1,attempts)
        online=false;canceled=false;val count=server.requestCount
        output.publish(source,fake,{canceled},{},{})
        assertTrue(output.committed());assertEquals(1,attempts);assertEquals(count,server.requestCount)
    }
    @Test fun realFfmpegRemuxesAesTsAndFragmentedMp4WithSeparateAudioUsingOnlyCachedInput() {
        Assume.assumeTrue("Host FFmpeg must be installed for this integration check",File("/usr/bin/ffmpeg").canExecute())
        store.release();store=HlsDownloads(context,Executor {it.run()},object: Mp4Remuxer {
            override fun remux(video: File,audio: File?,durationMs: Long,output: File,canceled: ()->Boolean,progress: (Float)->Unit) {
                online=false
                val args=NativeMp4Remuxer.arguments(video,audio,output).toMutableList()
                val help=ProcessBuilder("/usr/bin/ffmpeg","-hide_banner","-h","demuxer=hls").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                for(option in listOf("-extension_picky","-allowed_segment_extensions")) if(!help.contains(option)) {
                    val index=args.indexOf(option);args.removeAt(index);args.removeAt(index)
                }
                val process=ProcessBuilder(listOf("/usr/bin/ffmpeg")+args).redirectErrorStream(true).start()
                val log=process.inputStream.bufferedReader().readText();assertEquals(log,0,process.waitFor())
            }
        })
        for(path in listOf("encrypted/main.m3u8","master.m3u8","byterange/main.m3u8")+listOfNotNull(System.getenv("LUMEN_HLS_SAMPLE_URL"))) {
            online=true;val request=request(path);store.createDownloader(request).download(null)
            val saved=Mp4DownloadPackage(context,request).read();val file=File(Uri.parse(saved.getString("uri")).path!!)
            val probe=ProcessBuilder("/usr/bin/ffprobe","-v","error","-show_entries","stream=codec_type","-show_entries","format=duration","-of","json",file.absolutePath).start()
            val info=org.json.JSONObject(probe.inputStream.bufferedReader().readText());assertEquals(0,probe.waitFor())
            val types=info.getJSONArray("streams").let {streams ->(0 until streams.length()).map {streams.getJSONObject(it).getString("codec_type")}}
            assertTrue(types.containsAll(listOf("audio","video")));assertTrue(info.getJSONObject("format").getString("duration").toDouble()>=2)
            store.createDownloader(request).remove()
        }
    }
}
