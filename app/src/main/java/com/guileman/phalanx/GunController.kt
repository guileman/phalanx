package com.guileman.phalanx

import kotlin.math.max
import kotlin.math.min

enum class GunState { SAFE, SPIN_UP, FIRING, SPIN_DOWN, RELOADING }

/**
 * Máquina de estados del cañón (fuente única de verdad, se actualiza desde el hilo de UI).
 * El motor de audio solo lee [state] y [spin], que son volátiles.
 *
 * Gatillo apretado -> SPIN_UP (1 s, el motor eléctrico acelera)
 *                  -> FIRING  (4000 disparos/min)
 * Gatillo suelto   -> SPIN_DOWN (0.5 s, descarga de aire / turbina que se apaga)
 *                  -> SAFE
 * Gatillo suelto ANTES de llegar a disparar -> SPIN_DOWN "abortado":
 *                  el motor frena bruscamente (0.3 s), sin descarga de aire.
 */
class GunController {

    companion object {
        const val RATE_RPM = 4000.0
        const val SPIN_UP_TIME = 1.0
        const val SPIN_DOWN_TIME = 0.5
        const val BRAKE_TIME = 0.3
        const val MAGAZINE = 1550          // capacidad real del Phalanx Block 1B
        const val RELOAD_TIME = 4.0
    }

    @Volatile var state: GunState = GunState.SAFE
        private set

    /** Velocidad de giro del rotor 0..1 (leída por el audio). */
    @Volatile var spin: Float = 0f
        private set

    @Volatile var trigger: Boolean = false

    /** true si el SPIN_DOWN actual viene de soltar el gatillo antes de disparar (freno brusco). */
    @Volatile var aborted: Boolean = false
        private set
    /** Giro del rotor en el momento de empezar a detenerse. */
    @Volatile var spinAtRelease: Float = 0f
        private set

    var stateTime = 0.0
        private set
    var roundsFired = 0L
        private set
    var magazine = MAGAZINE
        private set
    /** Temperatura del cañón 0..1 */
    var heat = 0f
        private set

    private var shotAccumulator = 0.0
    private val recentShots = ArrayDeque<Long>()

    val reloadProgress: Float
        get() = if (state == GunState.RELOADING) (stateTime / RELOAD_TIME).toFloat() else 0f

    /** Disparos/minuto medidos en el último segundo. */
    fun measuredRpm(nowNanos: Long): Int {
        while (recentShots.isNotEmpty() && nowNanos - recentShots.first() > 1_000_000_000L) {
            recentShots.removeFirst()
        }
        return recentShots.size * 60
    }

    fun requestReload(): Boolean {
        if (state == GunState.SAFE && magazine < MAGAZINE) {
            setState(GunState.RELOADING)
            return true
        }
        return false
    }

    private fun beginSpinDown(abort: Boolean) {
        aborted = abort
        spinAtRelease = spin
        setState(GunState.SPIN_DOWN)
    }

    private fun setState(s: GunState) {
        state = s
        stateTime = 0.0
    }

    /**
     * Avanza la simulación [dt] segundos.
     * @return cantidad de proyectiles que hay que disparar en este paso.
     */
    fun update(dt: Double, nowNanos: Long): Int {
        stateTime += dt
        var shots = 0
        when (state) {
            GunState.SAFE -> {
                spin = 0f
                if (trigger && magazine > 0) setState(GunState.SPIN_UP)
            }
            GunState.SPIN_UP -> {
                spin = min(1f, spin + (dt / SPIN_UP_TIME).toFloat())
                if (!trigger) {
                    beginSpinDown(abort = true)
                } else if (spin >= 1f) {
                    setState(GunState.FIRING)
                    shotAccumulator = 1.0 // primer disparo inmediato
                }
            }
            GunState.FIRING -> {
                spin = 1f
                if (!trigger || magazine <= 0) {
                    beginSpinDown(abort = false)
                } else {
                    shotAccumulator += dt * RATE_RPM / 60.0
                    shots = min(shotAccumulator.toInt(), magazine)
                    shotAccumulator -= shots
                    magazine -= shots
                    roundsFired += shots
                    heat = min(1f, heat + shots * 0.0009f)
                    repeat(shots) { recentShots.addLast(nowNanos) }
                }
            }
            GunState.SPIN_DOWN -> {
                val dur = if (aborted) BRAKE_TIME else SPIN_DOWN_TIME
                val k = (1.0 - stateTime / dur).coerceIn(0.0, 1.0).toFloat()
                // freno: caída rápida al principio (cuadrática); normal: lineal
                spin = spinAtRelease * if (aborted) k * k else k
                if (stateTime >= dur) {
                    spin = 0f
                    setState(GunState.SAFE)
                } else if (trigger && magazine > 0 && stateTime > 0.08) {
                    // se vuelve a apretar: reacelera desde la velocidad actual
                    setState(GunState.SPIN_UP)
                }
            }
            GunState.RELOADING -> {
                spin = 0f
                if (stateTime >= RELOAD_TIME) {
                    magazine = MAGAZINE
                    setState(GunState.SAFE)
                }
            }
        }
        if (state != GunState.FIRING) heat = max(0f, heat - (dt * 0.07).toFloat())
        return shots
    }
}
