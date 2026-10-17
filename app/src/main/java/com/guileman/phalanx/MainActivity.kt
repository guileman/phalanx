package com.guileman.phalanx

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var hud: HudView
    private lateinit var fireButton: ControlButtonView
    private lateinit var reloadButton: ControlButtonView

    private val gun = GunController()
    private lateinit var orientation: OrientationTracker
    private lateinit var sound: PhalanxSound
    private var detector: TargetDetector? = null
    private lateinit var analysisExecutor: ExecutorService

    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        goFullscreen()

        previewView = findViewById(R.id.preview)
        hud = findViewById(R.id.hud)
        fireButton = findViewById(R.id.fireButton)
        reloadButton = findViewById(R.id.reloadButton)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: permite aplicar desenfoque

        orientation = OrientationTracker(this)
        sound = PhalanxSound(gun)
        analysisExecutor = Executors.newSingleThreadExecutor()
        detector = TargetDetector(this, orientation) { frame -> hud.submitDetections(frame) }

        hud.gun = gun
        hud.orientation = orientation
        hud.cameraView = previewView
        hud.detectorReady = detector?.isReady == true
        hud.onGunStateChanged = ::onGunState

        fireButton.label = "FUEGO"
        fireButton.onPressChanged = { pressed -> gun.trigger = pressed }

        reloadButton.label = "RECARGA"
        reloadButton.accent = android.graphics.Color.rgb(0xFF, 0xB0, 0x20)
        reloadButton.onPressChanged = { pressed -> if (pressed) gun.requestReload() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun currentRotation(): Int {
        val d = if (Build.VERSION.SDK_INT >= 30) display else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return d?.rotation ?: Surface.ROTATION_90
    }

    private fun applyRotation() {
        val rot = currentRotation()
        orientation.displayRotation = rot
        preview?.targetRotation = rot
        analysis?.targetRotation = rot
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRotation()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val rotation = currentRotation()

            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val pv = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .setTargetRotation(rotation)
                .build()
            pv.setSurfaceProvider(previewView.surfaceProvider)

            val an = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            detector?.let { an.setAnalyzer(analysisExecutor, it) }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
                preview = pv
                analysis = an
                applyFieldOfView(camera)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar la cámara", e)
                Toast.makeText(this, "Error de cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Calcula el campo de visión horizontal real de la cámara para que la proyección coincida con la imagen. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFieldOfView(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && sensor != null && focal > 0f) {
                val hfov = 2.0 * atan(sensor.width / (2.0 * focal))
                Log.i(TAG, "FOV horizontal = ${Math.toDegrees(hfov)}°")
                hud.setHorizontalFov(hfov)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el FOV, se usa el valor por defecto", e)
        }
    }

    private fun onGunState(state: GunState) {
        val v = vibrator ?: return
        try {
            when (state) {
                GunState.SPIN_UP -> v.vibrate(VibrationEffect.createOneShot(1000, 35))
                GunState.FIRING -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(8, 7), intArrayOf(220, 0), 0))
                GunState.SPIN_DOWN -> v.vibrate(VibrationEffect.createOneShot(300, 60))
                else -> v.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibración no disponible", e)
        }
    }

    // Tecla de volumen: bajar = gatillo, subir = recargar
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { gun.trigger = true; return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { if (event.repeatCount == 0) gun.requestReload(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { gun.trigger = false; return true }
            KeyEvent.KEYCODE_VOLUME_UP -> return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
        applyRotation()
        orientation.start()
        sound.start()
    }

    override fun onPause() {
        gun.trigger = false
        orientation.stop()
        sound.stop()
        vibrator?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        val d = detector
        analysisExecutor.execute { d?.close() }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Phalanx"
    }
}
