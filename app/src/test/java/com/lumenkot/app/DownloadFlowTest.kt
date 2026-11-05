package com.lumenkot.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.shadows.ShadowAlertDialog

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadFlowTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val settings=Settings(context)
    private lateinit var server: MockWebServer
    private val film=Parser.film("<div class='b-post__title'><h1>Fixture</h1></div><li class='b-translator__item' data-translator_id='7'>Voice</li>","/films/42-fixture.html")
    @Before fun setup() {
        settings.prefs.edit().clear().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit()
        server=MockWebServer();server.start();settings.provider=server.url("/").toString().trimEnd('/');settings.official=false
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest)=MockResponse().setBody("")}
    }
    @After fun cleanup() {HlsDownloads.resetForTests();server.shutdown();settings.prefs.edit().clear().commit()}
    private fun menu(activity: MainActivity,url: String): AlertDialog {
        val method=MainActivity::class.java.getDeclaredMethod("deliverVideo",Film::class.java,Voice::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType,List::class.java,Video::class.java).apply {isAccessible=true}
        method.invoke(activity,film,film.voices.single(),"1","2",true,emptyList<Season>(),Video(listOf(Stream("720p",url)),selectedQuality="720p"))
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    @Test fun downloadMenuOffersTwoExplicitActionsAndSharingKeepsTheWholeSelectedUrl() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            val url=server.url("/master.m3u8?token=fixture&variant=720").toString()
            val dialog=menu(c.get(),url)
            assertEquals(listOf("Скачать","Поделиться ссылкой"),(0 until dialog.listView.adapter.count).map {dialog.listView.adapter.getItem(it).toString()})
            assertTrue(settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty())
            assertFalse(settings.prefs.getBoolean("hasHlsDownloads",false))
            dialog.listView.performItemClick(null,1,1)
            val chooser=shadowOf(c.get()).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            val share=chooser.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND,share.action);assertEquals("text/plain",share.type)
            assertEquals(url,share.getStringExtra(Intent.EXTRA_TEXT));assertNull(share.getStringExtra("Cookie"))
            assertFalse(settings.prefs.getBoolean("hasHlsDownloads",false))
        }
    }
    @Test fun cancelingDownloadActionsDoesNotQueueAnything() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            menu(c.get(),server.url("/master.m3u8").toString()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(settings.prefs.getBoolean("hasHlsDownloads",false))
            assertTrue(settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty())
        }
    }
    @Test fun mp4StillQueuesThroughAndroidDownloadManagerAfterExplicitDownload() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            val dialog=menu(c.get(),server.url("/video.mp4?token=fixture").toString())
            dialog.listView.performItemClick(null,0,0)
            val destination=ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(dialog,destination)
            destination.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val end=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<end && settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty()) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertEquals(1,settings.prefs.getStringSet("downloads",emptySet())!!.size)
            assertFalse(settings.prefs.getBoolean("hasHlsDownloads",false))
        }
    }
    @Test fun folderIsAskedOnEachDownloadWhenRememberIsDisabled() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            menu(c.get(),server.url("/video.mp4").toString()).listView.performItemClick(null,0,0)
            val folder=ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(folder.findViewById<android.view.View>(R.id.pick_download_folder))
            assertFalse(folder.findViewById<android.widget.Switch>(R.id.remember_download_path).isChecked)
            folder.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty())
            menu(c.get(),server.url("/video.mp4").toString()).listView.performItemClick(null,0,0)
            assertNotSame(folder,ShadowAlertDialog.getLatestAlertDialog())
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }
    @Test fun rememberedAppFolderSkipsTheLocationPromptAndDisablingItRestoresThePrompt() {
        settings.prefs.edit().putBoolean("rememberDownloadPath",true).commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            val menu=menu(c.get(),server.url("/video.mp4").toString());menu.listView.performItemClick(null,0,0)
            val end=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<end && settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty()) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertEquals(1,settings.prefs.getStringSet("downloads",emptySet())!!.size)
            settings.prefs.edit().putBoolean("rememberDownloadPath",false).commit()
            menu(c.get(),server.url("/second.mp4").toString()).listView.performItemClick(null,0,0)
            assertNotNull(ShadowAlertDialog.getLatestAlertDialog().findViewById<android.view.View>(R.id.pick_download_folder))
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }
    @Test fun recreatingTheActivityKeepsThePendingDownloadAndUnconfirmedRememberChoice() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {c ->
            menu(c.get(),server.url("/video.mp4").toString()).listView.performItemClick(null,0,0)
            ShadowAlertDialog.getLatestAlertDialog().findViewById<android.widget.Switch>(R.id.remember_download_path).isChecked=true
            assertFalse(DownloadLocations(c.get()).always)
            c.recreate()
            val restored=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(restored.findViewById<android.widget.Switch>(R.id.remember_download_path).isChecked)
            assertTrue(settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty())
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val end=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<end && settings.prefs.getStringSet("downloads",emptySet())!!.isEmpty()) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertTrue(DownloadLocations(c.get()).always);assertEquals(1,settings.prefs.getStringSet("downloads",emptySet())!!.size)
        }
    }
}
