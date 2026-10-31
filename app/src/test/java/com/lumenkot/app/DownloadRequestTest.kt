package com.lumenkot.app

import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadRequestTest {
    @Test fun noSubtitlesFiltersHlsRenditionsAndPersistsDestination() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Russian",URI="subtitles.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES="subs"
video.m3u8
"""));server.start()
            val metadata=JSONObject().put("url",server.url("/master.m3u8").toString()).put("format","HLS").put("includeSubtitles",false).put("tree","content://chosen/tree/folder")
            val request=OfflineDownloads.request(context,metadata)
            assertEquals(MimeTypes.APPLICATION_M3U8,request.mimeType)
            assertEquals(1,request.streamKeys.size)
            assertEquals(0,request.streamKeys.single().groupIndex)
            assertEquals("content://chosen/tree/folder",JSONObject(request.data.toString(Charsets.UTF_8)).getString("tree"))
        }
    }
}
