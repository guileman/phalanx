package com.guileman.phalanx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Capa HUD dibujada encima de la cámara. También corre el "game loop":
 * en cada frame avanza el arma, los proyectiles/misiles, los aviones y el seguimiento de blancos.
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------- dependencias (las asigna MainActivity) ----------
    var weapon = Weapon.PHALANX
    lateinit var gun: GunController
    lateinit var orientation: OrientationTracker
    var detectorReady = true
    var onGunStateChanged: ((GunState) -> Unit)? = null
    /** Se lanzó un misil (vibración y destello). */
    var onMissileLaunched: (() -> Unit)? = null
    /** Vista de la cámara: se sacude, se desenfoca y puede pasar a vista térmica. */
    var cameraView: View? = null
    /** Motor de audio. */
    var sound: PhalanxSound? = null

    val aircraft = AircraftManager()
    val javelin = JavelinSystem()
    /** TAM u obús (lo asigna MainActivity). */
    var cannon: CannonSystem? = null
    /** Se disparó el cañón pesado (vibración y destello). */
    var onCannonFired: (() -> Unit)? = null
    /** Obús: el panel derecho muestra el dron observador en vez del mapa. */
    var showDrone = false
    private val effects = Effects(resources)

    /** Vista térmica simulada (Javelin). */
    var thermal = false
    /** Campo estrecho (zoom) del Javelin. */
    val narrowFov get() = projector.zoom > 1.01f

    val projector = Projector()
    private val ballistics = Ballistics()
    private var horizontalFovRad = Math.toRadians(66.0)

    fun setHorizontalFov(rad: Double) {
        horizontalFovRad = rad
        if (width > 0) projector.setFov(width, height, rad)
    }

    fun setZoom(z: Float) { projector.zoom = z }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        projector.setFov(w, h, horizontalFovRad)
    }

    // ---------- estado ----------
    private val r = FloatArray(9)
    private val prevR = FloatArray(9)
    private var havePrevR = false
    /** Velocidad angular del celular = velocidad de giro de la torreta (rad/s, suavizada). */
    private var slewRate = 0f
    private var lastNanos = 0L
    private var lastGunState = GunState.SAFE
    private var hits = 0
    private val rnd = Random(42)
    private val p0 = FloatArray(2)
    private val p1 = FloatArray(2)
    private val v3 = FloatArray(3)

    @Volatile private var pendingFrame: DetectionFrame? = null
    fun submitDetections(frame: DetectionFrame) { pendingFrame = frame }

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1
    private var locked: Track? = null
    private var lastObjectBoomSound = 0L
    private var lastFlakSound = 0L

    // dirección de tiro del Gepard (telémetro / espoleta)
    private var fcRange = 0f
    private var fcSource = "---"
    private var muzzleFlashSide = 0
    private var muzzleFlashTimer = 0f
    private var launchKick = 0f

    // ---------- pinturas ----------
    private val dp = resources.displayMetrics.density
    private val hudColor = Color.rgb(0x39, 0xFF, 0x6A)
    private val warnColor = Color.rgb(0xFF, 0xB0, 0x20)
    private val alertColor = Color.rgb(0xFF, 0x3B, 0x30)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; color = hudColor
        strokeCap = Paint.Cap.ROUND
    }
    private val lineDash = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = hudColor }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hudColor; textSize = 13f * dp; typeface = mono
        setShadowLayer(3f * dp, 0f, 0f, Color.BLACK)
    }
    private val textSmall = Paint(text).apply { textSize = 11f * dp }
    private val textBig = Paint(text).apply { textSize = 18f * dp }
    private val panelBg = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val tracerCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val tracerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(230, 255, 245, 200), Color.argb(150, 255, 160, 40), Color.argb(0, 255, 90, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val path = Path()

    // =====================================================================
    //                              GAME LOOP
    // =====================================================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::gun.isInitialized || !::orientation.isInitialized) return

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, 0.05)
        val dtf = dt.toFloat()
        lastNanos = now
        orientation.copyMatrix(r)
        updateSlewRate(dtf)
        val att = Projector.attitude(r)

        // --- arma ---
        if (weapon.isGun) {
            updateFireControl()
            val shots = gun.update(dt, now)
            ballistics.update(dtf)
            spawnShots(shots, dt)
            handleBursts(now)
            if (gun.state != lastGunState) {
                lastGunState = gun.state
                onGunStateChanged?.invoke(gun.state)
            }
        }

        val cn = cannon
        if (weapon.isCannon && cn != null) {
            cn.update(dtf)
            if (cn.justLoaded) sound?.clank()
            ballistics.update(dtf)
            clock += dtf
            if (weapon == Weapon.TAM) updateLaserTracking()
        }

        aircraft.update(dtf, att.azimuthDeg, att.elevationDeg, effects, sound)
        if (weapon.usesBallistics) hits += aircraft.checkHits(ballistics, dtf, effects, sound, now, if (weapon.isGun) gun.cfg.damage else 1)
        effects.update(dtf)
        updateTracks(now)
        if (weapon.usesBallistics) checkHits(now)
        if (weapon.isCannon) handleImpacts(now)
        if (weapon == Weapon.OBUS) updatePrediction()
        if (weapon == Weapon.JAVELIN) javelin.update(dtf, lockCandidates(), gateRect(), effects, sound, aircraft, projector, r)

        val firing = weapon.isGun && gun.state == GunState.FIRING
        applyCameraEffects(firing, dtf)
        if (muzzleFlashTimer > 0f) muzzleFlashTimer -= dtf

        canvas.save()
        if (firing) canvas.translate((rnd.nextFloat() - 0.5f) * 3f * dp, (rnd.nextFloat() - 0.5f) * 3f * dp)

        if (weapon.isGun || weapon == Weapon.OBUS) drawPitchLadder(canvas, att)
        if (weapon == Weapon.OBUS) drawPrediction(canvas)
        aircraft.draw(canvas, projector, r)
        effects.draw(canvas, projector, r)
        if (weapon.usesBallistics) drawTracers(canvas)
        if (weapon == Weapon.JAVELIN) javelin.draw(canvas, projector, r)
        drawTracks(canvas, now)
        drawAircraftMarkers(canvas)
        when (weapon) {
            Weapon.JAVELIN -> drawJavelinReticle(canvas)
            Weapon.TAM -> drawTamReticle(canvas)
            Weapon.OBUS -> drawObusReticle(canvas)
            else -> drawCrosshair(canvas)
        }
        drawHeadingTape(canvas, att)
        if (weapon.isGun) drawBankScale(canvas, att)
        drawLeftPanel(canvas, att, now)
        drawRightPanel(canvas)
        drawMessages(canvas, att)
        canvas.restore()
        if (firing || muzzleFlashTimer > 0f) drawMuzzleFlash(canvas)

        postInvalidateOnAnimation()
    }

    // =====================================================================
    //                              CAÑONES
    // =====================================================================
    private fun spawnShots(shots: Int, dt: Double) {
        val cfg = gun.cfg
        for (k in 0 until shots) {
            // reparte los disparos del frame en el tiempo: el más viejo va más adelantado
            val ahead = (dt * (shots - k - 0.5) / shots).toFloat()
            val side = gun.nextMuzzle % cfg.muzzles.size
            gun.nextMuzzle++
            val aheadAmmo = gun.ahead
            val fuse = if (aheadAmmo) (if (fcRange > 0f) fcRange else 1500f) else 0f
            ballistics.spawn(projector, r, ahead, cfg.muzzles[side],
                if (aheadAmmo) cfg.aheadVelocity else cfg.muzzleVelocity, fuse, weapon == Weapon.GEPARD)
            if (weapon == Weapon.GEPARD) { muzzleFlashSide = side; muzzleFlashTimer = 0.035f }
        }
    }

    /**
     * Dirección de tiro (Gepard): el telémetro mide el avión más cercano a la mira (calculando el punto
     * de intercepción) o el objeto bloqueado; con eso se programa la espoleta AHEAD.
     */
    private val pipTmp = FloatArray(3)
    private fun updateFireControl() {
        if (weapon != Weapon.GEPARD) return
        projector.screenToWorldDir(r, width / 2f, height / 2f, v3)
        var best: Aircraft? = null
        var bestAng = Math.toRadians(6.0).toFloat()
        for (a in aircraft.list) {
            if (a.down) continue
            aircraft.leadPoint(a, pipTmp)
            val d = sqrt(pipTmp[0] * pipTmp[0] + pipTmp[1] * pipTmp[1] + pipTmp[2] * pipTmp[2])
            val dot = (pipTmp[0] * v3[0] + pipTmp[1] * v3[1] + pipTmp[2] * v3[2]) / d
            val ang = kotlin.math.acos(dot.coerceIn(-1f, 1f))
            if (ang < bestAng) { bestAng = ang; best = a; fcRange = d }
        }
        if (best != null) { fcSource = "AVIÓN"; return }
        val t = locked
        if (t != null) { fcRange = t.range; fcSource = "OBJETO"; return }
        fcRange = 0f; fcSource = "---"
    }

    /** Estallidos AHEAD: nube de fragmentos en cono (~10°) hacia adelante. */
    private fun handleBursts(now: Long) {
        val b = ballistics
        val cone = tan(Math.toRadians(10.0)).toFloat()
        for (k in 0 until b.burstCount) {
            val o = k * 6
            val bx = b.bursts[o]; val by = b.bursts[o + 1]; val bz = b.bursts[o + 2]
            val vx = b.bursts[o + 3]; val vy = b.bursts[o + 4]; val vz = b.bursts[o + 5]
            effects.flak(bx, by, bz, vx, vy, vz)
            val dist = sqrt(bx * bx + by * by + bz * bz)
            if (now - lastFlakSound > 60_000_000L) { lastFlakSound = now; sound?.explosion(dist, 0.1f) }
            val sp = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1f)
            val ux = vx / sp; val uy = vy / sp; val uz = vz / sp
            for (a in aircraft.list) {
                if (a.down) continue
                val dx = a.x - bx; val dy = a.y - by; val dz = a.z - bz
                val along = dx * ux + dy * uy + dz * uz
                val rr = a.model.hitRadius
                if (along < -rr || along > 45f) continue
                val perp = sqrt(max(0f, dx * dx + dy * dy + dz * dz - along * along))
                if (perp < cone * max(along, 0f) + rr) {
                    hits++
                    effects.explode(a.x - dx * 0.3f, a.y - dy * 0.3f, a.z - dz * 0.3f, 2f, 0.4f)
                    aircraft.damage(a, 2, effects, sound)
                }
            }
        }
    }

    // =====================================================================
    //                  CAÑONES PESADOS: TAM 105 mm Y OBÚS 155 mm
    // =====================================================================
    private var clock = 0f
    private val cv = FloatArray(3)
    private val cm = FloatArray(3)

    // telémetro láser y computadora de tiro del TAM
    private var laseRange = 0f
    private var laseSource = "---"
    private var laseTarget: Aircraft? = null
    private val lasePoint = FloatArray(3)
    private var laseTime = -99f
    /** Corrección de elevación aplicada por la computadora en el último cálculo (mils). */
    private var superElevMils = 0f
    private var leadMils = 0f

    // obús: predicción del impacto y proyectiles en vuelo
    private val predImpact = FloatArray(3)
    private var predValid = false
    private var predTof = 0f
    private var predRange = 0f
    private val predPath = FloatArray(3 * 160)
    private var predPathN = 0
    private class Shell(val launch: Float, val tof: Float, val ix: Float, val iy: Float) { var splash = false }
    private val shellsInFlight = ArrayList<Shell>()
    private class Impact(val x: Float, val y: Float, val t: Float)
    private val recentImpacts = ArrayList<Impact>()
    private var focusTarget: Aircraft? = null

    /** Botón FUEGO del TAM / obús. */
    fun fireCannon() {
        val cn = cannon ?: return
        if (!cn.ready) { sound?.denied(); return }
        val a = cn.fire() ?: return
        val obus = weapon == Weapon.OBUS
        // boca del cañón (abajo y adelante del visor)
        projector.deviceToWorld(r, 0f, if (obus) -0.6f else -0.35f, if (obus) -2.5f else -1.2f, cm)
        if (weapon == Weapon.TAM) tamAimDir(a, cv) else projector.deviceToWorld(r, 0f, 0f, -1f, cv)
        val speed = cn.muzzleSpeed
        ballistics.spawnDir(cm[0], cm[1], cm[2], cv[0], cv[1], cv[2], speed, a.kind,
            dragCoef = a.drag, lifetime = if (obus) 120f else 8f, damage = a.damage)
        // fogonazo y nube de humo/polvo en la boca: tapa la vista un instante, como en la realidad
        val n = sqrt(cv[0] * cv[0] + cv[1] * cv[1] + cv[2] * cv[2])
        val ux = cv[0] / n; val uy = cv[1] / n; val uz = cv[2] / n
        effects.explode(cm[0] + ux * 5f, cm[1] + uy * 5f, cm[2] + uz * 5f, if (obus) 2.6f else 1.8f, 0.3f)
        for (k in 0 until 4) {
            val d = 6f + k * 4f
            effects.smoke(cm[0] + ux * d + (rnd.nextFloat() - 0.5f) * 3f, cm[1] + uy * d + (rnd.nextFloat() - 0.5f) * 3f,
                cm[2] + uz * d, if (obus) 3.2f else 2.4f, 2.2f, 0.75f)
        }
        sound?.cannonShot(if (obus) 1f else 0.7f)
        launchKick = 1.5f
        muzzleFlashTimer = 0.09f
        if (obus && predValid) shellsInFlight += Shell(clock, predTof, predImpact[0], predImpact[1])
        onCannonFired?.invoke()
    }

    /** Telémetro láser: mide la distancia a lo que está en el centro de la mira. */
    fun lase() {
        projector.deviceToWorld(r, 0f, 0f, -1f, cv)
        val cx = cv[0]; val cy = cv[1]; val cz = cv[2]
        var best = Float.MAX_VALUE
        var src = "SIN ECO"
        var tgt: Aircraft? = null
        for (a in aircraft.list) {
            if (a.down) continue
            val t = a.x * cx + a.y * cy + a.z * cz
            if (t <= 0f) continue
            val hr = a.model.hitRadius
            val perp2 = a.x * a.x + a.y * a.y + a.z * a.z - t * t
            if (perp2 < hr * hr) {
                val d = t - sqrt(hr * hr - perp2)
                if (d < best) { best = d; src = a.model.name; tgt = a }
            }
        }
        for (t in tracks) {
            if (!t.visible || !t.rect.contains(width / 2f, height / 2f)) continue
            if (t.range < best) { best = t.range; src = TargetClasses.display(t.label); tgt = null }
        }
        if (best == Float.MAX_VALUE && cz < -0.001f) {
            best = AircraftManager.GROUND_Z / cz
            src = "SUELO"
        }
        sound?.beep()
        laseTime = clock
        if (best == Float.MAX_VALUE || best > 9990f) {
            laseRange = 0f; laseSource = "SIN ECO"; laseTarget = null
            return
        }
        laseRange = best; laseSource = src; laseTarget = tgt
        lasePoint[0] = cx * best; lasePoint[1] = cy * best; lasePoint[2] = cz * best
    }

    /** Si se midió un vehículo, la computadora lo sigue mientras siga cerca de la mira. */
    private fun updateLaserTracking() {
        val t = laseTarget ?: return
        if (t.down || t !in aircraft.list) { laseTarget = null; return }
        projector.deviceToWorld(r, 0f, 0f, -1f, cv)
        val d = t.range
        val dot = (t.x * cv[0] + t.y * cv[1] + t.z * cv[2]) / d
        if (dot < cos(Math.toRadians(4.0)).toFloat()) { laseTarget = null; return }
        laseRange = d
    }

    /**
     * Computadora de tiro del TAM: con la distancia del láser calcula la elevación extra (caída del proyectil)
     * y el adelanto si el blanco medido se mueve. Sin medición, el disparo sale derecho por la mira.
     */
    private fun tamAimDir(a: CannonSystem.Ammo, out: FloatArray) {
        projector.deviceToWorld(r, 0f, 0f, -1f, out)
        superElevMils = 0f; leadMils = 0f
        if (laseRange <= 0f) return
        val n0 = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        val c0 = out[0] / n0; val c1 = out[1] / n0; val c2 = out[2] / n0
        val R = laseRange
        val t = laseTarget
        var dx = c0; var dy = c1; var dz = c2
        repeat(3) {
            val (drop, tof) = simulateDrop(dx, dy, dz, a.speed, a.drag, R)
            var px = c0 * R; var py = c1 * R; var pz = c2 * R
            if (t != null) { px += t.vx * tof; py += t.vy * tof; pz += t.vz * tof }
            pz += drop
            val ax = px - cm[0]; val ay = py - cm[1]; val az = pz - cm[2]
            val l = sqrt(ax * ax + ay * ay + az * az)
            dx = ax / l; dy = ay / l; dz = az / l
        }
        val elevNew = kotlin.math.asin(dz.coerceIn(-1f, 1f)); val elevOld = kotlin.math.asin(c2.coerceIn(-1f, 1f))
        superElevMils = Math.toDegrees((elevNew - elevOld).toDouble()).toFloat() * MILS_PER_DEG
        val azNew = kotlin.math.atan2(dx, dy); val azOld = kotlin.math.atan2(c0, c1)
        leadMils = Math.toDegrees((azNew - azOld).toDouble()).toFloat() * MILS_PER_DEG
        out[0] = dx; out[1] = dy; out[2] = dz
    }

    /** Caída vertical respecto de la línea recta y tiempo de vuelo hasta recorrer [dist] metros. */
    private fun simulateDrop(dx: Float, dy: Float, dz: Float, speed: Float, dragC: Float, dist: Float): Pair<Float, Float> {
        var x = 0f; var y = 0f; var z = 0f
        var vx = dx * speed; var vy = dy * speed; var vz = dz * speed
        var t = 0f
        val h = 0.004f
        while (x * x + y * y + z * z < dist * dist && t < 20f) {
            val d = exp(-dragC * h)
            vx *= d; vy *= d; vz = vz * d - Ballistics.GRAVITY * h
            x += vx * h; y += vy * h; z += vz * h; t += h
        }
        val s = sqrt(x * x + y * y + z * z)
        return Pair(dz * s - z, t)
    }

    /** Explosiones de los proyectiles pesados (suelo o vehículos), con daño en área. */
    private fun handleImpacts(now: Long) {
        val b = ballistics
        val cn = cannon ?: return
        for (k in 0 until b.impactCount) {
            val o = k * 4
            val ix = b.impacts[o]; val iy = b.impacts[o + 1]; val iz = b.impacts[o + 2]
            val kind = b.impacts[o + 3].toInt()
            val dist = sqrt(ix * ix + iy * iy + iz * iz)
            val ammo = cn.ammo.firstOrNull { it.kind == kind }
            if (Ballistics.explosive(kind) && ammo != null) {
                blast(ix, iy, iz, ammo.blastRadius, ammo.blastDamage, kind == Ballistics.K_155_HE, now)
            } else {
                // perforante: chispazo y polvo
                effects.explode(ix, iy, iz + 0.5f, 1.4f, 0.4f)
                effects.smoke(ix, iy, iz + 1f, 2f, 2.5f, 0.6f)
                sound?.explosion(dist, 0.2f)
            }
            if (weapon == Weapon.OBUS) {
                recentImpacts += Impact(ix, iy, clock)
                shellsInFlight.minByOrNull { (it.ix - ix) * (it.ix - ix) + (it.iy - iy) * (it.iy - iy) }
                    ?.let { shellsInFlight.remove(it) }
            }
        }
        recentImpacts.removeAll { clock - it.t > 30f }
        shellsInFlight.removeAll { clock - it.launch > it.tof + 3f }
    }

    /** Explosión de granada: efecto proporcional al calibre y daño a todo lo que esté dentro del radio. */
    private fun blast(x: Float, y: Float, z: Float, radius: Float, damage: Int, big: Boolean, now: Long) {
        effects.explode(x, y, z + radius * 0.25f, radius * 0.6f, if (big) 1.5f else 1.1f)
        effects.explode(x, y, z + radius * 0.5f, radius * 0.4f, 1.2f)
        for (k in 0 until (if (big) 6 else 3)) {
            effects.smoke(x + (rnd.nextFloat() - 0.5f) * radius, y + (rnd.nextFloat() - 0.5f) * radius,
                z + radius * (0.2f + k * 0.25f), radius * 0.45f, if (big) 8f else 5f, 0.85f)
        }
        val dist = sqrt(x * x + y * y + z * z)
        sound?.explosion(dist, if (big) 1f else 0.6f)
        for (a in aircraft.list) {
            if (a.down) continue
            val dx = a.x - x; val dy = a.y - y; val dz = a.z - z
            val d = sqrt(dx * dx + dy * dy + dz * dz) - a.model.hitRadius
            if (d < radius) {
                val dmg = if (d < radius * 0.5f) damage else (damage + 1) / 2
                hits++
                aircraft.damage(a, dmg, effects, sound)
            }
        }
        for (t in tracks) {
            if (!t.visible) continue
            val tx = t.dir[0] * t.range - x; val ty = t.dir[1] * t.range - y; val tz = t.dir[2] * t.range - z
            if (tx * tx + ty * ty + tz * tz < radius * radius) {
                t.hits++; t.flashUntil = now + 300_000_000L; hits++
            }
        }
    }

    /** Proyectil pesado: punto brillante con estela corta (trazadora del tanque / proyectil del obús). */
    private fun drawShell(c: Canvas, i: Int, d: Float, speed: Float) {
        val b = ballistics
        val obus = b.kind[i] == Ballistics.K_155_HE
        val tail = min(speed * (if (obus) 0.12f else 0.025f), d - 0.5f)
        val inv = tail / speed
        if (!projector.worldToScreen(r, b.x[i], b.y[i], b.z[i], p0)) return
        if (!projector.worldToScreen(r, b.x[i] - b.vx[i] * inv, b.y[i] - b.vy[i] * inv, b.z[i] - b.vz[i] * inv, p1)) return
        val w = (projector.focalPx * 0.25f / d).coerceIn(2.5f * dp, 10f * dp)
        tracerGlow.color = if (obus) Color.argb(110, 255, 210, 140) else Color.argb(120, 255, 80, 30)
        tracerGlow.strokeWidth = w * 3.2f
        c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerGlow)
        tracerCore.color = if (obus) Color.rgb(255, 240, 200) else Color.rgb(255, 150, 90)
        tracerCore.strokeWidth = w
        c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerCore)
    }

    /** Obús: simula la trayectoria con la elevación y la carga actuales para saber dónde va a caer. */
    private fun updatePrediction() {
        val cn = cannon ?: return
        projector.deviceToWorld(r, 0f, -0.6f, -2.5f, cm)
        projector.deviceToWorld(r, 0f, 0f, -1f, cv)
        val n = sqrt(cv[0] * cv[0] + cv[1] * cv[1] + cv[2] * cv[2])
        val v = cn.muzzleSpeed
        var x = cm[0]; var y = cm[1]; var z = cm[2]
        var vx = cv[0] / n * v; var vy = cv[1] / n * v; var vz = cv[2] / n * v
        var t = 0f
        val h = 0.05f
        val d = exp(-CannonSystem.OBUS_DRAG * h)
        predPathN = 0
        predValid = false
        var sample = 0f
        while (t < 120f) {
            val pxx = x; val pyy = y; val pzz = z
            vx *= d; vy *= d; vz = vz * d - Ballistics.GRAVITY * h
            x += vx * h; y += vy * h; z += vz * h; t += h
            sample += h
            if (sample >= 0.5f && predPathN < predPath.size / 3) {
                sample = 0f
                predPath[predPathN * 3] = x; predPath[predPathN * 3 + 1] = y; predPath[predPathN * 3 + 2] = z
                predPathN++
            }
            if (z < AircraftManager.GROUND_Z && vz < 0f) {
                val f = ((pzz - AircraftManager.GROUND_Z) / (pzz - z)).coerceIn(0f, 1f)
                predImpact[0] = pxx + (x - pxx) * f; predImpact[1] = pyy + (y - pyy) * f; predImpact[2] = AircraftManager.GROUND_Z
                predTof = t; predRange = sqrt(predImpact[0] * predImpact[0] + predImpact[1] * predImpact[1])
                predValid = true
                break
            }
        }
        // blanco más cercano al punto de impacto (para las correcciones)
        focusTarget = aircraft.list.filter { it.model.ground && !it.down }
            .minByOrNull { (it.x - predImpact[0]) * (it.x - predImpact[0]) + (it.y - predImpact[1]) * (it.y - predImpact[1]) }
        // aviso "splash" 5 s antes de cada impacto
        for (sh in shellsInFlight) {
            if (!sh.splash && clock - sh.launch >= sh.tof - 5f) { sh.splash = true; sound?.beep() }
        }
    }

    /** Obús: trayectoria prevista (punteada) y marca de impacto sobre el suelo real. */
    private fun drawPrediction(c: Canvas) {
        if (!predValid) return
        lineDash.color = warnColor; lineDash.alpha = 170
        var have = false
        for (k in 0 until predPathN) {
            val ok = projector.worldToScreen(r, predPath[3 * k], predPath[3 * k + 1], predPath[3 * k + 2], p1)
            if (ok && have) c.drawLine(p0[0], p0[1], p1[0], p1[1], lineDash)
            if (ok) { p0[0] = p1[0]; p0[1] = p1[1] }
            have = ok
        }
        lineDash.color = hudColor; lineDash.alpha = 255
        // círculo de 25 m y cruz sobre el suelo
        line.color = warnColor
        val ix = predImpact[0]; val iy = predImpact[1]; val iz = predImpact[2]
        have = false
        for (k in 0..24) {
            val a = 2.0 * PI * k / 24
            val ok = projector.worldToScreen(r, ix + 25f * cos(a).toFloat(), iy + 25f * sin(a).toFloat(), iz, p1)
            if (ok && have) c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
            if (ok) { p0[0] = p1[0]; p0[1] = p1[1] }
            have = ok
        }
        if (projector.worldToScreen(r, ix - 30f, iy, iz, p0) && projector.worldToScreen(r, ix + 30f, iy, iz, p1)) c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
        if (projector.worldToScreen(r, ix, iy - 30f, iz, p0) && projector.worldToScreen(r, ix, iy + 30f, iz, p1)) c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
        if (projector.worldToScreen(r, ix, iy, iz, p0)) {
            textSmall.color = warnColor
            c.drawText("IMPACTO %.1f km · %.0f s".format(predRange / 1000f, predTof), p0[0] + 8 * dp, p0[1] - 8 * dp, textSmall)
            textSmall.color = hudColor
        }
        line.color = hudColor
    }

    /** Mira del artillero del TAM: chevrón, escala en milésimas, distancia del láser y estado del cañón. */
    private fun drawTamReticle(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val cn = cannon ?: return
        val col = if (cn.ready) hudColor else warnColor
        line.color = col; textSmall.color = col; text.color = col
        // chevrón con el vértice en el centro
        path.reset()
        path.moveTo(cx - 16 * dp, cy + 16 * dp); path.lineTo(cx, cy); path.lineTo(cx + 16 * dp, cy + 16 * dp)
        c.drawPath(path, line)
        // línea horizontal con marcas cada 4 milésimas
        val pxPerMil = projector.focalPx * (Math.PI / 3200.0).toFloat()
        c.drawLine(cx - 150 * dp, cy, cx - 30 * dp, cy, line)
        c.drawLine(cx + 30 * dp, cy, cx + 150 * dp, cy, line)
        for (m in -40..40 step 4) {
            if (m == 0) continue
            val x = cx + m * pxPerMil
            if (abs(x - cx) < 30 * dp || abs(x - cx) > 150 * dp) continue
            c.drawLine(x, cy - (if (m % 20 == 0) 7 else 4) * dp, x, cy, line)
        }
        c.drawLine(cx, cy + 30 * dp, cx, cy + 60 * dp, line)
        // lecturas bajo la mira
        val rng = if (laseRange > 0f) "%04d".format(laseRange.roundToInt().coerceAtMost(9999)) else "----"
        c.drawText(rng, cx - text.measureText(rng) / 2, cy + 78 * dp, text)
        val st = when {
            cn.current.count == 0 -> "SIN ${cn.current.label}"
            cn.ready -> "● ${cn.current.label} LISTO"
            else -> "CARGANDO ${cn.current.label} %.1fs".format(cn.reloadLeft)
        }
        c.drawText(st, cx - textSmall.measureText(st) / 2, cy + 94 * dp, textSmall)
        // punto del láser recién medido
        if (laseRange > 0f && clock - laseTime < 0.6f && projector.worldToScreen(r, lasePoint[0], lasePoint[1], lasePoint[2], p0)) {
            fill.color = alertColor
            c.drawCircle(p0[0], p0[1], 4 * dp, fill)
            fill.color = hudColor
        }
        line.color = hudColor; textSmall.color = hudColor; text.color = hudColor
    }

    /** Mira panorámica del obús: cruz con escala en milésimas. */
    private fun drawObusReticle(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val cn = cannon ?: return
        line.color = if (cn.ready) hudColor else warnColor
        c.drawLine(cx - 60 * dp, cy, cx - 8 * dp, cy, line)
        c.drawLine(cx + 8 * dp, cy, cx + 60 * dp, cy, line)
        c.drawLine(cx, cy - 60 * dp, cx, cy - 8 * dp, line)
        c.drawLine(cx, cy + 8 * dp, cx, cy + 60 * dp, line)
        val pxPerMil = projector.focalPx * (Math.PI / 3200.0).toFloat()
        for (m in -50..50 step 10) {
            if (m == 0) continue
            val o = m * pxPerMil
            if (abs(o) > 60 * dp) continue
            c.drawLine(cx + o, cy - 4 * dp, cx + o, cy + 4 * dp, line)
            c.drawLine(cx - 4 * dp, cy + o, cx + 4 * dp, cy + o, line)
        }
        line.color = hudColor
    }

    /** Obús: mapa de tiro (vista desde arriba) o dron observador sobre el blanco. */
    private fun drawObusPanel(c: Canvas, x: Float, y: Float, size: Float) {
        c.drawRect(x, y, x + size, y + size, panelBg)
        line.color = hudColor
        c.drawRect(x, y, x + size, y + size, line)
        if (showDrone) drawDrone(c, x, y, size) else drawMap(c, x, y, size)
    }

    private fun drawMap(c: Canvas, x0: Float, y0: Float, size: Float) {
        val cx = x0 + size / 2; val cy = y0 + size / 2
        val maxR = 4000f
        val k = (size / 2 - 6 * dp) / maxR
        c.save(); c.clipRect(x0, y0, x0 + size, y0 + size)
        line.alpha = 90
        for (km in 1..4) c.drawCircle(cx, cy, km * 1000f * k, line)
        line.alpha = 255
        textSmall.color = hudColor
        for (km in 1..4) c.drawText("$km", cx + 2 * dp, cy - km * 1000f * k + 11 * dp, textSmall)
        c.drawText("N", cx - 4 * dp, y0 + 12 * dp, textSmall)
        // línea de tiro hasta el impacto previsto
        if (predValid) {
            val px = cx + predImpact[0] * k; val py = cy - predImpact[1] * k
            lineDash.color = warnColor
            c.drawLine(cx, cy, px, py, lineDash)
            lineDash.color = hudColor
            line.color = warnColor
            c.drawLine(px - 5 * dp, py - 5 * dp, px + 5 * dp, py + 5 * dp, line)
            c.drawLine(px - 5 * dp, py + 5 * dp, px + 5 * dp, py - 5 * dp, line)
            line.color = hudColor
        }
        // blancos terrestres
        for (a in aircraft.list) {
            if (!a.model.ground) continue
            val ax = cx + a.x * k; val ay = cy - a.y * k
            fill.color = if (a.down) Color.GRAY else alertColor
            c.drawRect(ax - 3.5f * dp, ay - 3.5f * dp, ax + 3.5f * dp, ay + 3.5f * dp, fill)
        }
        // impactos recientes y proyectiles en vuelo
        for (im in recentImpacts) {
            val a = (1f - (clock - im.t) / 30f).coerceIn(0f, 1f)
            line.color = Color.argb((255 * a).toInt(), 255, 140, 40)
            c.drawCircle(cx + im.x * k, cy - im.y * k, 5 * dp, line)
        }
        line.color = hudColor
        val b = ballistics
        fill.color = Color.rgb(255, 230, 160)
        for (i in 0 until b.cap) {
            if (!b.alive[i] || b.kind[i] != Ballistics.K_155_HE) continue
            c.drawCircle(cx + b.x[i] * k, cy - b.y[i] * k, 2.2f * dp, fill)
        }
        // pieza (jugador) apuntando al azimut actual
        val az = Math.toRadians(Projector.attitude(r).azimuthDeg.toDouble())
        fill.color = hudColor
        path.reset()
        path.moveTo(cx + (9 * dp * sin(az)).toFloat(), cy - (9 * dp * cos(az)).toFloat())
        path.lineTo(cx + (5 * dp * sin(az + 2.4)).toFloat(), cy - (5 * dp * cos(az + 2.4)).toFloat())
        path.lineTo(cx + (5 * dp * sin(az - 2.4)).toFloat(), cy - (5 * dp * cos(az - 2.4)).toFloat())
        path.close()
        c.drawPath(path, fill)
        c.restore()
        textSmall.color = hudColor
        c.drawText("MAPA DE TIRO · 4 km", x0 + 6 * dp, y0 + size - 6 * dp, textSmall)
    }

    // ----- dron observador: segunda cámara virtual que mira el blanco desde arriba -----
    private val droneProj = Projector()
    private val droneR = FloatArray(9)

    private fun drawDrone(c: Canvas, x0: Float, y0: Float, size: Float) {
        val ft = focusTarget
        val fx = ft?.x ?: predImpact[0]; val fy = ft?.y ?: predImpact[1]
        if (!predValid && ft == null) {
            textSmall.color = warnColor
            c.drawText("DRON: SIN BLANCO", x0 + 8 * dp, y0 + size / 2, textSmall)
            textSmall.color = hudColor
            return
        }
        // el dron vuela detrás del blanco (visto desde la pieza), alto y mirando hacia abajo
        val az = kotlin.math.atan2(fx, fy)
        val ex = fx - sin(az) * 110f; val ey = fy - cos(az) * 110f; val ez = 140f
        lookAt(ex, ey, ez, fx, fy, AircraftManager.GROUND_Z, droneR)
        droneProj.ox = ex; droneProj.oy = ey; droneProj.oz = ez
        droneProj.setSimple(size, size, Math.toRadians(55.0))
        c.save()
        c.clipRect(x0, y0, x0 + size, y0 + size)
        c.translate(x0, y0)
        fill.color = Color.rgb(46, 58, 40)
        c.drawRect(0f, 0f, size, size, fill)
        // grilla del terreno cada 25 m
        line.color = Color.argb(70, 0x39, 0xFF, 0x6A)
        val gz = AircraftManager.GROUND_Z
        for (g in -6..6) {
            val o = g * 25f
            if (droneProj.worldToScreen(droneR, fx + o, fy - 150f, gz, p0) && droneProj.worldToScreen(droneR, fx + o, fy + 150f, gz, p1))
                c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
            if (droneProj.worldToScreen(droneR, fx - 150f, fy + o, gz, p0) && droneProj.worldToScreen(droneR, fx + 150f, fy + o, gz, p1))
                c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
        }
        line.color = hudColor
        // impacto previsto
        if (predValid && droneProj.worldToScreen(droneR, predImpact[0], predImpact[1], gz, p0)) {
            line.color = warnColor
            c.drawCircle(p0[0], p0[1], 6 * dp, line)
            c.drawLine(p0[0] - 9 * dp, p0[1], p0[0] + 9 * dp, p0[1], line)
            c.drawLine(p0[0], p0[1] - 9 * dp, p0[0], p0[1] + 9 * dp, line)
            line.color = hudColor
        }
        aircraft.draw(c, droneProj, droneR, updateScreen = false)
        effects.draw(c, droneProj, droneR)
        // proyectiles cayendo
        val b = ballistics
        fill.color = Color.rgb(255, 240, 200)
        for (i in 0 until b.cap) {
            if (!b.alive[i] || b.kind[i] != Ballistics.K_155_HE) continue
            if (droneProj.worldToScreen(droneR, b.x[i], b.y[i], b.z[i], p0)) c.drawCircle(p0[0], p0[1], 2.5f * dp, fill)
        }
        c.restore()
        textSmall.color = hudColor
        val lbl = "DRON · ${ft?.model?.name ?: "IMPACTO"} %.1f km".format(sqrt(fx * fx + fy * fy) / 1000f)
        c.drawText(lbl, x0 + 6 * dp, y0 + size - 6 * dp, textSmall)
    }

    private fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, out: FloatArray) {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // derecha = adelante × arriba(mundo)
        var rx = fy * 1f - fz * 0f; var ry = fz * 0f - fx * 1f; var rz = 0f
        val rl = sqrt(rx * rx + ry * ry).coerceAtLeast(1e-4f); rx /= rl; ry /= rl
        // arriba = derecha × adelante
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        out[0] = rx; out[3] = ry; out[6] = rz
        out[1] = ux; out[4] = uy; out[7] = uz
        out[2] = -fx; out[5] = -fy; out[8] = -fz
    }

    // =====================================================================
    //                              JAVELIN
    // =====================================================================
    fun fireJavelin() {
        if (javelin.fire(projector, r, sound, effects)) {
            launchKick = 1f
            onMissileLaunched?.invoke()
        }
    }

    private fun lockCandidates(): List<LockTarget> {
        val out = ArrayList<LockTarget>(tracks.size + aircraft.list.size)
        for (t in tracks) if (t.visible) out += ObjTarget(t)
        for (a in aircraft.list) if (!a.down && a.onScreen) out += AirTarget(a, aircraft)
        return out
    }

    private val gate = RectF()
    private fun gateRect(): RectF {
        val half = height * 0.13f
        gate.set(width / 2f - half * 1.3f, height / 2f - half, width / 2f + half * 1.3f, height / 2f + half)
        return gate
    }

    /** Ángulo rotado entre frames: acos((traza(Rprev^T·R) − 1) / 2). */
    private fun updateSlewRate(dt: Float) {
        if (havePrevR && dt > 0f) {
            var tr = 0f
            for (i in 0 until 9) tr += prevR[i] * r[i]
            val ang = kotlin.math.acos(((tr - 1f) / 2f).coerceIn(-1f, 1f))
            val rate = ang / dt
            slewRate += (rate - slewRate) * min(1f, dt * 12f)
        }
        System.arraycopy(r, 0, prevR, 0, 9)
        havePrevR = true
        sound?.slewRate = if (slewRate > 0.12f) slewRate else 0f
    }

    // =====================================================================
    //          VIBRACIÓN, DESENFOQUE Y VISTA TÉRMICA DE LA CÁMARA
    // =====================================================================
    private var shake = 0f            // intensidad 0..1 (suavizada)
    private var appliedBlur = -1f
    private var appliedThermal = false

    private fun applyCameraEffects(firing: Boolean, dt: Float) {
        val cam = cameraView ?: return
        // sube rápido al empezar el fuego y se apaga en ~0.3 s al soltar; el lanzamiento da un golpe
        val target = max(if (firing) 1f else 0f, launchKick)
        val rate = if (target > shake) 18f else 7f
        shake += (target - shake) * min(1f, rate * dt)
        if (shake < 0.01f) shake = 0f
        launchKick = max(0f, launchKick - dt * 3f)

        val strength = when (weapon) {
            Weapon.GEPARD -> 1.3f; Weapon.JAVELIN -> 0.8f; Weapon.TAM -> 1.6f; Weapon.OBUS -> 2f; else -> 1f
        }
        val amp = 7f * dp * shake * strength
        cam.translationX = (rnd.nextFloat() - 0.5f) * 2f * amp
        cam.translationY = (rnd.nextFloat() - 0.5f) * 2f * amp
        cam.rotation = (rnd.nextFloat() - 0.5f) * 0.8f * shake
        // agrandar un poco para que no se vean bordes negros al sacudir
        val scale = 1f + 0.035f * shake
        cam.scaleX = scale; cam.scaleY = scale

        val blur = 9f * dp * shake * strength
        val blurChanged = abs(blur - appliedBlur) > 0.75f * dp || (blur == 0f && appliedBlur != 0f)
        if (!blurChanged && thermal == appliedThermal) return
        appliedBlur = blur
        appliedThermal = thermal
        if (Build.VERSION.SDK_INT >= 31) {
            val blurFx = if (blur < 0.5f) null
                else android.graphics.RenderEffect.createBlurEffect(blur * 0.55f, blur, Shader.TileMode.CLAMP)
            val thermalFx = if (thermal) android.graphics.RenderEffect.createColorFilterEffect(thermalFilter) else null
            cam.setRenderEffect(when {
                blurFx != null && thermalFx != null -> android.graphics.RenderEffect.createChainEffect(thermalFx, blurFx)
                else -> blurFx ?: thermalFx
            })
        } else {
            // Android < 12: sin desenfoque; la vista térmica se aplica con un filtro de capa
            cam.setLayerType(if (thermal) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE,
                if (thermal) Paint().apply { colorFilter = thermalFilter } else null)
        }
    }

    /** Monocromo de alto contraste con tinte verdoso, imitando el visor térmico de la CLU. */
    private val thermalFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        val c = 1.6f; val off = -0.28f * 255f
        val lr = 0.299f * c; val lg = 0.587f * c; val lb = 0.114f * c
        set(floatArrayOf(
            lr * 0.85f, lg * 0.85f, lb * 0.85f, 0f, off,
            lr, lg, lb, 0f, off,
            lr * 0.8f, lg * 0.8f, lb * 0.8f, 0f, off,
            0f, 0f, 0f, 1f, 0f,
        ))
    })

    // =====================================================================
    //                          SEGUIMIENTO DE BLANCOS
    // =====================================================================
    private fun updateTracks(now: Long) {
        val frame = pendingFrame
        if (frame != null) {
            pendingFrame = null
            ingest(frame, now)
        }
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (now - t.lastSeen > 450_000_000L) { t.gone = true; it.remove() }
        }

        val cx = width / 2f; val cy = height / 2f
        val f = projector.focalPx
        var best: Track? = null
        var bestD = 0.16f * width
        for (t in tracks) {
            t.visible = projector.worldToScreen(r, t.dir[0], t.dir[1], t.dir[2], p0)
            if (!t.visible) continue
            val w = t.angW * f; val h = t.angH * f
            t.rect.set(p0[0] - w / 2, p0[1] - h / 2, p0[0] + w / 2, p0[1] + h / 2)
            t.range = TargetClasses.realHeight(t.label) / max(t.angH, 1e-4f)
            val d = if (t.rect.contains(cx, cy)) 0f else sqrt((p0[0] - cx) * (p0[0] - cx) + (p0[1] - cy) * (p0[1] - cy))
            if (d < bestD) { bestD = d; best = t }
        }
        locked = if (weapon.isGun) best else null
    }

    /** Convierte detecciones de la imagen a pantalla y las ancla a una dirección del mundo. */
    private fun ingest(frame: DetectionFrame, now: Long) {
        if (width == 0) return
        // mapeo FILL_CENTER: imagen escalada hasta cubrir la vista, centrada
        val s = max(width / frame.imageWidth.toFloat(), height / frame.imageHeight.toFloat())
        val ox = (width - frame.imageWidth * s) / 2f
        val oy = (height - frame.imageHeight * s) / 2f
        val f = projector.focalPx
        val matched = HashSet<Track>()
        for (d in frame.detections) {
            val l = ox + d.box.left * s; val rr = ox + d.box.right * s
            val t = oy + d.box.top * s; val b = oy + d.box.bottom * s
            val aw = (rr - l) / f; val ah = (b - t) / f
            // dirección del centro del cuadro según la orientación AL MOMENTO DE LA CAPTURA
            projector.screenToWorldDir(frame.captureMatrix, (l + rr) / 2f, (t + b) / 2f, v3)

            var bestTrack: Track? = null
            var bestDot = cos(0.6 * max(aw, ah)).toFloat()
            for (tr in tracks) {
                if (tr.label != d.label || tr in matched) continue
                val dot = tr.dir[0] * v3[0] + tr.dir[1] * v3[1] + tr.dir[2] * v3[2]
                if (dot > bestDot) { bestDot = dot; bestTrack = tr }
            }
            if (bestTrack != null) {
                val k = 0.6f
                for (i in 0..2) bestTrack.dir[i] += (v3[i] - bestTrack.dir[i]) * k
                val n = sqrt(bestTrack.dir[0] * bestTrack.dir[0] + bestTrack.dir[1] * bestTrack.dir[1] + bestTrack.dir[2] * bestTrack.dir[2])
                for (i in 0..2) bestTrack.dir[i] /= n
                bestTrack.angW += (aw - bestTrack.angW) * k
                bestTrack.angH += (ah - bestTrack.angH) * k
                bestTrack.score = d.score
                bestTrack.lastSeen = now
                matched += bestTrack
            } else {
                val tr = Track(nextTrackId++, d.label, v3.copyOf(), aw, ah, d.score, now)
                tracks += tr
                matched += tr
            }
        }
    }

    /** Un proyectil impacta si cruza la distancia estimada del blanco estando dentro de su recuadro. */
    private fun checkHits(now: Long) {
        if (tracks.isEmpty()) return
        val b = ballistics
        for (i in 0 until b.cap) {
            if (!b.alive[i]) continue
            for (t in tracks) {
                if (!t.visible) continue
                if (b.prevDist[i] < t.range && b.dist[i] >= t.range) {
                    if (projector.worldToScreen(r, b.x[i], b.y[i], b.z[i], p0) && t.rect.contains(p0[0], p0[1])) {
                        t.hits++
                        t.flashUntil = now + 120_000_000L
                        hits++
                        val k = b.kind[i]
                        if (Ballistics.heavy(k)) {
                            t.lastExplosion = 0L
                            objectExplosion(t, now, 2.5f)
                            if (Ballistics.explosive(k)) b.addImpact(b.x[i], b.y[i], b.z[i], k)
                        } else {
                            objectExplosion(t, now, if (weapon == Weapon.GEPARD) 1.6f else 1f)
                        }
                        b.kill(i)
                        break
                    }
                }
            }
        }
    }

    /**
     * Explosión sobre un objeto detectado. Se ancla en el mundo a la distancia estimada del objeto
     * y su radio es proporcional al tamaño del recuadro (objeto cercano = recuadro grande = explosión grande).
     */
    private fun objectExplosion(t: Track, now: Long, scale: Float) {
        if (now - t.lastExplosion < 70_000_000L) return
        t.lastExplosion = now
        val rc = t.rect
        val sx = rc.left + rc.width() * (0.2f + rnd.nextFloat() * 0.6f)
        val sy = rc.top + rc.height() * (0.25f + rnd.nextFloat() * 0.6f)
        projector.screenToWorldDir(r, sx, sy, v3)
        val dist = t.range * 0.97f
        val boxPx = max(rc.width(), rc.height())
        val radiusM = boxPx / projector.focalPx * dist * (0.28f + rnd.nextFloat() * 0.3f) * scale
        effects.explode(v3[0] * dist, v3[1] * dist, v3[2] * dist, radiusM, 0.8f + 0.4f * rnd.nextFloat())
        if (rnd.nextFloat() < 0.35f) {
            effects.smoke(v3[0] * dist, v3[1] * dist, v3[2] * dist + radiusM * 0.5f, radiusM * 0.8f, 2.5f, 0.6f)
        }
        if (now - lastObjectBoomSound > 140_000_000L) {
            lastObjectBoomSound = now
            sound?.explosion(dist, (boxPx / height).coerceIn(0.1f, 0.8f))
        }
    }

    // =====================================================================
    //                               DIBUJO
    // =====================================================================

    /** Escalera de cabeceo anclada al mundo (como un HUD conformal de avión). */
    private fun drawPitchLadder(c: Canvas, att: Attitude) {
        // no dibujar debajo de la cinta de rumbo (evita que los números se pisen)
        c.save()
        c.clipRect(0f, 74 * dp, width.toFloat(), height.toFloat())
        drawPitchLadderInner(c, att)
        c.restore()
    }

    private fun drawPitchLadderInner(c: Canvas, att: Attitude) {
        val az = att.azimuthDeg
        line.alpha = 200; lineDash.alpha = 200
        var havePrev = false
        var a = -60f
        while (a <= 60f) {
            Projector.dirFromAngles(az + a, 0f, v3)
            val ok = projector.worldToScreen(r, v3[0], v3[1], v3[2], p1)
            if (ok && havePrev && abs(a) > 2.5f) c.drawLine(p0[0], p0[1], p1[0], p1[1], line)
            if (ok) { p0[0] = p1[0]; p0[1] = p1[1] }
            havePrev = ok
            a += 2.5f
        }
        for ((deg, name) in CARDINALS) {
            Projector.dirFromAngles(deg, 0f, v3)
            if (projector.worldToScreen(r, v3[0], v3[1], v3[2], p0)) {
                c.drawLine(p0[0], p0[1] - 6 * dp, p0[0], p0[1] + 6 * dp, line)
                c.drawText(name, p0[0] - text.measureText(name) / 2, p0[1] - 10 * dp, text)
            }
        }
        val start = (floor((att.elevationDeg - 40f) / 5f) * 5f).toInt()
        var p = start
        while (p <= att.elevationDeg + 40f) {
            if (p != 0 && p in -85..85) {
                val k = 1f / max(0.2f, cos(Math.toRadians(p.toDouble())).toFloat())
                val paint = if (p < 0) lineDash else line
                for (side in intArrayOf(-1, 1)) {
                    Projector.dirFromAngles(az + side * 2.5f * k, p.toFloat(), v3)
                    val okA = projector.worldToScreen(r, v3[0], v3[1], v3[2], p0)
                    Projector.dirFromAngles(az + side * 8f * k, p.toFloat(), v3)
                    val okB = projector.worldToScreen(r, v3[0], v3[1], v3[2], p1)
                    if (okA && okB) {
                        c.drawLine(p0[0], p0[1], p1[0], p1[1], paint)
                        val tick = if (p > 0) 6 * dp else -6 * dp
                        c.drawLine(p1[0], p1[1], p1[0], p1[1] + tick, paint)
                        val label = abs(p).toString()
                        val tx = if (side > 0) p1[0] + 5 * dp else p1[0] - 5 * dp - textSmall.measureText(label)
                        c.drawText(label, tx, p1[1] + 4 * dp, textSmall)
                    }
                }
            }
            p += 5
        }
        line.alpha = 255; lineDash.alpha = 255
    }

    private fun drawTracers(c: Canvas) {
        val b = ballistics
        val f = projector.focalPx
        for (i in 0 until b.cap) {
            if (!b.alive[i]) continue
            val d = b.dist[i]
            if (d < 0.6f) continue
            val speed = sqrt(b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i])
            val tail = min(speed * 0.014f, d - 0.5f)
            val inv = tail / speed
            if (!projector.worldToScreen(r, b.x[i], b.y[i], b.z[i], p0)) continue
            if (!projector.worldToScreen(r, b.x[i] - b.vx[i] * inv, b.y[i] - b.vy[i] * inv, b.z[i] - b.vz[i] * inv, p1)) continue
            if (Ballistics.heavy(b.kind[i])) { drawShell(c, i, d, speed); continue }
            val life = 1f - b.age[i] / b.life[i]
            val fade = (life * life).coerceIn(0f, 1f)
            val thick = if (b.big[i]) 0.2f else 0.1f
            val w = (f * thick / d).coerceIn(1.3f * dp, (if (b.big[i]) 14f else 9f) * dp)
            val hot = (1f - d / 1500f).coerceIn(0f, 1f)
            tracerGlow.color = Color.argb((90 * fade).toInt(), 255, (120 + 80 * hot).toInt(), 20)
            tracerGlow.strokeWidth = w * 3f
            c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerGlow)
            tracerCore.color = Color.argb((255 * fade).toInt(), 255, (200 + 55 * hot).toInt(), (90 + 150 * hot).toInt())
            tracerCore.strokeWidth = w
            c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerCore)
        }
    }

    private fun brackets(c: Canvas, rc: RectF, L: Float, paint: Paint) {
        path.reset()
        path.moveTo(rc.left, rc.top + L); path.lineTo(rc.left, rc.top); path.lineTo(rc.left + L, rc.top)
        path.moveTo(rc.right - L, rc.top); path.lineTo(rc.right, rc.top); path.lineTo(rc.right, rc.top + L)
        path.moveTo(rc.right, rc.bottom - L); path.lineTo(rc.right, rc.bottom); path.lineTo(rc.right - L, rc.bottom)
        path.moveTo(rc.left + L, rc.bottom); path.lineTo(rc.left, rc.bottom); path.lineTo(rc.left, rc.bottom - L)
        c.drawPath(path, paint)
    }

    private fun drawTracks(c: Canvas, now: Long) {
        for (t in tracks) {
            if (!t.visible) continue
            val isLocked = t === locked
            val color = when {
                now < t.flashUntil -> alertColor
                isLocked -> warnColor
                else -> hudColor
            }
            line.color = color; text.color = color; textSmall.color = color
            val rc = t.rect
            line.strokeWidth = (if (isLocked) 2.6f else 1.8f) * dp
            if (weapon == Weapon.JAVELIN) line.alpha = 150
            brackets(c, rc, min(rc.width(), rc.height()) * 0.25f, line)
            line.alpha = 255
            if (isLocked) {
                val cx = rc.centerX(); val cy = rc.centerY(); val s = 9 * dp
                path.reset()
                path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
                c.drawPath(path, line)
            }
            line.strokeWidth = 1.6f * dp
            val label = "${TargetClasses.display(t.label)} ${(t.score * 100).roundToInt()}%  ~${t.range.roundToInt()}m" +
                    if (t.hits > 0) "  x${t.hits}" else ""
            c.drawText(label, rc.left, rc.top - 5 * dp, textSmall)
            c.drawText("T%02d".format(t.id % 100), rc.left, rc.bottom + 13 * dp, textSmall)
        }
        line.color = hudColor; text.color = hudColor; textSmall.color = hudColor
    }

    /** Recuadro, datos, retícula de adelanto e indicador fuera de pantalla para cada avión. */
    private fun drawAircraftMarkers(c: Canvas) {
        for (a in aircraft.list) {
            if (a.down) {
                if (a.onScreen && a.downAge < 3f) {
                    textSmall.color = alertColor
                    val rc = a.screenRect
                    val word = if (a.model.ground) "DESTRUIDO" else "DERRIBADO"
                    c.drawText(word, rc.centerX() - textSmall.measureText(word) / 2, rc.top - 10 * dp, textSmall)
                    textSmall.color = hudColor
                }
                continue
            }
            if (a.onScreen) {
                val rc = RectF(a.screenRect)
                val pad = 10 * dp
                rc.inset(-pad, -pad)
                if (rc.width() < 34 * dp) rc.inset(-(34 * dp - rc.width()) / 2, 0f)
                if (rc.height() < 34 * dp) rc.inset(0f, -(34 * dp - rc.height()) / 2)
                val col = if (a.hp < a.model.hp) warnColor else alertColor
                line.color = col; textSmall.color = col
                brackets(c, rc, 8 * dp, line)
                val spd = if (a.model.ground) "${(a.speed * 3.6f).roundToInt()}km/h" else "${(a.speed * 1.944f).roundToInt()}kt"
                val tag = if (a.attack && a.closest >= a.range - 1f) "  ▼ATQ" else ""
                c.drawText("${a.model.name}  A%02d".format(a.id % 100) + tag, rc.left, rc.top - 18 * dp, textSmall)
                c.drawText("%dm  %s  ".format(a.range.roundToInt(), spd) + "■".repeat(a.hp.coerceIn(0, 12)), rc.left, rc.top - 5 * dp, textSmall)

                // retícula de adelanto (solo cañones: el misil calcula su propia intercepción)
                if (weapon.isGun) {
                    aircraft.leadPoint(a, pipTmp)
                    if (projector.worldToScreen(r, pipTmp[0], pipTmp[1], pipTmp[2], p0)) {
                        line.alpha = 170
                        c.drawLine(rc.centerX(), rc.centerY(), p0[0], p0[1], line)
                        line.alpha = 255
                        c.drawCircle(p0[0], p0[1], 7 * dp, line)
                        fill.color = col
                        c.drawCircle(p0[0], p0[1], 1.6f * dp, fill)
                        fill.color = hudColor
                    }
                }
            } else {
                drawOffscreenArrow(c, a)
            }
        }
        line.color = hudColor; textSmall.color = hudColor
    }

    private fun drawOffscreenArrow(c: Canvas, a: Aircraft) {
        projector.worldToCamera(r, a.x, a.y, a.z, v3)
        var dx = v3[0]; var dy = -v3[1]
        val n = sqrt(dx * dx + dy * dy)
        if (n < 1e-3f) return
        dx /= n; dy /= n
        val cx = width / 2f; val cy = height / 2f
        val ex = width * 0.40f; val ey = height * 0.34f
        val px = cx + dx * ex; val py = cy + dy * ey
        val ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        fill.color = alertColor
        c.save()
        c.rotate(ang, px, py)
        path.reset()
        path.moveTo(px + 14 * dp, py); path.lineTo(px - 6 * dp, py - 9 * dp); path.lineTo(px - 6 * dp, py + 9 * dp); path.close()
        c.drawPath(path, fill)
        c.restore()
        fill.color = hudColor
        textSmall.color = alertColor
        val lbl = "${a.model.name} ${a.range.roundToInt()}m"
        c.drawText(lbl, px - textSmall.measureText(lbl) / 2 - dx * 30 * dp, py - dy * 22 * dp + 4 * dp, textSmall)
        textSmall.color = hudColor
    }

    private fun drawCrosshair(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val color = when (gun.state) {
            GunState.FIRING, GunState.SPIN_UP, GunState.SPIN_DOWN -> warnColor
            GunState.RELOADING -> alertColor
            else -> hudColor
        }
        line.color = color; fill.color = color
        val rad = 30 * dp
        c.drawCircle(cx, cy, rad, line)
        c.drawLine(cx - rad - 14 * dp, cy, cx - rad + 6 * dp, cy, line)
        c.drawLine(cx + rad - 6 * dp, cy, cx + rad + 14 * dp, cy, line)
        c.drawLine(cx, cy + rad - 6 * dp, cx, cy + rad + 14 * dp, line)
        c.drawCircle(cx, cy, 2.2f * dp, fill)
        if (weapon == Weapon.PHALANX && gun.spin > 0.01f) {
            // anillo de giro del rotor
            line.strokeWidth = 3.5f * dp
            val rr = rad + 6 * dp
            c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * gun.spin, false, line)
            line.strokeWidth = 1.6f * dp
        }
        if (weapon == Weapon.GEPARD) {
            // marcas de reloj y arco de distancia medida (0–3 km)
            for (k in 0 until 12) {
                val a = Math.toRadians(k * 30.0)
                val r0 = rad - 4 * dp; val r1 = rad + (if (k % 3 == 0) 7 else 3) * dp
                c.drawLine(cx + (r0 * sin(a)).toFloat(), cy - (r0 * cos(a)).toFloat(),
                    cx + (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat(), line)
            }
            if (fcRange > 0f) {
                line.strokeWidth = 3f * dp
                val rr = rad + 10 * dp
                c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * (fcRange / 3000f).coerceIn(0f, 1f), false, line)
                line.strokeWidth = 1.6f * dp
            }
            textSmall.color = color
            val lbl = if (gun.mode.ahead) "AHEAD ${if (fcRange > 0) fcRange.roundToInt() else 1500}m" else "HE"
            c.drawText(lbl, cx + rad + 16 * dp, cy + rad + 4 * dp, textSmall)
            textSmall.color = hudColor
        }
        line.color = hudColor; fill.color = hudColor
    }

    /** Mira de la unidad de comando (CLU) del Javelin: ventana de seguimiento y cruz de fijación. */
    private fun drawJavelinReticle(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val g = gateRect()
        val jv = javelin
        val tgt = jv.lockTarget
        val trc = tgt?.screenRect()
        val col = when {
            !jv.ready -> Color.argb(150, 0x39, 0xFF, 0x6A)
            jv.locked -> alertColor
            tgt != null -> warnColor
            else -> hudColor
        }
        line.color = col; textSmall.color = col
        line.strokeWidth = 2f * dp
        if (trc != null && tgt != null) {
            // la ventana se ajusta al blanco
            val rc = RectF(trc); rc.inset(-8 * dp, -8 * dp)
            brackets(c, rc, 12 * dp, line)
            if (jv.locked) {
                // cruz de fijación de borde a borde
                line.alpha = 200
                c.drawLine(0f, rc.centerY(), rc.left - 6 * dp, rc.centerY(), line)
                c.drawLine(rc.right + 6 * dp, rc.centerY(), width.toFloat(), rc.centerY(), line)
                c.drawLine(rc.centerX(), 0f, rc.centerX(), rc.top - 6 * dp, line)
                c.drawLine(rc.centerX(), rc.bottom + 6 * dp, rc.centerX(), height.toFloat(), line)
                line.alpha = 255
                c.drawText("FIJADO  ${tgt.label}  ${tgt.range.roundToInt()}m", rc.left, rc.bottom + 16 * dp, textSmall)
            } else {
                // barra de progreso de fijación
                c.drawRect(rc.left, rc.bottom + 6 * dp, rc.right, rc.bottom + 11 * dp, line)
                fill.color = col
                c.drawRect(rc.left, rc.bottom + 6 * dp, rc.left + rc.width() * jv.lockProgress, rc.bottom + 11 * dp, fill)
                fill.color = hudColor
                c.drawText("BUSCANDO  ${tgt.label}", rc.left, rc.bottom + 24 * dp, textSmall)
            }
        } else {
            brackets(c, g, 16 * dp, line)
        }
        // cruz central fina
        line.strokeWidth = 1.2f * dp
        line.color = hudColor
        c.drawLine(cx - 18 * dp, cy, cx - 5 * dp, cy, line)
        c.drawLine(cx + 5 * dp, cy, cx + 18 * dp, cy, line)
        c.drawLine(cx, cy - 18 * dp, cx, cy - 5 * dp, line)
        c.drawLine(cx, cy + 5 * dp, cx, cy + 18 * dp, line)
        line.strokeWidth = 1.6f * dp
        textSmall.color = hudColor
        val info = "${if (narrowFov) "NFOV" else "WFOV"}  ${if (thermal) "TÉRM" else "DÍA"}  ${if (jv.topAttack) "TOP" else "DIR"}"
        c.drawText(info, cx - textSmall.measureText(info) / 2, g.bottom + 38 * dp, textSmall)
    }

    private fun drawHeadingTape(c: Canvas, att: Attitude) {
        val cx = width / 2f
        val y = 34 * dp
        val tapeW = width * 0.46f
        val span = 50f
        val ppd = tapeW / span
        val az = att.azimuthDeg
        c.drawRect(cx - tapeW / 2 - 6 * dp, y - 26 * dp, cx + tapeW / 2 + 6 * dp, y + 10 * dp, panelBg)
        var d = (floor((az - span / 2) / 5f) * 5f).toInt()
        while (d <= az + span / 2) {
            val x = cx + (d - az) * ppd
            if (abs(x - cx) <= tapeW / 2) {
                val major = d % 15 == 0
                c.drawLine(x, y, x, y - (if (major) 10 else 5) * dp, line)
                if (major) {
                    val h = ((d % 360) + 360) % 360
                    val lbl = when (h) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "O"; else -> "%03d".format(h) }
                    c.drawText(lbl, x - textSmall.measureText(lbl) / 2, y - 13 * dp, textSmall)
                }
            }
            d += 5
        }
        val v = "%03d°".format(az.roundToInt() % 360)
        val tw = textBig.measureText(v)
        val bx = cx - tw / 2 - 6 * dp
        c.drawRect(bx, y + 12 * dp, cx + tw / 2 + 6 * dp, y + 36 * dp, panelBg)
        c.drawRect(bx, y + 12 * dp, cx + tw / 2 + 6 * dp, y + 36 * dp, line)
        c.drawText(v, cx - tw / 2, y + 31 * dp, textBig)
        path.reset()
        path.moveTo(cx, y + 2 * dp); path.lineTo(cx - 5 * dp, y + 11 * dp); path.lineTo(cx + 5 * dp, y + 11 * dp); path.close()
        c.drawPath(path, fill)
    }

    /** Escala de alabeo (bank) en arco, abajo al centro. */
    private fun drawBankScale(c: Canvas, att: Attitude) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = height * 0.36f
        for (a in BANK_TICKS) {
            val rd = Math.toRadians(a.toDouble())
            val len = (if (a % 30 == 0) 12 else 7) * dp
            val sx = sin(rd).toFloat(); val sy = cos(rd).toFloat()
            c.drawLine(cx + rad * sx, cy + rad * sy, cx + (rad + len) * sx, cy + (rad + len) * sy, line)
        }
        val b = att.bankDeg.coerceIn(-70f, 70f)
        val rd = Math.toRadians(b.toDouble())
        val sx = sin(rd).toFloat(); val sy = cos(rd).toFloat()
        val px = cx + (rad - 3 * dp) * sx; val py = cy + (rad - 3 * dp) * sy
        c.save()
        c.rotate(-b, px, py)
        path.reset()
        path.moveTo(px, py); path.lineTo(px - 6 * dp, py - 11 * dp); path.lineTo(px + 6 * dp, py - 11 * dp); path.close()
        c.drawPath(path, fill)
        c.restore()
    }

    private fun drawLeftPanel(c: Canvas, att: Attitude, now: Long) {
        val x = 18 * dp
        var y = 28 * dp
        val lh = 18 * dp
        val pw = 196 * dp
        val rows = when (weapon) {
            Weapon.PHALANX -> 13.2f; Weapon.GEPARD -> 13.9f; Weapon.JAVELIN -> 10.6f; Weapon.TAM -> 10.9f; Weapon.OBUS -> 12.2f
        }
        c.drawRect(x - 8 * dp, y - 16 * dp, x + pw, y + lh * rows, panelBg)

        fun row(label: String, value: String, color: Int = hudColor) {
            text.color = hudColor
            c.drawText(label, x, y, text)
            text.color = color
            c.drawText(value, x + 92 * dp, y, text)
            text.color = hudColor
            y += lh
        }
        fun bar(frac: Float, color: Int) {
            val bw = pw - 18 * dp
            line.color = hudColor
            c.drawRect(x, y - 10 * dp, x + bw, y - 2 * dp, line)
            fill.color = color
            c.drawRect(x, y - 10 * dp, x + bw * frac.coerceIn(0f, 1f), y - 2 * dp, fill)
            fill.color = hudColor
            y += lh * 0.7f
        }

        row("INCLIN", "%+6.1f°".format(att.elevationDeg))
        row("RUMBO", "%5.1f°".format(att.azimuthDeg))
        row("ALABEO", "%+6.1f°".format(att.bankDeg))
        y += lh * 0.3f

        val cn = cannon
        if (weapon == Weapon.TAM && cn != null) {
            for ((i, a) in cn.ammo.withIndex()) {
                val sel = i == cn.selected
                row(if (sel) "▶ ${a.label}" else "  ${a.label}", "${a.count}", if (sel) warnColor else hudColor)
            }
            val (st, sc) = when {
                cn.current.count == 0 -> "SIN MUNICIÓN" to alertColor
                cn.ready -> "CARGADO" to hudColor
                else -> "CARGANDO %.1fs".format(cn.reloadLeft) to warnColor
            }
            row("CAÑÓN", st, sc)
            bar(1f - cn.reloadLeft / cn.reloadTime, sc)
            row("LÁSER", if (laseRange > 0f) "${laseRange.roundToInt()}m" else "---")
            row("ECO", laseSource.take(12), if (laseTarget != null) warnColor else hudColor)
            row("SEGUIM.", if (laseTarget != null) "ACTIVO" else "---")
            row("CORR.ELEV", "%+.1f mil".format(superElevMils))
            row("ADELANTO", "%+.1f mil".format(leadMils))
            row("DISPAROS", cn.shots.toString())
            return
        }
        if (weapon == Weapon.OBUS && cn != null) {
            row("AZIMUT", "%05.0f mil".format(att.azimuthDeg * MILS_PER_DEG))
            row("ELEVACIÓN", "%4.0f mil %.0f°".format(att.elevationDeg * MILS_PER_DEG, att.elevationDeg),
                if (att.elevationDeg < 0f) alertColor else hudColor)
            row("CARGA", "${cn.charge + 1}  (${cn.muzzleSpeed.roundToInt()} m/s)", warnColor)
            row("ALCANCE", if (predValid) "%.2f km".format(predRange / 1000f) else "---")
            row("T. VUELO", if (predValid) "%.1f s".format(predTof) else "---")
            val (st, sc) = when {
                cn.current.count == 0 -> "SIN MUNICIÓN" to alertColor
                cn.ready -> "LISTO" to hudColor
                else -> "CARGANDO %.1fs".format(cn.reloadLeft) to warnColor
            }
            row("PIEZA", st, sc)
            bar(1f - cn.reloadLeft / cn.reloadTime, sc)
            row("PROYECT.", "${cn.current.count}")
            val ft = focusTarget
            if (ft != null && predValid) {
                // corrección respecto del blanco más cercano al impacto previsto
                val azr = kotlin.math.atan2(predImpact[0], predImpact[1])
                val ux = sin(azr); val uy = cos(azr)
                val ex = ft.x - predImpact[0]; val ey = ft.y - predImpact[1]
                val along = ex * ux + ey * uy
                val side = ex * uy - ey * ux
                row("BLANCO", "${ft.model.name} %.1fkm".format(ft.range / 1000f), alertColor)
                val ok = abs(along) < 25f && abs(side) < 25f
                row("CORRECC.", if (ok) "¡EN BLANCO!" else
                    "${abs(along).roundToInt()}m ${if (along > 0) "CORTO" else "LARGO"} ${abs(side).roundToInt()}m ${if (side > 0) "IZQ" else "DER"}",
                    if (ok) alertColor else warnColor)
            } else {
                row("BLANCO", if (aircraft.tanksEnabled) "BUSCANDO…" else "ACTIVAR CARROS")
            }
            return
        }

        if (weapon == Weapon.JAVELIN) {
            val jv = javelin
            val (seeker, sc) = when {
                !jv.inTube -> "SIN MISIL" to warnColor
                jv.cooling -> "ENFRIANDO %.1fs".format(jv.coolLeft) to warnColor
                else -> "LISTO" to hudColor
            }
            row("BUSCADOR", seeker, sc)
            val (tube, tc) = when {
                jv.inTube -> "CARGADO" to hudColor
                jv.reloadLeft > 0f -> "CARGA %d%%".format(((1f - jv.reloadLeft / JavelinSystem.RELOAD_TIME) * 100).roundToInt()) to warnColor
                else -> "REABAST %.0fs".format(jv.resupplyLeft) to alertColor
            }
            row("TUBO", tube, tc)
            row("MISILES", "${jv.totalLeft}/${JavelinSystem.TOTAL}", if (jv.totalLeft == 0) alertColor else hudColor)
            row("ATAQUE", if (jv.topAttack) "TOP (ARRIBA)" else "DIRECTO")
            row("CAMPO", if (narrowFov) "NFOV %.1fx".format(projector.zoom) else "WFOV")
            row("VISTA", if (thermal) "TÉRMICA" else "DÍA")
            row("FIJACIÓN", if (jv.locked) "FIJADO" else "%3d%%".format((jv.lockProgress * 100).roundToInt()),
                if (jv.locked) alertColor else hudColor)
            bar(jv.lockProgress, if (jv.locked) alertColor else warnColor)
            return
        }

        val (st, stColor) = when (gun.state) {
            GunState.SAFE -> "LISTO" to hudColor
            GunState.SPIN_UP -> (if (weapon == Weapon.PHALANX) "ACELERANDO" else "CARGANDO") to warnColor
            GunState.FIRING -> "FUEGO" to alertColor
            GunState.SPIN_DOWN -> "DETENIENDO" to warnColor
            GunState.RELOADING -> "RECARGA" to warnColor
        }
        row("ESTADO", st, stColor)
        val m = gun.mode
        if (weapon == Weapon.GEPARD) {
            row("MUNICIÓN", if (m.ahead) "AHEAD" else "HE CONTACTO", if (m.ahead) warnColor else hudColor)
        } else {
            val modeTxt = if (m.burst > 0) {
                if (gun.burstLeft > 0) "RÁF ${gun.burstLeft}/${m.burst}" else "RÁFAGA ${m.burst}"
            } else "CONT ${m.label}"
            row("MODO", modeTxt)
            row("GIRO", "%3d%%".format((gun.spin * 100).roundToInt()))
        }
        row("CADENCIA", "%4d/min".format(gun.measuredRpm(now)))
        row("DISPAROS", gun.roundsFired.toString())
        val slewDeg = Math.toDegrees(slewRate.toDouble()).roundToInt()
        val maxSlew = gun.cfg.maxSlewDeg
        val over = maxSlew > 0 && slewDeg > maxSlew
        row("TORRETA", "%3d°/s".format(slewDeg) + if (over) " MÁX" else "", if (over) warnColor else hudColor)
        val mag = gun.magazine
        val cap = gun.cfg.magazine
        val magColor = if (mag < cap * 0.15f) alertColor else hudColor
        if (weapon == Weapon.GEPARD) {
            row("IZQ / DER", "${(mag + 1) / 2} / ${mag / 2}", magColor)
        } else {
            row("CARGADOR", "$mag/$cap", magColor)
        }
        bar(mag / cap.toFloat(), magColor)
        val heatColor = when {
            gun.heat > 0.8f -> alertColor
            gun.heat > 0.5f -> warnColor
            else -> hudColor
        }
        row("TEMP CAÑÓN", "%3d%%".format((gun.heat * 100).roundToInt()), heatColor)
        bar(gun.heat, heatColor)
        if (weapon == Weapon.GEPARD) {
            row("TELÉMETRO", if (fcRange > 0f) "${fcRange.roundToInt()}m $fcSource" else "---")
            row("ESPOLETA", if (m.ahead) "${if (fcRange > 0f) fcRange.roundToInt() else 1500}m" else "CONTACTO",
                if (m.ahead) warnColor else hudColor)
        }
    }

    private fun drawRightPanel(c: Canvas) {
        val pw = 190 * dp
        val x = width - pw - 10 * dp
        var y = 28 * dp
        val lh = 18 * dp
        if (weapon == Weapon.OBUS) {
            text.color = warnColor
            c.drawText(weapon.title, x, y, text)
            text.color = hudColor
            val size = min(pw, height - y - 160 * dp).coerceAtLeast(110 * dp)
            drawObusPanel(c, x - 8 * dp, y + 8 * dp, size)
            y += size + 26 * dp
            c.drawText("DESTRUID  ${aircraft.tankKills}", x, y, text)
            return
        }
        c.drawRect(x - 8 * dp, y - 16 * dp, x + pw, y + lh * 11.4f, panelBg)
        text.color = warnColor
        c.drawText(weapon.title, x, y, text); y += lh * 1.2f
        text.color = hudColor
        val visible = tracks.count { it.visible }
        c.drawText("BLANCOS   $visible", x, y, text); y += lh
        c.drawText("IMPACTOS  ${hits + javelin.hits}", x, y, text); y += lh
        val inFlight = if (weapon == Weapon.JAVELIN) javelin.missiles.size else ballistics.count
        c.drawText("EN VUELO  $inFlight", x, y, text); y += lh
        text.color = if (aircraft.enabled) warnColor else hudColor
        c.drawText("AVIONES   " + if (aircraft.enabled) "${aircraft.list.count { !it.down }} ACT" else "OFF", x, y, text); y += lh
        text.color = hudColor
        c.drawText("DERRIBOS  ${aircraft.kills}", x, y, text); y += lh
        text.color = if (aircraft.tanksEnabled) warnColor else hudColor
        c.drawText("CARROS    " + if (aircraft.tanksEnabled) "${aircraft.list.count { it.model.ground && !it.down }} ACT" else "OFF", x, y, text); y += lh
        text.color = hudColor
        c.drawText("DESTRUID  ${aircraft.tankKills}", x, y, text); y += lh * 1.3f
        if (weapon == Weapon.JAVELIN) {
            val t = javelin.lockTarget
            if (t != null) {
                text.color = if (javelin.locked) alertColor else warnColor
                c.drawText(if (javelin.locked) "◆ FIJADO" else "◇ BUSCANDO", x, y, text); y += lh
                c.drawText(t.label, x, y, text); y += lh
                c.drawText("DIST ~%dm".format(t.range.roundToInt()), x, y, text)
                text.color = hudColor
            } else {
                c.drawText("SIN BLANCO", x, y, text)
            }
            return
        }
        if (weapon == Weapon.TAM) {
            text.color = if (laseRange > 0f) warnColor else hudColor
            c.drawText(if (laseRange > 0f) "◆ LÁSER ${laseRange.roundToInt()}m" else "SIN MEDICIÓN", x, y, text); y += lh
            if (laseRange > 0f) c.drawText(laseSource, x, y, text)
            text.color = hudColor
            return
        }
        val t = locked
        if (t != null) {
            text.color = warnColor
            c.drawText("◆ BLOQUEO T%02d".format(t.id % 100), x, y, text); y += lh
            c.drawText(TargetClasses.display(t.label), x, y, text); y += lh
            val tof = t.range / gun.cfg.muzzleVelocity
            c.drawText("DIST ~%dm  TV %.2fs".format(t.range.roundToInt(), tof), x, y, text)
            text.color = hudColor
        } else {
            c.drawText("SIN BLOQUEO", x, y, text)
        }
    }

    private fun drawMessages(c: Canvas, att: Attitude) {
        val msgs = ArrayList<Pair<String, Int>>(3)
        if (!orientation.available) msgs += "SIN SENSOR DE ORIENTACIÓN" to alertColor
        else if (!orientation.hasData) msgs += "ESPERANDO SENSORES…" to warnColor
        if (!detectorReady) msgs += "DETECTOR NO DISPONIBLE" to warnColor
        val cn = cannon
        if (weapon.isCannon && cn != null) {
            if (cn.totalLeft == 0) msgs += "SIN MUNICIÓN" to alertColor
            if (weapon == Weapon.TAM && cn.ready && laseRange <= 0f) msgs += "MEDÍ CON EL LÁSER ANTES DE TIRAR" to hudColor
            if (weapon == Weapon.OBUS) {
                val next = shellsInFlight.minByOrNull { it.launch + it.tof }
                if (next != null) {
                    val left = next.launch + next.tof - clock
                    msgs += (if (left <= 5f) "¡SPLASH! IMPACTO EN %.0f s" else "PROYECTIL EN VUELO · IMPACTO EN %.0f s")
                        .format(left.coerceAtLeast(0f)) to (if (left <= 5f) alertColor else warnColor)
                }
                if (att.elevationDeg < 2f) msgs += "ELEVÁ EL TUBO (APUNTÁ HACIA ARRIBA)" to warnColor
            }
        } else if (weapon.isGun) {
            when {
                gun.state == GunState.RELOADING ->
                    msgs += "RECARGANDO %d%%".format((gun.reloadProgress * 100).roundToInt()) to warnColor
                gun.magazine == 0 -> msgs += "SIN MUNICIÓN — RECARGAR" to alertColor
            }
            if (gun.heat > 0.85f) msgs += "CAÑÓN SOBRECALENTADO" to alertColor
        } else if (weapon == Weapon.JAVELIN) {
            val jv = javelin
            when {
                !jv.inTube && jv.reloadLeft <= 0f -> msgs += "SIN MISILES — REABASTECIENDO" to alertColor
                !jv.inTube -> msgs += "CARGANDO TUBO NUEVO" to warnColor
                jv.cooling -> msgs += "ENFRIANDO BUSCADOR…" to warnColor
                jv.locked -> msgs += "BLANCO FIJADO — LANZAR" to alertColor
            }
        }
        if (aircraft.enabled || aircraft.tanksEnabled) {
            val contact = aircraft.lastContact
            val threat = aircraft.incomingThreat()
            if (threat != null && threat.range < 2500f) {
                val blink = (System.nanoTime() / 250_000_000L) % 2L == 0L
                msgs += (if (blink) "¡AMENAZA ENTRANTE! ${threat.model.name} ${threat.range.roundToInt()}m" else " ") to alertColor
            } else if (contact != null && aircraft.contactMessageAge() < 2.5f) {
                msgs += contact to alertColor
            }
        }
        // Javelin: los mensajes van arriba de la ventana para no quedar tapados por los botones
        var y = if (weapon == Weapon.JAVELIN) height / 2f - height * 0.13f - 14 * dp - (msgs.size - 1).coerceAtLeast(0) * 24 * dp
                else height / 2f + 70 * dp
        for ((m, col) in msgs) {
            textBig.color = col
            c.drawText(m, width / 2f - textBig.measureText(m) / 2, y, textBig)
            y += 24 * dp
        }
        textBig.color = hudColor
    }

    private fun drawMuzzleFlash(c: Canvas) {
        if (weapon.isCannon) {
            if (muzzleFlashTimer <= 0f) return
            // fogonazo enorme en la boca del cañón (todo el borde inferior)
            val rad = (if (weapon == Weapon.OBUS) 420f else 320f) * dp * (0.8f + rnd.nextFloat() * 0.4f)
            c.save(); c.translate(width / 2f, height + 40 * dp); c.scale(rad, rad)
            c.drawCircle(0f, 0f, 1f, flashPaint)
            c.restore()
            return
        }
        if (weapon == Weapon.GEPARD) {
            if (muzzleFlashTimer <= 0f) return
            // fogonazo del cañón que acaba de disparar (izquierdo o derecho)
            val rad = (120 + rnd.nextFloat() * 80) * dp
            val x = if (muzzleFlashSide == 0) width * 0.1f else width * 0.9f
            c.save(); c.translate(x, height + 10 * dp); c.scale(rad, rad)
            c.drawCircle(0f, 0f, 1f, flashPaint)
            c.restore()
            return
        }
        if (weapon != Weapon.PHALANX) return
        val rad = (90 + rnd.nextFloat() * 70) * dp
        c.save()
        c.translate(width / 2f + projector.focalPx * 0.1f, height + 10 * dp)
        c.scale(rad, rad)
        c.drawCircle(0f, 0f, 1f, flashPaint)
        c.restore()
    }

    companion object {
        const val MILS_PER_DEG = 6400f / 360f
        private val CARDINALS = listOf(0f to "N", 90f to "E", 180f to "S", 270f to "O")
        private val BANK_TICKS = intArrayOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)
    }
}
