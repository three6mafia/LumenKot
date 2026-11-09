package com.lumenkot.app

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the shipped FFmpegKit .so on Android, then decodes the exported standalone MP4. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Mp4DownloadTest {
    @Test fun nativeFfmpegCreatesSeekableMp4FromEncryptedHlsAndSeparateFmp4Audio() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val directory=File(context.cacheDir,"native-mp4-test").apply {deleteRecursively();mkdirs()}
        fun copyAssets(path: String) {
            val files=assets.list(path)!!
            for(name in files) {
                val child="$path/$name"
                if(assets.list(child)!!.isEmpty()) {
                    val file=File(directory,child.removePrefix("mp4-fixture/")).apply {parentFile!!.mkdirs()}
                    assets.open(child).use {input ->file.outputStream().use {input.copyTo(it)}}
                } else copyAssets(child)
            }
        }
        copyAssets("mp4-fixture")
        try {
            for(separate in listOf(false,true)) {
                val video=File(directory,if(separate) "fmp4/main.m3u8" else "encrypted/main.m3u8")
                val audio=if(separate) File(directory,"audio/main.m3u8") else null
                val output=File(directory,"output-$separate.mp4")
                NativeMp4Remuxer.remux(video,audio,2000,output,{false},{})
                assertTrue(output.length()>0)
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val types=(0 until extractor.trackCount).map {extractor.getTrackFormat(it).getString("mime").orEmpty()}
                    assertTrue(types.any {it.startsWith("video/")});assertTrue(types.any {it.startsWith("audio/")})
                } finally {extractor.release()}
                val payload=org.json.JSONObject().put("title","MP4 fixture").put("url",Uri.fromFile(output).toString()).put("offline",true)
                ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context,payload)).use {scenario ->
                    val end=android.os.SystemClock.elapsedRealtime()+30000
                    var played=false
                    while(!played && android.os.SystemClock.elapsedRealtime()<end) {
                        scenario.onActivity {activity ->
                            val player=activity.findViewById<PlayerView>(R.id.player_surface).player!!
                            assertNull(player.playerError)
                            played=player.isPlaying && player.currentPosition>300 && player.duration>=1900
                        }
                        android.os.SystemClock.sleep(100)
                    }
                    assertTrue("The standalone MP4 must actually decode and advance",played)
                    scenario.onActivity {activity ->activity.findViewById<PlayerView>(R.id.player_surface).player!!.seekTo(1200)}
                }
            }
            // Optional external fixture, supplied by the test runner; never baked into the APK.
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("sampleUrl")?.let {url ->
                val settings=Settings(context)
                settings.prefs.edit().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit()
                val uri=Uri.parse(url)
                settings.provider=uri.buildUpon().path("").clearQuery().fragment(null).build().toString().trimEnd('/')
                settings.official=false
                val film=Parser.film("""<div class="b-post__title"><h1>Provided HLS sample</h1></div><li class="b-translator__item" data-translator_id="1">Main</li>""","/films/42.html")
                val repository=HlsDownloads.get(context)
                val request=repository.prepare(film,film.voices.first(),"","",Stream("720p",url),emptyList())
                ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                    scenario.onActivity {activity ->
                        repository.manager.requirements=androidx.media3.exoplayer.scheduler.Requirements(0)
                        repository.add(activity,request)
                    }
                    val end=android.os.SystemClock.elapsedRealtime()+120000
                    var completed=false
                    while(!completed && android.os.SystemClock.elapsedRealtime()<end) {
                        val state=repository.index.getDownload(request.id)?.state
                        assertNotEquals("Provided HLS sample download failed",androidx.media3.exoplayer.offline.Download.STATE_FAILED,state)
                        completed=state==androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                        android.os.SystemClock.sleep(100)
                    }
                    assertTrue("Provided HLS sample must finish as MP4",completed)
                }
                val block=uri.buildUpon().path("/block").clearQuery().build().toString()
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(block).build()).execute().use {assertTrue(it.isSuccessful)}
                val payload=repository.offlinePayload(repository.offlineRequest(request.id))
                assertFalse(payload.has("offlineHls"));assertTrue(payload.getString("url").endsWith("video.mp4"))
                ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context,payload)).use {scenario ->
                    val end=android.os.SystemClock.elapsedRealtime()+30000
                    var played=false
                    while(!played && android.os.SystemClock.elapsedRealtime()<end) {
                        scenario.onActivity {activity ->
                            val player=activity.findViewById<PlayerView>(R.id.player_surface).player!!
                            assertNull(player.playerError);played=player.isPlaying && player.currentPosition>500 && player.duration>10000
                        }
                        android.os.SystemClock.sleep(100)
                    }
                    assertTrue("Provided sample MP4 must play after HTTP is blocked",played)
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {repository.remove(context,request.id)}
            }
        } finally {directory.deleteRecursively()}
    }
}
