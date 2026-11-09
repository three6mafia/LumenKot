package com.lumenkot.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Android lifecycle simulation. Real system PiP is covered separately by instrumentation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackLifecycleTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun prepare() {Settings(context).prefs.edit().clear().commit()}
    @After fun cleanup() {Settings(context).prefs.edit().clear().commit()}
    private fun media(episode: String="1")=PlayerActivity.intent(context,JSONObject().put("title","Fixture").put("film","42").put("voice","7").put("season","1").put("episode",episode).put("url","asset:///missing.mp4"))
    @Test fun pauseKeepsVisiblePipButStopReleasesItAndSavesPosition() {
        val c=Robolectric.buildActivity(PlayerActivity::class.java,media()).setup()
        val a=c.get();val surface=a.findViewById<PlayerView>(R.id.player_surface);val original=surface.player!!
        original.seekTo(43210)
        a.onPictureInPictureModeChanged(true,Configuration());c.pause()
        assertSame(original,surface.player)
        a.onPictureInPictureModeChanged(false,Configuration())
        c.stop()
        assertNull(surface.player);assertTrue(a.isFinishing)
        assertEquals(43210,Settings(context).prefs.getLong("position:42:7:1:1",0))
        c.destroy()
    }
    @Test fun expandingPipDoesNotCloseTheResumedFullscreenSession() {
        val c=Robolectric.buildActivity(PlayerActivity::class.java,media()).setup()
        val a=c.get();a.onPictureInPictureModeChanged(true,Configuration());c.pause()
        a.onPictureInPictureModeChanged(false,Configuration());c.resume()
        assertNotNull(a.findViewById<PlayerView>(R.id.player_surface).player)
        c.pause().stop();assertFalse(a.isFinishing);c.destroy()
    }
    @Test fun closeActionFinishesAndReleasesEvenBeforeSystemStopCallback() {
        Robolectric.buildActivity(PlayerActivity::class.java,media()).setup().use {c ->
            val a=c.get();val surface=a.findViewById<PlayerView>(R.id.player_surface)
            surface.player!!.seekTo(12345)
            val params=PlayerActivity::class.java.getDeclaredMethod("pipParams").apply {isAccessible=true}.invoke(a) as PictureInPictureParams
            assertEquals("Закрыть плеер",params.closeAction!!.title.toString())
            params.closeAction!!.actionIntent.send();shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(a.isFinishing);assertNull(surface.player)
            assertEquals(12345,Settings(context).prefs.getLong("position:42:7:1:1",0))
        }
    }
    @Test fun repeatingSameMediaKeepsPlayerAndDifferentMediaReplacesSessionInPlace() {
        Robolectric.buildActivity(PlayerActivity::class.java,media()).setup().use {c ->
            val a=c.get();val oldSurface=a.findViewById<PlayerView>(R.id.player_surface);val original=oldSurface.player!!
            original.seekTo(9876);c.newIntent(media())
            assertSame(original,a.findViewById<PlayerView>(R.id.player_surface).player)
            assertEquals(9876,original.currentPosition)
            c.newIntent(media("2"))
            assertSame(a,c.get());assertNull(oldSurface.player)
            assertNotSame(original,a.findViewById<PlayerView>(R.id.player_surface).player)
            assertEquals(9876,Settings(context).prefs.getLong("position:42:7:1:1",0))
            val state=Bundle();c.saveInstanceState(state)
            assertEquals("2",JSONObject(state.getString("media")!!).getString("episode"))
        }
    }
    @Test fun hardwareVolumeKeysUseConfiguredStepAndClamp() {
        Settings(context).prefs.edit().putInt("volumeStep",3).commit()
        Robolectric.buildActivity(PlayerActivity::class.java,media()).setup().use {c ->
            val a=c.get();val audio=a.getSystemService(AudioManager::class.java)
            val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);val old=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
                a.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP))
                assertEquals(minOf(4,max),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN))
                assertEquals(maxOf(0,minOf(4,max)-3),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            } finally {audio.setStreamVolume(AudioManager.STREAM_MUSIC,old,0)}
        }
    }
    @Test fun savedEpisodePayloadSurvivesActivityRecreation() {
        val c=Robolectric.buildActivity(PlayerActivity::class.java,media()).setup()
        c.newIntent(media("9"));val state=Bundle();c.saveInstanceState(state);c.pause().stop().destroy()
        Robolectric.buildActivity(PlayerActivity::class.java,media()).create(state).use {restored ->
            assertTrue(restored.get().findViewById<android.widget.TextView>(R.id.player_title).text.contains("Серия 9"))
        }
    }
}
