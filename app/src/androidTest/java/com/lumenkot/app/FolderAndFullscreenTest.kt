package com.lumenkot.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A dedicated emulator only: uses DocumentsUI to grant a fresh fixture folder, and real system PiP. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FolderAndFullscreenTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(message: String,condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<end) {if(condition()) return;SystemClock.sleep(200)}
        fail(message)
    }
    private fun find(node: AccessibilityNodeInfo?,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        if(node==null) return null
        if(predicate(node)) return node
        for(i in 0 until node.childCount) find(node.getChild(i),predicate)?.let {return it}
        return null
    }
    private fun setup(): Settings=Settings(context).apply {prefs.edit().clear().putBoolean("downloadPoster",false).putBoolean("downloadSubtitles",false).commit();provider="http://127.0.0.1:8765";official=false}
    @Test fun pickedFolderStoresMp4AndHlsAndBothPlayWithTheHttpSourceBlocked() {
        val settings=setup();val api=RezkaApi(settings);val repository=HlsDownloads.get(context)
        val name="LumenKotFixture"+SystemClock.uptimeMillis()
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/$name").use {descriptor ->java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes()}}
        val tree=DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents","primary:Download/$name")
        settings.prefs.edit().putString("downloadTree",tree.toString()).commit()
        val ids=mutableListOf<String>()
        try {
            api.request("/hls-control",mapOf("blocked" to "0"))
            val film=api.film("/films/42-native.html")
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {activity ->
                    val method=MainActivity::class.java.getDeclaredMethod("startDownload",Film::class.java,Voice::class.java,Stream::class.java,String::class.java,String::class.java,List::class.java).apply {isAccessible=true}
                    method.invoke(activity,film,film.voices.first(),Stream("720p","http://127.0.0.1:8765/video.mp4"),"","",emptyList<Subtitle>())
                    val dialog=MainActivity::class.java.getDeclaredField("destinationDialog").apply {isAccessible=true}.get(activity) as AlertDialog
                    dialog.findViewById<View>(R.id.pick_download_folder).performClick()
                }
                var choose: AccessibilityNodeInfo?=null
                waitFor("DocumentsUI must show the fixture folder") {
                    choose=find(instrumentation.uiAutomation.rootInActiveWindow) {node ->val text=node.text?.toString().orEmpty().lowercase();node.isClickable && node.isEnabled && (text=="use this folder" || text=="использовать эту папку")};choose!=null
                }
                assertTrue(choose!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                var allow: AccessibilityNodeInfo?=null
                waitFor("Folder permission confirmation must appear") {
                    allow=find(instrumentation.uiAutomation.rootInActiveWindow) {node ->val text=node.text?.toString().orEmpty().lowercase();node.isClickable && (text=="allow" || text=="разрешить")};allow!=null
                }
                assertTrue(allow!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("Read/write folder access must be persisted") {DownloadLocations(context).hasAccess(tree)}
                scenario.onActivity {activity ->
                    val dialog=MainActivity::class.java.getDeclaredField("destinationDialog").apply {isAccessible=true}.get(activity) as AlertDialog
                    dialog.findViewById<Switch>(R.id.remember_download_path).isChecked=true
                    repository.manager.requirements=Requirements(0)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                waitFor("MP4 must be saved to the selected folder") {
                    val rows=repository.list();rows.forEach {if(it.request.id !in ids) ids.add(it.request.id)}
                    rows.any {it.state==Download.STATE_COMPLETED && HlsDownloads.metadata(it.request).optString("treeUri")==tree.toString()}
                }
            }
            assertTrue(DownloadLocations(context).always)
            val hls=repository.prepare(film,film.voices.first(),"","",Stream("720p","http://127.0.0.1:8765/hls/master.m3u8"),emptyList(),tree)
            ids.add(hls.id)
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {repository.add(it,hls)}
                waitFor("HLS package must finish saving") {repository.index.getDownload(hls.id)?.state==Download.STATE_COMPLETED}
            }
            val before=JSONObject(api.request("/hls-control",mapOf("blocked" to "1"))).getInt("requests")
            for(id in ids.distinct()) {
                val payload=repository.offlinePayload(repository.offlineRequest(id))
                ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(context,payload)).use {scenario ->
                    waitFor("Saved media must advance from the chosen document tree") {
                        var played=false;scenario.onActivity {activity ->activity.findViewById<PlayerView>(R.id.player_surface).player?.let {player ->assertNull(player.playerError);played=player.isPlaying && player.currentPosition>500}};played
                    }
                }
            }
            assertEquals(before,JSONObject(api.request("/hls-control")).getInt("requests"))
        } finally {
            runCatching {api.request("/hls-control",mapOf("blocked" to "0"))}
            ids.distinct().forEach {id ->instrumentation.runOnMainSync {repository.remove(context,id)}}
            waitFor("Downloaded packages must be removed") {ids.all {repository.index.getDownload(it)==null}}
            instrumentation.runOnMainSync {repository.manager.requirements=Requirements(Requirements.NETWORK)}
            settings.prefs.edit().clear().commit()
        }
    }
    @Test fun pipExpansionRestoresEdgeToEdgeVideoAndControllerHidesTheToolbar() {
        val settings=setup()
        assertTrue("Requires real system PiP",context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
        val intent=PlayerActivity.intent(context,JSONObject().put("title","PiP fullscreen fixture").put("url","http://127.0.0.1:8765/video.mp4"))
        try {
            ActivityScenario.launch<PlayerActivity>(intent).use {scenario ->
                lateinit var activity: PlayerActivity
                waitFor("Video must actually play") {
                    var ready=false
                    scenario.onActivity {current ->
                        activity=current
                        val view=current.findViewById<PlayerView>(R.id.player_surface)
                        view.setControllerAnimationEnabled(false)
                        view.player?.let {player ->player.repeatMode=Player.REPEAT_MODE_ONE;assertNull(player.playerError);ready=current.hasWindowFocus() && player.isPlaying}
                    };ready
                }
                repeat(2) {
                    instrumentation.runOnMainSync {activity.findViewById<PlayerView>(R.id.player_surface).showController();activity.findViewById<View>(R.id.player_pip).performClick()}
                    waitFor("System PiP must open") {var mini=false;instrumentation.runOnMainSync {mini=activity.isInPictureInPictureMode};mini}
                    instrumentation.runOnMainSync {activity.startActivity(Intent(activity,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
                    waitFor("Fullscreen must have no status-bar inset or video offset") {
                        var full=false;instrumentation.runOnMainSync {
                            val view=activity.findViewById<PlayerView>(R.id.player_surface);val video=IntArray(2);val decor=IntArray(2);view.getLocationOnScreen(video);activity.window.decorView.getLocationOnScreen(decor)
                            full=!activity.isInPictureInPictureMode && activity.hasWindowFocus() && ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.systemBars())==false && video[1]==decor[1] && view.height==activity.window.decorView.height
                        };full
                    }
                    waitFor("Media3 timeout must hide both the controls and top toolbar") {var hidden=false;instrumentation.runOnMainSync {hidden=!activity.findViewById<PlayerView>(R.id.player_surface).isControllerFullyVisible && activity.findViewById<View>(R.id.player_toolbar).visibility!=View.VISIBLE};hidden}
                }
            }
        } finally {settings.prefs.edit().clear().commit()}
    }
}
