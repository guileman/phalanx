package com.guileman.phalanx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// =====================================================================
//                        MODELOS 3D LOW-POLY
// =====================================================================

/**
 * Malla en coordenadas del avión, en METROS: x = adelante, y = izquierda, z = arriba.
 * Cada polígono es plano y se dibuja de las dos caras.
 */
class Mesh {
    val polys = ArrayList<FloatArray>()
    val colors = ArrayList<Int>()

    fun poly(color: Int, pts: FloatArray) { polys += pts; colors += color }

    /** Fuselaje: secciones elípticas (x, semiancho, semialto, z del centro) unidas por cuadriláteros. */
    fun body(color: Int, vararg st: FloatArray, sides: Int = 8, y0: Float = 0f) {
        for (s in 0 until st.size - 1) {
            val a = st[s]; val b = st[s + 1]
            for (j in 0 until sides) {
                val t0 = 2 * PI * j / sides; val t1 = 2 * PI * (j + 1) / sides
                fun p(q: FloatArray, t: Double) = floatArrayOf(
                    q[0], y0 + q[1] * cos(t).toFloat(), q[3] + q[2] * sin(t).toFloat())
                val p0 = p(a, t0); val p1 = p(a, t1); val p2 = p(b, t1); val p3 = p(b, t0)
                poly(color, p0 + p1 + p2 + p3)
            }
        }
    }

    /** Ala/plano horizontal: contorno (x, y) de la mitad izquierda; se espeja. Diedro en grados. */
    fun wing(color: Int, z: Float, dihedralDeg: Float, vararg xy: Float) {
        val k = tan(Math.toRadians(dihedralDeg.toDouble())).toFloat()
        val y0 = xy[1]
        for (side in intArrayOf(1, -1)) {
            val out = FloatArray(xy.size / 2 * 3)
            for (i in 0 until xy.size / 2) {
                val x = xy[2 * i]; val y = xy[2 * i + 1]
                out[3 * i] = x; out[3 * i + 1] = side * y; out[3 * i + 2] = z + (y - y0) * k
            }
            poly(color, out)
        }
    }

    /**
     * Prisma deformado (8 vértices): base y tapa en orden
     * (x0,yIzq,zBajo) … igual que una caja: [atrás-izq-abajo, atrás-izq-arriba, atrás-der-abajo, atrás-der-arriba,
     *  adelante-izq-abajo, adelante-izq-arriba, adelante-der-abajo, adelante-der-arriba].
     */
    fun prism(color: Int, v: Array<FloatArray>) {
        val faces = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), intArrayOf(0, 4, 5, 1),
            intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3))
        for (f in faces) poly(color, v[f[0]] + v[f[1]] + v[f[2]] + v[f[3]])
    }

    /** Caja alineada: x0..x1, y ±hw (centrada en yc), z0..z1. */
    fun box(color: Int, x0: Float, x1: Float, hw: Float, z0: Float, z1: Float, yc: Float = 0f) {
        prism(color, arrayOf(
            floatArrayOf(x0, yc + hw, z0), floatArrayOf(x0, yc + hw, z1), floatArrayOf(x0, yc - hw, z0), floatArrayOf(x0, yc - hw, z1),
            floatArrayOf(x1, yc + hw, z0), floatArrayOf(x1, yc + hw, z1), floatArrayOf(x1, yc - hw, z0), floatArrayOf(x1, yc - hw, z1)))
    }

    /** Deriva vertical: contorno (x, z). [y] desplazamiento lateral, [cantDeg] inclinación hacia afuera. */
    fun fin(color: Int, y: Float, cantDeg: Float, vararg xz: Float) {
        val k = tan(Math.toRadians(cantDeg.toDouble())).toFloat()
        val z0 = xz[1]
        val sides = if (y == 0f) intArrayOf(1) else intArrayOf(1, -1)
        for (side in sides) {
            val out = FloatArray(xz.size / 2 * 3)
            for (i in 0 until xz.size / 2) {
                val x = xz[2 * i]; val z = xz[2 * i + 1]
                out[3 * i] = x; out[3 * i + 1] = side * (y + (z - z0) * k); out[3 * i + 2] = z
            }
            poly(color, out)
        }
    }
}

private fun st(x: Float, hw: Float, hh: Float, z: Float = 0f) = floatArrayOf(x, hw, hh, z)

class AircraftModel(
    val name: String,
    val length: Float,
    val span: Float,
    val speed: Float,          // m/s
    val hp: Int,               // impactos para derribarlo
    val minRange: Float,
    val maxRange: Float,
    val mesh: Mesh,
    /** Vehículo terrestre (carro de combate). */
    val ground: Boolean = false,
) {
    val hitRadius = max(length, span) * 0.42f
}

object AircraftModels {
    private val glass = Color.rgb(40, 52, 62)
    private val dark = Color.rgb(48, 50, 52)

    private fun f16(): AircraftModel {
        val c = Color.rgb(140, 148, 156)
        val m = Mesh()
        m.body(c, st(7.6f, 0f, 0f, 0.05f), st(6.4f, 0.32f, 0.32f, 0.05f), st(4.5f, 0.6f, 0.62f, 0.1f),
            st(2.0f, 0.78f, 0.75f, 0.1f), st(-1.0f, 0.85f, 0.72f, 0f), st(-4.5f, 0.82f, 0.68f, 0f),
            st(-6.6f, 0.6f, 0.58f, 0f), st(-7.5f, 0.48f, 0.48f, 0f))
        m.body(glass, st(4.6f, 0f, 0f, 0.62f), st(3.6f, 0.34f, 0.32f, 0.62f), st(2.0f, 0.36f, 0.3f, 0.62f), st(0.8f, 0f, 0f, 0.62f), sides = 6)
        m.body(dark, st(3.2f, 0.5f, 0.35f, -0.55f), st(0.0f, 0.55f, 0.4f, -0.5f), sides = 6) // toma de aire ventral
        m.wing(c, 0f, 0f, 4.6f, 0.7f, 1.6f, 1.4f, -2.2f, 4.7f, -3.6f, 4.7f, -3.6f, 0.8f)
        m.wing(c, -0.1f, -8f, -5.2f, 0.7f, -7.2f, 3.0f, -8.1f, 3.0f, -7.9f, 0.7f)
        m.fin(c, 0f, 0f, -4.6f, 0.75f, -6.9f, 3.6f, -7.7f, 3.6f, -7.9f, 0.75f)
        return AircraftModel("F-16", 15f, 9.5f, 240f, 4, 450f, 1300f, m)
    }

