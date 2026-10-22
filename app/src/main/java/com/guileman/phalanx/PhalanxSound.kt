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
 *  1) SPIN_UP   : motor NEUMÁTICO (aire comprimido) acelerando el conjunto de cañones (~0.4 s):
 *                 golpe de válvula + silbido de turbina + soplido de aire que sube.
 *  2) FIRING    : metralla a 4500 o 3000 disparos/min (75 / 50 Hz) -> el clásico "BRRRRT".
 *  +) Servos eléctricos de la torreta: zumbido proporcional a la velocidad de giro del celular.
 *  3) SPIN_DOWN : ráfaga de aire a presión / turbina que se apaga (0.5 s).
 *     Si se suelta antes de disparar: sin aire, el motor frena bruscamente (golpe + chirrido).
 *  + explosiones (con retardo por la velocidad del sonido).
 *
 * Gepard: estampido grave de 35 mm alternando los dos cañones (1100/min) + golpe del cerrojo.
 * Javelin: siseo de enfriamiento del buscador, tonos de búsqueda/fijación, "pop" del lanzamiento
 * suave, rugido del motor al encenderse y motor a lo lejos; zumbido de rechazo si no hay fijación.
 *
 * Se genera muestra a muestra en un hilo de audio, así la cadencia es exacta
 * y las transiciones entre los sonidos no tienen cortes.
 */
class PhalanxSound(private val gun: GunController, private val weapon: Weapon = Weapon.PHALANX) {

    // ---- eventos del Javelin ----
    private val evCool = java.util.concurrent.atomic.AtomicBoolean(false)
    private val evLaunch = java.util.concurrent.atomic.AtomicBoolean(false)
    private val evIgnite = java.util.concurrent.atomic.AtomicBoolean(false)
    private val evDenied = java.util.concurrent.atomic.AtomicBoolean(false)
    fun seekerCool() = evCool.set(true)
    fun javelinLaunch() = evLaunch.set(true)
    fun javelinIgnite() = evIgnite.set(true)
    fun denied() = evDenied.set(true)
    /** -1 = sin blanco; 0..1 progreso de fijación; 1 = fijado. */
    @Volatile var lockProgress = -1f
    /** Volumen del motor del misil en vuelo (0..1, ya atenuado por distancia). */
    @Volatile var missileMotor = 0f

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

    /** Velocidad angular de la torreta en rad/s (la calcula el HUD a partir de los sensores). */
    @Volatile var slewRate = 0f

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
        val valveHp = OnePoleLP(0.3f)
        val airSvf = Svf()
        var valveT = -1f

        var shotPeriodSamples = srf * 60f / 4500f
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

        val isPhalanx = weapon == Weapon.PHALANX
        val isGepard = weapon == Weapon.GEPARD

        // Gepard: estampido de 35 mm
        var gEnv = 0f; var gPh = 0.0; var gFreq = 50.0; var gClankT = -1f
        val gBodyLp = OnePoleLP(0.035f)
        val gClankSvf = Svf()
        val gEnvDecay = exp(-1f / (0.075f * srf))

        // Javelin
        var coolT = -1f; val coolSvf = Svf()
        var beepClock = 0f; var tonePh = 0.0
        var launchT = -1f; var launchPh = 0.0; val launchLp = OnePoleLP(0.04f)
        var igniteT = -1f; val roarLp = OnePoleLP(0.06f); val roarLp2 = OnePoleLP(0.12f)
        var motorS = 0f
        var deniedT = -1f; var deniedPh = 0.0

