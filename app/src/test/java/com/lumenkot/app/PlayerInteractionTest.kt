package com.lumenkot.app

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerInteractionTest {
    private fun send(view: PlayerGestureLayout, action: Int, time: Long, x: Float=320f,y: Float=140f) {
        val event=MotionEvent.obtain(time,time,action,x,y,0)
        view.dispatchTouchEvent(event);event.recycle()
    }
    @Test fun holdBeginsAfterThresholdAndCancelRestoresItWithoutTogglingControls() {
        Robolectric.buildActivity(Activity::class.java).setup().use {controller ->
            val view=PlayerGestureLayout(controller.get());controller.get().setContentView(view);view.layout(0,0,640,360)
            val holds=mutableListOf<Boolean>();var toggles=0
            view.hold={holds.add(it)};view.toggleControls={toggles++}
            val now=SystemClock.uptimeMillis();send(view,MotionEvent.ACTION_DOWN,now)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(260))
            assertEquals(listOf(true),holds)
            send(view,MotionEvent.ACTION_CANCEL,now+260)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(true,false),holds);assertEquals(0,toggles)
        }
    }
    @Test fun doubleTapSeeksOnTheChosenSideAndDoesNotToggleControls() {
        Robolectric.buildActivity(Activity::class.java).setup().use {controller ->
            val view=PlayerGestureLayout(controller.get());controller.get().setContentView(view);view.layout(0,0,640,360)
            val seeks=mutableListOf<Boolean>();var toggles=0
            view.seek={seeks.add(it)};view.toggleControls={toggles++}
            val now=SystemClock.uptimeMillis()
            send(view,MotionEvent.ACTION_DOWN,now,500f);send(view,MotionEvent.ACTION_UP,now+30,500f)
            send(view,MotionEvent.ACTION_DOWN,now+100,500f);send(view,MotionEvent.ACTION_UP,now+130,500f)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(true),seeks);assertEquals(0,toggles)
        }
    }
    @Test fun controlsReceiveTheirOwnTouches() {
        Robolectric.buildActivity(Activity::class.java).setup().use {controller ->
            val view=PlayerGestureLayout(controller.get());val button=Button(controller.get());var clicks=0
            button.setOnClickListener {clicks++};view.addView(button,FrameLayout.LayoutParams(100,80));controller.get().setContentView(view)
            view.layout(0,0,640,360);button.layout(0,0,100,80)
            val now=SystemClock.uptimeMillis();send(view,MotionEvent.ACTION_DOWN,now,40f,40f);send(view,MotionEvent.ACTION_UP,now+30,40f,40f)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1,clicks)
        }
    }
    @Test fun verticalSwipesKeepTheirSideAndDoNotTriggerHoldTapOrSeek() {
        Robolectric.buildActivity(Activity::class.java).setup().use {controller ->
            val view=PlayerGestureLayout(controller.get());controller.get().setContentView(view);view.layout(0,0,640,360)
            val sides=mutableListOf<Boolean>();val deltas=mutableListOf<Float>();var ends=0;var otherActions=0
            view.verticalStart={sides.add(it)};view.verticalChange={deltas.add(it)};view.verticalEnd={ends++}
            view.hold={otherActions++};view.seek={otherActions++};view.toggleControls={otherActions++}
            val now=SystemClock.uptimeMillis()
            send(view,MotionEvent.ACTION_DOWN,now,500f,240f);send(view,MotionEvent.ACTION_MOVE,now+40,500f,100f)
            send(view,MotionEvent.ACTION_UP,now+80,500f,100f)
            assertEquals(listOf(true),sides);assertTrue(deltas.last()>0)
            send(view,MotionEvent.ACTION_DOWN,now+500,100f,100f);send(view,MotionEvent.ACTION_MOVE,now+540,100f,240f)
            send(view,MotionEvent.ACTION_CANCEL,now+580,100f,240f)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertEquals(listOf(true,false),sides);assertTrue(deltas.last()<0);assertEquals(2,ends);assertEquals(0,otherActions)
        }
    }
    @Test fun exactPositionAndSelectionSurviveNewInstancesAndMirrorChanges() {
        val settings=Settings(ApplicationProvider.getApplicationContext());settings.prefs.edit().clear().commit()
        val first=WatchProgress(settings.prefs);first.record("42","7","3","5",65432)
        settings.provider="https://rezka-ua.pub"
        val restored=WatchProgress(Settings(ApplicationProvider.getApplicationContext()).prefs)
        assertEquals(WatchSelection("7","3","5"),restored.last("42"));assertEquals(65432,restored.position("42","7","3","5"))
        assertEquals(0,restored.position("42","7","3","6"));assertTrue(restored.hasProgress("42"))
        settings.prefs.edit().clear().commit()
    }
    @Test fun oldTimestampKeysStillEnableContinueWithoutResettingPosition() {
        val settings=Settings(ApplicationProvider.getApplicationContext());settings.prefs.edit().clear().putLong("position:42:7:2:9",123456).commit()
        val progress=WatchProgress(settings.prefs)
        assertTrue(progress.hasProgress("42"));assertEquals(123456,progress.position("42","7","2","9"))
        settings.prefs.edit().clear().commit()
    }
}
