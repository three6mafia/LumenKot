package com.lumenkot.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VisualLayoutTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun phoneShowsPinnedNavigationAndAlignedCards()=render("phone")
    @Test @Config(qualifiers="w800dp-h1000dp-mdpi") fun tabletShowsPinnedNavigationAndAlignedCards()=render("tablet")
    private fun render(name: String) {
        val settings=Settings(ApplicationProvider.getApplicationContext())
        MockWebServer().use { server ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest)=MockResponse().setBody((1..6).joinToString("") { id ->
                    """<div class="b-content__inline_item" data-id="$id"><div class="b-content__inline_item-link"><a href="/films/$id.html">${if(id==1) "Недолго и несчастливо" else if(id==2) "Драйв — Притворщики" else "Название фильма $id"}</a><div>2026, Драма</div></div></div>"""
                })
            }
            server.start();settings.prefs.edit().clear().putString("theme","light").commit();settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    compose.waitUntil(10000) {
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                        compose.onAllNodesWithText("Недолго и несчастливо").fetchSemanticsNodes().isNotEmpty()
                    }
                    val home=compose.onNodeWithContentDescription("Главная").fetchSemanticsNode().boundsInRoot
                    val account=compose.onNodeWithContentDescription("Аккаунт").fetchSemanticsNode().boundsInRoot
                    check(home.top==account.top && home.bottom==account.bottom)
                    val first=compose.onNodeWithText("Недолго и несчастливо").fetchSemanticsNode().boundsInRoot
                    val second=compose.onNodeWithText("Драйв — Притворщики").fetchSemanticsNode().boundsInRoot
                    check(first.top==second.top && first.height==second.height)
                    scenario.onActivity { activity ->
                        val view=activity.window.decorView
                        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        check(bitmap.getPixel(20,bitmap.height-2)==android.graphics.Color.WHITE) { "Content overlaps bottom navigation" }
                        File("build/ui-review").mkdirs()
                        File("build/ui-review/$name.png").outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG,100,output) }
                    }
                }
            } finally { settings.prefs.edit().clear().commit() }
        }
    }
}
