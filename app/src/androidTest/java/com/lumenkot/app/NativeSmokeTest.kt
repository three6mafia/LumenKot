package com.lumenkot.app

import android.content.Intent
import android.os.SystemClock
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Requires tools/smoke_server.py on the emulator host. No real accounts or services. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var settings: Settings
    private lateinit var api: RezkaApi
    @Before fun setup() {
        settings=Settings(context);settings.prefs.edit().clear().commit()
        settings.provider="http://127.0.0.1:8765";settings.official=false;api=RezkaApi(settings)
    }
    @After fun cleanup() { settings.prefs.edit().clear().commit() }
    @Test fun catalogFilmAndStreamUseNativeApi() {
        val catalog=api.catalog()
        assertEquals("Проверка Kotlin",catalog.cards.single().title)
        val film=api.film(catalog.cards.single().url)
        assertEquals("42",film.card.id)
        assertEquals("1",film.voices.single().id)
        val video=api.video(film,film.voices.single())
        assertEquals("http://127.0.0.1:8765/video.mp4",video.streams.single().url)
        assertEquals("Нативные комментарии",api.comments("42",1).first.single().text)
        assertEquals("Проверка Kotlin",api.search("Kotlin",1).cards.single().title)
    }
    @Test fun loginCookiesPersistAndRespectHostScope() {
        api.login("fixture","fixture")
        assertTrue(api.cookies.signedIn())
        val restored=RezkaApi(Settings(context));assertTrue(restored.cookies.signedIn())
        assertEquals("Избранное",restored.bookmarks().single().title)
        assertTrue(restored.profile().contains("Fixture user"))
        settings.provider="https://rezka.ag"
        assertFalse(RezkaApi(settings).cookies.signedIn())
    }
    @Test fun localHistoryPersists() {
        settings.history(Card("42","Фильм","/films/42-native.html"))
        settings.history(Card("42","Фильм","/films/42-native.html"))
        assertEquals(1,Settings(context).history().size)
        settings.clearHistory();assertTrue(settings.history().isEmpty())
    }
    @Test fun nativeMainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { assertFalse(it.isFinishing) } }
    }
    @Test fun media3PlaysLocalMp4() {
        val intent=Intent(context,PlayerActivity::class.java).putExtra("media",JSONObject().put("title","Native smoke").put("url","http://127.0.0.1:8765/video.mp4").toString())
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            var played=false
            val deadline=SystemClock.elapsedRealtime()+120000
            while(!played && SystemClock.elapsedRealtime()<deadline) {
                scenario.onActivity { activity ->
                    val player=activity.findViewById<PlayerView>(R.id.player_surface).player
                    assertNotNull(player);assertNull(player!!.playerError)
                    played=player.currentPosition>500 && player.duration>=3000
                }
                if(!played) SystemClock.sleep(500)
            }
            assertTrue("Media3 must actually advance through the MP4",played)
        }
    }
}
