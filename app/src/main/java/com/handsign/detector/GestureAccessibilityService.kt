package com.handsign.detector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Performs system-wide swipe gestures so pinch moves scroll whatever app is open.
 * The user enables this once in Settings > Accessibility > Hand Sign Detector.
 * Foreground pinch (inside this app) scrolls the demo ScrollView directly and does
 * not need this service.
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event processing needed; gestures are dispatched on demand.
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** Throttled system swipe. Positive dy = hand moved down = swipe up (content down). */
        fun scrollByPx(dyPx: Int): Boolean {
            val svc = instance ?: return false
            return svc.doSwipe(dyPx)
        }

        fun isEnabled(): Boolean = instance != null
    }

    private var lastDispatch = 0L

    private fun doSwipe(dyPx: Int): Boolean {
        if (dyPx == 0) return true
        val now = System.currentTimeMillis()
        if (now - lastDispatch < 120) return true // coalesce rapid frames
        lastDispatch = now
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val len = dyPx.coerceIn(-metrics.heightPixels / 3, metrics.heightPixels / 3).toFloat()
        if (len == 0f) return true
        val path = Path()
        if (len > 0) {
            // Hand moved down: drag content down (swipe from lower to upper point).
            path.moveTo(cx, cy + len / 2f)
            path.lineTo(cx, cy - len / 2f)
        } else {
            path.moveTo(cx, cy + len / 2f)
            path.lineTo(cx, cy - len / 2f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
