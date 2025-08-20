package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), CameraXConfig.Provider {

    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: Button
    private lateinit var fpsCounter: TextView
    private lateinit var qualityDisplay: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private lateinit var cameraExecutor: ExecutorService
    private var activeRecording: Recording? = null

    // FPS tracking
    private var frameCount = 0
    private var lastTime = System.currentTimeMillis()

    // Top bar widgets
    private lateinit var qualitySpinner: Spinner
    private lateinit var nightVisionToggle: Switch
    private var nightVisionEnabled = false
    private var selectedQuality: Quality = Quality.HIGHEST

    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        fpsCounter = findViewById(R.id.fpsCounter)

        // Quality display
     //
        val qualityParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        qualityParams.gravity = Gravity.TOP or Gravity.END
        qualityParams.topMargin = 40
        qualityParams.rightMargin = 40 
       // addContentView(qualityDisplay, qualityParams)

        // Apply red drawable background for button
        captureButton.background = ContextCompat.getDrawable(this, R.drawable.capture_button_bg)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupTopBar()
        setupCaptureButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupTopBar() {
        // Quality spinner
        qualitySpinner = Spinner(this)
        val qualityOptions = arrayOf("High", "Medium", "Low")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = adapter
        qualitySpinner.alpha = 0.8f
        val spinnerParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        spinnerParams.gravity = Gravity.TOP or Gravity.START
        spinnerParams.topMargin = 40
        spinnerParams.leftMargin = 40
        addContentView(qualitySpinner, spinnerParams)

        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                selectedQuality = when (position) {
                    0 -> Quality.HIGHEST
                    1 -> Quality.FHD
                    2 -> Quality.SD
                    else -> Quality.HIGHEST
                }
                if(allPermissionsGranted()) startCamera()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Night Vision toggle
        nightVisionToggle = Switch(this)
        nightVisionToggle.text = "Night Vision"
        nightVisionToggle.alpha = 0.8f
        val toggleParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        toggleParams.gravity = Gravity.TOP or Gravity.START
        toggleParams.topMargin = 40
        toggleParams.leftMargin = 250
        addContentView(nightVisionToggle, toggleParams)

        nightVisionToggle.setOnCheckedChangeListener { _, isChecked ->
            nightVisionEnabled = isChecked
        }
    }

    private fun setupCaptureButton() {
        captureButton.setOnClickListener {
            vibratePhone()
            // Update quality display for photo


            takePhotoWithFilter()
        }

        captureButton.setOnLongClickListener {
            // Update quality display for video
            val videoQualityText = when(selectedQuality) {
                Quality.HIGHEST -> "4K"
                Quality.FHD -> "1080p"
                Quality.SD -> "720p"
                Quality.UHD -> "2160p"
                else -> "Auto"
            }
            qualityDisplay.text = "Video: $videoQualityText"
            startVideoRecording()
            true
        }

        captureButton.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                stopVideoRecording()
            }
            false
        }
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            // Max quality photo capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(viewFinder.display.rotation)
                .build()

            // Update quality display for photo


            // Video capture with selected quality
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(selectedQuality))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // ImageAnalysis for FPS tracking
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTime >= 1000) {
                    val fps = frameCount
                    frameCount = 0
                    lastTime = currentTime
                    runOnUiThread {
                        fpsCounter.text = "FPS: $fps"
                    }
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoWithFilter() {
        val imageCapture = imageCapture ?: return
        imageCapture.targetRotation = viewFinder.display.rotation

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cam-hv")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { uri ->
                        try {
                            val inputStream = contentResolver.openInputStream(uri)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()

                            val exifStream = contentResolver.openInputStream(uri)
                            val exif = ExifInterface(exifStream!!)
                            val orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                            )
                            exifStream.close()

                            val rotatedBitmap = rotateBitmapIfNeeded(bitmap!!, orientation)
                            val filteredBitmap = if(nightVisionEnabled)
                                applyNightVisionFilter(rotatedBitmap)
                            else
                                applyCustomFilter(rotatedBitmap)

                            contentResolver.openOutputStream(uri, "w")?.use {
                                filteredBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                            }

                            runOnUiThread {
                                Toast.makeText(applicationContext, "Photo captured", Toast.LENGTH_SHORT).show()
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error applying filter: ${e.message}", e)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }

    private fun startVideoRecording() {
        val videoCapture = videoCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cam-hv")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) withAudioEnabled()
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(this, "Recording saved: ${recordEvent.outputResults.outputUri}", Toast.LENGTH_SHORT).show()
                        } else Log.e(TAG, "Video capture error: ${recordEvent.error}")
                    }
                }
            }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyCustomFilter(original: Bitmap): Bitmap {
        val contrast = 1.5f
        val saturation = 1.3f
        val brightness = -70f

        val cm = ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(ColorMatrix(floatArrayOf(
                contrast,0f,0f,0f,brightness,
                0f,contrast,0f,0f,brightness,
                0f,0f,contrast,0f,brightness,
                0f,0f,0f,1f,0f
            )))
        }

        val config = original.config ?: Bitmap.Config.ARGB_8888
        val filteredBitmap = Bitmap.createBitmap(original.width, original.height, config)
        val canvas = Canvas(filteredBitmap)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(original, 0f, 0f, paint)
        return filteredBitmap
    }

    private fun applyNightVisionFilter(original: Bitmap): Bitmap {
        val contrast = 1.2f
        val saturation = 0.5f
        val brightness = 50f

        val cm = ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(ColorMatrix(floatArrayOf(
                contrast,0f,0f,0f,brightness,
                0f,contrast,0f,0f,brightness,
                0f,0f,contrast,0f,brightness,
                0f,0f,0f,1f,0f
            )))
        }

        val config = original.config ?: Bitmap.Config.ARGB_8888
        val filteredBitmap = Bitmap.createBitmap(original.width, original.height, config)
        val canvas = Canvas(filteredBitmap)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(original, 0f, 0f, paint)
        return filteredBitmap
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
}
