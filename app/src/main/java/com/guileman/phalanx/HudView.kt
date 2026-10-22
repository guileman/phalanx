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
import kotlin.math.abs
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

        aircraft.update(dtf, att.azimuthDeg, att.elevationDeg, effects, sound)
        if (weapon.isGun) hits += aircraft.checkHits(ballistics, dtf, effects, sound, now, gun.cfg.damage)
        effects.update(dtf)
        updateTracks(now)
        if (weapon.isGun) checkHits(now)
        if (weapon == Weapon.JAVELIN) javelin.update(dtf, lockCandidates(), gateRect(), effects, sound, aircraft, projector, r)

        val firing = weapon.isGun && gun.state == GunState.FIRING
        applyCameraEffects(firing, dtf)
        if (muzzleFlashTimer > 0f) muzzleFlashTimer -= dtf

        canvas.save()
        if (firing) canvas.translate((rnd.nextFloat() - 0.5f) * 3f * dp, (rnd.nextFloat() - 0.5f) * 3f * dp)

        if (weapon.isGun) drawPitchLadder(canvas, att)
        aircraft.draw(canvas, projector, r)
        effects.draw(canvas, projector, r)
        if (weapon.isGun) drawTracers(canvas)
        if (weapon == Weapon.JAVELIN) javelin.draw(canvas, projector, r)
        drawTracks(canvas, now)
        drawAircraftMarkers(canvas)
        if (weapon == Weapon.JAVELIN) drawJavelinReticle(canvas) else drawCrosshair(canvas)
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

        val strength = when (weapon) { Weapon.GEPARD -> 1.3f; Weapon.JAVELIN -> 0.8f; else -> 1f }
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
                        objectExplosion(t, now, if (weapon == Weapon.GEPARD) 1.6f else 1f)
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
            val life = 1f - b.age[i] / Ballistics.LIFETIME
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
        val rows = when (weapon) { Weapon.PHALANX -> 13.2f; Weapon.GEPARD -> 13.9f; Weapon.JAVELIN -> 10.6f }
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
        if (weapon.isGun) {
            when {
                gun.state == GunState.RELOADING ->
                    msgs += "RECARGANDO %d%%".format((gun.reloadProgress * 100).roundToInt()) to warnColor
                gun.magazine == 0 -> msgs += "SIN MUNICIÓN — RECARGAR" to alertColor
            }
            if (gun.heat > 0.85f) msgs += "CAÑÓN SOBRECALENTADO" to alertColor
        } else {
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
        private val CARDINALS = listOf(0f to "N", 90f to "E", 180f to "S", 270f to "O")
        private val BANK_TICKS = intArrayOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)
    }
}
