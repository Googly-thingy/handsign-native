package com.handsign.detector

import kotlin.math.hypot

/**
 * Heuristic hand-pose classifier. Same poses/thresholds as the web prototype:
 * Open Palm, Fist, Peace/V, Pointing, Thumbs Up/Down, OK, Pinch, I Love You,
 * Rock Horns, Shaka, L/Gun, Three/Four + finger counts.
 *
 * Landmarks follow the MediaPipe 21-point layout (0 = wrist, 4 = thumb tip,
 * 8/12/16/20 = finger tips, 6/10/14/18 = PIP joints, 9 = middle MCP).
 */
data class Point3(val x: Float, val y: Float, val z: Float = 0f)

data class FingerState(
    val thumb: Boolean,
    val index: Boolean,
    val middle: Boolean,
    val ring: Boolean,
    val pinky: Boolean
)

data class Gesture(
    val emoji: String,
    val name: String,
    val detail: String,
    val fingers: FingerState,
    /** Thumb-tip to index-tip distance, normalized by hand size. < ~0.45 = pinched. */
    val pinch: Float
)

object GestureClassifier {

    const val UNKNOWN = "No hand"

    private fun d2(a: Point3, b: Point3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return hypot(dx, dy)
    }

    fun fingerStates(lm: List<Point3>): FingerState {
        val wrist = lm[0]
        val idx = d2(lm[8], wrist) > d2(lm[6], wrist) * 1.07f
        val mid = d2(lm[12], wrist) > d2(lm[10], wrist) * 1.07f
        val rng = d2(lm[16], wrist) > d2(lm[14], wrist) * 1.07f
        val pky = d2(lm[20], wrist) > d2(lm[18], wrist) * 1.07f
        val wristRatio = d2(lm[4], wrist) / (d2(lm[2], wrist) + 1e-6f)
        val spread = d2(lm[4], lm[17]) / (d2(lm[2], lm[17]) + 1e-6f)
        val thumb = (wristRatio > 1.12f && spread > 0.95f) ||
            (d2(lm[4], wrist) > d2(lm[3], wrist) * 1.15f)
        return FingerState(thumb, idx, mid, rng, pky)
    }

    fun pinchMetric(lm: List<Point3>): Float {
        val handSize = d2(lm[0], lm[9]) + 1e-6f
        return d2(lm[4], lm[8]) / handSize
    }

    fun classify(lm: List<Point3>): Gesture? {
        if (lm.size < 21) return null
        val f = fingerStates(lm)
        val pinch = pinchMetric(lm)
        val count = listOf(f.thumb, f.index, f.middle, f.ring, f.pinky).count { it }

        if (pinch < 0.42f) {
            if (f.middle && f.ring && f.pinky)
                return Gesture("\uD83D\uDC4C", "OK", "thumb + index circle", f, pinch)
            if (!f.middle && !f.ring && !f.pinky)
                return Gesture("\uD83E\uDD0F", "Pinch", "thumb + index together", f, pinch)
            return Gesture("\uD83D\uDC4C", "OK / Pinch", "thumb near index", f, pinch)
        }
        if (f.thumb && f.index && !f.middle && !f.ring && f.pinky)
            return Gesture("\uD83E\uDD1F", "I Love You", "thumb + index + pinky", f, pinch)
        if (!f.middle && !f.ring && f.index && f.pinky)
            return Gesture("\uD83E\uDD18", "Rock Horns", "index + pinky", f, pinch)
        if (f.thumb && !f.index && !f.middle && !f.ring && f.pinky)
            return Gesture("\uD83E\uDD19", "Shaka / Call Me", "thumb + pinky", f, pinch)
        if (f.thumb && f.index && !f.middle && !f.ring && !f.pinky)
            return Gesture("\uD83D\uDD2B", "L / Gun", "thumb + index", f, pinch)
        if (!f.thumb && f.index && f.middle && !f.ring && !f.pinky)
            return Gesture("✌️", "Peace / V", "index + middle", f, pinch)
        if (!f.thumb && f.index && !f.middle && !f.ring && !f.pinky)
            return Gesture("☝️", "Pointing", "index only", f, pinch)
        if (f.thumb && !f.index && !f.middle && !f.ring && !f.pinky) {
            val up = lm[4].y < lm[2].y - 0.02f
            val down = lm[4].y > lm[2].y + 0.02f
            return if (down && !up)
                Gesture("\uD83D\uDC4E", "Thumbs Down", "thumb only, down", f, pinch)
            else
                Gesture("\uD83D\uDC4D", "Thumbs Up", "thumb only, up", f, pinch)
        }
        if (!f.thumb && !f.index && !f.middle && !f.ring && !f.pinky)
            return Gesture("✊", "Fist", "0 fingers", f, pinch)
        if (f.thumb && f.index && f.middle && f.ring && f.pinky)
            return Gesture("\uD83D\uDD90️", "Open Palm", "5 fingers", f, pinch)
        if (!f.thumb && f.index && f.middle && f.ring && f.pinky)
            return Gesture("✋", "Four", "4 fingers, no thumb", f, pinch)
        if (!f.thumb && f.index && f.middle && f.ring && !f.pinky)
            return Gesture("3️⃣", "Three", "index + middle + ring", f, pinch)
        if (f.thumb && f.index && f.middle && !f.ring && !f.pinky)
            return Gesture("3️⃣", "Three", "thumb + 2 fingers", f, pinch)
        if (f.thumb && f.index && f.middle && f.ring && !f.pinky)
            return Gesture("4️⃣", "Four", "thumb + 3 fingers", f, pinch)
        if (!f.thumb && !f.index && f.middle && f.ring && f.pinky)
            return Gesture("3️⃣", "Three", "middle + ring + pinky", f, pinch)
        return when (count) {
            2 -> Gesture("✌️", "Two Fingers", "2 fingers", f, pinch)
            1 -> Gesture("☝️", "One Finger", "1 finger", f, pinch)
            3 -> Gesture("3️⃣", "Three Fingers", "3 fingers", f, pinch)
            4 -> Gesture("✋", "Four Fingers", "4 fingers", f, pinch)
            else -> Gesture("✋", "$count Fingers", "custom pose", f, pinch)
        }
    }
}
