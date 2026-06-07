package com.laobademirror

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.laobademirror.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var minZoom = 1f
    private var maxZoom = 1f
    private var currentZoom = 1f
    private var isCapturing = false
    private var isBinding = false
    private var isPausedByInactivity = false
    private var isTrackingZoom = false
    private var isTimeoutSettingsVisible = false

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: SharedPreferences
    private val prefName = "mirror_pref_v1"
    private val keyMirror = "mirror_enabled"
    private val keyCaptureTimeoutMinutes = "capture_timeout_minutes"
    private val keyHistoryUrisJson = "history_uris_json"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val historyUris = ArrayList<Uri>()
    private val defaultTimeoutMinutes = 1
    private val minTimeoutMinutes = 1
    private val maxTimeoutMinutes = 30
    private var captureTimeoutMinutes = defaultTimeoutMinutes

    private val uiHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable { pausePreviewForInactivity() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindCameraUseCases()
        } else {
            updateStatus(getString(R.string.status_permission_denied))
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showPermissionDialog()
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasCameraPermission()) {
            bindCameraUseCases()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)

        setupSystemUi()
        loadConfigs()
        initUi()
        bindHistoryThumbnails()
        requestPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        hideIdlePauseOverlay()
        isPausedByInactivity = false
        if (hasCameraPermission() && imageCapture == null) {
            bindCameraUseCases()
        } else if (imageCapture != null) {
            startInactivityTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        hideIdlePauseOverlay()
        hideTimeoutSettingsPanel()
        stopInactivityTimer()
        unbindCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopInactivityTimer()
        cameraExecutor.shutdown()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isPausedByInactivity) {
            resumeFromIdlePause()
            return
        }
        startInactivityTimer()
    }

    private fun setupSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadConfigs() {
        currentZoom = 1f
        captureTimeoutMinutes = prefs.getInt(keyCaptureTimeoutMinutes, defaultTimeoutMinutes)
            .coerceIn(minTimeoutMinutes, maxTimeoutMinutes)
        updateTimeoutButtonLabel()

        historyUris.clear()
        historyUris.addAll(loadHistoryFromPrefs())
        pruneMissingHistory()
    }

    private fun initUi() {
        val mirrorEnabled = prefs.getBoolean(keyMirror, true)
        binding.mirrorToggleButton.isSelected = mirrorEnabled
        applyMirror(mirrorEnabled)

        binding.mirrorToggleButton.setOnClickListener {
                val checked = !binding.mirrorToggleButton.isSelected
                prefs.edit().putBoolean(keyMirror, checked).apply()
                binding.mirrorToggleButton.isSelected = checked
                applyMirror(checked)
                val msg = if (checked) {
                    getString(R.string.mirror_toast_front_view)
                } else {
                    getString(R.string.mirror_toast_other_view)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                startInactivityTimer()
            }

        binding.zoomSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || imageCapture == null) return
                setZoomRatio(ratioFromProgress(progress))
                startInactivityTimer()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isTrackingZoom = true
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isTrackingZoom = false
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                doTapToFocus(event.x, event.y)
            }
            startInactivityTimer()
            false
        }

        binding.settingsButton.setOnClickListener {
            showCaptureTimeoutPanel()
            startInactivityTimer()
        }

        binding.timeoutSettingsOverlay.setOnClickListener {
            hideTimeoutSettingsPanel()
            startInactivityTimer()
        }
        binding.timeoutSettingsPanel.setOnClickListener { }
        binding.timeoutSettingsSaveButton.setOnClickListener {
            val typed = binding.timeoutMinutesInput.text?.toString()?.toIntOrNull()
            if (typed == null) {
                Toast.makeText(this, getString(R.string.status_save_failed, "输入无效"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            captureTimeoutMinutes = typed.coerceIn(minTimeoutMinutes, maxTimeoutMinutes)
            prefs.edit().putInt(keyCaptureTimeoutMinutes, captureTimeoutMinutes).apply()
            updateTimeoutButtonLabel()
            updateStatus(getString(R.string.status_timeout_set, captureTimeoutMinutes))
            hideTimeoutSettingsPanel()
            startInactivityTimer()
        }
        binding.timeoutSettingsCancelButton.setOnClickListener {
            hideTimeoutSettingsPanel()
            startInactivityTimer()
        }

        binding.captureButton.isEnabled = true
        binding.captureButton.setOnClickListener {
            if (isPausedByInactivity) {
                resumeFromIdlePause()
                return@setOnClickListener
            }
            takePhoto()
            startInactivityTimer()
        }
        binding.idleOverlay.setOnClickListener { resumeFromIdlePause() }
        setMirrorUiState(mirrorEnabled)
    }

    private fun resumeFromIdlePause() {
        if (!isPausedByInactivity) return
        isPausedByInactivity = false
        hideIdlePauseOverlay()
        startInactivityTimer()
        bindCameraUseCases()
    }

    private fun requestPermissionIfNeeded() {
        if (hasCameraPermission()) {
            bindCameraUseCases()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCameraUseCases() {
        if (isBinding || isPausedByInactivity || !hasCameraPermission()) return
        isBinding = true
        val providerFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                val provider = cameraProvider ?: throw IllegalStateException("camera provider null")
                provider.unbindAll()

                val preview = Preview.Builder()
                    .setTargetRotation(binding.previewView.display.rotation)
                    .build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .setTargetRotation(binding.previewView.display.rotation)
                    .build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture
                )
                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo

                updateZoomRangeFromCameraInfo()
                applyMirror(prefs.getBoolean(keyMirror, true))
                updateStatus("")
                binding.captureButton.isEnabled = true
                binding.captureButton.alpha = 1f
                if (isPausedByInactivity) {
                    isPausedByInactivity = false
                    hideIdlePauseOverlay()
                }
                isPausedByInactivity = false
                currentZoom = 1f
                setZoomRatio(1f)
                startInactivityTimer()
            } catch (_: Exception) {
                imageCapture = null
                updateStatus(getString(R.string.status_no_front_camera))
            } finally {
                isBinding = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
        camera = null
        cameraControl = null
        cameraInfo = null
        isCapturing = false
        if (!isPausedByInactivity) {
            binding.captureButton.isEnabled = true
            binding.captureButton.alpha = 1f
        }
    }

    private fun updateZoomRangeFromCameraInfo() {
        cameraInfo?.zoomState?.observe(this) { state ->
            minZoom = state.minZoomRatio.coerceAtLeast(1f)
            maxZoom = state.maxZoomRatio
            binding.zoomSeekBar.max = 1000
            val actualZoom = state.zoomRatio.coerceIn(minZoom, maxZoom)
            if (!isTrackingZoom) {
                currentZoom = actualZoom
                setSeekBarProgressFromZoom(actualZoom)
                updateZoomText(actualZoom)
            }
        }
    }

    private fun setSeekBarProgressFromZoom(zoom: Float) {
        val range = maxZoom - minZoom
        val progress = if (range <= 0f) {
            0
        } else {
            (((zoom - minZoom) / range) * binding.zoomSeekBar.max.toFloat()).roundToInt().coerceIn(0, binding.zoomSeekBar.max)
        }
        binding.zoomSeekBar.setProgress(progress, true)
    }

    private fun ratioFromProgress(progress: Int): Float {
        val range = maxZoom - minZoom
        if (range <= 0f) return minZoom
        return minZoom + range * (progress.toFloat() / binding.zoomSeekBar.max.toFloat())
    }

    private fun setZoomRatio(requested: Float) {
        if (cameraControl == null) return
        val target = requested.coerceIn(minZoom, maxZoom)
        currentZoom = target
        updateZoomText(target)
        setSeekBarProgressFromZoom(target)
        cameraControl?.setZoomRatio(target)
    }

    private fun updateZoomText(zoom: Float) {
        val normalized = if (abs(zoom - zoom.toInt()) < 0.05f) {
            zoom.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", zoom)
        }
        val text = normalized
        binding.zoomValueText.text = getString(R.string.zoom_label, text)
    }

    private fun setMirrorUiState(enabled: Boolean) {
        val colorRes = if (enabled) {
            R.color.white
        } else {
            R.color.zoom_accent
        }
        binding.mirrorToggleButton.setColorFilter(ContextCompat.getColor(this, colorRes))
        binding.mirrorToggleButton.alpha = 1f
    }

    private fun doTapToFocus(x: Float, y: Float) {
        val control = cameraControl ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF
        ).setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        control.startFocusAndMetering(action)
        binding.statusText.text = getString(R.string.status_focusing)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true
        binding.captureButton.isEnabled = false
        binding.statusText.text = getString(R.string.capture)

        val ratio = currentZoom
        cameraControl?.setZoomRatio(ratio)?.addListener({
            if (binding.mirrorToggleButton.isSelected) {
                captureToTempThenMirror(capture)
            } else {
                captureToMediaStore(capture)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureToMediaStore(capture: ImageCapture) {
        val filename = nextImageName()
        val metadata = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            metadata
        ).build()

        capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = outputFileResults.savedUri
                if (savedUri == null) {
                    failCapture("保存返回路径为空")
                } else {
                    onPhotoSaved(savedUri)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                failCapture(exception.message ?: exception.javaClass.simpleName)
            }
        })
    }

    private fun captureToTempThenMirror(capture: ImageCapture) {
        val tempFile = File(cacheDir, "mirror_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                var source: Bitmap? = null
                var oriented: Bitmap? = null
                var mirrored: Bitmap? = null

                try {
                    source = BitmapFactory.decodeFile(tempFile.absolutePath)
                    if (source == null) {
                        failCapture("读取照片失败")
                        return
                    }
                    oriented = normalizeBitmapByExif(tempFile.absolutePath, source)
                    mirrored = mirrorBitmapHorizontally(oriented)
                    val uri = saveBitmapToMediaStore(mirrored, nextImageName())
                    if (uri != null) {
                        onPhotoSaved(uri)
                    } else {
                        failCapture("保存到相册失败")
                    }
                } catch (e: IOException) {
                    failCapture(e.message ?: e.javaClass.simpleName)
                } finally {
                    if (oriented != source && oriented != null && !oriented.isRecycled) {
                        oriented.recycle()
                    }
                    source?.recycle()
                    mirrored?.recycle()
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                failCapture(exception.message ?: exception.javaClass.simpleName)
            }
        })
    }

    private fun onPhotoSaved(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
        addToHistory(uri)
        doneCapture()
        runOnUiThread {
            updateStatus(getString(R.string.status_saved))
            binding.captureButton.isEnabled = true
            Toast.makeText(this@MainActivity, getString(R.string.status_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        return try {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            } ?: run {
                contentResolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeBitmapByExif(path: String, original: Bitmap): Bitmap {
        val orientation = runCatching {
            val exif = ExifInterface(path)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> preRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    preRotate(90f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    preRotate(270f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> preRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_270 -> preRotate(270f)
                else -> return original
            }
        }

        return if (matrix.isIdentity) {
            original
        } else {
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        }
    }

    private fun mirrorBitmapHorizontally(src: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            preScale(-1f, 1f, src.width / 2f, src.height / 2f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
    }

    private fun doneCapture() {
        isCapturing = false
        runOnUiThread {
            binding.captureButton.isEnabled = true
        }
    }

    private fun failCapture(message: String) {
        runOnUiThread {
            updateStatus(getString(R.string.status_save_failed, message))
            isCapturing = false
            binding.captureButton.isEnabled = true
            binding.captureButton.alpha = 1f
            Toast.makeText(this, getString(R.string.status_save_failed, message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextImageName(): String {
        val randomSuffix = System.currentTimeMillis() % 1000
        return "laobadejizi_${dateFormat.format(Date())}_$randomSuffix.jpg"
    }

    private fun pausePreviewForInactivity() {
        if (isPausedByInactivity) return
        isPausedByInactivity = true
        stopInactivityTimer()
        hideTimeoutSettingsPanel()
        unbindCamera()
        showIdlePauseOverlay()
        binding.captureButton.isEnabled = false
        binding.captureButton.alpha = 0.45f
    }

    private fun showCaptureTimeoutPanel() {
        if (isTimeoutSettingsVisible) return
        binding.timeoutSettingsOverlay.visibility = View.VISIBLE
        binding.timeoutMinutesInput.setText(captureTimeoutMinutes.toString())
        binding.timeoutMinutesInput.setSelection(binding.timeoutMinutesInput.text.length)
        isTimeoutSettingsVisible = true
        startInactivityTimer()
    }

    private fun hideTimeoutSettingsPanel() {
        if (!isTimeoutSettingsVisible && binding.timeoutSettingsOverlay.visibility != View.VISIBLE) return
        isTimeoutSettingsVisible = false
        binding.timeoutSettingsOverlay.visibility = View.GONE
    }

    private fun updateTimeoutButtonLabel() {
        binding.settingsButton.text = getString(R.string.capture_timeout_button_label, captureTimeoutMinutes)
    }

    private fun showIdlePauseOverlay() {
        binding.idleOverlay.visibility = View.VISIBLE
        binding.topInfo.visibility = View.GONE
        binding.zoomPanel.visibility = View.GONE
        binding.controlBar.visibility = View.GONE
        updateStatus("")
    }

    private fun hideIdlePauseOverlay() {
        binding.idleOverlay.visibility = View.GONE
        binding.topInfo.visibility = View.VISIBLE
        binding.zoomPanel.visibility = View.VISIBLE
        binding.controlBar.visibility = View.VISIBLE
    }

    private fun applyMirror(enabled: Boolean) {
        binding.previewView.scaleX = if (enabled) -1f else 1f
        setMirrorUiState(enabled)
    }

    private fun startInactivityTimer() {
        if (isPausedByInactivity || !hasCameraPermission()) {
            return
        }
        stopInactivityTimer()
        uiHandler.postDelayed(
            idleTimeoutRunnable,
            TimeUnit.MINUTES.toMillis(captureTimeoutMinutes.toLong())
        )
    }

    private fun stopInactivityTimer() {
        uiHandler.removeCallbacks(idleTimeoutRunnable)
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.grant_camera_permission))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                settingsLauncher.launch(intent)
            }
            .setNegativeButton(getString(R.string.dismiss), null)
            .show()
    }

    private fun updateStatus(msg: String) {
        binding.statusText.text = msg
    }

    private fun addToHistory(uri: Uri) {
        historyUris.removeAll { it.toString() == uri.toString() }
        historyUris.add(0, uri)
        saveHistoryToPrefs()
        bindHistoryThumbnails()
    }

    private fun bindHistoryThumbnails() {
        runOnUiThread {
            binding.historyContainer.removeAllViews()
            val latestUri = historyUris.firstOrNull()
            if (latestUri == null) {
                val placeholder = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.mirror_history_size),
                        resources.getDimensionPixelSize(R.dimen.mirror_history_size)
                    )
                    setBackgroundResource(R.drawable.bg_history_placeholder)
                }
                binding.historyContainer.addView(placeholder)
                return@runOnUiThread
            }

            val imageSize = resources.getDimensionPixelSize(R.dimen.mirror_history_size)
            val params = LinearLayout.LayoutParams(imageSize, imageSize)
            val thumb = ImageView(this@MainActivity).apply {
                layoutParams = params
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1D2B44.toInt())
                setImageURI(latestUri)
                contentDescription = getString(R.string.history_item_desc)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openPhoto(latestUri)
                }
            }
            binding.historyContainer.addView(thumb)
        }
    }

    private fun openPhoto(uri: Uri) {
        if (!isUriAvailable(uri)) {
            removeHistoryUri(uri)
            bindHistoryThumbnails()
            Toast.makeText(this, getString(R.string.history_missing_photo), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, getString(R.string.status_save_failed, "打开历史照片失败"), Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadHistoryFromPrefs(): List<Uri> {
        val result = ArrayList<Uri>()
        val json = prefs.getString(keyHistoryUrisJson, "[]") ?: return result
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val value = arr.optString(i)
                if (!value.isNullOrBlank()) {
                    result.add(Uri.parse(value))
                }
            }
        }.onFailure {
            // ignore malformed cache
        }
        return result
    }

    private fun saveHistoryToPrefs() {
        val arr = JSONArray()
        historyUris.forEach { arr.put(it.toString()) }
        prefs.edit().putString(keyHistoryUrisJson, arr.toString()).apply()
    }

    private fun pruneMissingHistory() {
        if (historyUris.isEmpty()) return
        val validUris = ArrayList<Uri>()
        historyUris.forEach { uri ->
            if (isUriAvailable(uri)) {
                validUris.add(uri)
            }
        }
        if (validUris.size == historyUris.size) return
        val removedCount = historyUris.size - validUris.size
        historyUris.clear()
        historyUris.addAll(validUris)
        saveHistoryToPrefs()
        if (removedCount > 0) {
            Toast.makeText(this, getString(R.string.history_cleanup_message, removedCount), Toast.LENGTH_SHORT).show()
        }
        bindHistoryThumbnails()
    }

    private fun removeHistoryUri(uri: Uri) {
        val removed = historyUris.removeAll { it.toString() == uri.toString() }
        if (removed) {
            saveHistoryToPrefs()
        }
    }

    private fun isUriAvailable(uri: Uri): Boolean {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }
}
