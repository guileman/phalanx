package com.guileman.phalanx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import kotlin.random.Random

/**
 * Capa HUD dibujada encima de la cámara. También corre el "game loop":
 * en cada frame avanza el cañón, los proyectiles y el seguimiento de blancos.
 */
class HudView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------- dependencias (las asigna MainActivity) ----------
    lateinit var gun: GunController
    lateinit var orientation: OrientationTracker
    var detectorReady = true
    var onGunStateChanged: ((GunState) -> Unit)? = null

    val projector = Projector()
    private val ballistics = Ballistics()
    private var horizontalFovRad = Math.toRadians(66.0)

    fun setHorizontalFov(rad: Double) {
        horizontalFovRad = rad
        if (width > 0) projector.setFov(width, height, rad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        projector.setFov(w, h, horizontalFovRad)
    }

    // ---------- estado ----------
    private val r = FloatArray(9)
    private var lastNanos = 0L
    private var lastGunState = GunState.SAFE
    private var hits = 0
    private val rnd = Random(42)
    private val p0 = FloatArray(2)
    private val p1 = FloatArray(2)
    private val v3 = FloatArray(3)
    private val v3b = FloatArray(3)

    @Volatile private var pendingFrame: DetectionFrame? = null
    fun submitDetections(frame: DetectionFrame) { pendingFrame = frame }

    private class Track(
        val id: Int,
        val label: String,
        val dir: FloatArray,          // dirección en el mundo (queda fija al entorno)
        var w: Float, var h: Float,   // tamaño en píxeles de pantalla
        var score: Float,
        var lastSeen: Long,
    ) {
        val rect = RectF()
        var visible = false
        var range = 0f
        var hits = 0
        var flashUntil = 0L
    }

    private val tracks = ArrayList<Track>()
    private var nextTrackId = 1
    private var locked: Track? = null

    private class Spark(val x: Float, val y: Float, val z: Float, val born: Long)
    private val sparks = ArrayList<Spark>()

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
        lastNanos = now
        orientation.copyMatrix(r)

        // --- cañón y proyectiles ---
        val shots = gun.update(dt, now)
        ballistics.update(dt.toFloat())
        for (k in 0 until shots) {
            // reparte los disparos del frame en el tiempo: el más viejo va más adelantado
            val ahead = (dt * (shots - k - 0.5) / shots).toFloat()
            ballistics.spawn(projector, r, ahead)
        }
        if (gun.state != lastGunState) {
            lastGunState = gun.state
            onGunStateChanged?.invoke(gun.state)
        }

        updateTracks(now)
        checkHits(now)

        val firing = gun.state == GunState.FIRING
        canvas.save()
        if (firing) canvas.translate((rnd.nextFloat() - 0.5f) * 3f * dp, (rnd.nextFloat() - 0.5f) * 3f * dp)

        val att = Projector.attitude(r)
        drawPitchLadder(canvas, att)
        drawTracers(canvas)
        drawSparks(canvas, now)
        drawTracks(canvas, now)
        drawCrosshair(canvas)
        drawHeadingTape(canvas, att)
        drawBankScale(canvas, att)
        drawLeftPanel(canvas, att, now)
        drawRightPanel(canvas)
        drawMessages(canvas)
        canvas.restore()
        if (firing) drawMuzzleFlash(canvas)

        postInvalidateOnAnimation()
    }

    // =====================================================================
    //                          SEGUIMIENTO DE BLANCOS
    // =====================================================================
    private fun updateTracks(now: Long) {
        val frame = pendingFrame
        if (frame != null) {
            pendingFrame = null
            ingest(frame, now)
        }
        tracks.removeAll { now - it.lastSeen > 450_000_000L }

        val cx = width / 2f; val cy = height / 2f
        var best: Track? = null
        var bestD = 0.16f * width
        for (t in tracks) {
            t.visible = projector.worldToScreen(r, t.dir[0], t.dir[1], t.dir[2], p0)
            if (!t.visible) continue
            t.rect.set(p0[0] - t.w / 2, p0[1] - t.h / 2, p0[0] + t.w / 2, p0[1] + t.h / 2)
            t.range = TargetClasses.realHeight(t.label) * projector.focalPx / max(t.h, 4f)
            val d = if (t.rect.contains(cx, cy)) 0f else sqrt((p0[0] - cx) * (p0[0] - cx) + (p0[1] - cy) * (p0[1] - cy))
            if (d < bestD) { bestD = d; best = t }
        }
        locked = best
    }

    /** Convierte detecciones de la imagen a pantalla y las ancla a una dirección del mundo. */
    private fun ingest(frame: DetectionFrame, now: Long) {
        if (width == 0) return
        // mapeo FILL_CENTER: imagen escalada hasta cubrir la vista, centrada
        val s = max(width / frame.imageWidth.toFloat(), height / frame.imageHeight.toFloat())
        val ox = (width - frame.imageWidth * s) / 2f
        val oy = (height - frame.imageHeight * s) / 2f
        val matched = HashSet<Track>()
        for (d in frame.detections) {
            val l = ox + d.box.left * s; val rr = ox + d.box.right * s
            val t = oy + d.box.top * s; val b = oy + d.box.bottom * s
            val bw = rr - l; val bh = b - t
            // dirección del centro del cuadro según la orientación AL MOMENTO DE LA CAPTURA
            projector.screenToWorldDir(frame.captureMatrix, (l + rr) / 2f, (t + b) / 2f, v3)

            var bestTrack: Track? = null
            var bestDot = cos(0.6 * max(bw, bh) / projector.focalPx).toFloat()
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
                bestTrack.w += (bw - bestTrack.w) * k
                bestTrack.h += (bh - bestTrack.h) * k
                bestTrack.score = d.score
                bestTrack.lastSeen = now
                matched += bestTrack
            } else {
                val tr = Track(nextTrackId++, d.label, v3.copyOf(), bw, bh, d.score, now)
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
                        sparks += Spark(b.x[i], b.y[i], b.z[i], now)
                        b.kill(i)
                        break
                    }
                }
            }
        }
        if (sparks.size > 80) sparks.subList(0, sparks.size - 80).clear()
    }

    // =====================================================================
    //                               DIBUJO
    // =====================================================================

    /** Escalera de cabeceo anclada al mundo (como un HUD conformal de avión). */
    private fun drawPitchLadder(c: Canvas, att: Attitude) {
        val az = att.azimuthDeg
        line.alpha = 200; lineDash.alpha = 200
        // horizonte
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
        // puntos cardinales sobre el horizonte
        for ((deg, name) in CARDINALS) {
            Projector.dirFromAngles(deg, 0f, v3)
            if (projector.worldToScreen(r, v3[0], v3[1], v3[2], p0)) {
                c.drawLine(p0[0], p0[1] - 6 * dp, p0[0], p0[1] + 6 * dp, line)
                c.drawText(name, p0[0] - text.measureText(name) / 2, p0[1] - 10 * dp, text)
            }
        }
        // escalones cada 5°
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
                        // pestaña indicando hacia el horizonte
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
            val w = (f * 0.10f / d).coerceIn(1.3f * dp, 9f * dp)
            // brillo: amarillo-blanco cerca, naranja lejos
            val hot = (1f - d / 1500f).coerceIn(0f, 1f)
            tracerGlow.color = Color.argb((90 * fade).toInt(), 255, (120 + 80 * hot).toInt(), 20)
            tracerGlow.strokeWidth = w * 3f
            c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerGlow)
            tracerCore.color = Color.argb((255 * fade).toInt(), 255, (200 + 55 * hot).toInt(), (90 + 150 * hot).toInt())
            tracerCore.strokeWidth = w
            c.drawLine(p1[0], p1[1], p0[0], p0[1], tracerCore)
        }
    }

    private fun drawSparks(c: Canvas, now: Long) {
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val age = (now - s.born) / 1e9f
            if (age > 0.35f) { it.remove(); continue }
            if (!projector.worldToScreen(r, s.x, s.y, s.z, p0)) continue
            val k = age / 0.35f
            val rad = (6 + 26 * k) * dp
            c.save()
            c.translate(p0[0], p0[1])
            c.scale(rad, rad)
            flashPaint.alpha = (255 * (1 - k)).toInt()
            c.drawCircle(0f, 0f, 1f, flashPaint)
            c.restore()
        }
        flashPaint.alpha = 255
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
            val L = min(rc.width(), rc.height()) * 0.25f
            // recuadro con esquinas tipo "brackets"
            path.reset()
            path.moveTo(rc.left, rc.top + L); path.lineTo(rc.left, rc.top); path.lineTo(rc.left + L, rc.top)
            path.moveTo(rc.right - L, rc.top); path.lineTo(rc.right, rc.top); path.lineTo(rc.right, rc.top + L)
            path.moveTo(rc.right, rc.bottom - L); path.lineTo(rc.right, rc.bottom); path.lineTo(rc.right - L, rc.bottom)
            path.moveTo(rc.left + L, rc.bottom); path.lineTo(rc.left, rc.bottom); path.lineTo(rc.left, rc.bottom - L)
            line.strokeWidth = (if (isLocked) 2.6f else 1.8f) * dp
            c.drawPath(path, line)
            if (isLocked) {
                // rombo de bloqueo
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

    private fun drawCrosshair(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val state = gun.state
        val color = when (state) {
            GunState.FIRING -> warnColor
            GunState.SPIN_UP, GunState.SPIN_DOWN -> warnColor
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
        // anillo de giro del rotor
        if (gun.spin > 0.01f) {
            line.strokeWidth = 3.5f * dp
            val rr = rad + 6 * dp
            c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f, 360f * gun.spin, false, line)
            line.strokeWidth = 1.6f * dp
        }
        line.color = hudColor; fill.color = hudColor
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
        // caja de lectura
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
        c.drawRect(x - 8 * dp, y - 16 * dp, x + pw, y + lh * 11.2f, panelBg)

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
        val (st, stColor) = when (gun.state) {
            GunState.SAFE -> "LISTO" to hudColor
            GunState.SPIN_UP -> "ACELERANDO" to warnColor
            GunState.FIRING -> "FUEGO" to alertColor
            GunState.SPIN_DOWN -> "DETENIENDO" to warnColor
            GunState.RELOADING -> "RECARGA" to warnColor
        }
        row("ESTADO", st, stColor)
        row("GIRO", "%3d%%".format((gun.spin * 100).roundToInt()))
        row("CADENCIA", "%4d/min".format(gun.measuredRpm(now)))
        row("DISPAROS", gun.roundsFired.toString())
        val magColor = if (gun.magazine < GunController.MAGAZINE * 0.15f) alertColor else hudColor
        row("CARGADOR", "${gun.magazine}/${GunController.MAGAZINE}", magColor)
        bar(gun.magazine / GunController.MAGAZINE.toFloat(), magColor)
        val heatColor = when {
            gun.heat > 0.8f -> alertColor
            gun.heat > 0.5f -> warnColor
            else -> hudColor
        }
        row("TEMP CAÑÓN", "%3d%%".format((gun.heat * 100).roundToInt()), heatColor)
        bar(gun.heat, heatColor)
    }

    private fun drawRightPanel(c: Canvas) {
        val pw = 190 * dp
        val x = width - pw - 10 * dp
        var y = 28 * dp
        val lh = 18 * dp
        c.drawRect(x - 8 * dp, y - 16 * dp, x + pw, y + lh * 6.4f, panelBg)
        val visible = tracks.count { it.visible }
        c.drawText("BLANCOS   $visible", x, y, text); y += lh
        c.drawText("IMPACTOS  $hits", x, y, text); y += lh
        c.drawText("EN VUELO  ${ballistics.count}", x, y, text); y += lh * 1.3f
        val t = locked
        if (t != null) {
            text.color = warnColor
            c.drawText("◆ BLOQUEO T%02d".format(t.id % 100), x, y, text); y += lh
            c.drawText(TargetClasses.display(t.label), x, y, text); y += lh
            val tof = t.range / Ballistics.MUZZLE_VELOCITY
            c.drawText("DIST ~%dm  TV %.2fs".format(t.range.roundToInt(), tof), x, y, text)
            text.color = hudColor
        } else {
            c.drawText("SIN BLOQUEO", x, y, text)
        }
    }

    private fun drawMessages(c: Canvas) {
        val msgs = ArrayList<Pair<String, Int>>(3)
        if (!orientation.available) msgs += "SIN SENSOR DE ORIENTACIÓN" to alertColor
        else if (!orientation.hasData) msgs += "ESPERANDO SENSORES…" to warnColor
        if (!detectorReady) msgs += "DETECTOR NO DISPONIBLE" to warnColor
        when {
            gun.state == GunState.RELOADING ->
                msgs += "RECARGANDO %d%%".format((gun.reloadProgress * 100).roundToInt()) to warnColor
            gun.magazine == 0 -> msgs += "SIN MUNICIÓN — RECARGAR" to alertColor
        }
        if (gun.heat > 0.85f) msgs += "CAÑÓN SOBRECALENTADO" to alertColor
        var y = height / 2f + 70 * dp
        for ((m, col) in msgs) {
            textBig.color = col
            c.drawText(m, width / 2f - textBig.measureText(m) / 2, y, textBig)
            y += 24 * dp
        }
        textBig.color = hudColor
    }

    private fun drawMuzzleFlash(c: Canvas) {
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
