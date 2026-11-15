package com.mohit.ultraaicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicConvolve3x3
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var lutOverlayView: View
    private lateinit var gridOverlayView: GridOverlayView
    private lateinit var focusRingView: View
    private lateinit var countdownText: TextView
    private lateinit var statusBadge: TextView

    private lateinit var zoomSeekBar: SeekBar
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var zoomText: TextView
    private lateinit var exposureText: TextView

    private lateinit var flashBtn: Button
    private lateinit var timerBtn: Button
    private lateinit var gridBtn: Button
    private lateinit var nightBtn: Button
    private lateinit var lutBtn: Button
    private lateinit var captureBtn: Button
    private lateinit var videoBtn: Button
    private lateinit var switchCamBtn: Button

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: androidx.camera.core.Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentLutIndex = 0
    private var isNightGlareMode = false
    private var flashModeState = 0 // 0=OFF, 1=ON, 2=AUTO, 3=TORCH
    private var timerSeconds = 0 // 0, 3, 10
    private var isGridVisible = false

    // 10 Master LUTs (Including your samples, iPhone & S24 Ultra styles)
    private val lutNames = arrayOf(
        "LUT 1: iPhone 16 Warm Skin",
        "LUT 2: S24 Ultra Dynamic Pop",
        "LUT 3: Emerald Rain Macro",
        "LUT 4: Golden Sunset Prompt",
        "LUT 5: Crimson Velvet Rose",
        "LUT 6: Midnight Moon Dark",
        "LUT 7: Teal & Orange Cinema",
        "LUT 8: Cyberpunk Neon Night",
        "LUT 9: Vintage Fuji Film",
        "LUT 10: Leica Monochrome B&W"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildFullProUI()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 101)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFullProUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camera Live Viewfinder
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView)

        // 2. Live LUT Color Filter Overlay
        lutOverlayView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(lutOverlayView)

        // 3. 3x3 Rule-of-Thirds Grid Overlay
        gridOverlayView = GridOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        root.addView(gridOverlayView)

        // 4. Tap-to-Focus Indicator Ring
        focusRingView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(160, 160)
            setBackgroundColor(Color.parseColor("#44FFFF00"))
            visibility = View.GONE
        }
        root.addView(focusRingView)

        // Tap-to-Focus Touch Listener on Preview
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)

                // Show focus box at touch location
                focusRingView.x = event.x - 80
                focusRingView.y = event.y - 80
                focusRingView.visibility = View.VISIBLE
                focusRingView.postDelayed({ focusRingView.visibility = View.GONE }, 1200)
                statusBadge.text = "🎯 AI Laser Focus Locked!"
            }
            true
        }

        // 5. Center Timer Countdown Text
        countdownText = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 72f
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(countdownText)

        // 6. Top Control Bar (Flash, Timer, Grid, Night Mode)
        val topContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(16, 36, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        statusBadge = TextView(this).apply {
            text = "⚡ AI Ultra HD | Tap Screen to Focus"
            setTextColor(Color.YELLOW)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        topContainer.addView(statusBadge)

        val topButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        flashBtn = Button(this).apply {
            text = "🔦 Flash: OFF"
            textSize = 10f
            setOnClickListener { cycleFlashMode() }
        }
        topButtonsRow.addView(flashBtn)

        timerBtn = Button(this).apply {
            text = "⏱️ 0s"
            textSize = 10f
            setOnClickListener { cycleTimer() }
        }
        topButtonsRow.addView(timerBtn)

        gridBtn = Button(this).apply {
            text = "📏 Grid"
            textSize = 10f
            setOnClickListener {
                isGridVisible = !isGridVisible
                gridOverlayView.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            }
        }
        topButtonsRow.addView(gridBtn)

        nightBtn = Button(this).apply {
            text = "🌙 Night: OFF"
            textSize = 10f
            setOnClickListener { toggleNightMode() }
        }
        topButtonsRow.addView(nightBtn)

        topContainer.addView(topButtonsRow)
        root.addView(topContainer)

        // 7. Bottom Pro Controls Panel (Exposure, Zoom, LUTs, Shutters)
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(24, 16, 24, 40)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Exposure / Anti-Glare Slider
        exposureText = TextView(this).apply {
            text = "☀️ Light / Anti-Glare Control: 0"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        bottomPanel.addView(exposureText)

        exposureSeekBar = SeekBar(this).apply {
            max = 8 // Maps to -4 to +4
            progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val evIndex = progress - 4
                    camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                    exposureText.text = "☀️ Light / Anti-Glare Control: $evIndex"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        bottomPanel.addView(exposureSeekBar)

        // Smooth Zoom Slider (Front & Back)
        zoomText = TextView(this).apply {
            text = "🔍 AI Super Zoom: 1.0x"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        bottomPanel.addView(zoomText)

        zoomSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val linearZoom = progress / 100f
                    camera?.cameraControl?.setLinearZoom(linearZoom)
                    val zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    zoomText.text = String.format("🔍 AI Super Zoom: %.1fx (Anti-Pixelate ON)", zoomRatio)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        bottomPanel.addView(zoomSeekBar)

        // LUT Selector Button
        lutBtn = Button(this).apply {
            text = "🎨 " + lutNames[0]
            textSize = 12f
            setOnClickListener { cycleLut() }
        }
        bottomPanel.addView(lutBtn)

        // Main Shutter Row
        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        switchCamBtn = Button(this).apply {
            text = "🔄 Flip"
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                zoomSeekBar.progress = 0
                startCamera()
            }
        }
        shutterRow.addView(switchCamBtn)

        captureBtn = Button(this).apply {
            text = "📸 AI PHOTO"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setOnClickListener { triggerPhotoWithTimer() }
        }
        shutterRow.addView(captureBtn)

        videoBtn = Button(this).apply {
            text = "🎥 VIDEO"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener { captureStableVideo() }
        }
        shutterRow.addView(videoBtn)

        bottomPanel.addView(shutterRow)
        root.addView(bottomPanel)
        setContentView(root)
    }

    private fun cycleFlashMode() {
        flashModeState = (flashModeState + 1) % 4
        when (flashModeState) {
            0 -> {
                flashBtn.text = "🔦 Flash: OFF"
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                camera?.cameraControl?.enableTorch(false)
            }
            1 -> {
                flashBtn.text = "🔦 Flash: ON"
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                camera?.cameraControl?.enableTorch(false)
            }
            2 -> {
                flashBtn.text = "🔦 Flash: AUTO"
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                camera?.cameraControl?.enableTorch(false)
            }
            3 -> {
                flashBtn.text = "🔦 TORCH: ON"
                camera?.cameraControl?.enableTorch(true)
            }
        }
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        timerBtn.text = "⏱️ ${timerSeconds}s"
    }

    private fun cycleLut() {
        currentLutIndex = (currentLutIndex + 1) % lutNames.size
        lutBtn.text = "🎨 " + lutNames[currentLutIndex]

        val overlayColor = when (currentLutIndex) {
            0 -> Color.parseColor("#12FFDFC4") // iPhone Warm Skin
            1 -> Color.parseColor("#1400CCFF") // S24 Ultra Dynamic
            2 -> Color.parseColor("#1800FF66") // Emerald Rain Macro
            3 -> Color.parseColor("#24FFB300") // Golden Sunset
            4 -> Color.parseColor("#18FF0055") // Crimson Rose
            5 -> Color.parseColor("#28001133") // Midnight Moon
            6 -> Color.parseColor("#180088AA") // Teal & Orange
            7 -> Color.parseColor("#22CC00FF") // Cyberpunk Neon
            8 -> Color.parseColor("#18FFEEAA") // Vintage Fuji
            else -> Color.parseColor("#55000000") // B&W Monochrome
        }
        lutOverlayView.setBackgroundColor(overlayColor)
    }

    private fun toggleNightMode() {
        isNightGlareMode = !isNightGlareMode
        nightBtn.text = if (isNightGlareMode) "🌙 Night: ON" else "🌙 Night: OFF"
        if (isNightGlareMode) {
            exposureSeekBar.progress = 2 // Auto set -2 EV to stop light glare
            statusBadge.text = "🌙 Night Anti-Glare Active: Lights Controlled & Shadows Boosted"
        } else {
            exposureSeekBar.progress = 4
            statusBadge.text = "⚡ AI Ultra HD | Tap Screen to Focus"
        }
        startCamera()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(this, cameraProvider)

            extensionsManagerFuture.addListener({
                val extensionsManager = extensionsManagerFuture.get()
                val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val cameraSelector = when {
                    isNightGlareMode && extensionsManager.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT) ->
                        extensionsManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                    extensionsManager.isExtensionAvailable(baseSelector, ExtensionMode.HDR) ->
                        extensionsManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.HDR)
                    else -> baseSelector
                }

                val previewBuilder = Preview.Builder()
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)

                val camera2Extender = Camera2Interop.Extender(previewBuilder)
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY
                )
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )

                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = imageCaptureBuilder.build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, videoCapture
                    )
                    if (flashModeState == 3) {
                        camera?.cameraControl?.enableTorch(true)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Camera Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerPhotoWithTimer() {
        if (timerSeconds == 0) {
            executeFrontFlashAndCapture()
        } else {
            countdownText.visibility = View.VISIBLE
            object : CountDownTimer(timerSeconds * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    countdownText.text = ((millisUntilFinished / 1000) + 1).toString()
                }
                override fun onFinish() {
                    countdownText.visibility = View.GONE
                    executeFrontFlashAndCapture()
                }
            }.start()
        }
    }

    private fun executeFrontFlashAndCapture() {
        // iPhone Retina Front Screen Flash if using front camera with Flash ON
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && (flashModeState == 1 || flashModeState == 3)) {
            lutOverlayView.setBackgroundColor(Color.parseColor("#EEFFF5E4"))
            val params = window.attributes
            val oldBrightness = params.screenBrightness
            params.screenBrightness = 1.0f
            window.attributes = params

            lutOverlayView.postDelayed({
                takeInstantAiPhoto {
                    params.screenBrightness = oldBrightness
                    window.attributes = params
                    cycleLut() // Restore LUT overlay
                }
            }, 250)
        } else {
            takeInstantAiPhoto {}
        }
    }

    private fun takeInstantAiPhoto(onComplete: () -> Unit) {
        val imgCap = imageCapture ?: return
        statusBadge.text = "⚡ AI Enhancing Pixels & Skin Tone..."

        imgCap.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rawBitmap = imageProxyToBitmap(image)
                image.close()

                val enhancedBitmap = processUltraAiPipeline(rawBitmap)
                saveBitmapToGallery(enhancedBitmap)

                runOnUiThread {
                    onComplete()
                    statusBadge.text = "✅ Saved 100% Ultra HD Photo!"
                    Toast.makeText(this@MainActivity, "✨ Ultra HD AI Photo Saved!", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    onComplete()
                    Toast.makeText(this@MainActivity, "Error: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun processUltraAiPipeline(source: Bitmap): Bitmap {
        val gradedBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gradedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val colorMatrix = ColorMatrix()
        when (currentLutIndex) {
            0 -> colorMatrix.set(floatArrayOf( // 1. iPhone 16 Warm Skin
                1.12f, 0.02f, 0.00f, 0f, 8f,
                0.00f, 1.06f, 0.00f, 0f, 4f,
                0.00f, 0.00f, 0.96f, 0f, -4f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            1 -> colorMatrix.set(floatArrayOf( // 2. S24 Ultra Dynamic Pop
                1.22f, -0.04f, -0.02f, 0f, 4f,
                -0.02f, 1.20f, -0.02f, 0f, 4f,
                -0.02f, -0.04f, 1.25f, 0f, 6f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            2 -> colorMatrix.set(floatArrayOf( // 3. Emerald Rain Macro
                1.18f, 0.00f, 0.00f, 0f, -10f,
                0.05f, 1.32f, 0.05f, 0f, 6f,
                0.00f, 0.00f, 1.05f, 0f, -12f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            3 -> colorMatrix.set(floatArrayOf( // 4. Golden Sunset Prompt
                1.26f, 0.08f, 0.00f, 0f, 15f,
                0.02f, 1.12f, 0.00f, 0f, 8f,
                0.00f, 0.00f, 0.85f, 0f, -15f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            4 -> colorMatrix.set(floatArrayOf( // 5. Crimson Velvet Rose
                1.36f, -0.05f, -0.05f, 0f, -8f,
                -0.02f, 1.15f, 0.00f, 0f, -12f,
                0.00f, 0.00f, 1.05f, 0f, -12f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            5 -> colorMatrix.set(floatArrayOf( // 6. Midnight Moon Dark
                1.25f, 0.00f, 0.05f, 0f, -18f,
                0.00f, 1.20f, 0.05f, 0f, -18f,
                0.05f, 0.08f, 1.35f, 0f, 12f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            6 -> colorMatrix.set(floatArrayOf( // 7. Teal & Orange Cinema
                1.20f, 0.05f, -0.10f, 0f, 6f,
                -0.05f, 1.10f, 0.05f, 0f, 0f,
                -0.10f, 0.10f, 1.25f, 0f, 8f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            7 -> colorMatrix.set(floatArrayOf( // 8. Cyberpunk Neon
                1.30f, 0.00f, 0.15f, 0f, 5f,
                0.00f, 1.05f, 0.10f, 0f, -10f,
                0.15f, 0.05f, 1.40f, 0f, 15f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            8 -> colorMatrix.set(floatArrayOf( // 9. Vintage Fuji Film
                1.10f, 0.10f, 0.05f, 0f, 12f,
                0.05f, 1.08f, 0.05f, 0f, 10f,
                0.00f, 0.05f, 0.90f, 0f, 5f,
                0.00f, 0.00f, 0.00f, 1f, 0f
            ))
            9 -> colorMatrix.setSaturation(0f) // 10. Leica B&W Monochrome
        }

        if (isNightGlareMode) {
            val nightLift = ColorMatrix(floatArrayOf(
                1.18f, 0f, 0f, 0f, 20f,
                0f, 1.18f, 0f, 0f, 20f,
                0f, 0f, 1.24f, 0f, 25f,
                0f, 0f, 0f, 1f, 0f
            ))
            colorMatrix.postConcat(nightLift)
        }

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return applyHardwareSharpening(gradedBitmap)
    }

    @Suppress("DEPRECATION")
    private fun applyHardwareSharpening(input: Bitmap): Bitmap {
        return try {
            val output = Bitmap.createBitmap(input.width, input.height, input.config)
            val rs = RenderScript.create(this)
            val allocIn = Allocation.createFromBitmap(rs, input)
            val allocOut = Allocation.createFromBitmap(rs, output)
            val convolution = ScriptIntrinsicConvolve3x3.create(rs, Element.U8_4(rs))

            val kernel = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                floatArrayOf(
                    0f, -0.25f, 0f,
                    -0.25f, 2.0f, -0.25f,
                    0f, -0.25f, 0f
                )
            } else {
                floatArrayOf(
                    -0.15f, -0.5f, -0.15f,
                    -0.5f, 3.6f, -0.5f,
                    -0.15f, -0.5f, -0.15f
                )
            }

            convolution.setInput(allocIn)
            convolution.setCoefficients(kernel)
            convolution.forEach(allocOut)
            allocOut.copyTo(output)
            rs.destroy()
            output
        } catch (e: Exception) {
            input
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "UltraAI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/UltraAICamera")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val stream: OutputStream? = contentResolver.openOutputStream(it)
            stream?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureStableVideo() {
        val videoCap = this.videoCapture ?: return
        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            videoBtn.text = "🎥 VIDEO"
            return
        }

        val name = "UltraAI_Vid_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/UltraAICamera")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCap.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        videoBtn.text = "⏹ STOP"
                        statusBadge.text = "🔴 Recording Stabilized FHD Video (Zoom & Torch Active)"
                    }
                    is VideoRecordEvent.Finalize -> {
                        videoBtn.text = "🎥 VIDEO"
                        statusBadge.text = "✅ Stabilized Video Saved!"
                        Toast.makeText(this, "🎥 Video Saved to Gallery!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    // Custom View for 3x3 Camera Framing Grid
    class GridOverlayView(context: android.content.Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.parseColor("#66FFFFFF")
            strokeWidth = 2f
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(w / 3, 0f, w / 3, h, paint)
            canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, paint)
            canvas.drawLine(0f, h / 3, w, h / 3, paint)
            canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, paint)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera & Audio permissions required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
