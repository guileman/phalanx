package com.guileman.phalanx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sintetizador procedural en tiempo real del sonido del Phalanx.
 *
 *  1) SPIN_UP   : motor eléctrico acelerando (~1 s), tono que sube.
 *  2) FIRING    : metralla a 4000 disparos/min (66.7 Hz) -> el clásico "BRRRRT".
 *  3) SPIN_DOWN : ráfaga de aire a presión / turbina que se apaga (0.5 s).
 *     Si se suelta antes de disparar: sin aire, el motor frena bruscamente (golpe + chirrido).
 *  + explosiones (con retardo por la velocidad del sonido).
 *
 * Se genera muestra a muestra en un hilo de audio, así la cadencia es exacta
 * y las transiciones entre los tres sonidos no tienen cortes.
 */
class PhalanxSound(private val gun: GunController) {

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Volumen maestro 0..1 (el volumen general se maneja con las teclas del celular) */
    @Volatile var volume = 0.9f

    private class Boom(val delaySamples: Int, val gain: Float, val size: Float)
    private val booms = java.util.concurrent.ConcurrentLinkedQueue<Boom>()

    /**
     * Encola una explosión.
     * @param distance distancia en metros (atenúa y retrasa el sonido)
     * @param size 0..1 tamaño relativo (más grande = más grave y largo)
     */
    fun explosion(distance: Float, size: Float) {
        if (booms.size > 12) return
        val delay = (distance / 343f).coerceAtMost(2.2f)
        val gain = (1f / (1f + distance / 250f)).coerceIn(0.12f, 1f) * (0.5f + 0.5f * size)
        booms.add(Boom((delay * sampleRate).toInt(), gain, size.coerceIn(0f, 1f)))
    }

    @Volatile private var sampleRate = 48000

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "phalanx-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    // ---------------- DSP ----------------

    private class OnePoleLP(var a: Float) {
        var y = 0f
        fun process(x: Float): Float { y += a * (x - y); return y }
    }

