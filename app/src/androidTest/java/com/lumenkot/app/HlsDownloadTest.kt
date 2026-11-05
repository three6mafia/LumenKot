package com.lumenkot.app

import android.content.Context
import android.os.SystemClock
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local HTTP fixture only; HLS serving is disabled before actual offline Media3 playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class HlsDownloadTest {
    @Test fun backgroundServiceDownloadsAndPlayerAdvancesWithHlsNetworkDisabled() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val settings=Settings(context);settings.prefs.edit().clear().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit()
        settings.provider="http://127.0.0.1:8765";settings.official=false
        val api=RezkaApi(settings);val repository=HlsDownloads.get(context)
        fun waitFor(message: String,condition: ()->Boolean) {
            val end=SystemClock.elapsedRealtime()+120000
            while(SystemClock.elapsedRealtime()<end) {if(condition()) return;SystemClock.sleep(200)}
            fail(message)
        }
        var id: String?=null
        try {
            api.request("/hls-control",mapOf("blocked" to "0"))
            val film=api.film("/films/42-native.html")
            val request=repository.prepare(film,film.voices.first(),"","",Stream("720p","http://127.0.0.1:8765/hls/master.m3u8"),emptyList())
            id=request.id
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {activity ->
                    // ATD's loopback-only fixture route has no validated external Internet network.
                    repository.manager.requirements=Requirements(0)
                    repository.add(activity,request)
                }
                waitFor("DownloadService must persist completed HLS") {
                    val download=repository.index.getDownload(request.id)
                    assertNotEquals("HLS download failed",Download.STATE_FAILED,download?.state)
                    download?.state==Download.STATE_COMPLETED
                }
            }
            val blocked=JSONObject(api.request("/hls-control",mapOf("blocked" to "1"))).getInt("requests")
            val offline=repository.offlinePayload(repository.offlineRequest(request.id))
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context,offline)).use {scenario ->
                waitFor("Media3 must actually advance through saved HLS while HTTP serves 503") {
                    var played=false
                    scenario.onActivity {activity ->
                        val player=activity.findViewById<PlayerView>(R.id.player_surface).player!!
                        assertNull(player.playerError)
                        played=player.isPlaying && player.currentPosition>500 && player.duration>=3000
                    }
                    played
                }
            }
            assertEquals("Offline playback must not request HLS over HTTP",blocked,JSONObject(api.request("/hls-control")).getInt("requests"))
        } finally {
            runCatching {api.request("/hls-control",mapOf("blocked" to "0"))}
            if(id!=null) {
                instrumentation.runOnMainSync {repository.remove(context,id!!)}
                waitFor("HLS removal must complete") {repository.index.getDownload(id!!)==null}
            }
            instrumentation.runOnMainSync {repository.manager.requirements=Requirements(Requirements.NETWORK)}
            settings.prefs.edit().clear().commit()
        }
    }
}
