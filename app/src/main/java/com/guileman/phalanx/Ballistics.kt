package com.guileman.phalanx

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Proyectiles simulados en coordenadas del MUNDO (Este/Norte/Arriba, origen = el jugador).
 * Como su posición no depende de hacia dónde apunta el celular, al girar el teléfono
 * la "manguera" de trazadoras queda fija respecto del entorno que muestra la cámara.
 *
 * Estructura de arrays (sin objetos por bala) para no generar basura a 66 disparos/s.
 */
class Ballistics(capacity: Int = 600) {

    companion object {
        const val MUZZLE_VELOCITY = 1100f      // m/s (M940 / Mk 149 APDS)
        const val GRAVITY = 9.81f
        const val DRAG = 0.12f                 // 1/s, arrastre simplificado
        const val LIFETIME = 3.2f              // s
        const val DISPERSION_RAD = 0.0028f     // ~2.8 mrad de dispersión
    }

    val cap = capacity
    val x = FloatArray(cap); val y = FloatArray(cap); val z = FloatArray(cap)
    /** Posición en el paso anterior (para detectar cruces con aviones). */
    val px = FloatArray(cap); val py = FloatArray(cap); val pz = FloatArray(cap)
    val vx = FloatArray(cap); val vy = FloatArray(cap); val vz = FloatArray(cap)
    val age = FloatArray(cap)
    /** Distancia recorrida desde el jugador (aprox. = norma de la posición). */
    val dist = FloatArray(cap)
    val prevDist = FloatArray(cap)
    val alive = BooleanArray(cap)
    val hit = BooleanArray(cap)
    /** Distancia de estallido programada (0 = espoleta de contacto). */
    val fuse = FloatArray(cap)
    /** Proyectil grueso (35 mm): trazadora más ancha. */
    val big = BooleanArray(cap)

    /** Estallidos AHEAD de este paso: x, y, z, vx, vy, vz por cada uno. */
    val bursts = FloatArray(6 * 64)
    var burstCount = 0
        private set
    var count = 0
        private set

    private var next = 0
    private val rnd = Random(System.nanoTime())
    private val tmp = FloatArray(3)

    /**
     * Dispara una bala.
     * @param r matriz de orientación actual
     * @param timeAhead segundos que hay que adelantar la bala (para repartir varios disparos dentro de un mismo frame)
     */
    fun spawn(
        projector: Projector, r: FloatArray, timeAhead: Float,
        muzzle: FloatArray, speed: Float = MUZZLE_VELOCITY, fuseDist: Float = 0f, heavy: Boolean = false,
    ) {
        val i = next
        next = (next + 1) % cap
        if (!alive[i]) count++

        // dirección de disparo en coordenadas del dispositivo, con dispersión gaussiana
        val gx = gaussian() * DISPERSION_RAD
        val gy = gaussian() * DISPERSION_RAD
        projector.deviceToWorld(r, gx, gy, -1f, tmp)
        val n = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2])
        vx[i] = tmp[0] / n * speed
        vy[i] = tmp[1] / n * speed
        vz[i] = tmp[2] / n * speed

        projector.deviceToWorld(r, muzzle[0], muzzle[1], muzzle[2], tmp)
        x[i] = tmp[0]; y[i] = tmp[1]; z[i] = tmp[2]
        px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]
        age[i] = 0f
        dist[i] = 0f
        prevDist[i] = 0f
        alive[i] = true
        hit[i] = false
        fuse[i] = fuseDist
        big[i] = heavy
        if (timeAhead > 0f) step(i, timeAhead)
    }

    fun update(dt: Float) {
        burstCount = 0
        for (i in 0 until cap) {
            if (!alive[i]) continue
            step(i, dt)
            if (fuse[i] > 0f && dist[i] >= fuse[i]) {
                // espoleta programada: estalla y libera la nube de fragmentos
                if (burstCount < bursts.size / 6) {
                    val k = burstCount * 6
                    bursts[k] = x[i]; bursts[k + 1] = y[i]; bursts[k + 2] = z[i]
                    bursts[k + 3] = vx[i]; bursts[k + 4] = vy[i]; bursts[k + 5] = vz[i]
                    burstCount++
                }
                alive[i] = false; count--
                continue
            }
            if (age[i] > LIFETIME) { alive[i] = false; count-- }
        }
    }

    private fun step(i: Int, dt: Float) {
        val d = exp(-DRAG * dt)
        px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]
        vx[i] *= d; vy[i] *= d; vz[i] = vz[i] * d - GRAVITY * dt
        x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
        age[i] += dt
        prevDist[i] = dist[i]
        dist[i] = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
    }

    fun kill(i: Int) {
        if (alive[i]) { alive[i] = false; count-- }
    }

    fun clear() {
        alive.fill(false); count = 0
    }

    private fun gaussian(): Float {
        // Box-Muller simplificado (suma de uniformes ~ normal)
        return (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 2f) * 1.732f
    }
}
