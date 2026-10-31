package com.lumenkot.app

import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerLifecycleTest {
    private fun intent()=Intent(ApplicationProvider.getApplicationContext(),PlayerActivity::class.java)
        .putExtra("media",JSONObject().put("url","file:///does-not-exist/test.mp4").toString())
    @Test fun dismissingPipFinishesPlayerTask() {
        val controller=Robolectric.buildActivity(PlayerActivity::class.java,intent()).create().start().resume()
        try {
            val activity=controller.get()
            activity.onPictureInPictureModeChanged(true,Configuration())
            controller.pause().stop()
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }
    @Test fun returningFromPipThenBackgroundingDoesNotFinishTask() {
        val controller=Robolectric.buildActivity(PlayerActivity::class.java,intent()).create().start().resume()
        try {
            controller.get().onPictureInPictureModeChanged(true,Configuration())
            controller.get().onPictureInPictureModeChanged(false,Configuration())
            controller.pause().resume().pause().stop()
            assertFalse(controller.get().isFinishing)
        } finally { controller.destroy() }
    }
    @Test fun backgroundingNormalPlayerDoesNotFinishTask() {
        val controller=Robolectric.buildActivity(PlayerActivity::class.java,intent()).create().start().resume()
        try {
            controller.pause().stop()
            assertFalse(controller.get().isFinishing)
        } finally { controller.destroy() }
    }
}
