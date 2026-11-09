package com.lumenkot.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executor

/** Real HlsDownloader + HTTP + SQLite/cache. No decoder or real service/account is required. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class HlsOfflineTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: Settings
    private lateinit var server: MockWebServer
    private lateinit var store: HlsDownloads
    private var running=false
    private val paths=Collections.synchronizedList(mutableListOf<String>())
    private val requests=Collections.synchronizedList(mutableListOf<RecordedRequest>())
    @Volatile private var failMedia=false
    private val master="""#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="voice",NAME="Main",DEFAULT=YES,AUTOSELECT=YES,URI="audio/main.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="voice",NAME="Other",DEFAULT=NO,URI="other/main.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Russian",LANGUAGE="ru",DEFAULT=YES,URI="sub/main.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="voice",SUBTITLES="subs"
360/main.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="voice",SUBTITLES="subs"
720/main.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,AUDIO="voice",SUBTITLES="subs"
1080/main.m3u8
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=500000,RESOLUTION=3840x2160,URI="trick/main.m3u8"
"""
    private fun media(kind: String)=when(kind) {
        "video" -> """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:4
#EXT-X-KEY:METHOD=AES-128,URI="../key.bin",IV=0x00000000000000000000000000000001
#EXT-X-MAP:URI="init.mp4",BYTERANGE="4@0"
#EXTINF:2.0,
#EXT-X-BYTERANGE:4@0
all.m4s
#EXTINF:2.0,
#EXT-X-BYTERANGE:4@4
all.m4s
#EXT-X-ENDLIST
"""
        else -> """#EXTM3U
#EXT-X-TARGETDURATION:4
#EXTINF:4.0,
${if(kind=="sub") "segment.vtt" else "segment.aac"}
#EXT-X-ENDLIST
"""
    }
    @Before fun setup() {
        settings=Settings(context);settings.prefs.edit().clear().putBoolean("downloadPoster",false).commit()
        context.deleteDatabase("exoplayer_internal.db")
        context.getExternalFilesDir("downloads")!!.apply {deleteRecursively();mkdirs()}
        server=MockWebServer();server.start();running=true
        settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path=request.requestUrl!!.encodedPath;paths.add(path);requests.add(request)
                if(failMedia && path.endsWith("all.m4s")) return MockResponse().setResponseCode(503)
                val body=when {
                    path=="/master.m3u8" || path=="/opaque" -> master.toByteArray()
                    path=="/live.m3u8" -> media("video").replace("#EXT-X-ENDLIST","").toByteArray()
                    path=="/file.MP4" -> byteArrayOf(0,1,2,3)
                    path.endsWith("main.m3u8") -> media(if(path.startsWith("/audio/")) "audio" else if(path.startsWith("/sub/")) "sub" else "video").toByteArray()
                    path.endsWith("key.bin") -> ByteArray(16) {it.toByte()}
                    path.endsWith("init.mp4") -> "INIT".toByteArray()
                    path.endsWith("all.m4s") -> "ABCDEFGH".toByteArray()
                    path.endsWith("segment.aac") -> "audio".toByteArray()
                    path.endsWith(".vtt") -> "WEBVTT\n\n00:00.000 --> 00:04.000\nFixture\n".toByteArray()
                    else -> return MockResponse().setResponseCode(404)
                }
                val response=MockResponse().setHeader("Content-Type",if(path.endsWith("m3u8") || path=="/opaque") "application/vnd.apple.mpegurl" else "application/octet-stream")
                val range=request.getHeader("Range")?.removePrefix("bytes=")
                if(range!=null) {
                    val start=range.substringBefore('-').toInt();val end=range.substringAfter('-').toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                    return response.setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${body.size}").setBody(Buffer().write(body.copyOfRange(start,end+1)))
                }
                return response.setBody(Buffer().write(body))
            }
        }
        store=HlsDownloads(context,Executor {it.run()})
    }
    @After fun cleanup() {store.release();HlsDownloads.resetForTests();if(running) server.shutdown();settings.prefs.edit().clear().commit()}
    private fun stopServer() {server.shutdown();running=false}
    private fun request(quality: String="720p",subtitles: List<Subtitle> = listOf(Subtitle("Russian",server.url("/external.vtt").toString(),"ru",true))): DownloadRequest {
        val film=Parser.film("<div class='b-post__title'><h1>HLS fixture</h1></div><li class='b-translator__item' data-translator_id='7'>Voice</li>","/films/42-fixture.html")
        return store.prepare(film,film.voices.single(),"1","2",Stream(quality,server.url("/master.m3u8?token=fixture").toString()),subtitles,exportMp4=false)
    }
    private fun bytes(request: DownloadRequest,path: String,length: Long=C.LENGTH_UNSET.toLong()): ByteArray {
        val spec=DataSpec.Builder().setUri(server.url(path).toString()).setLength(length).build()
        return DataSourceInputStream(store.cacheFactory(request,true).createDataSource(),spec).use {it.readBytes()}
    }
    private fun record(request: DownloadRequest,state: Int) {
        store.index.putDownload(Download(request,state,1L,2L,C.LENGTH_UNSET.toLong(),Download.STOP_REASON_NONE,if(state==Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE))
    }
    @Test fun masterSelectsOneQualityAndDefaultAudioAndRejectsLive() {
        val selected=request()
        assertEquals(listOf(StreamKey(0,1),StreamKey(1,0),StreamKey(2,0)),selected.streamKeys)
        assertFalse(paths.any {it.startsWith("/360/") || it.startsWith("/1080/") || it.startsWith("/other/")})
        val maximum=HlsDownloadPlan.prepare(server.url("/master.m3u8").toString(),"4K",false,store.networkFactory())
        assertEquals(StreamKey(0,2),maximum.first());assertFalse(paths.any {it.startsWith("/trick/")})
        val failure=runCatching {HlsDownloadPlan.prepare(server.url("/live.m3u8").toString(),"720p",true,store.networkFactory())}.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("прямая трансляция"))
        assertTrue(HlsDownloadPlan.isHls(server.url("/opaque").toString(),store.networkFactory()))
        assertFalse(HlsDownloadPlan.isHls(server.url("/file.MP4?token=a").toString(),store.networkFactory()))
    }
    @Test fun encryptedSegmentsAudioSubtitlesAndInitRemainReadableAfterServerStopsAndStoreReopens() {
        val request=request();store.createDownloader(request).download(null);record(request,Download.STATE_COMPLETED)
        assertFalse(paths.any {it.startsWith("/360/") || it.startsWith("/1080/") || it.startsWith("/other/")})
        val count=server.requestCount
        assertTrue(runCatching {bytes(request,"/not-downloaded.ts",4)}.exceptionOrNull() is IOException)
        assertEquals("Cache misses must never use the still-running HTTP server",count,server.requestCount)
        stopServer();store.release();store=HlsDownloads(context,Executor {it.run()})
        val restored=store.offlineRequest(request.id)
        assertEquals(request.streamKeys,restored.streamKeys)
        assertTrue(String(bytes(restored,"/master.m3u8?token=fixture")).contains("EXT-X-STREAM-INF"))
        assertArrayEquals("INIT".toByteArray(),bytes(restored,"/720/init.mp4",4))
        assertArrayEquals("ABCDEFGH".toByteArray(),bytes(restored,"/720/all.m4s",8))
        assertArrayEquals(ByteArray(16) {it.toByte()},bytes(restored,"/key.bin"))
        assertArrayEquals("audio".toByteArray(),bytes(restored,"/audio/segment.aac"))
        assertTrue(String(bytes(restored,"/sub/segment.vtt")).contains("WEBVTT"))
        assertTrue(String(bytes(restored,"/external.vtt")).contains("Fixture"))
        val item=store.offlineMediaItem(restored)
        assertEquals(request.streamKeys,item.localConfiguration!!.streamKeys)
        assertEquals(1,item.localConfiguration!!.subtitleConfigurations.size)
        assertEquals(count,server.requestCount)
        val missing=runCatching {bytes(restored,"/not-downloaded.ts",4)}.exceptionOrNull()
        assertTrue(missing is IOException);assertEquals(count,server.requestCount)
    }
    @Test fun interruptedDownloadResumesFromCacheAfterProcessStorageReopens() {
        val request=request(subtitles=emptyList());failMedia=true
        assertNotNull(runCatching {store.createDownloader(request).download(null)}.exceptionOrNull())
        record(request,Download.STATE_FAILED)
        val keysBefore=paths.count {it.endsWith("key.bin")};assertTrue(keysBefore>0)
        store.release();store=HlsDownloads(context,Executor {it.run()});failMedia=false
        val restored=store.index.getDownload(request.id)!!
        assertEquals(Download.STATE_FAILED,restored.state)
        store.createDownloader(restored.request).download(null);record(restored.request,Download.STATE_COMPLETED)
        assertEquals(keysBefore,paths.count {it.endsWith("key.bin")})
        assertEquals(request.id,store.offlineRequest(request.id).id)
    }
    @Test fun deletingOneQualityPreservesTheOtherQualityIncludingSharedManifestAndAudio() {
        val low=request();val high=request("1080p")
        store.createDownloader(low).download(null);store.createDownloader(high).download(null)
        stopServer();store.createDownloader(low).remove()
        assertTrue(runCatching {bytes(low,"/master.m3u8?token=fixture")}.isFailure)
        assertTrue(String(bytes(high,"/master.m3u8?token=fixture")).contains("EXTM3U"))
        assertArrayEquals("ABCDEFGH".toByteArray(),bytes(high,"/1080/all.m4s",8))
        assertArrayEquals("audio".toByteArray(),bytes(high,"/audio/segment.aac"))
        assertFalse(store.isAssetComplete(low,server.url("/external.vtt").toString()))
        assertTrue(store.isAssetComplete(high,server.url("/external.vtt").toString()))
    }
    @Test fun optionalMissingSubtitleDoesNotInvalidateVideoOrCreateAnOnlineSubtitle() {
        val request=request(subtitles=listOf(Subtitle("Missing",server.url("/missing.srt").toString())))
        store.createDownloader(request).download(null);record(request,Download.STATE_COMPLETED)
        assertEquals(0,store.offlinePayload(request).getJSONArray("subtitles").length())
        settings.prefs.edit().putBoolean("downloadSubtitles",false).commit()
        assertEquals(listOf(StreamKey(0,1),StreamKey(1,0)),request(subtitles=emptyList()).streamKeys)
    }
    @Test fun downloadManagerPersistsACompletedQueueEntryAndRemovesItsCache() {
        val request=request(subtitles=emptyList());val manager=store.manager
        manager.requirements=Requirements(0);manager.resumeDownloads();manager.addDownload(request)
        fun waitFor(condition: ()->Boolean) {
            val end=System.nanoTime()+10_000_000_000L
            while(System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)}
            fail("DownloadManager did not reach the expected persistent state")
        }
        waitFor {store.index.getDownload(request.id)?.state==Download.STATE_COMPLETED}
        assertEquals(request.id,store.offlineRequest(request.id).id)
        manager.removeDownload(request.id);waitFor {store.index.getDownload(request.id)==null}
        assertTrue(runCatching {bytes(request,"/master.m3u8?token=fixture")}.isFailure)
    }
    @Test fun pausedQueueSurvivesReopenAndResumesOnlyAfterUserRequestsIt() {
        val request=request(subtitles=emptyList());val manager=store.manager
        manager.requirements=Requirements(0);manager.resumeDownloads();manager.addDownload(request,HlsDownloads.STOP_REASON_USER)
        fun waitFor(condition: ()->Boolean) {
            val end=System.nanoTime()+10_000_000_000L
            while(System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)}
            fail("Queue did not reach expected state")
        }
        waitFor {store.index.getDownload(request.id)?.state==Download.STATE_STOPPED}
        store.release();store=HlsDownloads(context,Executor {it.run()})
        val restored=store.index.getDownload(request.id)!!
        assertEquals(Download.STATE_STOPPED,restored.state);assertEquals(HlsDownloads.STOP_REASON_USER,restored.stopReason)
        val resumed=store.manager;resumed.requirements=Requirements(0);resumed.resumeDownloads();resumed.setStopReason(request.id,Download.STOP_REASON_NONE)
        waitFor {store.index.getDownload(request.id)?.state==Download.STATE_COMPLETED}
    }
    @Test fun openingOfflineCopyOfTheSameUrlReplacesTheStreamingSessionAndKeepsStreamKeys() {
        val request=request(subtitles=emptyList());store.createDownloader(request).download(null);record(request,Download.STATE_COMPLETED)
        val offline=store.offlinePayload(request);val online=org.json.JSONObject(offline.toString()).put("offline",false).apply {remove("offlineHls")}
        store.release()
        org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java,PlayerActivity.intent(context,online)).setup().use {controller ->
            val activity=controller.get();val streaming=activity.findViewById<androidx.media3.ui.PlayerView>(R.id.player_surface).player!!
            controller.newIntent(PlayerActivity.intent(context,offline))
            val saved=activity.findViewById<androidx.media3.ui.PlayerView>(R.id.player_surface).player!!
            assertNotSame(streaming,saved);assertEquals(request.streamKeys,saved.currentMediaItem!!.localConfiguration!!.streamKeys)
            controller.newIntent(PlayerActivity.intent(context,online))
            val back=activity.findViewById<androidx.media3.ui.PlayerView>(R.id.player_surface).player!!
            assertNotSame(saved,back);assertTrue(back.currentMediaItem!!.localConfiguration!!.streamKeys.isEmpty())
        }
    }
    @Test fun downloadKeepsAgentRefererAndCookiesWithoutCrossingHostBoundaries() {
        val url=server.url("/")
        settings.userAgent="LumenFixture/1"
        RezkaApi(settings).cookies.saveFromResponse(url,listOf(okhttp3.Cookie.Builder().name("fixture_session").value("fixture").hostOnlyDomain(url.host).path("/").build()))
        val request=request(subtitles=emptyList());store.createDownloader(request).download(null)
        assertTrue(requests.all {it.getHeader("User-Agent")=="LumenFixture/1" && it.getHeader("Cookie").orEmpty().contains("fixture_session=fixture")})
        assertTrue(requests.all {it.getHeader("Referer")==server.url("/films/42-fixture.html").toString()})
        val before=requests.size
        val other=server.url("/master.m3u8").newBuilder().host(if(url.host=="localhost") "127.0.0.1" else "localhost").build()
        HlsDownloadPlan.prepare(other.toString(),"720p",false,store.networkFactory())
        assertTrue(requests.drop(before).all {it.getHeader("Cookie")==null})
    }
    @Test fun changingDownloadStorageDoesNotRedirectExistingOfflineMedia() {
        val request=request(subtitles=emptyList());store.createDownloader(request).download(null);record(request,Download.STATE_COMPLETED)
        settings.prefs.edit().putInt("downloadStorage",99).commit()
        assertArrayEquals("ABCDEFGH".toByteArray(),bytes(store.offlineRequest(request.id),"/720/all.m4s",8))
        val error=runCatching {request("1080p")}.exceptionOrNull()
        assertTrue(error!!.message!!.contains("накопитель"))
    }
    @Test fun missingStorageFailsOneJobWithoutBreakingTheRestOfTheQueue() {
        val valid=request(subtitles=emptyList())
        val broken=DownloadRequest.Builder(valid.id+"-missing",valid.uri).setMimeType(valid.mimeType).setStreamKeys(valid.streamKeys)
            .setData(HlsDownloads.metadata(valid).put("root","/unmounted-fixture-storage").toString().toByteArray()).build()
        val manager=store.manager;manager.requirements=Requirements(0);manager.resumeDownloads();manager.addDownload(broken);manager.addDownload(valid)
        val end=System.nanoTime()+10_000_000_000L
        while(System.nanoTime()<end) {
            shadowOf(Looper.getMainLooper()).idle()
            if(store.index.getDownload(broken.id)?.state==Download.STATE_FAILED && store.index.getDownload(valid.id)?.state==Download.STATE_COMPLETED) return
            Thread.sleep(10)
        }
        fail("The queue must report the missing storage and finish the other download")
    }
}
