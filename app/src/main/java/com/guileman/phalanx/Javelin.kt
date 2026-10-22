package com.guileman.phalanx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Algo que el buscador del Javelin puede fijar: un objeto detectado por la cámara o un avión. */
sealed interface LockTarget {
    /** Sigue existiendo (no se perdió el seguimiento ni fue destruido). */
    val valid: Boolean
    /** Recuadro en pantalla si está visible, o null. */
    fun screenRect(): RectF?
    /** Posición en el mundo (m). */
    fun position(out: FloatArray)
    /** Velocidad en el mundo (m/s). */
    fun velocity(out: FloatArray)
    val label: String
    val range: Float
}

data class ObjTarget(val track: Track) : LockTarget {
    override val valid get() = !track.gone
    override fun screenRect() = if (track.visible && !track.gone) track.rect else null
    override fun position(out: FloatArray) {
        out[0] = track.dir[0] * track.range; out[1] = track.dir[1] * track.range; out[2] = track.dir[2] * track.range
    }
    override fun velocity(out: FloatArray) { out[0] = 0f; out[1] = 0f; out[2] = 0f }
    override val label get() = TargetClasses.display(track.label)
    override val range get() = track.range
}

data class AirTarget(val a: Aircraft, val mgr: AircraftManager) : LockTarget {
    override val valid get() = !a.down && a in mgr.list
    override fun screenRect() = if (a.onScreen && valid) a.screenRect else null
    override fun position(out: FloatArray) { out[0] = a.x; out[1] = a.y; out[2] = a.z }
    override fun velocity(out: FloatArray) { out[0] = a.vx; out[1] = a.vy; out[2] = a.vz }
    override val label get() = a.model.name
    override val range get() = a.range
}

/**
 * Javelin: unidad de comando (CLU) + misil "dispara y olvida".
 *
 * Secuencia: enfriar el buscador → mantener el blanco dentro de la ventana de seguimiento hasta fijarlo →
 * lanzar. Lanzamiento suave (el misil sale despacio del tubo), a los 0.3 s enciende el motor principal
 * y vuela solo hacia el blanco: en modo TOP sube y cae desde arriba; en modo DIR va casi directo.
 * Ficción: también puede fijar aviones (con espoleta de proximidad).
 */
class JavelinSystem {

    companion object {
        const val COOL_TIME = 2.5f
        const val LOCK_TIME = 1.4f
        const val RELOAD_TIME = 4f
        const val RESUPPLY_TIME = 8f
        const val TOTAL = 6
        const val EJECT_TIME = 0.3f
        const val EJECT_SPEED = 28f
        const val ACCEL = 260f
        const val MAX_SPEED = 320f
        /** Ficción: contra aviones vuela más rápido para poder alcanzarlos. */
        const val AIR_MAX_SPEED = 420f
        const val BURN_TIME = 4f
        const val MAX_TURN = 8f        // rad/s
        const val MAX_FLIGHT = 12f
        /** Salida del tubo en coordenadas del dispositivo. */
        val MUZZLE = floatArrayOf(0.15f, -0.12f, -0.6f)
    }

    class Missile(
        val target: LockTarget, val top: Boolean,
        var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float,
    ) {
        var age = 0f
        var ignited = false
        var hd0 = 0f
        var apex = 0f
        /** Velocidad máxima: a corta distancia vuela más lento para poder doblar hacia el blanco. */
        var vmax = MAX_SPEED
        var smokeTimer = 0f
        var alive = true
        val last = FloatArray(3)
        val burning get() = ignited && age < EJECT_TIME + BURN_TIME
        val range get() = sqrt(x * x + y * y + z * z)
    }

    var coolLeft = COOL_TIME
        private set
    var inTube = true
        private set
    var reserve = TOTAL - 1
        private set
    var reloadLeft = 0f
        private set
    var resupplyLeft = 0f
        private set
    var topAttack = true

    var lockTarget: LockTarget? = null
        private set
    var lockProgress = 0f
        private set
    private var lostTime = 0f
    val locked get() = lockProgress >= 1f && lockTarget?.valid == true