    private fun mirage(): AircraftModel {
        val c = Color.rgb(150, 136, 104)
        val m = Mesh()
        m.body(c, st(7.6f, 0f, 0f), st(6.2f, 0.25f, 0.25f), st(4.0f, 0.55f, 0.6f, 0.05f), st(1.0f, 0.75f, 0.75f),
            st(-3.0f, 0.75f, 0.72f), st(-6.0f, 0.6f, 0.6f), st(-7.4f, 0.5f, 0.5f))
        m.body(glass, st(4.3f, 0f, 0f, 0.55f), st(3.4f, 0.33f, 0.3f, 0.55f), st(1.8f, 0.3f, 0.25f, 0.55f), st(0.8f, 0f, 0f, 0.55f), sides = 6)
        m.body(dark, st(3.0f, 0.38f, 0.45f, 0f), st(-1.5f, 0.42f, 0.45f, 0f), sides = 6, y0 = 0.85f)
        m.body(dark, st(3.0f, 0.38f, 0.45f, 0f), st(-1.5f, 0.42f, 0.45f, 0f), sides = 6, y0 = -0.85f)
        m.wing(c, -0.2f, -1f, 2.8f, 0.8f, -2.9f, 4.1f, -3.6f, 4.1f, -4.3f, 0.8f)
        m.fin(c, 0f, 0f, -2.6f, 0.7f, -5.6f, 3.5f, -6.4f, 3.5f, -6.9f, 0.7f)
        return AircraftModel("MIRAGE III", 15f, 8.2f, 230f, 4, 450f, 1300f, m)
    }

    private fun skyhawk(): AircraftModel {
        val c = Color.rgb(112, 122, 104)
        val m = Mesh()
        m.body(c, st(6.1f, 0f, 0f, 0.1f), st(5.0f, 0.35f, 0.42f, 0.1f), st(3.0f, 0.62f, 0.75f, 0.1f),
            st(0.0f, 0.72f, 0.8f, 0f), st(-3.0f, 0.6f, 0.65f, 0.1f), st(-5.0f, 0.35f, 0.45f, 0.2f), st(-6.1f, 0.25f, 0.3f, 0.25f))
        m.body(glass, st(4.0f, 0f, 0f, 0.75f), st(3.2f, 0.3f, 0.3f, 0.75f), st(1.6f, 0.3f, 0.25f, 0.75f), st(0.6f, 0f, 0f, 0.75f), sides = 6)
        m.body(dark, st(2.4f, 0.3f, 0.4f, 0.1f), st(-0.8f, 0.32f, 0.4f, 0.1f), sides = 6, y0 = 0.75f)
        m.body(dark, st(2.4f, 0.3f, 0.4f, 0.1f), st(-0.8f, 0.32f, 0.4f, 0.1f), sides = 6, y0 = -0.75f)
        m.wing(c, -0.45f, 2f, 1.8f, 0.7f, -1.1f, 4.2f, -2.0f, 4.2f, -3.0f, 0.7f)
        m.fin(c, 0f, 0f, -2.8f, 0.6f, -4.9f, 2.9f, -5.6f, 2.9f, -6.0f, 0.4f)
        m.wing(c, 1.3f, 0f, -4.5f, 0.2f, -5.4f, 2.2f, -6.0f, 2.2f, -5.8f, 0.2f)
        return AircraftModel("A-4 SKYHAWK", 12.2f, 8.4f, 200f, 4, 400f, 1200f, m)
    }

    private fun mig29(): AircraftModel {
        val c = Color.rgb(122, 138, 150)
        val m = Mesh()
        m.body(c, st(8.6f, 0f, 0f, 0.1f), st(7.2f, 0.32f, 0.34f, 0.1f), st(5.0f, 0.58f, 0.6f, 0.2f),
            st(3.0f, 0.7f, 0.58f, 0.2f), st(0f, 0.8f, 0.45f, 0.1f), st(-4f, 0.6f, 0.4f, 0.1f), st(-7f, 0.35f, 0.3f, 0.1f))
        m.body(glass, st(5.2f, 0f, 0f, 0.72f), st(4.2f, 0.35f, 0.32f, 0.72f), st(2.6f, 0.35f, 0.25f, 0.72f), st(1.4f, 0f, 0f, 0.72f), sides = 6)
        for (y in floatArrayOf(1.15f, -1.15f)) {
            m.body(dark, st(3.4f, 0.45f, 0.5f, -0.45f), st(0f, 0.55f, 0.55f, -0.4f), st(-6.5f, 0.55f, 0.5f, -0.2f),
                st(-8.2f, 0.45f, 0.42f, -0.15f), sides = 6, y0 = y)
        }
        m.wing(c, 0f, -2f, 4.6f, 0.9f, 1.0f, 2.0f, -3.0f, 5.7f, -4.6f, 5.7f, -4.4f, 1.6f)
        m.wing(c, -0.1f, 0f, -6.0f, 1.6f, -8.0f, 3.9f, -8.9f, 3.9f, -8.6f, 1.6f)
        m.fin(c, 1.5f, 8f, -4.6f, 0.4f, -6.8f, 3.3f, -7.8f, 3.3f, -8.2f, 0.4f)
        return AircraftModel("MiG-29", 17.3f, 11.4f, 250f, 5, 500f, 1400f, m)
    }

