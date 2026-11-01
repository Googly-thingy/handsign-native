package com.handsign.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline checks for the gesture -> action state machine. No device needed. */
class ActionEngineTest {

    private fun openPalm(): List<Point3> {
        // Wrist bottom-center, all tips far from wrist, thumb spread wide.
        val m = mutableMapOf<Int, Point3>()
        m[0] = Point3(0.5f, 0.9f)
        m[2] = Point3(0.40f, 0.60f)
        m[3] = Point3(0.35f, 0.55f)
        m[4] = Point3(0.30f, 0.50f)
        m[6] = Point3(0.45f, 0.50f)
        m[8] = Point3(0.45f, 0.30f)
        m[9] = Point3(0.50f, 0.50f)
        m[10] = Point3(0.50f, 0.50f)
        m[12] = Point3(0.50f, 0.28f)
        m[14] = Point3(0.55f, 0.50f)
        m[16] = Point3(0.55f, 0.30f)
        m[17] = Point3(0.60f, 0.50f)
        m[18] = Point3(0.60f, 0.50f)
        m[20] = Point3(0.60f, 0.30f)
        return (0..20).map { m[it] ?: Point3(0.5f, 0.5f) }
    }

    private fun fist(): List<Point3> {
        // All tips curled near the wrist/palm.
        val m = mutableMapOf<Int, Point3>()
        m[0] = Point3(0.5f, 0.9f)
        m[2] = Point3(0.45f, 0.62f)
        m[3] = Point3(0.46f, 0.60f)
        m[4] = Point3(0.47f, 0.60f)
        m[6] = Point3(0.45f, 0.55f)
        m[8] = Point3(0.47f, 0.62f)
        m[9] = Point3(0.50f, 0.55f)
        m[10] = Point3(0.50f, 0.55f)
        m[12] = Point3(0.51f, 0.62f)
        m[14] = Point3(0.55f, 0.55f)
        m[16] = Point3(0.55f, 0.62f)
        m[17] = Point3(0.58f, 0.60f)
        m[18] = Point3(0.58f, 0.58f)
        m[20] = Point3(0.57f, 0.62f)
        return (0..20).map { m[it] ?: Point3(0.5f, 0.6f) }
    }

    @Test
    fun classifier_namesOpenPalmAndFist() {
        assertEquals("Open Palm", GestureClassifier.classify(openPalm())?.name)
        assertEquals("Fist", GestureClassifier.classify(fist())?.name)
    }

    @Test
    fun pinchScroll_grabMoveRelease() {
        var now = 0L
        val engine = ActionEngine(clock = { now })
        // open hand, no pinch
        var out = engine.onFrame("Open Palm", 0.7f, 0.5f, 0f)
        assertFalse(out.pinchActive)
        assertNull(out.scrollToPx)
        // grab at y=0.5
        out = engine.onFrame("Pinch", 0.1f, 0.5f, 100f)
        assertTrue(out.pinchActive)
        // move down 0.1 -> +160px at default gain
        out = engine.onFrame("Pinch", 0.1f, 0.6f, 100f)
        assertNotNull(out.scrollToPx)
        assertEquals(260f, out.scrollToPx!!, 0.01f)
        // release
        out = engine.onFrame("Pinch", 0.8f, 0.6f, 260f)
        assertFalse(out.pinchActive)
        assertNull(out.scrollToPx)
    }

    @Test
    fun palmThenFist_firesOnceWithCooldown() {
        var now = 0L
        val engine = ActionEngine(clock = { now })
        // hold palm 600ms
        var out = engine.onFrame("Open Palm", 0.7f, 0.5f, 0f)
        assertFalse(out.capture)
        now += 600
        out = engine.onFrame("Open Palm", 0.7f, 0.5f, 0f)
        assertFalse(out.capture)
        assertTrue(out.stageText.contains("Fist"))
        // fist -> capture
        out = engine.onFrame("Fist", 0.7f, 0.5f, 0f)
        assertTrue(out.capture)
        // immediate re-arm blocked by cooldown
        now += 100
        out = engine.onFrame("Open Palm", 0.7f, 0.5f, 0f)
        assertFalse(out.capture)
        assertTrue(out.stageText.contains("Cooldown"))
    }
}
