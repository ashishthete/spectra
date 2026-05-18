package com.spectra.camera

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator

class RackFocusEngine {
    private var animator: ValueAnimator? = null
    private var currentFocusDistance: Float = 0f
    var isActive: Boolean = false
        private set

    data class FocusPoint(
        val distance: Float,
        val label: String = ""
    )

    private var pointA: FocusPoint? = null
    private var pointB: FocusPoint? = null

    fun setPoints(a: FocusPoint, b: FocusPoint) {
        pointA = a
        pointB = b
    }

    fun rackTo(
        target: FocusPoint,
        durationMs: Long = 1500,
        onUpdate: (Float) -> Unit
    ) {
        cancel()
        val start = currentFocusDistance
        val end = target.distance
        isActive = true
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                currentFocusDistance = it.animatedValue as Float
                onUpdate(currentFocusDistance)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isActive = false
                }
            })
            start()
        }
    }

    fun rackAToB(durationMs: Long = 1500, onUpdate: (Float) -> Unit) {
        val b = pointB ?: return
        rackTo(b, durationMs, onUpdate)
    }

    fun rackBToA(durationMs: Long = 1500, onUpdate: (Float) -> Unit) {
        val a = pointA ?: return
        rackTo(a, durationMs, onUpdate)
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        isActive = false
    }

    fun release() {
        cancel()
        pointA = null
        pointB = null
    }
}
