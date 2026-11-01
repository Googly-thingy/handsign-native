package com.handsign.detector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Foreground service: front camera + hand detection keep running while the app is
 * backgrounded (persistent notification). This is the part a browser/PWA cannot do:
 * getUserMedia stops when a page is hidden, but a foreground service keeps going.
 *
 * Per frame: classify hand 1 -> ActionEngine -> pinch scroll (AccessibilityService
 * swipe, system-wide) or palm-then-fist capture (MediaProjection screen shot, or
 * camera frame fallback).
 */
class TrackingService : LifecycleService() {

    private val setupExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var helper: HandLandmarkerHelper? = null
    private var engine = ActionEngine()
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var lastFgType = 0

    private val bmpLock = Any()
    private var lastCameraBitmap: Bitmap? = null

    private var smoothBuf = ArrayDeque<String>()
    private var frames = 0
    private var fpsWindowStart = System.currentTimeMillis()
    private var lastStatusPush = 0L
    private var lastAppliedScroll = 0f
    private var lastScrollHintPush = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                engine = ActionEngine(
                    scrollEnabled = intent?.getBooleanExtra(EXTRA_SCROLL, true) ?: true,
                    seqEnabled = intent?.getBooleanExtra(EXTRA_SEQ, true) ?: true,
                    gain = intent?.getFloatExtra(EXTRA_GAIN, 1600f) ?: 1600f
                )
                startFg(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                acquireWake()
                startPipeline()
            }
        }
        return START_STICKY
    }

    // ---------- foreground / wake ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hand tracking", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Hand Sign Detector")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startFg(type: Int) {
        lastFgType = lastFgType or type
        val notif = buildNotification("Tracking front camera…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, lastFgType)
                return
            } catch (e: Exception) {
                // fall through to no-type variant
            }
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif)
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandSign:track")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(30 * 60 * 1000L)
        } catch (e: Exception) {
            broadcast("error", "WakeLock failed: " + e.message)
        }
    }

    // ---------- camera pipeline ----------

    private fun startPipeline() {
        setupExecutor.execute {
            val h = HandLandmarkerHelper(this, serviceListener)
            if (!h.setup()) {
                broadcast("error", "Hand model failed to load. Reinstall / check assets.")
                stopSelf()
                return@execute
            }
            helper = h
            ContextCompat.getMainExecutor(this).execute { bindAnalysis() }
        }
    }

    private fun bindAnalysis() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    provider.unbindAll()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(640, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor, ::analyze)
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                    )
                    broadcast("state", "Tracking in background (front camera).")
                } catch (e: Exception) {
                    broadcast("error", "Camera bind failed: " + e.message)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            broadcast("error", "Camera init failed: " + e.message)
        }
    }

    private fun analyze(proxy: androidx.camera.core.ImageProxy) {
        try {
            val img = proxy.image
            if (img == null) return
            val bmp = YuvToRgb.toBitmap(img)
            if (bmp != null) {
                val fixed = YuvToRgb.rotate(bmp, proxy.imageInfo.rotationDegrees)
                synchronized(bmpLock) {
                    try {
                        lastCameraBitmap?.recycle()
                    } catch (e: Exception) {
                    }
                    lastCameraBitmap = fixed
                }
                helper?.detectAsync(fixed, proxy.imageInfo.timestamp / 1_000_000)
            }
        } catch (e: Exception) {
            // keep the loop alive
        } finally {
            try {
                proxy.close()
            } catch (e: Exception) {
            }
        }
    }

    private val serviceListener = object : HandLandmarkerHelper.Listener {
        override fun onResult(first: Gesture?, allNames: List<String>, handedness: String, pointerY: Float) {
            frames++
            val now = System.currentTimeMillis()
            val name = first?.name ?: GestureClassifier.UNKNOWN
            smoothBuf.addLast(name)
            if (smoothBuf.size > 7) smoothBuf.removeFirst()
            val stable = smoothBuf.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: name

            if (first != null) {
                val out = engine.onFrame(stable, first.pinch, pointerY, 0f)
                if (out.scrollToPx != null) applySystemScroll(out.scrollToPx)
                if (out.capture) doCapture()
            } else {
                engine.onFrame(stable, 1f, 0.5f, 0f)
            }

            if (now - fpsWindowStart >= 1000) {
                val fps = frames
                frames = 0
                fpsWindowStart = now
                val label = if (first == null) "No hand" else (stable + " " + (first.emoji))
                broadcast("status", "$label|${allNames.size}|$fps|$handedness")
            }
        }

        override fun onError(e: Exception) {
            if (System.currentTimeMillis() - lastStatusPush > 5000) {
                lastStatusPush = System.currentTimeMillis()
                broadcast("error", e.message ?: "detection error")
            }
        }
    }

    private fun applySystemScroll(target: Float) {
        val delta = (target - lastAppliedScroll).toInt()
        if (delta == 0) return
        if (!GestureAccessibilityService.isEnabled()) {
            if (System.currentTimeMillis() - lastScrollHintPush > 10000) {
                lastScrollHintPush = System.currentTimeMillis()
                broadcast("hint", "Pinch moves detected but Accessibility scroll is off.")
            }
            lastAppliedScroll = target
            return
        }
        // Incremental: only dispatch when the finger moved enough for one swipe.
        if (kotlin.math.abs(delta) >= 90) {
            if (GestureAccessibilityService.scrollByPx(delta)) lastAppliedScroll = target
        }
    }

    // ---------- capture ----------

    private fun doCapture() {
        val projData = ProjectionHolder.data
        if (projData != null) {
            try {
                startFg(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mgr.getMediaProjection(ProjectionHolder.resultCode, projData)
                mediaProjection = mp
                val uri = ScreenshotCapture.capture(this, mp)
                try {
                    mp.stop()
                } catch (e: Exception) {
                }
                mediaProjection = null
                if (uri != null) {
                    broadcast("captured", uri.toString())
                    return
                }
            } catch (e: Exception) {
                broadcast("error", "Screen capture failed, saving camera frame: " + e.message)
            }
        }
        // Fallback: camera frame (same as web version).
        val bmp: Bitmap? = synchronized(bmpLock) {
            lastCameraBitmap?.let {
                try {
                    it.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (bmp != null) {
            val uri = MediaStoreSaver.saveBitmap(this, bmp, "camera")
            try {
                bmp.recycle()
            } catch (e: Exception) {
            }
            if (uri != null) {
                broadcast("captured", uri.toString())
                return
            }
        }
        engine.notifyCaptureFailed()
        broadcast("error", "Capture failed: no frame yet.")
    }

    private fun broadcast(type: String, payload: String) {
        sendBroadcast(Intent(BROADCAST).putExtra("type", type).putExtra("payload", payload))
    }

    override fun onDestroy() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
        }
        try {
            helper?.close()
        } catch (e: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        analysisExecutor.shutdownNow()
        setupExecutor.shutdownNow()
        broadcast("state", "Background service stopped.")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.handsign.detector.START"
        const val ACTION_STOP = "com.handsign.detector.STOP"
        const val EXTRA_SCROLL = "scroll_enabled"
        const val EXTRA_SEQ = "seq_enabled"
        const val EXTRA_GAIN = "gain"
        const val BROADCAST = "com.handsign.detector.STATUS"
        const val CHANNEL = "tracking"
        const val NOTIF_ID = 1001

        fun start(
            c: Context,
            scroll: Boolean,
            seq: Boolean,
            gain: Float
        ) {
            val i = Intent(c, TrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCROLL, scroll)
                putExtra(EXTRA_SEQ, seq)
                putExtra(EXTRA_GAIN, gain)
            }
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, TrackingService::class.java).apply { action = ACTION_STOP })
        }
    }
}