    private fun c130(): AircraftModel {
        val c = Color.rgb(108, 116, 104)
        val m = Mesh()
        m.body(c, st(14.9f, 0f, 0f, 0.2f), st(14.2f, 1.1f, 1.1f, 0.3f), st(12.5f, 1.9f, 1.95f, 0.4f),
            st(8f, 2.2f, 2.2f, 0.5f), st(-4f, 2.2f, 2.2f, 0.5f), st(-9f, 1.6f, 1.4f, 1.1f),
            st(-13.3f, 0.75f, 0.6f, 1.8f), st(-14.9f, 0.4f, 0.3f, 2.0f), sides = 10)
        m.body(glass, st(14.0f, 0f, 0f, 1.4f), st(13.2f, 1.0f, 0.5f, 1.4f), st(12.0f, 1.1f, 0.5f, 1.6f), st(11.5f, 0f, 0f, 1.7f), sides = 6)
        m.wing(c, 2.75f, 1.5f, 3.1f, 2.0f, 1.4f, 20.2f, -0.9f, 20.2f, -1.9f, 2.0f)
        for (y in floatArrayOf(5.2f, -5.2f, 10.0f, -10.0f)) {
            m.body(dark, st(6.0f, 0f, 0f, 2.5f), st(5.4f, 0.6f, 0.7f, 2.4f), st(2.0f, 0.75f, 0.9f, 2.3f), st(-1.5f, 0.4f, 0.5f, 2.5f), sides = 6, y0 = y)
            m.poly(Color.argb(150, 60, 60, 60), floatArrayOf(6.1f, y, 4.2f, 6.1f, y - 1.9f, 2.5f, 6.1f, y, 0.6f, 6.1f, y + 1.9f, 2.5f))
        }
        m.wing(c, 2.2f, 0f, -10.5f, 1.0f, -12.2f, 7.8f, -14.0f, 7.8f, -14.8f, 1.0f)
        m.fin(c, 0f, 0f, -9.0f, 2.2f, -12.8f, 9.3f, -14.5f, 9.3f, -14.9f, 2.0f)
        return AircraftModel("C-130 HÉRCULES", 29.8f, 40.4f, 140f, 10, 800f, 1900f, m)
    }

    val all: List<AircraftModel> by lazy { listOf(f16(), mirage(), skyhawk(), mig29(), c130()) }

    // ----------------------------- carros de combate -----------------------------
    // Coordenadas: x adelante, y izquierda, z arriba; z = 0 es el suelo.
    private val track = Color.rgb(42, 42, 40)

    /** Casco con frente inclinado + orugas. */
    private fun hull(m: Mesh, c: Int, L: Float, W: Float, H: Float, glacis: Float = 1.6f) {
        val hw = W / 2
        for (side in floatArrayOf(1f, -1f)) {
            m.box(track, -L / 2, L / 2 - 0.3f, 0.32f, 0f, 0.95f, yc = side * (hw - 0.32f))
            m.box(c, -L / 2 + 0.1f, L / 2 - 0.4f, 0.34f, 0.95f, 1.05f, yc = side * (hw - 0.3f))   // guardabarros
        }
        val y = hw - 0.62f
        m.prism(c, arrayOf(
            floatArrayOf(-L / 2, y, 0.45f), floatArrayOf(-L / 2, y, H), floatArrayOf(-L / 2, -y, 0.45f), floatArrayOf(-L / 2, -y, H),
            floatArrayOf(L / 2, y, 0.55f), floatArrayOf(L / 2 - glacis, y, H), floatArrayOf(L / 2, -y, 0.55f), floatArrayOf(L / 2 - glacis, -y, H)))
        m.box(c, -L / 2 + 0.2f, L / 2 - glacis, hw - 0.05f, H - 0.35f, H)                         // cubierta sobre las orugas
    }

    private fun gun(m: Mesh, c: Int, x0: Float, x1: Float, z: Float, r: Float) {
        m.body(c, st(x0, r * 1.5f, r * 1.5f, z), st(x0 + 0.6f, r, r, z), st(x1 - 0.5f, r, r, z),
            st(x1 - 0.45f, r * 1.6f, r * 1.6f, z), st(x1 - 0.1f, r * 1.6f, r * 1.6f, z), st(x1, r, r, z), sides = 6)
    }

    private fun t72(): AircraftModel {
        val c = Color.rgb(96, 104, 70)
        val m = Mesh()
        hull(m, c, 6.9f, 3.6f, 1.55f)
        // torreta en forma de cúpula
        m.body(c, st(1.5f, 0f, 0f, 1.6f), st(1.0f, 1.0f, 0.45f, 1.65f), st(0f, 1.25f, 0.55f, 1.65f),
            st(-1.2f, 1.15f, 0.48f, 1.62f), st(-1.7f, 0f, 0f, 1.6f), sides = 10)
        gun(m, Color.rgb(78, 84, 58), 1.2f, 7.4f, 1.85f, 0.09f)
        return AircraftModel("T-72", 9.5f, 3.6f, 12f, 12, 200f, 1200f, m, ground = true)
    }

    private fun abrams(): AircraftModel {
        val c = Color.rgb(176, 162, 122)
        val m = Mesh()
        hull(m, c, 7.9f, 3.7f, 1.65f, glacis = 1.9f)
        // torreta angular, baja y ancha
        m.prism(c, arrayOf(
            floatArrayOf(-3.2f, 1.8f, 1.65f), floatArrayOf(-3.2f, 1.7f, 2.4f), floatArrayOf(-3.2f, -1.8f, 1.65f), floatArrayOf(-3.2f, -1.7f, 2.4f),
            floatArrayOf(2.2f, 1.5f, 1.65f), floatArrayOf(1.4f, 1.3f, 2.4f), floatArrayOf(2.2f, -1.5f, 1.65f), floatArrayOf(1.4f, -1.3f, 2.4f)))
        gun(m, Color.rgb(140, 128, 96), 1.9f, 7.6f, 2.05f, 0.1f)
        return AircraftModel("M1 ABRAMS", 9.8f, 3.7f, 13f, 12, 200f, 1200f, m, ground = true)
    }

