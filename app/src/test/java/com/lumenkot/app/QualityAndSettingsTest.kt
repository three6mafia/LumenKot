package com.lumenkot.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class QualityAndSettingsTest {
    private val settings=Settings(ApplicationProvider.getApplicationContext())
    @Before fun setup() {settings.prefs.edit().clear().commit();settings.official=false}
    @After fun cleanup() {settings.prefs.edit().clear().commit()}
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
    private fun untilReady(condition: ()->Boolean) {
        val end=System.nanoTime()+5_000_000_000L
        while(System.nanoTime()<end) {shadowOf(android.os.Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)}
        fail("Dialog did not finish loading")
    }
    private fun film()=Parser.film("<div class='b-post__title'><h1>Fixture</h1></div><li class='b-translator__item' data-translator_id='7'>Voice</li>","/films/42-fixture.html")
    @Test fun preplayQualityWaitsForSelectionAndPremiumPlaceholderDoesNotStartPlayback() {
        MockWebServer().use {server ->
            server.start();settings.provider=server.url("/").toString().trimEnd('/')
            settings.prefs.edit().putBoolean("askQuality",true).putBoolean("saveQuality",false).putString("quality","1080p").commit()
            server.enqueue(MockResponse().setBody(JSONObject().put("success",true).put("url","[720p]https://example.com/720.mp4,[1080p Ultra]Premium required").toString()))
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use {c ->
                var selected: Video?=null
                val dialog=VideoSelectionDialog(c.get(),RezkaApi(settings),film(),c.get().lifecycleScope,false) {_,_,_,_,video ->selected=video}
                dialog.show()
                untilReady {dialog.findViewById<View>(R.id.video_quality_options).visibility==View.VISIBLE}
                assertNull(selected)
                val buttons=descendants(dialog.window!!.decorView).filterIsInstance<Button>()
                buttons.first {it.text.contains("1080p Ultra")}.performClick()
                assertNull(selected);assertTrue(dialog.isShowing)
                assertTrue(dialog.findViewById<TextView>(R.id.video_selection_status).text.contains("Premium"))
                buttons.first {it.text=="720p"}.performClick()
                assertFalse(dialog.isShowing);assertEquals("720p",selected!!.selectedQuality)
                assertEquals("1080p",settings.quality)
            }
        }
    }
    @Test fun downloadAlwaysAsksQualityAndDoesNotChangePlayerPreference() {
        MockWebServer().use {server ->
            server.start();settings.provider=server.url("/").toString().trimEnd('/')
            settings.prefs.edit().putBoolean("askQuality",false).putString("quality","720p").commit()
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[1080p]https://example.com/1080.mp4"}"""))
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use {c ->
                var selected: Video?=null
                val dialog=VideoSelectionDialog(c.get(),RezkaApi(settings),film(),c.get().lifecycleScope,true) {_,_,_,_,v ->selected=v}
                dialog.show();untilReady {dialog.findViewById<View>(R.id.video_quality_options).visibility==View.VISIBLE}
                assertNull(selected)
                descendants(dialog.window!!.decorView).filterIsInstance<Button>().first {it.text=="1080p"}.performClick()
                assertEquals("1080p",selected!!.selectedQuality);assertEquals("720p",settings.quality)
            }
        }
    }
    @Test fun disablingAskUsesPreferenceAndRemembersOnlyWhenEnabled() {
        MockWebServer().use {server ->
            server.start();settings.provider=server.url("/").toString().trimEnd('/')
            settings.prefs.edit().putBoolean("askQuality",false).putString("quality","1080p Ultra").commit()
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[720p]https://example.com/720.mp4,[1080p Ultra]Premium"}"""))
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use {c ->
                var selected: Video?=null
                val dialog=VideoSelectionDialog(c.get(),RezkaApi(settings),film(),c.get().lifecycleScope,false) {_,_,_,_,v ->selected=v}
                dialog.show();untilReady {selected!=null}
                assertFalse(dialog.isShowing);assertEquals("720p",selected!!.selectedQuality);assertEquals("720p",settings.quality)
            }
        }
    }
    @Test fun apiPremiumRefusalIsActionableAndCustomCdnKeepsLockedQualitySafe() {
        MockWebServer().use {server ->
            server.start();settings.provider=server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"success":false,"message":"<b>Требуется Premium</b>"}"""))
            val api=RezkaApi(settings)
            val error=runCatching {api.video(film(),film().voices.single())}.exceptionOrNull()
            assertTrue(error is PremiumRequiredException);assertTrue(error!!.message!!.contains("выберите другое качество"))
            settings.cdn="https://stream.voidboost.cc"
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"[720p]https://example.com/video.mp4,[1080p Ultra]Premium"}"""))
            val video=api.video(film(),film().voices.single())
            assertEquals("https://stream.voidboost.cc/video.mp4",video.streams.first().url)
            assertFalse(video.streams.last().available)
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun deniedUltraOffersQualityChangeWithoutRetryingTheSameUrl() {
        MockWebServer().use {server ->
            server.start();server.enqueue(MockResponse().setResponseCode(403))
            val context=ApplicationProvider.getApplicationContext<android.content.Context>()
            val streams=org.json.JSONArray().put(JSONObject().put("quality","720p").put("url",server.url("/720.mp4"))).put(JSONObject().put("quality","1080p Ultra").put("url",server.url("/ultra.mp4")))
            val media=JSONObject().put("title","Premium fixture").put("url","asset:///missing.mp4").put("quality","1080p Ultra").put("streams",streams)
            Robolectric.buildActivity(PlayerActivity::class.java,PlayerActivity.intent(context,media)).setup().use {c ->
                val a=c.get();val error=a.findViewById<TextView>(R.id.player_error)
                // Drive a real Media3 HTTP data source, then deliver its error through the player callback path.
                val source=androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(RezkaApi(settings).client).createDataSource()
                val denied=try {runCatching {source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(server.url("/ultra.mp4").toString())))}.exceptionOrNull()!!} finally {source.close()}
                a.showPlaybackError(androidx.media3.common.PlaybackException("HTTP 403",denied,androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
                assertEquals(View.VISIBLE,error.visibility)
                assertTrue(error.text.contains("Premium"));assertEquals(1,server.requestCount)
                error.performClick()
                val chooser=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                server.enqueue(MockResponse().setResponseCode(404))
                chooser.listView.performItemClick(null,0,0)
                assertEquals(server.url("/720.mp4").toString(),a.findViewById<androidx.media3.ui.PlayerView>(R.id.player_surface).player!!.currentMediaItem!!.localConfiguration!!.uri.toString())
                assertEquals("720p",settings.quality)
            }
        }
    }
    @Test fun mobileSettingsOpenFiveOriginalGroupsAndOnlyOneModal() {
        Robolectric.buildActivity(Activity::class.java).setup().use {c ->
            val host=LinearLayout(c.get());c.get().setContentView(host)
            val screen=SettingsScreen(c.get(),settings,{}, {}, {})
            screen.render(host)
            val groupList=host.findViewById<LinearLayout>(R.id.settings_groups)
            val names=descendants(groupList).filterIsInstance<TextView>().map {it.text.toString()}.filter {it!="›"}
            assertEquals(listOf("Интерфейс","Сетевые настройки","Загрузки","Плеер","О приложении"),names)
            val player=groupList.getChildAt(3)
            player.performClick();val first=ShadowDialog.getLatestDialog()
            player.performClick();assertSame(first,ShadowDialog.getLatestDialog())
            val text=descendants(first.window!!.decorView).filterIsInstance<TextView>().map {it.text.toString()}
            assertTrue(text.contains("Шаг громкости"));assertTrue(text.contains("Всегда спрашивать качество"))
            val ask=descendants(first.window!!.decorView).filterIsInstance<android.widget.Switch>().first {it.contentDescription=="Всегда спрашивать качество"}
            assertTrue(ask.isChecked)
            ask.isChecked=false;assertFalse(settings.prefs.getBoolean("askQuality",true))
            ask.isChecked=true;assertTrue(settings.prefs.getBoolean("askQuality",false))
            val volume=descendants(first.window!!.decorView).filterIsInstance<TextView>().first {it.text=="Шаг громкости"}
            (volume.parent.parent as View).performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,2,2)
            assertEquals(3,Settings(c.get()).volumeStep)
            screen.dismiss()
        }
    }
    @Test @Config(qualifiers="television") fun tvSettingsKeepGroupsBesideTheirContent() {
        Robolectric.buildActivity(Activity::class.java).setup().use {c ->
            val host=LinearLayout(c.get());c.get().setContentView(host)
            val screen=SettingsScreen(c.get(),settings,{}, {}, {});screen.render(host)
            val groups=host.findViewById<LinearLayout>(R.id.settings_groups)
            groups.getChildAt(3).performClick()
            val content=host.findViewById<LinearLayout>(R.id.settings_content)
            assertNotNull(content)
            assertTrue(descendants(content).filterIsInstance<TextView>().any {it.text=="Воспроизведение по нажатию"})
            assertSame(groups.parent,content.parent);screen.dismiss()
        }
    }
}
