package com.handsign.detector

import kotlin.math.abs

/**
 * Gesture-to-action state machine. Pure Kotlin (no Android imports) so it is unit
 * tested (see ActionEngineTest) and shared by MainActivity (foreground) and
 * TrackingService (background).
 *
 * 1. Pinch-to-scroll: while thumb+index are pinched, vertical hand motion maps to a
 *    scroll target:  scrollTo = anchorScroll + (pointerY - anchorY) * gain.
 *    The caller applies the target (in-app ScrollView, or system swipe gestures).
 * 2. Palm-then-fist screenshot: Open Palm held [palmHoldMs] arms the trigger; a Fist
 *    within [seqWindowMs] fires capture; [cooldownMs] blocks double-fires.
 */
data class EngineOutput(
    /** Absolute scroll target in px, or null when no scroll change this frame. */
    val scrollToPx: Float?,
    val pinchActive: Boolean,
    val capture: Boolean,
    val stageText: String,
    /** 0..1 progress for UI (arming fill, or armed countdown). */
    val progress: Float
)

class ActionEngine(
    var scrollEnabled: Boolean = true,
    var seqEnabled: Boolean = true,
    /** Pixels of scroll per 1.0 of normalized hand travel. */
    var gain: Float = 1600f,
    var pinchThreshold: Float = 0.45f,
    var palmHoldMs: Long = 500,
    var seqWindowMs: Long = 3000,
    var cooldownMs: Long = 3500,
    private val clock: () -> Long = { System.currentTimeMillis() }
} {
    private var pinchActive = false
    private var anchorY = 0f
    private var anchorScroll = 0f

    private var palmSince = 0L
    private var armed = false
    private var armedAt = 0L
    private var cooldownUntil = 0L

    fun reset() {
        pinchActive = false
        palmSince = 0L
        armed = false
        cooldownUntil = 0L
    }

    /** Call when a capture attempt failed (e.g. no frame yet): short retry, no long lockout. */
    fun notifyCaptureFailed() {
        cooldownUntil = clock() + 800
    }

    fun onFrame(
        gesture: String,
        pinch: Float,
        /** Normalized vertical pointer position (0 top .. 1 bottom), e.g. midpoint of tips 4+8. */
        pointerY: Float,
        currentScrollPx: Float
    ): EngineOutput {
        var scrollTo: Float? = null
        if (scrollEnabled) {
            if (pinch < pinchThreshold) {
                if (!pinchActive) {
                    pinchActive = true
                    anchorY = pointerY
                    anchorScroll = currentScrollPx
                } else {
                    val d = pointerY - anchorY
                    if (abs(d) > 0.004f) scrollTo = anchorScroll + d * gain
                }
            } else {
                pinchActive = false
            }
        } else {
            pinchActive = false
        }

        var capture = false
        var text: String
        var prog = 0f
        val now = clock()
        if (!seqEnabled) {
            armed = false
            palmSince = 0L
            text = "Sequence off"
        } else if (now < cooldownUntil) {
            text = "Cooldown"
        } else if (!armed) {
            if (gesture == "Open Palm") {
                if (palmSince == 0L) palmSince = now
                val held = now - palmSince
                prog = (held.toFloat() / palmHoldMs).coerceIn(0f, 1f)
                if (held >= palmHoldMs) {
                    armed = true
                    armedAt = now
                    text = "Armed - make a Fist"
                } else {
                    text = "Hold palm…"
                }
            } else {
                palmSince = 0L
                text = "Show Open Palm"
            }
        } else {
            val left = seqWindowMs - (now - armedAt)
            when {
                gesture == "Fist" -> {
                    capture = true
                    armed = false
                    palmSince = 0L
                    cooldownUntil = now + cooldownMs
                    text = "Captured"
                }
                left <= 0 -> {
                    armed = false
                    palmSince = 0L
                    text = "Timed out"
                }
                else -> {
                    prog = left.toFloat() / seqWindowMs
                    text = "Make a Fist"
                }
            }
        }
        return EngineOutput(scrollTo, pinchActive, capture, text, prog)
    }
}
