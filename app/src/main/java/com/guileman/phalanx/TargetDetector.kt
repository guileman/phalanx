package com.guileman.phalanx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Una detección en coordenadas de la imagen analizada (ya rotada). */
data class RawDetection(val box: RectF, val label: String, val score: Float)

/** Resultado de un cuadro de cámara + orientación del celular en el momento de la captura. */
class DetectionFrame(
    val detections: List<RawDetection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val captureMatrix: FloatArray,
)

object TargetClasses {
    /** Etiqueta COCO -> (nombre en pantalla, altura real aproximada en metros para estimar distancia) */
    val info = mapOf(
        "person" to ("PERSONA" to 1.70f),
        "car" to ("AUTO" to 1.50f),
        "truck" to ("CAMIÓN" to 3.0f),
        "bus" to ("COLECTIVO" to 3.2f),
        "motorcycle" to ("MOTO" to 1.2f),
        "bicycle" to ("BICI" to 1.1f),
        "airplane" to ("AVIÓN" to 6.0f),
        "bird" to ("AVE" to 0.25f),
        "boat" to ("BOTE" to 2.0f),
        "dog" to ("PERRO" to 0.6f),
        "train" to ("TREN" to 3.8f),
    )

    fun display(label: String) = info[label]?.first ?: label.uppercase()
    fun realHeight(label: String) = info[label]?.second ?: 1.5f
}

/**
 * Analizador de CameraX que corre MediaPipe EfficientDet-Lite0 sobre cada cuadro
 * (solo el más reciente: si el modelo tarda, se descartan cuadros viejos).
 */
class TargetDetector(
    context: Context,
    private val orientation: OrientationTracker,
    private val onResult: (DetectionFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector: ObjectDetector? = try {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.VIDEO)
            .setMaxResults(10)
            .setScoreThreshold(0.40f)
            .setCategoryAllowlist(TargetClasses.info.keys.toList())
            .build()
        ObjectDetector.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo cargar el detector", e)
        null
    }

    val isReady get() = detector != null

    private var lastTs = 0L
    private val rotMatrix = Matrix()

    override fun analyze(image: ImageProxy) {
        val det = detector
        if (det == null) { image.close(); return }
        val m = FloatArray(9)
        orientation.copyMatrix(m)
        try {
            val rotation = image.imageInfo.rotationDegrees
            var bmp: Bitmap = image.toBitmap()
            if (rotation != 0) {
                rotMatrix.reset()
                rotMatrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rotMatrix, true)
                bmp.recycle()
                bmp = rotated
            }
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts

            val result = det.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            val list = result.detections().mapNotNull { d ->
                val c = d.categories().firstOrNull() ?: return@mapNotNull null
                RawDetection(RectF(d.boundingBox()), c.categoryName(), c.score())
            }
            onResult(DetectionFrame(list, bmp.width, bmp.height, m))
            bmp.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Fallo de detección", e)
        } finally {
            image.close()
        }
    }

    fun close() = detector?.close()

    companion object {
        private const val TAG = "TargetDetector"
        const val MODEL = "efficientdet_lite0.tflite"
    }
}
