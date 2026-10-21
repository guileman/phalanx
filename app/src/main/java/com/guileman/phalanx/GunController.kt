package com.guileman.phalanx

import kotlin.math.max
import kotlin.math.min

enum class GunState { SAFE, SPIN_UP, FIRING, SPIN_DOWN, RELOADING }

/**
 * Modos de fuego del Phalanx real: cadencia seleccionable 4500 / 3000 disparos/min (Block 1 / 1B)
 * y ráfagas automáticas de 60 o 100 tiros (Block 0).
 */
enum class FireMode(val label: String, val rpm: Double, val burst: Int) {
    CONT_4500("4500", 4500.0, 0),
    CONT_3000("3000", 3000.0, 0),
    BURST_60("R60", 4500.0, 60),
    BURST_100("R100", 4500.0, 100);

    fun next(): FireMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Máquina de estados del cañón (fuente única de verdad, se actualiza desde el hilo de UI).
 * El motor de audio solo lee [state] y [spin], que son volátiles.
 *
 * Gatillo apretado -> SPIN_UP (0.4 s, el motor neumático acelera el conjunto de cañones)
 *                  -> FIRING  (4500 o 3000 disparos/min, o ráfaga automática de 60/100 tiros)
 * Gatillo suelto   -> SPIN_DOWN (0.5 s, descarga de aire / turbina que se apaga)
 *                  -> SAFE
 * Gatillo suelto ANTES de llegar a disparar -> SPIN_DOWN "abortado":
 *                  el motor frena bruscamente (0.3 s), sin descarga de aire.
 */
class GunController {

    companion object {
        const val SPIN_UP_TIME = 0.4
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

    /** Modo de fuego seleccionado (se aplica al próximo disparo). */
    @Volatile var mode: FireMode = FireMode.CONT_4500
    /** Cadencia de la ráfaga en curso (la lee el audio). */
    @Volatile var rateRpm: Double = 4500.0
        private set
    /** Tiros que faltan de la ráfaga automática en curso (0 = sin ráfaga). */
    var burstLeft = 0
        private set
    private var waitRelease = false

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
    /** Gatillo apretado de nuevo (no el mismo toque que terminó la ráfaga anterior). */
    private fun freshPress() = trigger && !waitRelease && magazine > 0

    private fun startSpinUp() {
        val m = mode
        rateRpm = m.rpm
        burstLeft = if (m.burst > 0) min(m.burst, magazine) else 0
        setState(GunState.SPIN_UP)
    }

    /** En ráfaga automática la computadora completa la ráfaga aunque se suelte el botón. */
    private fun firing() = (trigger && !waitRelease) || burstLeft > 0

    fun update(dt: Double, nowNanos: Long): Int {
        stateTime += dt
        if (!trigger) waitRelease = false
        var shots = 0
        when (state) {
            GunState.SAFE -> {
                spin = 0f
                if (freshPress()) startSpinUp()
            }
            GunState.SPIN_UP -> {
                spin = min(1f, spin + (dt / SPIN_UP_TIME).toFloat())
                if (!firing()) {
                    beginSpinDown(abort = true)
                } else if (spin >= 1f) {
                    setState(GunState.FIRING)
                    shotAccumulator = 1.0 // primer disparo inmediato
                }
            }
            GunState.FIRING -> {
                spin = 1f
                if (!firing() || magazine <= 0) {
                    burstLeft = 0
                    beginSpinDown(abort = false)
                } else {
                    shotAccumulator += dt * rateRpm / 60.0
                    shots = min(shotAccumulator.toInt(), magazine)
                    if (burstLeft > 0) {
                        shots = min(shots, burstLeft)
                        burstLeft -= shots
                        if (burstLeft == 0) {
                            // fin de la ráfaga: hay que soltar y volver a apretar para otra
                            waitRelease = true
                        }
                    }
                    shotAccumulator -= shots
                    if (waitRelease) shotAccumulator = 0.0
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
                } else if (freshPress() && stateTime > 0.08) {
                    // se vuelve a apretar: reacelera desde la velocidad actual
                    startSpinUp()
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
