package com.lumenkot.app

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsOfflineTest {
    @Test fun downloadsSegmentsReadsWithoutNetworkAndRemovesCache() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val server=MockWebServer()
        server.start()
        val playlist="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.0,\nsegment.ts\n#EXT-X-ENDLIST\n"
        server.enqueue(MockResponse().setBody(playlist))
        server.enqueue(MockResponse().setBody("downloaded segment bytes"))
        val url=server.url("/video.m3u8").toString()
        val database=StandaloneDatabaseProvider(context)
        val cache=SimpleCache(Files.createTempDirectory("hls-cache").toFile(),NoOpCacheEvictor(),database)
        try {
            val factory=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            val downloader=HlsDownloader(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build(),factory)
            downloader.download(null)
            assertEquals(2,server.requestCount)
            assertTrue(cache.cacheSpace>0)
            server.shutdown()
            fun read(uri: String): String {
                val source=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).createDataSource()
                try {
                    source.open(DataSpec(android.net.Uri.parse(uri)))
                    val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(1024)
                    while(true) { val size=source.read(bytes,0,bytes.size);if(size==-1) break;out.write(bytes,0,size) }
                    return out.toString("UTF-8")
                } finally { source.close() }
            }
            assertEquals(playlist,read(url))
            assertEquals("downloaded segment bytes",read(url.substringBeforeLast('/')+"/segment.ts"))
            downloader.remove()
            assertEquals(0L,cache.cacheSpace)
        } finally { cache.release();database.close();runCatching { server.shutdown() } }
    }
}
