package com.lumenkot.app

import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeRuntimeTest {
    companion object {
        private lateinit var server: okhttp3.mockwebserver.MockWebServer
        private lateinit var base: String
        @JvmStatic @BeforeClass fun startFixtureServer() {
            server=okhttp3.mockwebserver.MockWebServer()
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            base=server.url("/").toString().trimEnd('/')
            val card="""<div class="b-content__inline_item" data-id="42"><div class="b-content__inline_item-link"><a href="/films/42-native.html">Проверка Kotlin</a></div></div>"""
            server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path=request.path.orEmpty().substringBefore('?')
                    val response=okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/html; charset=utf-8")
                    val body=when {
                        path.endsWith(".html") -> """<div class="b-post__title"><h1>Проверка Kotlin</h1></div><div id="user-favorites-holder" data-post_id="42"></div><li class="b-translator__item" data-translator_id="1" title="Тест"></li>"""
                        path.startsWith("/ajax/get_cdn_series") -> """{"success":true,"url":"[720p]$base/video.mp4"}"""
                        path.startsWith("/ajax/login") -> {
                            response.addHeader("Set-Cookie","dle_user_id=1; Path=/; HttpOnly")
                            response.addHeader("Set-Cookie","dle_password=fixture; Path=/; HttpOnly")
                            """{"success":true}"""
                        }
                        path.startsWith("/user/") -> "<title>Fixture user</title>"
                        path=="/favorites" -> """<div class="b-favorites_content__cats_list_item" data-cat_id="1"><b class="name">Избранное</b></div>"""
                        path.startsWith("/ajax/get_comments") -> """{"comments":"<div class='comments-tree-item' data-id='1'><div class='text'><div>Нативные комментарии</div></div></div>","navigation":""}"""
                        else -> card
                    }
                    return response.setBody(body)
                }
            }
        }
        @JvmStatic @AfterClass fun stopFixtureServer() { server.shutdown() }
    }

    private lateinit var settings: Settings
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        settings=Settings(context);settings.prefs.edit().clear().commit()
        settings.provider=base;settings.official=false
    }
    @After fun cleanup() { settings.prefs.edit().clear().commit() }
    @Test fun nativeActivityCreatesWithLightTheme() {
        settings.prefs.edit().putString("theme","light").commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller -> assertFalse(controller.get().isFinishing) }
    }
    @Test fun nativeActivityCreatesWithDarkTheme() {
        settings.prefs.edit().putString("theme","dark").commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller -> assertFalse(controller.get().isFinishing) }
    }
    @Test fun cookiesPersistWithoutCrossingMirrors() {
        val api=RezkaApi(settings);api.login("fixture","fixture")
        assertTrue(RezkaApi(settings).cookies.signedIn())
        assertTrue(api.profile().contains("Fixture user"))
        assertEquals("Избранное",api.bookmarks().single().title)
        assertTrue(api.cookies.loadForRequest("https://rezka.ag".toHttpUrl()).isEmpty())
        assertTrue(api.cookies.loadForRequest("https://stream.voidboost.cc".toHttpUrl()).isEmpty())
    }
    @Test fun nativeHttpCatalogFilmSearchCommentsAndVideo() {
        val api=RezkaApi(settings)
        assertEquals("42",api.catalog().cards.single().id)
        val film=api.film("/films/42-native.html")
        assertEquals("Проверка Kotlin",film.card.title)
        assertEquals("720p",api.video(film,film.voices.single()).streams.single().quality)
        assertEquals("Проверка Kotlin",api.search("Kotlin",1).cards.single().title)
        assertEquals("Нативные комментарии",api.comments("42",1).first.single().text)
    }
    @Test fun localHistoryDeduplicatesAndPersists() {
        val c=Card("42","Фильм","/films/42-native.html")
        settings.history(c);settings.history(c)
        assertEquals(listOf(c),Settings(ApplicationProvider.getApplicationContext()).history())
    }
}
