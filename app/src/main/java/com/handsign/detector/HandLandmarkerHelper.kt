package com.handsign.detector

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * Wraps MediaPipe Tasks Vision HandLandmarker in LIVE_STREAM mode.
 * Model loads from app assets ("hand_landmarker.task", auto-downloaded at build
 * time by the downloadModel gradle task). GPU first, CPU fallback.
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        /** First-hand gesture (null when no hand), all hand names, handedness label,
         *  pointerY = normalized midpoint of thumb/index tips (for pinch scroll). */
        fun onResult(first: Gesture?, allNames: List<String>, handedness: String, pointerY: Float)
        fun onError(e: Exception)
    }

    private var landmarker: HandLandmarker? = null
    var ready = false
        private set

    fun setup(): Boolean {
        close()
        val ok = trySetup(Delegate.GPU) || trySetup(Delegate.CPU)
        ready = ok
        return ok
    }

    private fun trySetup(delegate: Delegate): Boolean {
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(::onLandmarkerResult)
                .setErrorListener { e ->
                    listener.onError(e as? Exception ?: RuntimeException(e.toString()))
                }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            listener.onError(e)
            landmarker = null
            false
        }
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val lm = landmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            lm.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    private fun onLandmarkerResult(result: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage) {
        try {
            val hands = result.landmarks()
            if (hands.isEmpty()) {
                listener.onResult(null, emptyList(), "-", 0.5f)
                return
            }
            val points = hands[0].map { Point3(it.x(), it.y(), it.z()) }
            val gesture = GestureClassifier.classify(points)
            val pointerY = if (points.size >= 9) (points[4].y + points[8].y) / 2f else 0.5f
            val names = hands.mapNotNull { hand ->
                if (hand.size < 21) null else GestureClassifier.classify(
                    hand.map { Point3(it.x(), it.y(), it.z()) }
                )?.name
            }
            var side = "-"
            try {
                val h = result.handedness()
                if (h.isNotEmpty() && h[0].isNotEmpty()) {
                    val c = h[0][0]
                    side = c.categoryName() + " " + (c.score() * 100).toInt() + "%"
                }
            } catch (e: Exception) {
                // handedness optional; keep "-"
            }
            listener.onResult(gesture, names, side, pointerY)
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (e: Exception) {
            // ignore
        }
        landmarker = null
        ready = false
    }

    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    }
}