    val missiles = ArrayList<Missile>()
    var launches = 0
        private set
    var hits = 0
        private set

    val cooling get() = coolLeft > 0f
    val ready get() = inTube && !cooling

    private val t = FloatArray(3)
    private val tv = FloatArray(3)
    private val tmp = FloatArray(3)

    /**
     * @param candidates blancos posibles (objetos visibles y aviones)
     * @param gate ventana de seguimiento en pantalla
     */
    fun update(
        dt: Float, candidates: List<LockTarget>, gate: RectF,
        effects: Effects, sound: PhalanxSound?, aircraft: AircraftManager, projector: Projector, r: FloatArray,
    ) {
        // ---------- tubo, recarga y buscador ----------
        if (!inTube) {
            if (reloadLeft > 0f) {
                reloadLeft -= dt
                if (reloadLeft <= 0f) { inTube = true; coolLeft = COOL_TIME; sound?.seekerCool() }
            } else if (reserve == 0) {
                // sin misiles: llega un reabastecimiento
                resupplyLeft = if (resupplyLeft <= 0f) RESUPPLY_TIME else resupplyLeft - dt
                if (resupplyLeft <= dt) { reserve = TOTAL - 1; inTube = true; coolLeft = COOL_TIME; resupplyLeft = 0f; sound?.seekerCool() }
            }
        }
        if (coolLeft > 0f) coolLeft -= dt

        // ---------- fijación ----------
        if (!ready) {
            lockTarget = null; lockProgress = 0f
        } else {
            val cur = lockTarget
            if (cur != null && lockProgress >= 1f) {
                // ya fijado: la ventana sigue al blanco mientras siga existiendo
                if (!cur.valid) { lockTarget = null; lockProgress = 0f }
                else if (cur.screenRect() == null) {
                    lostTime += dt
                    if (lostTime > 0.8f) { lockTarget = null; lockProgress = 0f }
                } else lostTime = 0f
            } else {
                var best: LockTarget? = null
                var bestD = Float.MAX_VALUE
                for (c in candidates) {
                    val rc = c.screenRect() ?: continue
                    if (!gate.contains(rc.centerX(), rc.centerY())) continue
                    val dx = rc.centerX() - gate.centerX(); val dy = rc.centerY() - gate.centerY()
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = c }
                }
                if (best != null) {
                    lostTime = 0f
                    if (best == cur) {
                        lockProgress = min(1f, lockProgress + dt / LOCK_TIME)
                    } else {
                        lockTarget = best; lockProgress = 0f
                    }
                } else {
                    lostTime += dt
                    if (lostTime > 0.35f) { lockTarget = null; lockProgress = 0f }
                }
            }
        }
        sound?.lockProgress = if (lockTarget == null || !ready) -1f else lockProgress

