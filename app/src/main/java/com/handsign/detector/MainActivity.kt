package com.handsign.detector

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Holds the MediaProjection permission grant so the background service can screenshot. */
object ProjectionHolder {
    var resultCode: Int = 0
    var data: Intent? = null
}

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var signText: TextView
    private lateinit var detailText: TextView
    private lateinit var statsText: TextView
    private lateinit var serviceStateText: TextView
    private lateinit var logText: TextView
    private lateinit var gainLabel: TextView
    private lateinit var demoScroll: ScrollView
    private lateinit var galleryRow: LinearLayout

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var helper: HandLandmarkerHelper? = null
    private val engine = ActionEngine()
    private var running = false
    private var bgRunning = false
    private var provider: ProcessCameraProvider? = null

    private var smoothBuf = ArrayDeque<String>()
    private var frames = 0
    private var fpsStart = System.currentTimeMillis()

    private val bmpLock = Any()
    private var lastBmp: Bitmap? = null

    private val camPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startForegroundPipeline() else log("Camera permission denied.")
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional; service still runs, just silent */ }

    private val screenCap = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.resultData != null) {
            ProjectionHolder.resultCode = res.resultCode
            ProjectionHolder.data = res.resultData
            log("Screenshot permission granted. Palm-then-fist now captures the screen.")
        } else {
            log("Screenshot permission cancelled. Captures save the camera frame.")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            val payload = intent.getStringExtra("payload") ?: ""
            when (type) {
                "status" -> {
                    // payload: label|hands|fps|handedness
                    val p = payload.split("|")
                    serviceStateText.text = "Background: " + (p.getOrNull(0) ?: payload)
                    statsText.text = (p.getOrNull(1) ?: "0") + " hands · " +
                        (p.getOrNull(2) ?: "-") + " fps · " + (p.getOrNull(3) ?: "")
                }
                "captured" -> {
                    serviceStateText.text = "Background: captured ✓"
                    log("Background capture saved: $payload")
                    addGalleryUri(payload)
                }
                "hint", "error" -> {
                    serviceStateText.text = "Background: $payload"
                    log(payload)
                }
                "state" -> {
                    serviceStateText.text = "Background service: $payload"
                    if (payload.contains("stopped", ignoreCase = true)) {
                        bgRunning = false
                        findViewById<Button>(R.id.bgBtn).text = "Run in background (foreground service)"
                    }
                    log(payload)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        signText = findViewById(R.id.signText)
        detailText = findViewById(R.id.detailText)
        statsText = findViewById(R.id.statsText)
        serviceStateText = findViewById(R.id.serviceStateText)
        logText = findViewById(R.id.logText)
        gainLabel = findViewById(R.id.gainLabel)
        demoScroll = findViewById(R.id.demoScroll)
        galleryRow = findViewById(R.id.galleryRow)

        findViewById<Button>(R.id.startBtn).setOnClickListener { ensureCamera() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { stopForegroundPipeline() }
        findViewById<Button>(R.id.bgBtn).setOnClickListener { toggleBackground() }
        findViewById<Button>(R.id.captureBtn).setOnClickListener { saveCameraFrame() }
        findViewById<Button>(R.id.screenBtn).setOnClickListener { requestScreenCapture() }
        findViewById<Button>(R.id.accessBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Switch>(R.id.scrollSwitch).setOnCheckedChangeListener { _, on ->
            engine.scrollEnabled = on
        }
        findViewById<Switch>(R.id.seqSwitch).setOnCheckedChangeListener { _, on ->
            engine.seqEnabled = on
        }
        findViewById<Switch>(R.id.wakeSwitch).setOnCheckedChangeListener { _, on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<SeekBar>(R.id.gainSeek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    engine.gain = v.toFloat()
                    gainLabel.text = "Scroll speed: $v"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            }
        )

        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(TrackingService.BROADCAST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        log("Ready. Start camera, then pinch to scroll or palm-then-fist to capture.")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
        }
        stopForegroundPipeline()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------- foreground pipeline ----------

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startForegroundPipeline()
        } else {
            camPerm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startForegroundPipeline() {
        if (running) return
        lifecycleScope.launch(Dispatchers.IO) {
            val h = HandLandmarkerHelper(this@MainActivity, listener)
            if (!h.setup()) {
                withContext(Dispatchers.Main) {
                    detailText.text = "Model failed to load (assets/hand_landmarker.task missing?)."
                }
                return@launch
            }
            helper = h
            withContext(Dispatchers.Main) { bindCamera() }
        }
    }

    private fun bindCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    p.unbindAll()
                    val preview = androidx.camera.core.Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(640, 480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor, ::analyze)
                    p.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                    )
                    running = true
                    engine.reset()
                    detailText.text = "Show your hand to the front camera."
                } catch (e: Exception) {
                    detailText.text = "Camera failed: " + e.message
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            detailText.text = "Camera failed: " + e.message
        }
    }

    private fun stopForegroundPipeline() {
        running = false
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
        }
        try {
            helper?.close()
        } catch (e: Exception) {
        }
        helper = null
        signText.text = "Stopped"
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val img = proxy.image
            if (img != null) {
                val bmp = YuvToRgb.toBitmap(img)
                if (bmp != null) {
                    val fixed = YuvToRgb.rotate(bmp, proxy.imageInfo.rotationDegrees)
                    synchronized(bmpLock) {
                        try {
                            lastBmp?.recycle()
                        } catch (e: Exception) {
                        }
                        lastBmp = fixed
                    }
                    helper?.detectAsync(fixed, proxy.imageInfo.timestamp / 1_000_000)
                }
            }
        } catch (e: Exception) {
        } finally {
            try {
                proxy.close()
            } catch (e: Exception) {
            }
        }
    }

    private val listener = object : HandLandmarkerHelper.Listener {
        override fun onResult(
            first: Gesture?,
            allNames: List<String>,
            handedness: String,
            pointerY: Float
        ) {
            runOnUiThread {
                frames++
                val now = System.currentTimeMillis()
                val name = first?.name ?: GestureClassifier.UNKNOWN
                smoothBuf.addLast(name)
                if (smoothBuf.size > 7) smoothBuf.removeFirst()
                val stable = smoothBuf.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key ?: name

                if (first != null) {
                    signText.text = stable + " " + first.emoji
                    detailText.text = first.detail + " · " + handedness + " · " + engineStage()
                    val out = engine.onFrame(
                        stable, first.pinch, pointerY, demoScroll.scrollY.toFloat()
                    )
                    if (out.scrollToPx != null) {
                        demoScroll.post {
                            val max = (demoScroll.getChildAt(0)?.height ?: 0) - demoScroll.height
                            demoScroll.scrollTo(0, out.scrollToPx.toInt().coerceIn(0, max.coerceAtLeast(0)))
                        }
                    }
                    if (out.capture) saveCameraFrame()
                } else {
                    engine.onFrame(stable, 1f, 0.5f, demoScroll.scrollY.toFloat())
                    signText.text = "No hand"
                    detailText.text = "Show your hand to the front camera."
                }
                if (now - fpsStart >= 1000) {
                    statsText.text = "${allNames.size} hands · $frames fps · $handedness"
                    frames = 0
                    fpsStart = now
                }
            }
        }

        override fun onError(e: Exception) {
            runOnUiThread { detailText.text = "Detection issue: " + e.message }
        }
    }

    private fun engineStage(): String = "actions on"

    // ---------- actions ----------

    private fun saveCameraFrame() {
        val bmp: Bitmap? = synchronized(bmpLock) {
            lastBmp?.let {
                try {
                    it.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (bmp == null) {
            log("No camera frame yet — wait for the preview.")
            return
        }
        val uri = MediaStoreSaver.saveBitmap(this, bmp, "camera")
        addGalleryBitmap(bmp)
        log(if (uri != null) "Capture saved: $uri" else "Capture failed to save.")
    }

    private fun requestScreenCapture() {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCap.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            log("Screen capture not available here: " + e.message)
        }
    }

    private fun toggleBackground() {
        if (bgRunning) {
            TrackingService.stop(this)
            bgRunning = false
            findViewById<Button>(R.id.bgBtn).text = "Run in background (foreground service)"
            serviceStateText.text = "Background service: stopping…"
        } else {
            stopForegroundPipeline()
            TrackingService.start(
                this,
                findViewById<Switch>(R.id.scrollSwitch).isChecked,
                findViewById<Switch>(R.id.seqSwitch).isChecked,
                findViewById<SeekBar>(R.id.gainSeek).progress.toFloat()
            )
            bgRunning = true
            findViewById<Button>(R.id.bgBtn).text = "Stop background service"
            serviceStateText.text = "Background service: starting…"
        }
    }

    // ---------- gallery + log ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addGalleryBitmap(bmp: Bitmap) {
        try {
            val thumb = Bitmap.createScaledBitmap(bmp, dp(140), dp(105), true)
            val iv = ImageView(this)
            iv.layoutParams = LinearLayout.LayoutParams(dp(140), dp(105)).apply {
                setMargins(0, 0, dp(8), 0)
            }
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(thumb)
            galleryRow.addView(iv, 0)
            while (galleryRow.childCount > 20) galleryRow.removeViewAt(galleryRow.childCount - 1)
        } catch (e: Exception) {
        }
    }

    private fun addGalleryUri(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            contentResolver.openInputStream(uri)?.use {
                val bmp = BitmapFactory.decodeStream(it)
                if (bmp != null) addGalleryBitmap(bmp)
            }
        } catch (e: Exception) {
        }
    }

    private fun log(msg: String) {
        val cur = if (logText.text.toString() == "–") "" else logText.text.toString() + "\n"
        val next = (cur + msg).takeLast(2000)
        logText.text = next
    }
}
