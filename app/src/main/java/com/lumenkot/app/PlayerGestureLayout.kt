package com.lumenkot.app

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.media3.ui.DefaultTimeBar

/** Owns video gestures while leaving controller buttons and the time bar clickable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerGestureLayout(context: Context) : FrameLayout(context) {
    var enabledForVideo: () -> Boolean = { true }
    var toggleControls: () -> Unit = {}
    var seek: (Boolean) -> Unit = {}
    var hold: (Boolean) -> Unit = {}
    var verticalStart: (Boolean) -> Unit = {}
    var verticalChange: (Float) -> Unit = {}
    var verticalEnd: () -> Unit = {}
    private var vertical = false
    private var videoTouch = false
    private var holding = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val startHold = Runnable {
        if (videoTouch && !moved && enabledForVideo()) {
            holding = true
            hold(true)
        }
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            removeCallbacks(startHold)
            if (enabledForVideo()) seek(e.x >= width / 2f)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }
    init { isClickable = true; setOnClickListener { if (enabledForVideo()) toggleControls() } }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            videoTouch = enabledForVideo() && !hitsControl(this, event.x.toInt(), event.y.toInt())
        }
        return videoTouch
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!videoTouch) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                postDelayed(startHold, 250)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx=event.x-downX;val dy=event.y-downY
                if(!vertical && kotlin.math.abs(dy)>slop && kotlin.math.abs(dy)>kotlin.math.abs(dx)) {
                    removeCallbacks(startHold)
                    val cancel=MotionEvent.obtain(event).apply {action=MotionEvent.ACTION_CANCEL};gestures.onTouchEvent(cancel);cancel.recycle()
                    if(holding) {hold(false);holding=false}
                    moved=true;vertical=true;verticalStart(downX>=width/2f)
                } else if(!vertical && kotlin.math.abs(dx)>slop) {
                    moved=true;removeCallbacks(startHold)
                }
                if(vertical) verticalChange(-dy/(height.coerceAtLeast(1)*.65f))
            }
        }
        // A completed hold must not also become a single/double tap.
        if (!holding && !moved) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (holding || moved) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
            }
            resetGesture()
        }
        return true
    }
    fun resetGesture() {
        removeCallbacks(startHold)
        if (holding) hold(false)
        if(vertical) verticalEnd()
        holding = false; videoTouch = false; vertical=false
    }
    override fun performClick(): Boolean = super.performClick()
    override fun onDetachedFromWindow() { resetGesture(); super.onDetachedFromWindow() }
    private fun hitsControl(group: ViewGroup, x: Int, y: Int): Boolean {
        for (i in group.childCount-1 downTo 0) {
            val child=group.getChildAt(i)
            if (!child.isShown) continue
            if (child is ImageButton || child is Button || child is DefaultTimeBar || child.id == R.id.player_error) {
                val bounds=Rect(0,0,child.width,child.height)
                offsetDescendantRectToMyCoords(child,bounds)
                if (bounds.contains(x,y)) return true
            }
            if (child is ViewGroup && hitsControl(child,x,y)) return true
        }
        return false
    }
}
