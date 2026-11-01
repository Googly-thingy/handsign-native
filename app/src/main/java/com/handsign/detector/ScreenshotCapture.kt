package com.handsign.detector

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves Bitmaps to Pictures/HandSign via MediaStore (scoped storage on API 29+). */
object MediaStoreSaver {
    fun saveBitmap(context: Context, bmp: Bitmap, prefix: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = prefix + "-" + stamp + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HandSign")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.writePng(bmp) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "HandSign"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.writePng(bmp) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun java.io.OutputStream.writePng(bmp: Bitmap) {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
        flush()
    }
}

/**
 * One-shot screen capture through an active MediaProjection. Creates its own
 * VirtualDisplay + ImageReader, grabs a frame, saves it, then releases everything.
 */
object ScreenshotCapture {

    fun capture(context: Context, projection: MediaProjection): Uri? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h, dpi) = screenSize(wm)
        if (w <= 0 || h <= 0) return null
        var reader: ImageReader? = null
        var display: android.hardware.display.VirtualDisplay? = null
        val thread = HandlerThread("shot").also { it.start() }
        return try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "handsign-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, Handler(thread.looper)
            )
            val bmp = grabFrame(reader, w, h) ?: return null
            MediaStoreSaver.saveBitmap(context, bmp, "screen")
        } catch (e: Exception) {
            null
        } finally {
            try {
                display?.release()
            } catch (e: Exception) {
            }
            try {
                reader?.close()
            } catch (e: Exception) {
            }
            thread.quitSafely()
        }
    }

    private data class ScreenInfo(val w: Int, val h: Int, val dpi: Int)

    private fun screenSize(wm: WindowManager): ScreenInfo {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                val dpi = wm.maximumWindowMetrics.densityDpi
                ScreenInfo(b.width(), b.height(), if (dpi > 0) dpi else 320)
            } else {
                @Suppress("DEPRECATION")
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                ScreenInfo(m.widthPixels, m.heightPixels, if (m.densityDpi > 0) m.densityDpi else 320)
            }
        } catch (e: Exception) {
            ScreenInfo(0, 0, 320)
        }
    }

    private fun grabFrame(reader: ImageReader, w: Int, h: Int): Bitmap? {
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPad = rowStride - pixelStride * w
                    val full = Bitmap.createBitmap(
                        w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888
                    )
                    full.copyPixelsFromBuffer(buffer)
                    return if (full.width != w) {
                        Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
                    } else {
                        full
                    }
                } catch (e: Exception) {
                    return null
                } finally {
                    try {
                        image.close()
                    } catch (e: Exception) {
                    }
                }
            }
            try {
                Thread.sleep(60)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }
}
