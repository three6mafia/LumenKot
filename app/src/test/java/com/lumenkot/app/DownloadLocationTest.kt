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
import java.util.concurrent.Executor

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadLocationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val tree=DocumentsContract.buildTreeDocumentUri("com.lumenkot.fixture.documents","root")
    private val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private lateinit var provider: FixtureDocumentsProvider
    private lateinit var store: HlsDownloads
    private lateinit var server: MockWebServer
    private var running=false
    private val settings=Settings(context)
    private val film=Parser.film("<div class='b-post__title'><h1>Fixture</h1></div><li class='b-translator__item' data-translator_id='7'>Voice</li>","/films/42-fixture.html")
    @Before fun setup() {
        settings.prefs.edit().clear().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit()
        context.deleteDatabase("exoplayer_internal.db");context.getExternalFilesDir("downloads")!!.apply {deleteRecursively();mkdirs()}
        provider=FixtureDocumentsProvider();provider.attachInfo(context,ProviderInfo().apply {authority="com.lumenkot.fixture.documents";exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS"})
        provider.directory.deleteRecursively();provider.directory.mkdirs()
        ShadowContentResolver.registerProviderInternal("com.lumenkot.fixture.documents",provider)
        DownloadLocations(context).remember(tree,flags)
        server=MockWebServer();server.start();running=true;settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path=request.requestUrl!!.encodedPath
                val body=when(path) {
                    "/main.m3u8" -> "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\n#EXT-X-BYTERANGE:4@2\nchunk.ts\n#EXT-X-ENDLIST\n".toByteArray()
                    "/init.mp4" -> "INIT".toByteArray()
                    "/chunk.ts" -> "0123456789".toByteArray()
                    "/video.mp4" -> "fixture-mp4-video-bytes".toByteArray()
                    else -> return MockResponse().setResponseCode(404)
                }
                val range=request.getHeader("Range")?.removePrefix("bytes=")
                return if(range==null) MockResponse().setBody(Buffer().write(body)) else {
                    val start=range.substringBefore('-').toInt();val end=range.substringAfter('-').toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                    MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${body.size}").setBody(Buffer().write(body.copyOfRange(start,end+1)))
                }
            }
        }
        store=HlsDownloads(context,Executor {it.run()})
    }
    @After fun cleanup() {if(::store.isInitialized) store.release();HlsDownloads.resetForTests();if(running) server.shutdown();settings.prefs.edit().clear().commit()}
    private fun request(mp4: Boolean=false)=store.prepare(film,film.voices.first(),"1","2",Stream("720p",server.url(if(mp4) "/video.mp4" else "/main.m3u8").toString()),emptyList(),tree,mp4,exportMp4=false)
    private fun read(request: androidx.media3.exoplayer.offline.DownloadRequest,path: String,position: Long=0,length: Long=C.LENGTH_UNSET.toLong()): ByteArray =
        DataSourceInputStream(store.offlineFactory(request).createDataSource(),DataSpec.Builder().setUri(server.url(path).toString()).setPosition(position).setLength(length).build()).use {it.readBytes()}
    @Test fun chosenFolderAndAlwaysFlagPersistIndependentlyAndPickerUsesLocalFolders() {
        val locations=DownloadLocations(context)
        assertTrue(locations.hasAccess(tree));assertFalse(locations.always)
        locations.always=true
        assertEquals(tree,DownloadLocations(context).tree);assertTrue(DownloadLocations(context).always)
        locations.always=false
        assertEquals(tree,DownloadLocations(context).tree);assertFalse(DownloadLocations(context).always)
        val picker=locations.picker();assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,picker.action);assertTrue(picker.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false))
        val pending=PendingDownload(film,film.voices.first(),Stream("720p",server.url("/main.m3u8?token=fixture").toString()),"1","2",emptyList())
        assertEquals(pending,PendingDownload.from(pending.json()))
    }
    @Test fun hlsSpansAreSavedToPickedFolderAndReadWithoutStagingCacheOrNetwork() {
        val sentinel=java.io.File(provider.directory,"keep.txt").apply {writeText("keep")}
        val request=request();store.createDownloader(request).download(null)
        val folder=TreeDownloadPackage(context,request);assertTrue(folder.committed())
        val baseline=server.requestCount
        assertArrayEquals("2345".toByteArray(),read(request,"/chunk.ts",2,4))
        assertTrue(runCatching {read(request,"/chunk.ts",0,2)}.isFailure);assertEquals(baseline,server.requestCount)
        assertTrue(runCatching {DataSourceInputStream(store.cacheFactory(request,true).createDataSource(),DataSpec(request.uri)).use {it.readBytes()}}.isFailure)
        server.shutdown();running=false
        store.release();store=HlsDownloads(context,Executor {it.run()})
        assertArrayEquals("INIT".toByteArray(),read(request,"/init.mp4"))
        assertTrue(String(read(request,"/main.m3u8")).contains("EXTM3U"))
        store.createDownloader(request).remove()
        assertTrue(sentinel.isFile);assertEquals("keep",sentinel.readText());assertFalse(runCatching {folder.readIndex()}.isSuccess)
    }
    @Test fun mp4IsAnOrdinaryFileInThePickedFolderAndSupportsOfflineSeeking() {
        val request=request(true);store.createDownloader(request).download(null)
        val video=provider.directory.walkTopDown().first {it.name=="video.mp4"}
        assertArrayEquals("fixture-mp4-video-bytes".toByteArray(),video.readBytes())
        server.shutdown();running=false
        assertArrayEquals("mp4-".toByteArray(),read(request,"/video.mp4",8,4))
        assertTrue(TreeDownloadPackage(context,request).committed())
    }
    @Test fun interruptedFolderExportResumesFromStagedMediaWithoutTheServer() {
        val request=request(true);val downloader=store.createDownloader(request)
        assertNotNull(runCatching {downloader.download {_,_,percent ->if(percent>=85f) downloader.cancel()}}.exceptionOrNull())
        assertFalse(runCatching {TreeDownloadPackage(context,request).readIndex()}.isSuccess)
        server.shutdown();running=false
        store.createDownloader(request).download(null)
        assertTrue(TreeDownloadPackage(context,request).committed())
        assertArrayEquals("fixture-mp4-video-bytes".toByteArray(),read(request,"/video.mp4"))
    }
    @Test fun aRevokedFolderCannotQueueANewDownloadOrSilentlyUseAnotherLocation() {
        context.contentResolver.releasePersistableUriPermission(tree,flags)
        assertFalse(DownloadLocations(context).hasAccess(tree))
        val count=server.requestCount
        val error=runCatching {request()}.exceptionOrNull()
        assertTrue(error is DownloadFolderException);assertEquals(count,server.requestCount)
    }
}