        // servos de la torreta
        var servoS = 0f
        var servoPh = 0.0
        var pwmPh = 0.0

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
                    if (state == GunState.SPIN_UP && gun.spin < 0.3f) valveT = 0f   // se abre la válvula de aire
                    if (state == GunState.FIRING) {
                        shotPeriodSamples = srf * 60f / gun.rateRpm.toFloat()
                        shotCounter = shotPeriodSamples // disparo inmediato
                    }
                    lastState = state
                }
                val firing = state == GunState.FIRING
                val braking = state == GunState.SPIN_DOWN && gun.aborted
                val slewTarget = if (weapon == Weapon.JAVELIN) 0f else slewRate
                if (evCool.getAndSet(false)) coolT = 0f
                if (evLaunch.getAndSet(false)) { launchT = 0f; launchPh = 0.0 }
                if (evIgnite.getAndSet(false)) igniteT = 0f
                if (evDenied.getAndSet(false)) { deniedT = 0f; deniedPh = 0.0 }
                val lockP = lockProgress
                val motorTarget = missileMotor

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

                    // ---------- 1) Motor neumático (solo Phalanx) ----------
                    if (!isPhalanx) { valveT = -1f; spinS = 0f }
                    if (valveT >= 0f) {
                        // "pfff" de la válvula de aire al abrirse
                        val hp = noise - valveHp.process(noise)
                        s += hp * exp(-valveT * 22f) * 0.45f
                        valveT += dt
                        if (valveT > 0.3f) valveT = -1f
                    }
                    if (spinS > 0.0005f) {
                        val shaped = spinS.pow(0.9f)
                        // silbido de la turbina de aire (paso de álabes)
                        motorPh1 += (300.0 + 3200.0 * shaped) / sr
                        if (motorPh1 > 1.0) motorPh1 -= 1.0
                        // paso de los 6 cañones: 750 rpm del rotor x 6 = 75 Hz a 4500 disparos/min
                        motorPh2 += (spinS * 75.0) / sr
                        if (motorPh2 > 1.0) motorPh2 -= 1.0
                        val p1 = 2.0 * PI * motorPh1
                        val whine = (sin(p1) * 0.5 + sin(2 * p1) * 0.18).toFloat()
                        val air = airSvf.bandpass(noise, 500f + 3000f * shaped, 0.7f, srf)
                        val whirl = 0.75f + 0.25f * sin(2.0 * PI * motorPh2).toFloat()
                        // en ráfaga el motor queda de fondo
                        val motorAmp = 0.34f * shaped * (1f - 0.5f * fireMix)
                        s += (air * 1.1f * whirl + whine * 0.35f) * motorAmp
                    }

                    // ---------- Servos eléctricos de la torreta ----------
                    servoS += 0.0015f * (slewTarget - servoS)
                    if (servoS > 0.02f) {
                        val k = min(1f, servoS / 2.0f)          // 2 rad/s ≈ 115°/s, el máximo del Phalanx
                        val pitch = if (isGepard) 0.7 else 1.0  // torreta más pesada: zumbido más grave
                        servoPh += (160.0 + 950.0 * k) * pitch / sr
                        if (servoPh > 1.0) servoPh -= 1.0
                        pwmPh += 2600.0 / sr
                        if (pwmPh > 1.0) pwmPh -= 1.0
                        val ps = 2.0 * PI * servoPh
                        val gear = (sin(ps) * 0.6 + sin(3 * ps) * 0.25 + sin(5.3 * ps) * 0.1).toFloat()
                        val pwm = sin(2.0 * PI * pwmPh).toFloat() * 0.12f
                        s += (gear + pwm) * 0.16f * k.pow(0.8f)
                    }

                    // ---------- 2) Metralla (4500 / 3000 por minuto) ----------
                    fireMix += ((if (firing) 1f else 0f) - fireMix) * 0.004f
                    if (firing) {
                        shotCounter += 1f
                        if (shotCounter >= shotPeriodSamples) {
                            shotCounter -= shotPeriodSamples
                            if (isGepard) {
                                gEnv = 1f; gPh = 0.0; gFreq = 44.0 + rnd.nextDouble() * 10.0; gClankT = 0f
                                crackEnv = 0.9f
                            } else {
                                crackEnv = 0.85f + rnd.nextFloat() * 0.15f
                                bodyEnv = 0.9f + rnd.nextFloat() * 0.1f
                                thumpPh = 0.0
                                thumpFreq = 62.0 + rnd.nextDouble() * 14.0
                            }
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
                        s += rattleSvf.bandpass(noise, if (isGepard) 700f else 1350f, 2.5f, srf) * 0.18f * fireMix
                    }
                    // Gepard: estampido grave de 35 mm + golpe del cerrojo
                    if (gEnv > 1e-4f) {
                        gPh += gFreq * (1.0 - 0.3 * (1f - gEnv)) / sr
                        val boom = sin(2.0 * PI * gPh).toFloat() * gEnv * 1.3f
                        val body = gBodyLp.process(noise) * gEnv * 5f
                        s += boom + body
                        gEnv *= gEnvDecay
                    }
                    if (gClankT >= 0f) {
                        if (gClankT > 0.022f) s += gClankSvf.bandpass(noise, 950f, 5f, srf) * exp(-(gClankT - 0.022f) * 70f) * 0.9f
                        gClankT += dt
                        if (gClankT > 0.12f) gClankT = -1f
                    }

                    // ---------- Javelin ----------
                    if (coolT >= 0f) {
                        // siseo del gas que enfría el buscador
                        val env = (if (coolT < 0.05f) coolT / 0.05f else exp(-(coolT - 0.05f) * 1.2f))
                        s += coolSvf.bandpass(noise, 4200f, 1.2f, srf) * env * 0.35f
                        coolT += dt
                        if (coolT > JavelinSystem.COOL_TIME + 0.5f) coolT = -1f
                    }
                    if (lockP >= 0f) {
                        if (lockP >= 1f) {
                            // fijado: tono continuo
                            tonePh += 1500.0 / sr
                            val sq = if (sin(2.0 * PI * tonePh) > 0) 1f else -1f
                            s += sq * 0.07f
                        } else {
                            // buscando: pitidos cada vez más rápidos
                            val period = 0.42f - 0.33f * lockP
                            beepClock += dt
                            if (beepClock > period) beepClock -= period
                            if (beepClock < 0.045f) {
                                tonePh += 1150.0 / sr
                                s += (if (sin(2.0 * PI * tonePh) > 0) 1f else -1f) * 0.06f
                            }
                        }
                    }
                    if (deniedT >= 0f) {
                        deniedPh += 210.0 / sr
                        s += (if (sin(2.0 * PI * deniedPh) > 0) 1f else -1f) * 0.12f * (if (deniedT < 0.18f) 1f else 0f)
                        deniedT += dt
                        if (deniedT > 0.2f) deniedT = -1f
                    }
                    if (launchT >= 0f) {
                        // lanzamiento suave: "pop" seco del motor de eyección
                        launchPh += 58.0 / sr
                        val env = exp(-launchT * 18f)
                        s += (sin(2.0 * PI * launchPh).toFloat() * 1.1f + launchLp.process(noise) * 6f) * env
                        launchT += dt
                        if (launchT > 0.4f) launchT = -1f
                    }
                    if (igniteT >= 0f) {
                        // encendido del motor principal: rugido fuerte que se aleja
                        val env = if (igniteT < 0.02f) igniteT / 0.02f else exp(-(igniteT - 0.02f) * 2.2f)
                        val crackle = if (rnd.nextFloat() < 0.02f) noise * 2f else 0f
                        s += (roarLp.process(noise) * 4f + crackle) * env * 0.9f
                        igniteT += dt
                        if (igniteT > 1.6f) igniteT = -1f
                    }
                    motorS += 0.0008f * (motorTarget - motorS)
                    if (motorS > 0.003f) s += roarLp2.process(noise) * 2.2f * motorS

                    // ---------- 3) Descarga de aire / turbina que se apaga ----------
                    if (whooshT >= 0f && !isPhalanx) whooshT = -1f
                    if (whooshT >= 0f) {
                        val t = whooshT
                        val dur = gun.cfg.spinDownTime.toFloat()
                        val env = if (t < 0.015f) t / 0.015f else ((1f - (t - 0.015f) / (dur - 0.015f)).coerceAtLeast(0f)).pow(1.8f)
                        val fc = 300f + 2900f * exp(-t * 5.5f)
                        val air = whooshSvf.bandpass(noise, fc, 0.9f, srf) +
                                whooshSvf2.bandpass(noise, fc * 2.3f, 1.4f, srf) * 0.5f
                        s += air * env * 0.95f
                        whooshT += dt
                        if (whooshT > dur) whooshT = -1f
                    }

                    // ---------- 3b) Freno brusco del motor (soltar antes de disparar) ----------
                    if (brakeT >= 0f && !isPhalanx) brakeT = -1f
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
                        if (brakeT > gun.cfg.brakeTime + 0.15f) brakeT = -1f
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
