package com.lumenkot.app

import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveSearchTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun typingDisplaysCardsWithoutSearchButtonAndClearingRemovesThem() {
        val settings=Settings(ApplicationProvider.getApplicationContext())
        val queries=CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val term=request.requestUrl?.queryParameter("q")
                    if(term!=null) queries.add(term)
                    val html=if(term==null) "" else """<div class="b-content__inline_item" data-id="42"><div class="b-content__inline_item-link"><a href="/films/42.html">$term · результат</a></div></div>"""
                    return MockResponse().setBody(html)
                }
            }
            server.start();settings.prefs.edit().clear().commit();settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                    compose.waitForIdle()
                    compose.onNodeWithContentDescription("Поиск").performClick()
                    compose.waitForIdle()
                    scenario.onActivity { views(it.window.decorView).filterIsInstance<AutoCompleteTextView>().single().setText("Гарри Поттер") }
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
                    try { compose.waitUntil(10000) {
                        compose.waitForIdle()
                        var found=false
                        found=compose.onAllNodesWithText("Гарри Поттер · результат").fetchSemanticsNodes().isNotEmpty()
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                        found
                    }
                    } catch(e: androidx.compose.ui.test.ComposeTimeoutException) {
                        var labels=""
                        scenario.onActivity { labels=views(it.window.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() } }
                        throw AssertionError("Queries=$queries; UI=$labels",e)
                    }
                    assertTrue(queries.contains("Гарри Поттер"))
                    scenario.onActivity { views(it.window.decorView).filterIsInstance<AutoCompleteTextView>().single().setText("") }
                    compose.waitForIdle()
                    compose.onNodeWithText("Гарри Поттер · результат").assertDoesNotExist()
                }
            } finally { settings.prefs.edit().clear().commit() }
        }
    }
}
