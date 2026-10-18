package com.guileman.phalanx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Botón circular estilo consola militar. Informa presión y liberación por separado. */
class ControlButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var label = "FUEGO"
        set(v) { field = v; invalidate() }
    var accent = Color.rgb(0xFF, 0x3B, 0x30)
        set(v) { field = v; invalidate() }
    var onPressChanged: ((Boolean) -> Unit)? = null

    /** Modo interruptor: cada toque alterna encendido/apagado (onPressChanged recibe el estado). */
    var toggleMode = false
    var isOn = false
        set(v) { field = v; invalidate() }

    private var pressedNow = false
    private val dp = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - 4 * dp
        body.color = if (pressedNow || (toggleMode && isOn)) accent else Color.argb(110, Color.red(accent) / 3, Color.green(accent) / 3, Color.blue(accent) / 3)
        canvas.drawCircle(cx, cy, r, body)
        ring.color = accent
        ring.strokeWidth = 3 * dp
        canvas.drawCircle(cx, cy, r, ring)
        ring.strokeWidth = 1.2f * dp
        canvas.drawCircle(cx, cy, r * 0.78f, ring)
        text.textSize = r * 0.32f
        canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedState(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setPressedState(false)
        }
        return true
    }

    private fun setPressedState(p: Boolean) {
        if (p == pressedNow) return
        pressedNow = p
        if (p) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (toggleMode) {
            if (p) { isOn = !isOn; onPressChanged?.invoke(isOn) }
        } else {
            onPressChanged?.invoke(p)
        }
        invalidate()
    }
}
