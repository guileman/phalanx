package com.guileman.phalanx

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Menú de inicio: elegir el sistema de armas. */
class MenuActivity : AppCompatActivity() {

    private class Card(val weapon: Weapon, val image: Int, val tagline: String, val blurb: String)

    private val cards = listOf(
        Card(Weapon.PHALANX, R.drawable.weapon_phalanx,
            "Rotativo 20 mm · 4500/min",
            "Defensa de buques. Acá la manejás vos."),
        Card(Weapon.GEPARD, R.drawable.weapon_gepard,
            "Antiaéreo doble 35 mm",
            "Munición AHEAD que estalla adelante."),
        Card(Weapon.JAVELIN, R.drawable.weapon_javelin,
            "Misil · dispara y olvida",
            "Fijá el blanco y cae desde arriba."),
        Card(Weapon.TAM, R.drawable.weapon_tam,
            "Tanque argentino · 105 mm",
            "Láser, computadora de tiro y flechas."),
        Card(Weapon.OBUS, R.drawable.weapon_obus,
            "Obús argentino · 155 mm",
            "Tiro en parábola, mapa y dron."),
    )

    private val green = Color.rgb(0x39, 0xFF, 0x6A)
    private val amber = Color.rgb(0xFF, 0xB0, 0x20)
    private val bg = Color.rgb(0x07, 0x0D, 0x0A)
    private val panel = Color.rgb(0x0E, 0x1A, 0x13)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(buildUi())
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (bold) mono else Typeface.MONOSPACE
    }

    private fun buildUi(): View {
        // todo entra en una pantalla: título arriba y las tres tarjetas ocupando el resto
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(12f).toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        header.addView(text("SISTEMAS DE ARMAS AR", 18f, green, bold = true))
        header.addView(text("   tocá un sistema para entrar", 11f, Color.rgb(0x8C, 0xA8, 0x96)))
        col.addView(header)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, c) in cards.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) lp.marginStart = dp(8f).toInt()
            row.addView(card(c), lp)
        }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8f).toInt()
        })
        return col
    }

    /** Tarjeta: toda la tarjeta es el botón de entrar. */
    private fun card(c: Card): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(10f)
                setStroke(dp(1.5f).toInt(), Color.argb(150, 0x39, 0xFF, 0x6A))
            }
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Entrar a ${c.weapon.title}"
            setOnClickListener { start(c.weapon) }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.7f; v.scaleX = 0.98f; v.scaleY = 0.98f }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }
                }
                false
            }
        }
        val img = ImageView(this).apply {
            setImageResource(c.image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(6f); setColor(Color.BLACK) }
            contentDescription = c.weapon.title
        }
        // la imagen se estira para ocupar el espacio que sobra
        box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(text(c.weapon.title, 12.5f, green, bold = true).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(text("▶", 12.5f, green, bold = true))
        box.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f).toInt()
        })
        box.addView(text(c.tagline, 9.5f, amber, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        box.addView(text(c.blurb, 9.5f, Color.rgb(0xC8, 0xD4, 0xCC)).apply { maxLines = 2 })
        return box
    }

    private fun start(w: Weapon) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(Weapon.EXTRA, w.name))
    }
}
