package com.handsign.detector

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import kotlin.math.max
import kotlin.math.min

/**
 * YUV_420_888 -> ARGB Bitmap conversion without RenderScript (deprecated), plus
 * rotation. Runs on the analysis background thread.
 */
object YuvToRgb {

    fun toBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val w = image.width
        val h = image.height
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride
        val uPixStride = planes[1].pixelStride
        val vPixStride = planes[2].pixelStride

        val out = IntArray(w * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yVal = (yBuf.get(y * yRowStride + x).toInt() and 0xFF)
                val uvx = x / 2
                val uvy = y / 2
                val uVal = (uBuf.get(uvy * uRowStride + uvx * uPixStride).toInt() and 0xFF)
                val vVal = (vBuf.get(uvy * vRowStride + uvx * vPixStride).toInt() and 0xFF)
                out[i++] = yuvToArgb(yVal, uVal, vVal)
            }
        }
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
        } catch (e: Exception) {
            null
        }
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        var r = (298 * c + 409 * e + 128) shr 8
        var g = (298 * c - 100 * d - 208 * e + 128) shr 8
        var b = (298 * c + 516 * d + 128) shr 8
        r = min(255, max(0, r))
        g = min(255, max(0, g))
        b = min(255, max(0, b))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: Exception) {
            src
        }
    }
}
