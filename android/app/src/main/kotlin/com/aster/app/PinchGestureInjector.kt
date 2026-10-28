package com.aster.app

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties

object PinchGestureInjector {
    @JvmStatic
    fun main(args: Array<String>) {
        val zoomIn = args.contains("--in")
        val im = InputManager::class.java.getMethod("getInstance").invoke(null)
        val injectMethod = InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)

        val cx = 540f
        val cy = 1000f
        val startDist = if (zoomIn) 100f else 450f
        val endDist = if (zoomIn) 450f else 100f
        val steps = 30

        val props = arrayOf(
            PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        )

        val downTime = SystemClock.uptimeMillis()

        fun makeCoords(dist: Float): Array<PointerCoords> {
            val c0 = PointerCoords().apply { x = cx - dist / 2f; y = cy; pressure = 1f; size = 1f }
            val c1 = PointerCoords().apply { x = cx + dist / 2f; y = cy; pressure = 1f; size = 1f }
            return arrayOf(c0, c1)
        }

        // Pointer 0 Down
        val evDown0 = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, arrayOf(props[0]), arrayOf(makeCoords(startDist)[0]), 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
        injectMethod.invoke(im, evDown0, 0)
        Thread.sleep(20)

        // Pointer 1 Down
        val evDown1 = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, makeCoords(startDist), 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
        injectMethod.invoke(im, evDown1, 0)
        Thread.sleep(20)

        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val d = startDist + (endDist - startDist) * t
            val now = SystemClock.uptimeMillis()
            val evMove = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, 2, props, makeCoords(d), 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
            injectMethod.invoke(im, evMove, 0)
            Thread.sleep(16)
        }

        val upTime = SystemClock.uptimeMillis()
        // Pointer 1 Up
        val evUp1 = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, makeCoords(endDist), 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
        injectMethod.invoke(im, evUp1, 0)
        Thread.sleep(20)

        // Pointer 0 Up
        val evUp0 = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, arrayOf(props[0]), arrayOf(makeCoords(endDist)[0]), 0, 0, 1f, 1f, 0, 0, 0x1002, 0)
        injectMethod.invoke(im, evUp0, 0)

        println("Pinch injection complete: zoomIn=$zoomIn, start=$startDist, end=$endDist")
    }
}
