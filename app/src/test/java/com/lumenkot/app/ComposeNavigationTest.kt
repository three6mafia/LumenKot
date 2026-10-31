package com.lumenkot.app

import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun preservesPreviousMenuAndSwitchesScreens() {
        val settings=Settings(ApplicationProvider.getApplicationContext())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html></html>"));server.start()
            settings.prefs.edit().clear().commit()
            settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    fun views(view: View): List<View> = listOf(view)+
                        if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
                    compose.waitForIdle()
                    scenario.onActivity { activity ->
                        val tree=views(activity.window.decorView)
                        assertTrue(tree.filterIsInstance<TextView>().any { it.text.toString()=="LUMEN" })
                        tree.filterIsInstance<Button>().first { it.text.toString()=="Поиск" }.performClick()
                    }
                    compose.waitForIdle()
                    scenario.onActivity { activity ->
                        val tree=views(activity.window.decorView)
                        assertTrue(tree.any { it is AutoCompleteTextView })
                        assertTrue(tree.filterIsInstance<Button>().any { it.text.toString()=="Загрузки" })
                    }
                }
            } finally { settings.prefs.edit().clear().commit() }
        }
    }
}
