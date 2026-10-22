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
 * Cada proyectil tiene su tipo, arrastre, duración y daño: sirve para 20 mm, 35 mm,
 * granadas de tanque de 105 mm y proyectiles de obús de 155 mm.
 */
class Ballistics(capacity: Int = 600) {

    companion object {
        const val MUZZLE_VELOCITY = 1100f      // m/s (M940 / Mk 149 APDS)
        const val GRAVITY = 9.81f
        const val DRAG = 0.12f                 // 1/s, arrastre simplificado (munición liviana)
        const val LIFETIME = 3.2f              // s
        const val DISPERSION_RAD = 0.0028f     // ~2.8 mrad de dispersión

        // tipos de proyectil
        const val K_20MM = 0
        const val K_35MM = 1
        const val K_105_AP = 2      // perforante (flecha)
        const val K_105_HE = 3      // explosivo
        const val K_155_HE = 4      // obús

        /** Los proyectiles pesados impactan contra el suelo. */
        fun heavy(kind: Int) = kind >= K_105_AP
        fun explosive(kind: Int) = kind == K_105_HE || kind == K_155_HE
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
    val kind = IntArray(cap)
    val drag = FloatArray(cap)
    val life = FloatArray(cap)
    /** Daño por impacto directo (0 = el que diga el arma). */
    val dmg = IntArray(cap)

    /** Estallidos AHEAD de este paso: x, y, z, vx, vy, vz por cada uno. */
    val bursts = FloatArray(6 * 64)
    var burstCount = 0
        private set

    /** Impactos de proyectiles pesados en este paso (suelo o vehículo): x, y, z, tipo. */
    val impacts = FloatArray(4 * 32)
    var impactCount = 0
        private set

    var count = 0
        private set

    private var next = 0
    private val rnd = Random(System.nanoTime())
    private val tmp = FloatArray(3)

    /**
     * Dispara una bala en la dirección donde apunta el celular (con dispersión).
     * @param r matriz de orientación actual
     * @param timeAhead segundos que hay que adelantar la bala (para repartir varios disparos dentro de un mismo frame)
     */
    fun spawn(
        projector: Projector, r: FloatArray, timeAhead: Float,
        muzzle: FloatArray, speed: Float = MUZZLE_VELOCITY, fuseDist: Float = 0f, heavy: Boolean = false,
    ) {
        val gx = gaussian() * DISPERSION_RAD
        val gy = gaussian() * DISPERSION_RAD
        projector.deviceToWorld(r, gx, gy, -1f, tmp)
        val dx = tmp[0]; val dy = tmp[1]; val dz = tmp[2]
        projector.deviceToWorld(r, muzzle[0], muzzle[1], muzzle[2], tmp)
        spawnDir(tmp[0], tmp[1], tmp[2], dx, dy, dz, speed,
            kind = if (heavy) K_35MM else K_20MM, fuseDist = fuseDist, timeAhead = timeAhead)
    }

    /** Dispara un proyectil desde (x0,y0,z0) en el mundo con la dirección exacta (dx,dy,dz). */
    fun spawnDir(
        x0: Float, y0: Float, z0: Float, dx: Float, dy: Float, dz: Float, speed: Float,
        kind: Int, fuseDist: Float = 0f, timeAhead: Float = 0f,
        dragCoef: Float = DRAG, lifetime: Float = LIFETIME, damage: Int = 0,
    ) {
        val i = next
        next = (next + 1) % cap
        if (!alive[i]) count++
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        vx[i] = dx / n * speed; vy[i] = dy / n * speed; vz[i] = dz / n * speed
        x[i] = x0; y[i] = y0; z[i] = z0
        px[i] = x0; py[i] = y0; pz[i] = z0
        age[i] = 0f
        dist[i] = sqrt(x0 * x0 + y0 * y0 + z0 * z0)
        prevDist[i] = dist[i]
        alive[i] = true
        hit[i] = false
        fuse[i] = fuseDist
        big[i] = kind != K_20MM
        this.kind[i] = kind
        drag[i] = dragCoef
        life[i] = lifetime
        dmg[i] = damage
        if (timeAhead > 0f) step(i, timeAhead)
    }

    fun update(dt: Float) {
        burstCount = 0
        impactCount = 0
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
            if (heavy(kind[i]) && z[i] < AircraftManager.GROUND_Z && vz[i] < 0f) {
                // impacto contra el suelo: se interpola el punto exacto
                val f = ((pz[i] - AircraftManager.GROUND_Z) / (pz[i] - z[i]).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                addImpact(px[i] + (x[i] - px[i]) * f, py[i] + (y[i] - py[i]) * f, AircraftManager.GROUND_Z, kind[i])
                alive[i] = false; count--
                continue
            }
            if (age[i] > life[i]) { alive[i] = false; count-- }
        }
    }

    /** Registra un impacto de proyectil pesado (lo procesa el HUD: explosión, polvo, daño en área). */
    fun addImpact(ix: Float, iy: Float, iz: Float, k: Int) {
        if (impactCount >= impacts.size / 4) return
        val o = impactCount * 4
        impacts[o] = ix; impacts[o + 1] = iy; impacts[o + 2] = iz; impacts[o + 3] = k.toFloat()
        impactCount++
    }

    private fun step(i: Int, dt: Float) {
        val d = exp(-drag[i] * dt)
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
        // suma de uniformes ~ normal
        return (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 2f) * 1.732f
    }
}