    private fun leopard2(): AircraftModel {
        val c = Color.rgb(84, 100, 74)
        val m = Mesh()
        hull(m, c, 7.7f, 3.7f, 1.6f)
        // torreta con frente en cuña
        m.prism(c, arrayOf(
            floatArrayOf(-2.6f, 1.75f, 1.6f), floatArrayOf(-2.6f, 1.7f, 2.45f), floatArrayOf(-2.6f, -1.75f, 1.6f), floatArrayOf(-2.6f, -1.7f, 2.45f),
            floatArrayOf(2.4f, 0.6f, 1.6f), floatArrayOf(2.2f, 0.55f, 2.45f), floatArrayOf(2.4f, -0.6f, 1.6f), floatArrayOf(2.2f, -0.55f, 2.45f)))
        gun(m, Color.rgb(66, 78, 58), 2.2f, 8.6f, 2.05f, 0.1f)
        return AircraftModel("LEOPARD 2", 10.9f, 3.7f, 13f, 12, 200f, 1200f, m, ground = true)
    }

    private fun tam(): AircraftModel {
        val c = Color.rgb(126, 116, 82)
        val m = Mesh()
        hull(m, c, 6.8f, 3.1f, 1.75f, glacis = 1.4f)
        // torreta en la parte trasera (chasis tipo Marder)
        m.prism(c, arrayOf(
            floatArrayOf(-2.8f, 1.3f, 1.75f), floatArrayOf(-2.8f, 1.25f, 2.5f), floatArrayOf(-2.8f, -1.3f, 1.75f), floatArrayOf(-2.8f, -1.25f, 2.5f),
            floatArrayOf(0.4f, 1.2f, 1.75f), floatArrayOf(-0.2f, 1.0f, 2.5f), floatArrayOf(0.4f, -1.2f, 1.75f), floatArrayOf(-0.2f, -1.0f, 2.5f)))
        gun(m, Color.rgb(98, 90, 64), 0.2f, 5.6f, 2.15f, 0.08f)
        return AircraftModel("TAM", 8.2f, 3.1f, 12f, 12, 200f, 1100f, m, ground = true)
    }

    val tanks: List<AircraftModel> by lazy { listOf(tam(), t72(), abrams(), leopard2()) }
}

// =====================================================================
//                         AVIÓN EN VUELO
// =====================================================================

class Aircraft(val id: Int, val model: AircraftModel, var x: Float, var y: Float, var z: Float,
               var vx: Float, var vy: Float, var vz: Float) {
    var hp = model.hp
    var down = false
    var age = 0f
    var downAge = 0f
    var roll = 0f
    var rollRate = 0f
    var smokeTimer = 0f
    var fireTimer = 0f
    var lastHitSound = 0L
    var secondaryLeft = 0
    var secondaryTimer = 0f
    var dead = false
    /** Pasada de ataque: viene directo hacia el Phalanx. */
    var attack = false
    /** Distancia mínima registrada (para saber si ya pasó). */
    var closest = Float.MAX_VALUE

    // orientación (base ortonormal en el mundo)
    val fwd = FloatArray(3); val left = FloatArray(3); val up = FloatArray(3)

    // proyección en pantalla
    val screenRect = RectF()
    var onScreen = false
    val range get() = sqrt(x * x + y * y + z * z)
    val speed get() = sqrt(vx * vx + vy * vy + vz * vz)

    fun updateBasis() {
        val hs = sqrt(vx * vx + vy * vy)
        if (hs < 1e-3f && abs(vz) < 1e-3f) return   // detenido: conserva la orientación
        val psi = atan2(vx, vy)              // rumbo (desde el norte, horario)
        val theta = atan2(vz, hs)            // cabeceo
        val cp = cos(psi); val sp = sin(psi); val ct = cos(theta); val stt = sin(theta)
        fwd[0] = sp * ct; fwd[1] = cp * ct; fwd[2] = stt
        // derecha sin rolido = (cos ψ, -sin ψ, 0)
        val rx = cp; val ry = -sp; val rz = 0f
        // arriba = derecha × adelante
        val ux = ry * fwd[2] - rz * fwd[1]
        val uy = rz * fwd[0] - rx * fwd[2]
        val uz = rx * fwd[1] - ry * fwd[0]
        // izquierda = -derecha; aplicar rolido
        val lx = -rx; val ly = -ry; val lz = -rz
        val cr = cos(roll); val sr = sin(roll)
        left[0] = lx * cr + ux * sr; left[1] = ly * cr + uy * sr; left[2] = lz * cr + uz * sr
        up[0] = ux * cr - lx * sr; up[1] = uy * cr - ly * sr; up[2] = uz * cr - lz * sr
    }

    /** Punto del avión (coordenadas de cuerpo) en el mundo. */
    fun toWorld(bx: Float, by: Float, bz: Float, out: FloatArray) {
        out[0] = x + fwd[0] * bx + left[0] * by + up[0] * bz
        out[1] = y + fwd[1] * bx + left[1] * by + up[1] * bz
        out[2] = z + fwd[2] * bx + left[2] * by + up[2] * bz
    }
}

// =====================================================================
//                      GESTOR: APARICIÓN, FÍSICA, DIBUJO
// =====================================================================

class AircraftManager {

    companion object {
        const val SPAWN_INTERVAL = 10f
        const val TANK_INTERVAL = 12f
        /** Altura del suelo respecto del celular (m). */
        const val GROUND_Z = -1.5f
        const val MAX_ALIVE = 4
        /** Probabilidad de que un contacto sea una pasada de ataque directa. */
        const val ATTACK_CHANCE = 0.45f
    }