    /** Filtro de estado variable (Chamberlin) para pasa-banda con barrido. */
    private class Svf {
        var low = 0f; var band = 0f
        fun bandpass(x: Float, fc: Float, q: Float, sr: Float): Float {
            val f = (2.0 * sin(PI * min(fc, sr / 7f) / sr)).toFloat()
            low += f * band
            val high = x - low - band / q
            band += f * high
            return band
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 1024 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        sampleRate = sr
        val srf = sr.toFloat()
        val dt = 1f / srf
        val block = FloatArray(256)
        val rnd = Random(1234)

        // --- estado del sintetizador ---
        var spinS = 0f                      // giro suavizado
        val spinSmooth = 1f - exp(-1f / (0.012f * srf))
        var motorPh1 = 0.0
        var motorPh2 = 0.0
        val brushHp = OnePoleLP(0.25f)

        val shotPeriodSamples = srf * 60f / GunController.RATE_RPM.toFloat()
        var shotCounter = 0f
        var crackEnv = 0f
        var bodyEnv = 0f
        var thumpPh = 0.0
        var thumpFreq = 70.0
        val crackDecay = exp(-1f / (0.0028f * srf))
        val bodyDecay = exp(-1f / (0.011f * srf))
        val crackHp = OnePoleLP(0.45f)
        val bodyLp = OnePoleLP(0.06f)
        val rattleSvf = Svf()
        var fireMix = 0f                    // fundido de la metralla

        var lastState = GunState.SAFE
        var whooshT = -1f                   // <0 = inactivo
        val whooshSvf = Svf()
        val whooshSvf2 = Svf()
        val outLp = OnePoleLP(0.55f)

        // freno brusco del motor
        var brakeT = -1f
        var brakeAmp = 0f
        var clunkPh = 0.0
        val scrapeSvf = Svf()

        // voces de explosión
        class Voice { var wait = 0; var t = 0f; var gain = 0f; var size = 0f; var active = false
            val lp = OnePoleLP(0.05f); val lp2 = OnePoleLP(0.02f); var ph = 0.0 }
        val voices = Array(8) { Voice() }

        track.play()
        try {
            while (running) {
                val state = gun.state
                val targetSpin = gun.spin
                val vol = volume

                if (state != lastState) {
                    if (state == GunState.SPIN_DOWN) {
                        if (gun.aborted) {
                            brakeT = 0f
                            brakeAmp = 0.35f + 0.65f * gun.spinAtRelease
                            clunkPh = 0.0
                        } else {
                            whooshT = 0f
                        }
                    }
                    if (state == GunState.FIRING) shotCounter = shotPeriodSamples // disparo inmediato
                    lastState = state
                }
                val firing = state == GunState.FIRING
                val braking = state == GunState.SPIN_DOWN && gun.aborted

                while (true) {
                    val b = booms.poll() ?: break
                    val v = voices.firstOrNull { !it.active } ?: voices.minByOrNull { it.gain * (1f - it.t) } ?: break
                    v.wait = b.delaySamples; v.t = 0f; v.gain = b.gain; v.size = b.size; v.active = true; v.ph = 0.0
                    v.lp.a = 0.03f + 0.06f * (1f - b.size)
                }

                for (i in block.indices) {
                    spinS += (if (braking) spinSmooth * 3f else spinSmooth) * (targetSpin - spinS)
                    val noise = rnd.nextFloat() * 2f - 1f
                    var s = 0f

                    // ---------- 1) Motor eléctrico ----------
                    if (spinS > 0.0005f) {
                        val shaped = spinS.pow(1.15f)
                        val f = 55.0 + 1150.0 * shaped
                        motorPh1 += f / sr
                        motorPh2 += f * 3.21 / sr
                        if (motorPh1 > 1.0) motorPh1 -= 1.0
                        if (motorPh2 > 1.0) motorPh2 -= 1.0
                        val p1 = 2.0 * PI * motorPh1
                        val whine = (sin(p1) * 0.55 + sin(2 * p1) * 0.28 + sin(3 * p1) * 0.12 +
                                sin(2.0 * PI * motorPh2) * 0.16).toFloat()
                        val brush = (noise - brushHp.process(noise)) * 0.25f
                        // en ráfaga el motor queda de fondo
                        val motorAmp = 0.30f * spinS * (1f - 0.55f * fireMix)
                        s += (whine + brush * shaped) * motorAmp
                    }

                    // ---------- 2) Metralla 4000 rpm ----------
                    fireMix += ((if (firing) 1f else 0f) - fireMix) * 0.004f
                    if (firing) {
                        shotCounter += 1f
                        if (shotCounter >= shotPeriodSamples) {
                            shotCounter -= shotPeriodSamples
                            crackEnv = 0.85f + rnd.nextFloat() * 0.15f
                            bodyEnv = 0.9f + rnd.nextFloat() * 0.1f
                            thumpPh = 0.0
                            thumpFreq = 62.0 + rnd.nextDouble() * 14.0
                        }
                    }
                    if (crackEnv > 1e-4f || bodyEnv > 1e-4f) {
                        val crack = (noise - crackHp.process(noise)) * crackEnv
                        val body = bodyLp.process(noise) * bodyEnv * 3.2f
                        thumpPh += thumpFreq / sr
                        val thump = sin(2.0 * PI * thumpPh).toFloat() * bodyEnv
                        s += crack * 0.75f + body * 0.9f + thump * 0.55f
                        crackEnv *= crackDecay
                        bodyEnv *= bodyDecay
                    }
                    if (fireMix > 0.001f) {
                        // traqueteo mecánico continuo del alimentador
                        s += rattleSvf.bandpass(noise, 1350f, 2.5f, srf) * 0.18f * fireMix
                    }

                    // ---------- 3) Descarga de aire / turbina que se apaga ----------
                    if (whooshT >= 0f) {
                        val t = whooshT
                        val dur = GunController.SPIN_DOWN_TIME.toFloat()
                        val env = if (t < 0.015f) t / 0.015f else ((1f - (t - 0.015f) / (dur - 0.015f)).coerceAtLeast(0f)).pow(1.8f)
                        val fc = 300f + 2900f * exp(-t * 5.5f)
                        val air = whooshSvf.bandpass(noise, fc, 0.9f, srf) +
                                whooshSvf2.bandpass(noise, fc * 2.3f, 1.4f, srf) * 0.5f
                        s += air * env * 0.95f
                        whooshT += dt
                        if (whooshT > dur) whooshT = -1f
                    }

                    // ---------- 3b) Freno brusco del motor (soltar antes de disparar) ----------
                    if (brakeT >= 0f) {
                        val t = brakeT
                        // golpe mecánico al enganchar el freno
                        clunkPh += (95.0 - 40.0 * t) / sr
                        val clunk = (sin(2.0 * PI * clunkPh).toFloat() + noise * 0.5f) * exp(-t * 38f)
                        // chirrido del rotor frenando: sigue la velocidad del motor
                        val fc = 400f + 2600f * spinS
                        val scrape = scrapeSvf.bandpass(noise, fc, 6f, srf) * exp(-t * 7f)
                        s += (clunk * 0.9f + scrape * 0.55f) * brakeAmp
                        brakeT += dt
                        if (brakeT > GunController.BRAKE_TIME + 0.15f) brakeT = -1f
                    }

                    // ---------- Explosiones ----------
                    for (v in voices) {
                        if (!v.active) continue
                        if (v.wait > 0) { v.wait--; continue }
                        val t = v.t
                        val len = 0.6f + 1.2f * v.size
                        val env = (if (t < 0.004f) t / 0.004f else exp(-(t - 0.004f) * 5.5f / len))
                        val rumble = v.lp2.process(v.lp.process(noise) * 6f) * 3f
                        v.ph += (55.0 - 25.0 * v.size) * (1.0 - 0.5 * t) / sr
                        val thump = sin(2.0 * PI * v.ph).toFloat() * exp(-t * 9f)
                        val crack = noise * exp(-t * 60f) * 0.6f
                        s += (rumble + thump * 0.9f + crack) * env * v.gain
                        v.t += dt
                        if (v.t > len * 1.6f) v.active = false
                    }

                    block[i] = tanh(outLp.process(s) * 1.25f) * 0.92f * vol
                }
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }
}