        // ---------- misiles en vuelo ----------
        var motor = 0f
        val it = missiles.iterator()
        while (it.hasNext()) {
            val m = it.next()
            fly(m, dt, effects, sound, aircraft, projector)
            if (!m.alive) { it.remove(); continue }
            if (m.burning) motor = max(motor, 1f / (1f + m.range / 40f))
        }
        sound?.missileMotor = motor
    }

    /** Intenta lanzar. @return true si salió un misil. */
    fun fire(projector: Projector, r: FloatArray, sound: PhalanxSound?, effects: Effects): Boolean {
        val tgt = lockTarget
        if (!ready || tgt == null || !locked) { sound?.denied(); return false }
        projector.deviceToWorld(r, MUZZLE[0], MUZZLE[1], MUZZLE[2], tmp)
        val x = tmp[0]; val y = tmp[1]; val z = tmp[2]
        // sale apuntando donde mira la CLU, levemente hacia arriba
        projector.deviceToWorld(r, 0f, 0.15f, -1f, tmp)
        val n = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2])
        val m = Missile(tgt, topAttack && tgt is ObjTarget, x, y, z,
            tmp[0] / n * EJECT_SPEED, tmp[1] / n * EJECT_SPEED, tmp[2] / n * EJECT_SPEED)
        tgt.position(m.last)
        m.hd0 = sqrt((m.last[0] - x) * (m.last[0] - x) + (m.last[1] - y) * (m.last[1] - y)).coerceAtLeast(1f)
        // TOP: trepa y cae desde arriba (hasta 150 m); DIR: trayectoria baja
        m.apex = if (m.top) (m.hd0 * 0.6f).coerceIn(12f, 150f) else (m.hd0 * 0.06f).coerceIn(1f, 50f)
        val d0 = sqrt(m.hd0 * m.hd0 + (m.last[2] - z) * (m.last[2] - z))
        m.vmax = if (tgt is AirTarget) AIR_MAX_SPEED else (d0 * 1.2f).coerceIn(60f, MAX_SPEED)
        missiles += m
        launches++
        // lanzamiento suave: poca nube en el tubo
        effects.smoke(x, y, z, 0.6f, 1.5f, 0.6f)
        sound?.javelinLaunch()
        inTube = false
        lockTarget = null; lockProgress = 0f
        if (reserve > 0) { reserve--; reloadLeft = RELOAD_TIME }
        return true
    }

    val totalLeft get() = reserve + if (inTube) 1 else 0

    private fun fly(m: Missile, dt: Float, effects: Effects, sound: PhalanxSound?, aircraft: AircraftManager, projector: Projector) {
        m.age += dt
        if (m.target.valid) m.target.position(m.last)
        if (!m.ignited && m.age >= EJECT_TIME) {
            m.ignited = true
            sound?.javelinIgnite()
            effects.explode(m.x, m.y, m.z, 0.5f, 0.25f)
        }
        val sp = sqrt(m.vx * m.vx + m.vy * m.vy + m.vz * m.vz).coerceAtLeast(0.1f)
        if (!m.ignited) {
            m.vz -= 9.81f * dt
        } else {
            // ---- guiado ----
            if (m.target.valid) m.target.velocity(tv) else { tv[0] = 0f; tv[1] = 0f; tv[2] = 0f }
            val dx = m.last[0] - m.x; val dy = m.last[1] - m.y; val dz = m.last[2] - m.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            // punto de intercepción (iterado con la velocidad media esperada)
            val vAvg = 0.5f * (sp + m.vmax)
            var tgo = dist / vAvg
            repeat(3) {
                t[0] = m.last[0] + tv[0] * tgo; t[1] = m.last[1] + tv[1] * tgo; t[2] = m.last[2] + tv[2] * tgo
                tgo = sqrt((t[0] - m.x) * (t[0] - m.x) + (t[1] - m.y) * (t[1] - m.y) + (t[2] - m.z) * (t[2] - m.z)) / vAvg
            }
            t[0] = m.last[0] + tv[0] * tgo; t[1] = m.last[1] + tv[1] * tgo; t[2] = m.last[2] + tv[2] * tgo
            val hd = sqrt((t[0] - m.x) * (t[0] - m.x) + (t[1] - m.y) * (t[1] - m.y))
            val h = m.apex * ((hd - 0.18f * m.hd0) / (0.45f * m.hd0)).coerceIn(0f, 1f)
            var ax = t[0] - m.x; var ay = t[1] - m.y; var az = t[2] + h - m.z
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-3f)
            ax /= al; ay /= al; az /= al
            var cx = m.vx / sp; var cy = m.vy / sp; var cz = m.vz / sp
            // gira hacia la dirección deseada con un límite de velocidad angular
            val dot = (cx * ax + cy * ay + cz * az).coerceIn(-1f, 1f)
            val ang = kotlin.math.acos(dot)
            val maxA = MAX_TURN * dt
            if (ang > 1e-4f) {
                val k = min(1f, maxA / ang)
                cx += (ax - cx) * k; cy += (ay - cy) * k; cz += (az - cz) * k
                val cl = sqrt(cx * cx + cy * cy + cz * cz)
                cx /= cl; cy /= cl; cz /= cl
            }
            val newSp = if (m.burning) min(m.vmax, sp + ACCEL * dt) else sp * (1f - 0.08f * dt)
            m.vx = cx * newSp; m.vy = cy * newSp; m.vz = cz * newSp

            // estela de humo del motor
            if (m.burning) {
                m.smokeTimer -= dt
                if (m.smokeTimer <= 0f) {
                    m.smokeTimer = 0.025f
                    effects.smoke(m.x - cx * 0.8f, m.y - cy * 0.8f, m.z - cz * 0.8f, 0.45f, 2.6f, 0.7f)
                }
            }

            // ---- espoleta ----
            val hitR = when (val tg = m.target) {
                is AirTarget -> max(4f, tg.a.model.hitRadius * 0.9f)
                is ObjTarget -> 2.2f
            }
            val closing = dx * m.vx + dy * m.vy + dz * m.vz
            if (dist < hitR || (dist < hitR * 4f && closing < 0f)) {
                detonate(m, effects, sound, aircraft, projector); return
            }
        }
        m.x += m.vx * dt; m.y += m.vy * dt; m.z += m.vz * dt
        if (m.z < -1.6f && m.age > 0.6f) { detonate(m, effects, sound, aircraft, projector); return }   // suelo
        if (m.age > MAX_FLIGHT) { detonate(m, effects, sound, aircraft, projector); return }
    }

    private fun detonate(m: Missile, effects: Effects, sound: PhalanxSound?, aircraft: AircraftManager, projector: Projector) {
        m.alive = false
        val tg = m.target
        when {
            tg is AirTarget && tg.valid -> {
                aircraft.damage(tg.a, 99, effects, sound)
                hits++
            }
            tg is ObjTarget && tg.valid -> {
                // explosión grande, proporcional al tamaño del blanco en pantalla
                val tr = tg.track
                val boxPx = max(tr.rect.width(), tr.rect.height())
                val radiusM = (boxPx / projector.focalPx * tr.range * 0.9f).coerceIn(1.5f, 20f)
                effects.explode(m.last[0], m.last[1], m.last[2], radiusM, 1.2f)
                effects.explode(m.last[0], m.last[1], m.last[2] + radiusM * 0.6f, radiusM * 0.7f, 1.0f)
                for (k in 0 until 4) effects.smoke(m.last[0], m.last[1], m.last[2] + radiusM * k * 0.4f, radiusM * 0.7f, 5f, 0.85f)
                tr.flashUntil = System.nanoTime() + 400_000_000L
                tr.hits++
                sound?.explosion(tr.range, 1f)
                hits++
            }
            else -> {
                effects.explode(m.x, m.y, m.z, 4f, 1f)
                effects.smoke(m.x, m.y, m.z, 3f, 4f, 0.8f)
                sound?.explosion(m.range, 0.7f)
            }
        }
    }

    // ------------------------------- dibujo -------------------------------
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 76, 56); strokeCap = Paint.Cap.ROUND }
    private val flare = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(255, 255, 250, 225), Color.argb(170, 255, 170, 60), Color.argb(0, 255, 90, 0)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
    }
    private val p0 = FloatArray(2)
    private val p1 = FloatArray(2)

    fun draw(c: Canvas, projector: Projector, r: FloatArray) {
        for (m in missiles) {
            val sp = sqrt(m.vx * m.vx + m.vy * m.vy + m.vz * m.vz).coerceAtLeast(0.1f)
            val k = 1.1f / sp
            if (!projector.worldToScreen(r, m.x, m.y, m.z, p0)) continue
            if (projector.worldToScreen(r, m.x - m.vx * k, m.y - m.vy * k, m.z - m.vz * k, p1)) {
                body.strokeWidth = (0.13f * projector.focalPx / m.range.coerceAtLeast(0.5f)).coerceIn(1.5f, 40f)
                c.drawLine(p1[0], p1[1], p0[0], p0[1], body)
            } else { p1[0] = p0[0]; p1[1] = p0[1] }
            if (m.burning) {
                val rad = (0.7f * projector.focalPx / m.range.coerceAtLeast(0.5f)).coerceIn(4f, 120f)
                c.save(); c.translate(p1[0], p1[1]); c.scale(rad, rad)
                c.drawCircle(0f, 0f, 1f, flare)
                c.restore()
            }
        }
    }
}
