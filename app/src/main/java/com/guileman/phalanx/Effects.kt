package com.guileman.phalanx

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/** Sprite sheet en grilla (cuadros del mismo tamaño, leídos fila por fila). */
class SpriteSheet(res: Resources, id: Int, private val cols: Int, rows: Int) {
    val bitmap: Bitmap = BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    val frames = cols * rows
    private val fw = bitmap.width / cols
    private val fh = bitmap.height / rows
    private val src = Rect()
    private val dst = RectF()

    /** Dibuja el cuadro [frame] centrado en (x, y) con radio [radius] px, rotado [rotDeg]. */
    fun draw(c: Canvas, frame: Int, x: Float, y: Float, radius: Float, rotDeg: Float, paint: Paint) {
        val f = frame.coerceIn(0, frames - 1)
        val cx = f % cols; val cy = f / cols
        src.set(cx * fw, cy * fh, (cx + 1) * fw, (cy + 1) * fh)
        dst.set(x - radius, y - radius, x + radius, y + radius)
        if (rotDeg != 0f) {
            c.save(); c.rotate(rotDeg, x, y)
            c.drawBitmap(bitmap, src, dst, paint)
            c.restore()
        } else {
            c.drawBitmap(bitmap, src, dst, paint)
        }
    }
}

/**
 * Explosiones y humo anclados al MUNDO (como los proyectiles): se guardan con posición
 * en metros (Este/Norte/Arriba) y radio en metros, así su tamaño en pantalla depende
 * de la distancia y quedan fijos al entorno aunque se mueva el celular.
 */
class Effects(res: Resources) {

    private val explosionSheet = SpriteSheet(res, R.drawable.explosion_sheet, 4, 4)
    private val smokeSheet = SpriteSheet(res, R.drawable.smoke_sheet, 2, 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rnd = Random(System.nanoTime())

    private class Fx(
        var x: Float, var y: Float, var z: Float,
        val radius: Float,          // m
        val life: Float,            // s
        val smoke: Boolean,
        val variant: Int,
        val rot: Float,
        val rise: Float,            // m/s hacia arriba (el humo sube)
        val grow: Float,            // factor de crecimiento al final de la vida
        val alpha: Float,
    ) { var age = 0f }

    private val list = ArrayList<Fx>()
    private val p = FloatArray(2)

    val count get() = list.size

    /** Explosión: [radius] en metros. */
    fun explode(x: Float, y: Float, z: Float, radius: Float, life: Float = 0.9f) {
        if (list.size > 400) return
        list += Fx(x, y, z, radius, life * (0.85f + rnd.nextFloat() * 0.3f), false, 0,
            rnd.nextFloat() * 360f, radius * 0.15f, 1f, 1f)
    }

    /** Bocanada de humo (estelas, incendios). */
    fun smoke(x: Float, y: Float, z: Float, radius: Float, life: Float = 3f, alpha: Float = 0.85f) {
        if (list.size > 400) return
        list += Fx(x, y, z, radius, life * (0.8f + rnd.nextFloat() * 0.4f), true, rnd.nextInt(4),
            rnd.nextFloat() * 360f, 1.5f + rnd.nextFloat() * 2f, 3.2f, alpha)
    }

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.age += dt
            f.z += f.rise * dt
            if (f.age >= f.life) it.remove()
        }
    }

    /** Dibuja de lejos a cerca. Las coordenadas del mundo se proyectan con la orientación actual. */
    fun draw(c: Canvas, projector: Projector, r: FloatArray) {
        if (list.isEmpty()) return
        list.sortByDescending { it.x * it.x + it.y * it.y + it.z * it.z }
        for (f in list) {
            if (!projector.worldToScreen(r, f.x, f.y, f.z, p)) continue
            val dist = kotlin.math.sqrt(f.x * f.x + f.y * f.y + f.z * f.z).coerceAtLeast(0.5f)
            val t = f.age / f.life
            if (f.smoke) {
                val radiusM = f.radius * (1f + (f.grow - 1f) * t)
                val px = radiusM * projector.focalPx / dist
                if (px < 0.8f) continue
                paint.alpha = (255 * f.alpha * (1f - t) * kotlin.math.min(1f, t * 8f)).toInt()
                smokeSheet.draw(c, f.variant, p[0], p[1], px, f.rot + t * 25f, paint)
            } else {
                // el fuego del sprite ocupa ~70% del cuadro: se agranda para que el radio coincida
                val px = f.radius * 1.45f * projector.focalPx / dist
                if (px < 0.8f) continue
                paint.alpha = 255
                val frame = (t * explosionSheet.frames).toInt()
                explosionSheet.draw(c, frame, p[0], p[1], px, f.rot, paint)
            }
        }
        paint.alpha = 255
    }
}