    var enabled = false
        set(v) {
            if (v && !field) spawnTimer = 2f   // el primero aparece enseguida
            field = v
        }
    var spawnTimer = 2f
        private set
    var kills = 0
        private set

    /** Carros de combate activados (botón CARROS). */
    var tanksEnabled = false
        set(v) {
            if (v && !field) tankTimer = 2f
            field = v
        }
    var tankTimer = 2f
        private set
    var tankKills = 0
        private set
    /** Distancias y movimiento de los carros (el obús usa blancos lejanos y casi quietos). */
    var tankMinRange = -1f
    var tankMaxRange = -1f
    var tankSpeedScale = 1f
    var tankSpreadDeg = 30f

    /** Último mensaje de contacto ("CONTACTO AÉREO: F-16", "CONTACTO TERRESTRE: TAM"). */
    var lastContact: String? = null
        private set
    var lastContactTime = 0f
        private set

    /** Se llama al derribar un avión (para sonido/contador). */
    var onShotDown: ((Aircraft) -> Unit)? = null

    val list = ArrayList<Aircraft>()
    private var nextId = 1
    private val rnd = Random(System.nanoTime())
    private var clock = 0f
    private val tmp = FloatArray(3)

    fun update(dt: Float, camAzDeg: Float, camElDeg: Float, effects: Effects, sound: PhalanxSound?) {
        clock += dt
        if (enabled) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnTimer = SPAWN_INTERVAL
                if (list.count { !it.down && !it.model.ground } < MAX_ALIVE) spawn(camAzDeg, camElDeg)
            }
        }
        if (tanksEnabled) {
            tankTimer -= dt
            if (tankTimer <= 0f) {
                tankTimer = TANK_INTERVAL
                if (list.count { !it.down && it.model.ground } < MAX_ALIVE) spawnTank(camAzDeg)
            }
        }

        val it = list.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.age += dt
            if (a.down && a.model.ground) {
                // carro destruido: queda ardiendo en el lugar
                a.downAge += dt
                a.smokeTimer -= dt
                if (a.smokeTimer <= 0f) {
                    a.smokeTimer = 0.08f
                    a.toWorld((rnd.nextFloat() - 0.5f) * 2f, (rnd.nextFloat() - 0.5f) * 1.5f, 2.2f, tmp)
                    effects.smoke(tmp[0], tmp[1], tmp[2], 1.6f, 6f, 0.85f)
                }
                a.fireTimer -= dt
                if (a.fireTimer <= 0f && a.downAge < 12f) {
                    a.fireTimer = 0.14f
                    a.toWorld((rnd.nextFloat() - 0.5f) * 3f, (rnd.nextFloat() - 0.5f) * 2f, 2f, tmp)
                    effects.explode(tmp[0], tmp[1], tmp[2], 0.9f, 0.5f)
                }
                if (a.secondaryLeft > 0) {
                    a.secondaryTimer -= dt
                    if (a.secondaryTimer <= 0f) {
                        // explota la munición del carro
                        a.secondaryLeft--
                        a.secondaryTimer = 0.25f + rnd.nextFloat() * 0.5f
                        a.toWorld((rnd.nextFloat() - 0.5f) * 3f, (rnd.nextFloat() - 0.5f) * 2f, 2.2f, tmp)
                        effects.explode(tmp[0], tmp[1], tmp[2], 3f + rnd.nextFloat() * 2f)
                        sound?.explosion(a.range, 0.5f)
                    }
                }
            } else if (a.down) {
                a.downAge += dt
                // cae: gravedad, pierde velocidad y gira sobre su eje
                val drag = exp(-0.25f * dt)
                a.vx *= drag; a.vy *= drag
                a.vz = a.vz * exp(-0.05f * dt) - 9.81f * 1.4f * dt
                a.roll += a.rollRate * dt
                a.smokeTimer -= dt
                if (a.smokeTimer <= 0f) {
                    a.smokeTimer = 0.045f
                    a.toWorld(-a.model.length * 0.45f, 0f, 0f, tmp)
                    effects.smoke(tmp[0], tmp[1], tmp[2], a.model.span * 0.28f, 4.5f, 0.9f)
                }
                a.fireTimer -= dt
                if (a.fireTimer <= 0f) {
                    a.fireTimer = 0.09f
                    a.toWorld(-a.model.length * (0.2f + rnd.nextFloat() * 0.3f), (rnd.nextFloat() - 0.5f) * a.model.span * 0.5f, 0f, tmp)
                    effects.explode(tmp[0], tmp[1], tmp[2], a.model.span * 0.18f, 0.5f)
                }
                if (a.secondaryLeft > 0) {
                    a.secondaryTimer -= dt
                    if (a.secondaryTimer <= 0f) {
                        a.secondaryLeft--
                        a.secondaryTimer = 0.12f + rnd.nextFloat() * 0.2f
                        a.toWorld((rnd.nextFloat() - 0.5f) * a.model.length, (rnd.nextFloat() - 0.5f) * a.model.span, 0f, tmp)
                        effects.explode(tmp[0], tmp[1], tmp[2], a.model.span * (0.4f + rnd.nextFloat() * 0.3f))
                        sound?.explosion(a.range, 0.6f)
                    }
                }
            } else if (a.hp < a.model.hp) {
                // dañado: deja una estela de humo
                a.smokeTimer -= dt
                if (a.smokeTimer <= 0f) {
                    a.smokeTimer = 0.1f
                    if (a.model.ground) a.toWorld(-1.5f, 0f, 2f, tmp) else a.toWorld(-a.model.length * 0.5f, 0f, 0f, tmp)
                    effects.smoke(tmp[0], tmp[1], tmp[2], if (a.model.ground) 0.8f else a.model.span * 0.12f, 2.5f, 0.6f)
                }
            }
            if (!a.down && a.attack) attackManeuver(a, dt)
            a.x += a.vx * dt; a.y += a.vy * dt; a.z += a.vz * dt
            a.closest = min(a.closest, a.range)
            a.updateBasis()

            if (a.down && !a.model.ground && a.z < 0f) {
                // llega al suelo
                effects.explode(a.x, a.y, 0f, a.model.span * 1.2f, 1.3f)
                for (k in 0 until 6) effects.smoke(a.x + (rnd.nextFloat() - 0.5f) * 20f, a.y + (rnd.nextFloat() - 0.5f) * 20f,
                    rnd.nextFloat() * 10f, a.model.span * 0.6f, 6f)
                sound?.explosion(a.range, 1f)
                it.remove()
            } else if (a.range > 5000f || a.age > (if (a.model.ground) 150f else 60f) || (a.down && a.downAge > 25f)) {
                it.remove()
            }
        }
    }

    /** Hay algún avión acercándose en pasada de ataque (para la alarma del HUD). */
    fun incomingThreat(): Aircraft? = list.filter { it.attack && !it.down && it.closest >= it.range - 1f }
        .minByOrNull { it.range }

    /**
     * Pasada de ataque: el avión baja en picada hacia un punto a pocos metros del Phalanx;
     * no baja de ~70 m y, una vez que pasó por encima, tira de la palanca y se va trepando.
     */
    private fun attackManeuver(a: Aircraft, dt: Float) {
        val spd = a.speed
        val passed = a.x * a.vx + a.y * a.vy + a.z * a.vz > 0f   // ya se está alejando
        val tooLow = a.z < 70f && a.vz < 0f
        if (passed || tooLow) {
            val targetVz = spd * 0.35f
            if (a.vz < targetVz) {
                a.vz = min(targetVz, a.vz + 9.81f * 3.5f * dt)   // ~3.5 g de tirón
                val hs = sqrt(a.vx * a.vx + a.vy * a.vy)
                val newHs = sqrt(max(1f, spd * spd - a.vz * a.vz))
                if (hs > 1e-3f) { a.vx *= newHs / hs; a.vy *= newHs / hs }
            }
        }
    }

    private fun spawn(camAz: Float, camEl: Float) {
        if (rnd.nextFloat() < ATTACK_CHANCE) { spawnAttack(camAz, camEl); return }
        val model = AircraftModels.all[rnd.nextInt(AircraftModels.all.size)]
        val range = model.minRange + rnd.nextFloat() * (model.maxRange - model.minRange)
        val side = if (rnd.nextBoolean()) 1f else -1f
        val az = camAz + side * (26f + rnd.nextFloat() * 14f)
        val el = (camEl + rnd.nextFloat() * 16f - 6f).coerceIn(8f, 55f)
        Projector.dirFromAngles(az, el, tmp)
        val x = tmp[0] * range; val y = tmp[1] * range; val z = tmp[2] * range

        // rumbo: cruza la línea de visión hacia el otro lado, con algo de acercamiento
        val azr = Math.toRadians(az.toDouble())
        val rightX = cos(azr).toFloat(); val rightY = -sin(azr).toFloat()
        val inX = -sin(azr).toFloat(); val inY = -cos(azr).toFloat()
        val alpha = Math.toRadians((-15.0 + rnd.nextDouble() * 50.0))
        val ca = cos(alpha).toFloat(); val sa = sin(alpha).toFloat()
        val dx = -side * rightX * ca + inX * sa
        val dy = -side * rightY * ca + inY * sa
        val spd = model.speed * (0.85f + rnd.nextFloat() * 0.25f)
        val climb = (rnd.nextFloat() - 0.5f) * 0.08f
        val a = Aircraft(nextId++, model, x, y, z, dx * spd, dy * spd, climb * spd)
        a.updateBasis()
        list += a
        lastContact = "CONTACTO AÉREO: " + model.name
        lastContactTime = clock
    }

    /** Contacto que viene directo hacia el jugador, desde adelante y arriba, en picada. */
    private fun spawnAttack(camAz: Float, camEl: Float) {
        val model = AircraftModels.all[rnd.nextInt(AircraftModels.all.size)]
        val range = 2000f + rnd.nextFloat() * 1200f
        val az = camAz + (rnd.nextFloat() - 0.5f) * 30f
        val el = (camEl + rnd.nextFloat() * 10f - 4f).coerceIn(11f, 40f)
        Projector.dirFromAngles(az, el, tmp)
        val x = tmp[0] * range; val y = tmp[1] * range; val z = tmp[2] * range

        // punto de paso: muy cerca del Phalanx (de costado y por arriba)
        val azr = Math.toRadians(az.toDouble())
        val rightX = cos(azr).toFloat(); val rightY = -sin(azr).toFloat()
        val lateral = (if (rnd.nextBoolean()) 1f else -1f) * (15f + rnd.nextFloat() * 90f)
        val passAlt = 70f + rnd.nextFloat() * 130f
        var dx = rightX * lateral - x; var dy = rightY * lateral - y; var dz = passAlt - z
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= n; dy /= n; dz /= n
        val spd = model.speed * (1.0f + rnd.nextFloat() * 0.2f)
        val a = Aircraft(nextId++, model, x, y, z, dx * spd, dy * spd, dz * spd)
        a.attack = true
        a.updateBasis()
        list += a
        lastContact = "CONTACTO AÉREO: " + model.name + " — ATAQUE"
        lastContactTime = clock
    }

    /** Carro de combate: aparece sobre el suelo, cruzando o acercándose al jugador. */
    private fun spawnTank(camAz: Float) {
        val model = AircraftModels.tanks[rnd.nextInt(AircraftModels.tanks.size)]
        val lo = if (tankMinRange > 0f) tankMinRange else model.minRange
        val hi = if (tankMaxRange > 0f) tankMaxRange else model.maxRange
        val range = lo + rnd.nextFloat() * (hi - lo)
        val side = if (rnd.nextBoolean()) 1f else -1f
        val az = camAz + side * (4f + rnd.nextFloat() * tankSpreadDeg)
        val azr = Math.toRadians(az.toDouble())
        val x = (sin(azr) * range).toFloat(); val y = (cos(azr) * range).toFloat()
        val spd = model.speed * (0.7f + rnd.nextFloat() * 0.4f) * tankSpeedScale
        var dx: Float; var dy: Float
        if (rnd.nextBoolean()) {
            // avanza hacia el jugador (pasa a un costado)
            val rightX = cos(azr).toFloat(); val rightY = -sin(azr).toFloat()
            val lateral = (if (rnd.nextBoolean()) 1f else -1f) * (20f + rnd.nextFloat() * 120f)
            dx = rightX * lateral - x; dy = rightY * lateral - y
        } else {
            // cruza de costado
            dx = -side * cos(azr).toFloat(); dy = side * sin(azr).toFloat()
        }
        val n = sqrt(dx * dx + dy * dy)
        dx /= n; dy /= n
        // velocidad 0 conserva el rumbo inicial gracias a un empujón mínimo
        val v = max(spd, 0.002f)
        val a = Aircraft(nextId++, model, x, y, GROUND_Z, dx * v, dy * v, 0f)
        a.updateBasis()
        list += a
        lastContact = "CONTACTO TERRESTRE: " + model.name
        lastContactTime = clock
    }

    fun contactMessageAge() = clock - lastContactTime

    /**
     * Impactos de proyectiles: segmento recorrido por la bala en este paso, relativo al avión.
     * @return cantidad de impactos
     */
    fun checkHits(b: Ballistics, dt: Float, effects: Effects, sound: PhalanxSound?, nowNanos: Long, damage: Int = 1): Int {
        if (list.isEmpty()) return 0
        var hits = 0
        for (a in list) {
            if (a.down) continue
            val rr = a.model.hitRadius
            val ax0 = a.x - a.vx * dt; val ay0 = a.y - a.vy * dt; val az0 = a.z - a.vz * dt
            val reach = a.range
            for (i in 0 until b.cap) {
                if (!b.alive[i]) continue
                if (b.dist[i] < reach - 60f || b.prevDist[i] > reach + 60f) continue
                val q0x = b.px[i] - ax0; val q0y = b.py[i] - ay0; val q0z = b.pz[i] - az0
                val q1x = b.x[i] - a.x; val q1y = b.y[i] - a.y; val q1z = b.z[i] - a.z
                val dx = q1x - q0x; val dy = q1y - q0y; val dz = q1z - q0z
                val l2 = dx * dx + dy * dy + dz * dz
                val t = if (l2 > 1e-6f) (-(q0x * dx + q0y * dy + q0z * dz) / l2).coerceIn(0f, 1f) else 0f
                val cx = q0x + dx * t; val cy = q0y + dy * t; val cz = q0z + dz * t
                if (cx * cx + cy * cy + cz * cz < rr * rr) {
                    val k = b.kind[i]
                    val dmg = if (b.dmg[i] > 0) b.dmg[i] else damage
                    b.kill(i)
                    hits++
                    a.hp -= dmg
                    if (Ballistics.heavy(k)) {
                        // granada de tanque / obús: el HUD procesa la explosión (y el daño en área)
                        b.addImpact(a.x + cx * 0.5f, a.y + cy * 0.5f, a.z + cz * 0.5f, k)
                    } else {
                        effects.explode(a.x + cx * 0.5f, a.y + cy * 0.5f, a.z + cz * 0.5f, (1.6f + rnd.nextFloat()) * (if (dmg > 1) 1.6f else 1f), 0.45f)
                    }
                    if (nowNanos - a.lastHitSound > 120_000_000L) {
                        a.lastHitSound = nowNanos
                        sound?.explosion(a.range, 0.15f)
                    }
                    if (a.hp <= 0) {
                        shootDown(a, effects, sound)
                        break
                    }
                }
            }
        }
        return hits
    }

    /** Daño por fragmentos o misil. @return true si lo derribó. */
    fun damage(a: Aircraft, amount: Int, effects: Effects, sound: PhalanxSound?): Boolean {
        if (a.down) return false
        a.hp -= amount
        if (a.hp <= 0) { shootDown(a, effects, sound); return true }
        return false
    }

    private fun shootDown(a: Aircraft, effects: Effects, sound: PhalanxSound?) {
        a.down = true
        if (a.model.ground) {
            a.vx = 0f; a.vy = 0f; a.vz = 0f
            a.secondaryLeft = 2 + rnd.nextInt(3)
            a.secondaryTimer = 0.4f
            a.toWorld(0f, 0f, 1.8f, tmp)
            effects.explode(tmp[0], tmp[1], tmp[2], 6f, 1.3f)
            effects.explode(tmp[0], tmp[1], tmp[2] + 3f, 4f, 1.1f)
            sound?.explosion(a.range, 1f)
            tankKills++
            onShotDown?.invoke(a)
            return
        }
        a.rollRate = (if (rnd.nextBoolean()) 1f else -1f) * (1.5f + rnd.nextFloat() * 3f)
        a.vz += 5f + rnd.nextFloat() * 10f
        a.secondaryLeft = 3 + rnd.nextInt(3)
        a.secondaryTimer = 0.15f
        effects.explode(a.x, a.y, a.z, max(a.model.span * 0.9f, 9f), 1.2f)
        sound?.explosion(a.range, 1f)
        kills++
        onShotDown?.invoke(a)
    }

    // ------------------------------- dibujo -------------------------------

    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val sun = floatArrayOf(0.35f, 0.42f, 0.84f)
    private var bufWorld = FloatArray(0)
    private var bufScreen = FloatArray(0)
    private var depth = FloatArray(0)
    private var order = IntArray(0)
    private val p = FloatArray(2)

    /**
     * @param updateScreen guarda el recuadro en pantalla de cada avión (false para vistas secundarias, p. ej. el dron)
     */
    fun draw(c: Canvas, projector: Projector, r: FloatArray, updateScreen: Boolean = true) {
        // los más lejanos primero (respecto de la cámara que dibuja)
        val ox = projector.ox; val oy = projector.oy; val oz = projector.oz
        list.sortByDescending { (it.x - ox) * (it.x - ox) + (it.y - oy) * (it.y - oy) + (it.z - oz) * (it.z - oz) }
        for (a in list) drawOne(c, projector, r, a, updateScreen)
    }

    private fun drawOne(c: Canvas, projector: Projector, r: FloatArray, a: Aircraft, updateScreen: Boolean) {
        val mesh = a.model.mesh
        val n = mesh.polys.size
        if (depth.size < n) { depth = FloatArray(n); order = IntArray(n) }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var anyVisible = false

        // profundidad de cada polígono (distancia del centro a la cámara)
        for (k in 0 until n) {
            val pts = mesh.polys[k]
            var sx = 0f; var sy = 0f; var sz = 0f
            val m = pts.size / 3
            for (i in 0 until m) { sx += pts[3 * i]; sy += pts[3 * i + 1]; sz += pts[3 * i + 2] }
            a.toWorld(sx / m, sy / m, sz / m, tmp)
            val ddx = tmp[0] - projector.ox; val ddy = tmp[1] - projector.oy; val ddz = tmp[2] - projector.oz
            depth[k] = ddx * ddx + ddy * ddy + ddz * ddz
            order[k] = k
        }
        // ordenamiento por inserción (pocos polígonos)
        for (i in 1 until n) {
            val o = order[i]; val d = depth[o]
            var j = i - 1
            while (j >= 0 && depth[order[j]] < d) { order[j + 1] = order[j]; j-- }
            order[j + 1] = o
        }

        val dist = sqrt((a.x - projector.ox) * (a.x - projector.ox) + (a.y - projector.oy) * (a.y - projector.oy) + (a.z - projector.oz) * (a.z - projector.oz))
        val haze = min(0.55f, dist / 5000f)
        for (idx in 0 until n) {
            val k = order[idx]
            val pts = mesh.polys[k]
            val m = pts.size / 3
            if (bufWorld.size < pts.size) bufWorld = FloatArray(pts.size)
            if (bufScreen.size < m * 2) bufScreen = FloatArray(m * 2)
            var ok = true
            for (i in 0 until m) {
                a.toWorld(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2], tmp)
                bufWorld[3 * i] = tmp[0]; bufWorld[3 * i + 1] = tmp[1]; bufWorld[3 * i + 2] = tmp[2]
                if (!projector.worldToScreen(r, tmp[0], tmp[1], tmp[2], p)) { ok = false; break }
                bufScreen[2 * i] = p[0]; bufScreen[2 * i + 1] = p[1]
            }
            if (!ok) continue
            // normal con las diagonales (sirve también para cuadriláteros degenerados)
            val i2 = min(2, m - 1); val i3 = if (m >= 4) 3 else 0; val i1 = 1
            val ux = bufWorld[3 * i2] - bufWorld[0]; val uy = bufWorld[3 * i2 + 1] - bufWorld[1]; val uz = bufWorld[3 * i2 + 2] - bufWorld[2]
            val vx = bufWorld[3 * i3] - bufWorld[3 * i1]; val vy = bufWorld[3 * i3 + 1] - bufWorld[3 * i1 + 1]; val vz = bufWorld[3 * i3 + 2] - bufWorld[3 * i1 + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            val light = if (nl > 1e-6f) {
                nx /= nl; ny /= nl; nz /= nl
                0.4f + 0.6f * abs(nx * sun[0] + ny * sun[1] + nz * sun[2])
            } else 0.6f
            val base = mesh.colors[k]
            val burn = if (a.down) 0.45f else 1f
            fun ch(v: Int, sky: Int) = ((v * light * burn) * (1 - haze) + sky * haze).toInt().coerceIn(0, 255)
            polyPaint.color = Color.argb(Color.alpha(base),
                ch(Color.red(base), 170), ch(Color.green(base), 190), ch(Color.blue(base), 210))

            path.reset()
            path.moveTo(bufScreen[0], bufScreen[1])
            for (i in 1 until m) path.lineTo(bufScreen[2 * i], bufScreen[2 * i + 1])
            path.close()
            c.drawPath(path, polyPaint)
            for (i in 0 until m) {
                val sx = bufScreen[2 * i]; val sy = bufScreen[2 * i + 1]
                if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
            }
            anyVisible = true
        }
        // a lo lejos: un punto mínimo para que no desaparezca
        if (anyVisible && maxX - minX < 3f) {
            polyPaint.color = Color.rgb(60, 64, 70)
            c.drawCircle((minX + maxX) / 2, (minY + maxY) / 2, 1.8f, polyPaint)
        }
        if (!updateScreen) return
        a.onScreen = anyVisible && maxX > 0 && minX < projector.width && maxY > 0 && minY < projector.height
        if (anyVisible) a.screenRect.set(minX, minY, maxX, maxY)
    }

    /** Punto de puntería adelantado (intercepción + caída del proyectil). */
    fun leadPoint(a: Aircraft, out: FloatArray) {
        var t = a.range / Ballistics.MUZZLE_VELOCITY
        var px = a.x; var py = a.y; var pz = a.z
        repeat(3) {
            px = a.x + a.vx * t; py = a.y + a.vy * t; pz = a.z + a.vz * t
            val d = sqrt(px * px + py * py + pz * pz)
            val vAvg = Ballistics.MUZZLE_VELOCITY * exp(-Ballistics.DRAG * t * 0.5f)
            t = d / vAvg
        }
        out[0] = px; out[1] = py; out[2] = pz + 0.5f * Ballistics.GRAVITY * t * t
    }
}
