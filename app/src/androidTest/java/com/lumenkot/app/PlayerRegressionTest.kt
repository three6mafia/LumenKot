package com.lumenkot.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlayerRegressionTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var settings: Settings
    @Before fun setup() {settings=Settings(context);settings.prefs.edit().clear().commit();settings.provider="http://127.0.0.1:8765";settings.official=false}
    @After fun cleanup() {settings.prefs.edit().clear().commit()}
    private fun await(message: String, timeout: Long=90000, condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {if(condition()) return;SystemClock.sleep(200)}
        fail(message)
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let {bitmap ->File(context.filesDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun mediaIntent()=Intent(context,PlayerActivity::class.java).putExtra("media",JSONObject()
        .put("title","Kotlin · проверка жестов и PiP").put("url","http://127.0.0.1:8765/video.mp4")
        .put("film","42").put("voice","1").put("season","2").put("episode","3")
        .put("filmUrl","/series/42-native.html")
        .put("seasons",org.json.JSONArray("""[{"id":"1","title":"Сезон 1","episodes":[{"id":"1","title":"Серия 1"},{"id":"2","title":"Серия 2"}]},{"id":"2","title":"Сезон 2","episodes":[{"id":"3","title":"Серия 3"}]}]""")).toString())
    private fun waitPlaying(scenario: ActivityScenario<PlayerActivity>) {
        await("Test video must actually play") {var ready=false;scenario.onActivity {activity ->
            val player=activity.findViewById<PlayerView>(R.id.player_surface).player!!
            assertNull(player.playerError);ready=player.isPlaying && player.currentPosition>500 && activity.hasWindowFocus()
        };ready}
    }
    @Test fun aPosterActionsContinueAndSavedEpisodeAreVisible() {
        WatchProgress(settings.prefs).record("42","1","2","3",12000)
        val intent=Intent(context,MainActivity::class.java).setData(Uri.parse("http://127.0.0.1:8765/series/42-native.html"))
        ActivityScenario.launch<MainActivity>(intent).use {scenario ->
            await("Film poster must load and Continue must appear") {var ready=false;scenario.onActivity {activity ->
                ready=activity.findViewById<ImageView>(R.id.film_poster)?.drawable is BitmapDrawable && activity.findViewById<View>(R.id.film_continue)!=null
            };ready}
            scenario.onActivity {activity ->
                val watch=activity.findViewById<View>(R.id.film_watch);val resume=activity.findViewById<View>(R.id.film_continue)
                val actions=activity.findViewById<View>(R.id.film_actions)
                assertTrue(watch.width>0);assertTrue(watch.width>=actions.width)
                assertTrue(resume.top>=watch.bottom);assertNotNull(activity.findViewById<View>(R.id.film_download));assertNotNull(activity.findViewById<View>(R.id.film_bookmark))
            }
            capture("regression-film.png")
            val api=RezkaApi(settings);val film=api.film("/series/42-native.html")
            lateinit var dialog: VideoSelectionDialog
            scenario.onActivity {activity ->dialog=VideoSelectionDialog(activity,api,film,activity.lifecycleScope,false) {_,_,_,_,_->};dialog.show()}
            await("Last season/episode must be highlighted") {var ready=false;scenario.onActivity {
                val views=ArrayList<View>();dialog.window!!.decorView.findViewsWithText(views,"Серия 3",View.FIND_VIEWS_WITH_TEXT)
                ready=views.filterIsInstance<Button>().any {it.text=="Серия 3" && it.isSelected}
                if(ready) assertEquals(View.VISIBLE,dialog.findViewById<View>(R.id.season_episode_divider).visibility)
            };ready}
            capture("regression-selection.png")
            scenario.onActivity {dialog.dismiss()}
        }
    }
    private fun touch(action: Int,down: Long,x: Float,y: Float) {
        val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        instrumentation.sendPointerSync(e);e.recycle()
    }
    private fun doubleTap(x: Float,y: Float) {
        repeat(2) {val down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,x,y);SystemClock.sleep(40);touch(MotionEvent.ACTION_UP,down,x,y);SystemClock.sleep(80)}
    }
    @Test fun bHoldingAndSeekingHaveFeedbackAndRestoreSpeed() {
        ActivityScenario.launch<PlayerActivity>(mediaIntent()).use {scenario ->
            waitPlaying(scenario)
            var x=0f;var y=0f;var width=0;var height=0;var top=0f
            scenario.onActivity {activity ->
                val view=activity.findViewById<PlayerView>(R.id.player_surface);val location=IntArray(2);view.getLocationOnScreen(location)
                width=view.width;height=view.height;top=location[1].toFloat();x=location[0]+view.width*.75f;y=location[1]+view.height*.4f
                view.player!!.setPlaybackSpeed(1.25f)
            }
            val down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,x,y)
            try {
                await("Holding must show 2x and set playback speed to 2x",20000) {var held=false;scenario.onActivity {activity ->held=activity.findViewById<View>(R.id.player_hold_feedback).visibility==View.VISIBLE && activity.findViewById<PlayerView>(R.id.player_surface).player!!.playbackParameters.speed==2f};held}
                capture("regression-hold.png")
            } finally {touch(MotionEvent.ACTION_UP,down,x,y)}
            scenario.onActivity {activity ->assertEquals(1.25f,activity.findViewById<PlayerView>(R.id.player_surface).player!!.playbackParameters.speed,0f);assertEquals(View.GONE,activity.findViewById<View>(R.id.player_hold_feedback).visibility)}
            doubleTap(x,y)
            scenario.onActivity {activity ->assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.player_seek_forward).visibility);assertTrue(activity.findViewById<TextView>(R.id.player_seek_forward).text.contains("10"))}
            capture("regression-seek.png")
            SystemClock.sleep(400);doubleTap(x-width*.5f,y)
            scenario.onActivity {activity ->assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.player_seek_backward).visibility)}
            fun swipe(atX: Float, check: ()->Unit) {
                val started=SystemClock.uptimeMillis();val start=top+height*.65f;val end=top+height*.25f
                touch(MotionEvent.ACTION_DOWN,started,atX,start)
                try {repeat(6) {i ->SystemClock.sleep(25);touch(MotionEvent.ACTION_MOVE,started,atX,start+(end-start)*(i+1)/6f)};check()}
                finally {touch(MotionEvent.ACTION_UP,started,atX,end)}
            }
            val audio=context.getSystemService(android.media.AudioManager::class.java)
            val oldVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            try {
                if(!audio.isVolumeFixed) audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)/3,0)
                val before=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                swipe(x) {
                    scenario.onActivity {activity ->
                        val indicator=activity.findViewById<TextView>(R.id.player_swipe_feedback)
                        assertEquals(View.VISIBLE,indicator.visibility);assertTrue(indicator.text.contains("Громкость"))
                        if(!audio.isVolumeFixed) assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)>before)
                    }
                }
                val systemBefore=android.provider.Settings.System.getInt(context.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128)
                swipe(x-width*.5f) {
                    scenario.onActivity {activity ->
                        val indicator=activity.findViewById<TextView>(R.id.player_swipe_feedback)
                        assertEquals(View.VISIBLE,indicator.visibility);assertTrue(indicator.text.contains("Яркость"));assertTrue(indicator.text.contains("%"))
                        assertTrue(activity.window.attributes.screenBrightness in .01f..1f)
                    }
                    capture("regression-brightness.png")
                }
                assertEquals(systemBefore,android.provider.Settings.System.getInt(context.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128))
            } finally {if(!audio.isVolumeFixed) audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,oldVolume,0)}
        }
    }
    @Test fun cPipKeepsPlayingAndReopenRestoresExactPosition() {
        assertTrue("PiP requires a device supporting this feature",context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
        var saved=0L
        ActivityScenario.launch<PlayerActivity>(mediaIntent()).use {scenario ->
            waitPlaying(scenario)
            lateinit var activity: PlayerActivity
            lateinit var original: androidx.media3.common.Player
            scenario.onActivity {a ->activity=a;original=a.findViewById<PlayerView>(R.id.player_surface).player!!;original.seekTo(12000);a.findViewById<View>(R.id.player_pip).performClick()}
            await("PiP must actually open",30000) {var pip=false;instrumentation.runOnMainSync {pip=activity.isInPictureInPictureMode};pip}
            instrumentation.runOnMainSync {
                val view=activity.findViewById<PlayerView>(R.id.player_surface)
                assertSame(original,view.player);assertTrue(original.playWhenReady);assertFalse(view.useController)
            }
            var miniStart=0L;instrumentation.runOnMainSync {miniStart=original.currentPosition}
            await("Video position must keep advancing inside PiP",30000) {
                var advancing=false;instrumentation.runOnMainSync {assertNull(original.playerError);advancing=original.isPlaying && original.currentPosition>miniStart+500};advancing
            }
            context.sendBroadcast(Intent(PlayerActivity.PIP_PREVIOUS_ACTION).setPackage(context.packageName))
            await("PiP previous action must load the preceding episode",60000) {
                var changed=false;instrumentation.runOnMainSync {changed=activity.findViewById<TextView>(R.id.player_title).text.contains("Сезон 1 — Серия 2")};changed
            }
            context.sendBroadcast(Intent(PlayerActivity.PIP_NEXT_ACTION).setPackage(context.packageName))
            await("PiP next action must cross the season boundary",60000) {
                var changed=false;instrumentation.runOnMainSync {changed=activity.findViewById<TextView>(R.id.player_title).text.contains("Сезон 2 — Серия 3")};changed
            }
            instrumentation.runOnMainSync {original.seekTo(12000)}
            context.sendBroadcast(Intent(PlayerActivity.PIP_PLAY_PAUSE_ACTION).setPackage(context.packageName))
            await("PiP pause action must pause playback",15000) {var paused=false;instrumentation.runOnMainSync {paused=!original.playWhenReady};paused}
            context.sendBroadcast(Intent(PlayerActivity.PIP_PLAY_PAUSE_ACTION).setPackage(context.packageName))
            await("PiP play action must resume playback",15000) {var playing=false;instrumentation.runOnMainSync {playing=original.playWhenReady};playing}
            instrumentation.runOnMainSync {activity.startActivity(Intent(activity,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            await("Expanding PiP must restore fullscreen controls",30000) {
                var expanded=false;instrumentation.runOnMainSync {expanded=!activity.isInPictureInPictureMode && activity.hasWindowFocus() && activity.findViewById<PlayerView>(R.id.player_surface).useController};expanded
            }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            await("Home must enter PiP without stopping video",30000) {var pip=false;instrumentation.runOnMainSync {pip=activity.isInPictureInPictureMode && original.playWhenReady};pip}
            capture("regression-pip.png")
            instrumentation.runOnMainSync {saved=original.currentPosition;activity.finish()}
        }
        assertTrue(saved>=12000)
        assertEquals(WatchSelection("1","2","3"),WatchProgress(settings.prefs).last("42"))
        ActivityScenario.launch<PlayerActivity>(mediaIntent()).use {scenario ->
            waitPlaying(scenario)
            scenario.onActivity {activity ->assertTrue(activity.findViewById<PlayerView>(R.id.player_surface).player!!.currentPosition>=saved-1000)}
        }
    }
}
