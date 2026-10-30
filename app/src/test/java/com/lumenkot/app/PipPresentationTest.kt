package com.lumenkot.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Presentation/lifecycle simulation; not evidence of a real system PiP window. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class PipPresentationTest {
    @After fun cleanup() {Settings(ApplicationProvider.getApplicationContext()).prefs.edit().clear().commit()}
    private fun intent(episode: String="1", series: Boolean=true): Intent {
        val data=JSONObject().put("title","Fixture").put("url","https://example.invalid/video.mp4").put("film","42").put("filmUrl","/series/42-film.html")
        if(series) data.put("season","1").put("episode",episode).put("seasons",org.json.JSONArray("""[{"id":"1","title":"Сезон 1","episodes":[{"id":"1","title":"Серия 1"},{"id":"2","title":"Серия 2"}]}]"""))
        return Intent(ApplicationProvider.getApplicationContext(),PlayerActivity::class.java).putExtra("media",data.toString())
    }
    @Test fun systemActionsMatchEpisodeBoundariesAndMoviesOnlyHavePlay() {
        for(episode in listOf("1","2")) {
            Robolectric.buildActivity(PlayerActivity::class.java,intent(episode)).create().use {controller ->
                val method=PlayerActivity::class.java.getDeclaredMethod("pipParams").apply {isAccessible=true}
                val params=method.invoke(controller.get()) as PictureInPictureParams
                assertEquals(3,params.actions.size)
                assertEquals("Предыдущая серия",params.actions[0].title.toString())
                assertEquals("Следующая серия",params.actions[2].title.toString())
                assertEquals(episode=="2",params.actions[0].isEnabled);assertEquals(episode=="1",params.actions[2].isEnabled)
                assertNotNull(params.aspectRatio)
                assertTrue(params.aspectRatio!!.toFloat() in .42f..2.39f)
            }
        }
        Robolectric.buildActivity(PlayerActivity::class.java,intent(series=false)).create().use {controller ->
            val params=PlayerActivity::class.java.getDeclaredMethod("pipParams").apply {isAccessible=true}.invoke(controller.get()) as PictureInPictureParams
            assertEquals(1,params.actions.size);assertEquals("Воспроизвести",params.actions.single().title.toString())
        }
    }
    @Test fun leavingMiniModeRestoresFullscreenController() {
        Robolectric.buildActivity(PlayerActivity::class.java,intent()).create().use {controller ->
            val activity=controller.get();val view=activity.findViewById<PlayerView>(R.id.player_surface)
            // A controller needs a player; use a decoder-free idle player in this presentation test.
            val fake=object: androidx.media3.common.SimpleBasePlayer(android.os.Looper.getMainLooper()) {
                override fun getState()=State.Builder().build()
            }
            view.player=fake;view.setControllerAnimationEnabled(false)
            activity.onPictureInPictureModeChanged(true,Configuration())
            assertFalse(view.useController)
            activity.onPictureInPictureModeChanged(false,Configuration())
            assertTrue(view.useController);assertTrue(view.isControllerFullyVisible)
            view.player=null;fake.release()
        }
    }
    @Test fun wiredSwipeCallbacksAdjustMusicAndWindowWithoutWritingSystemBrightness() {
        Robolectric.buildActivity(PlayerActivity::class.java,intent()).create().use {controller ->
            val activity=controller.get();val root=activity.findViewById<PlayerView>(R.id.player_surface).parent as PlayerGestureLayout
            val before=android.provider.Settings.System.getInt(activity.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128)
            root.verticalStart(false);root.verticalChange(1f)
            assertEquals(1f,activity.window.attributes.screenBrightness,0f)
            assertTrue(activity.findViewById<TextView>(R.id.player_swipe_feedback).text.contains("100%"))
            assertEquals(before,android.provider.Settings.System.getInt(activity.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128))
            activity.onPictureInPictureModeChanged(true,Configuration());assertEquals(-1f,activity.window.attributes.screenBrightness,0f)
            activity.onPictureInPictureModeChanged(false,Configuration());assertEquals(1f,activity.window.attributes.screenBrightness,0f)
            val audio=activity.getSystemService(AudioManager::class.java)
            val old=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            try {
                root.verticalStart(true);root.verticalChange(1f)
                assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            } finally {audio.setStreamVolume(AudioManager.STREAM_MUSIC,old,0)}
            root.verticalEnd()
        }
    }
}
