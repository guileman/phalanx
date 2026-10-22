package com.guileman.phalanx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lee el vector de rotación (fusión giroscopio + acelerómetro + magnetómetro) y entrega
 * una matriz R tal que  mundo = R · dispositivo,  ya remapeada a la rotación de pantalla.
 *
 * Mundo:       X = Este, Y = Norte, Z = Arriba
 * Dispositivo: X = derecha de pantalla, Y = arriba de pantalla, Z = hacia el usuario
 * (la cámara trasera mira hacia -Z).
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val raw = FloatArray(9)
    private val remapped = FloatArray(9)
    private val current = FloatArray(9).also { it[0] = 1f; it[5] = -1f; it[7] = 1f } // mirando al norte, horizontal
    private val lock = Any()

    @Volatile var displayRotation: Int = Surface.ROTATION_90
    @Volatile var hasData = false
        private set

    val available get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() = sm.unregisterListener(this)

    fun copyMatrix(out: FloatArray) {
        synchronized(lock) { System.arraycopy(current, 0, out, 0, 9) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            else -> System.arraycopy(raw, 0, remapped, 0, 9)
        }
        synchronized(lock) { System.arraycopy(remapped, 0, current, 0, 9) }
        hasData = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Ángulos de actitud tipo avión derivados de la matriz R. */
data class Attitude(val azimuthDeg: Float, val elevationDeg: Float, val bankDeg: Float)

/**
 * Proyección pinhole entre direcciones del mundo y píxeles de la pantalla.
 * Toda la geometría (proyectiles, escalera de cabeceo, compensación de detecciones)
 * pasa por acá, por eso todo queda "pegado" al entorno real.
 */
class Projector {
    var width = 1f
    var height = 1f
    /** Distancia focal en píxeles de pantalla. */
    var focalPx = 1000f

    /** Zoom óptico/digital de la cámara (multiplica la focal). */
    var zoom = 1f
        set(v) { field = v; focalPx = baseFocalPx * v }
    private var baseFocalPx = 1000f

    fun setFov(viewW: Int, viewH: Int, horizontalFovRad: Double, previewAspect: Float = 16f / 9f) {
        width = viewW.toFloat()
        height = viewH.toFloat()
        // PreviewView en FILL_CENTER: el preview se escala hasta cubrir toda la vista
        val displayedPreviewWidth = maxOf(width, height * previewAspect)
        baseFocalPx = (displayedPreviewWidth / (2.0 * kotlin.math.tan(horizontalFovRad / 2.0))).toFloat()
        focalPx = baseFocalPx * zoom
    }

    /** Mundo -> pantalla. Devuelve false si el punto queda detrás de la cámara. */
    fun worldToScreen(r: FloatArray, x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val dx = r[0] * x + r[3] * y + r[6] * z
        val dy = r[1] * x + r[4] * y + r[7] * z
        val dz = r[2] * x + r[5] * y + r[8] * z
        if (dz > -0.02f * sqrt(x * x + y * y + z * z) - 0.01f) return false
        val inv = focalPx / -dz
        out[0] = width * 0.5f + dx * inv
        out[1] = height * 0.5f - dy * inv
        return true
    }

    /** Mundo -> coordenadas de cámara (x derecha, y arriba, z hacia el usuario; delante = z negativo). */
    fun worldToCamera(r: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = r[0] * x + r[3] * y + r[6] * z
        out[1] = r[1] * x + r[4] * y + r[7] * z
        out[2] = r[2] * x + r[5] * y + r[8] * z
    }

    /** Pantalla -> dirección unitaria en el mundo. */
    fun screenToWorldDir(r: FloatArray, sx: Float, sy: Float, out: FloatArray) {
        val dx = (sx - width * 0.5f) / focalPx
        val dy = -(sy - height * 0.5f) / focalPx
        val dz = -1f
        val wx = r[0] * dx + r[1] * dy + r[2] * dz
        val wy = r[3] * dx + r[4] * dy + r[5] * dz
        val wz = r[6] * dx + r[7] * dy + r[8] * dz
        val n = sqrt(wx * wx + wy * wy + wz * wz)
        out[0] = wx / n; out[1] = wy / n; out[2] = wz / n
    }

    /** Dispositivo -> mundo (para la boca del cañón). */
    fun deviceToWorld(r: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = r[0] * x + r[1] * y + r[2] * z
        out[1] = r[3] * x + r[4] * y + r[5] * z
        out[2] = r[6] * x + r[7] * y + r[8] * z
    }

    companion object {
        fun attitude(r: FloatArray): Attitude {
            val fx = -r[2]; val fy = -r[5]; val fz = -r[8]          // eje óptico
            val rightZ = r[6]
            val upZ = r[7]
            var az = Math.toDegrees(atan2(fx.toDouble(), fy.toDouble())).toFloat()
            if (az < 0) az += 360f
            val el = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble())).toFloat()
            val bank = Math.toDegrees(atan2(-rightZ.toDouble(), upZ.toDouble())).toFloat()
            return Attitude(az, el, bank)
        }

        /** Vector unitario mundo a partir de azimut/elevación en grados. */
        fun dirFromAngles(azDeg: Float, elDeg: Float, out: FloatArray) {
            val a = Math.toRadians(azDeg.toDouble())
            val e = Math.toRadians(elDeg.toDouble())
            out[0] = (cos(e) * sin(a)).toFloat()
            out[1] = (cos(e) * cos(a)).toFloat()
            out[2] = sin(e).toFloat()
        }
    }
}
