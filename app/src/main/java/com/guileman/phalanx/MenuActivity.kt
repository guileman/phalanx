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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Menú de inicio: elegir el sistema de armas. */
class MenuActivity : AppCompatActivity() {

    private class Card(
        val weapon: Weapon,
        val image: Int,
        val tagline: String,
        val real: String,
        val fiction: String,
        val controls: String,
    )

    private val cards = listOf(
        Card(
            Weapon.PHALANX, R.drawable.weapon_phalanx,
            "Cañón rotativo de 20 mm · 4500 disparos/min",
            "Defensa de buques contra misiles y aviones. En la realidad es automático: radar y computadora deciden cuándo disparar.",
            "Acá lo apuntás y disparás vos.",
            "FUEGO · RECARGA · MODO (cadencia / ráfagas) · AVIONES",
        ),
        Card(
            Weapon.GEPARD, R.drawable.weapon_gepard,
            "Antiaéreo doble de 35 mm · munición AHEAD",
            "Blindado antiaéreo con dos cañones de 35 mm y radares de búsqueda y seguimiento. La munición AHEAD (del Skynex) estalla delante del blanco y larga una nube de fragmentos.",
            "Apuntado manual y munición AHEAD montada en el Gepard.",
            "FUEGO · RECARGA · MUNICIÓN (HE / AHEAD) · AVIONES",
        ),
        Card(
            Weapon.JAVELIN, R.drawable.weapon_javelin,
            "Misil antitanque · dispara y olvida",
            "Se fija el blanco en la pantalla de la unidad de comando y el misil vuela solo; en modo TOP sube y cae sobre el blanco desde arriba.",
            "También puede fijar aviones.",
            "LANZAR · WFOV/NFOV · TOP/DIR · DÍA/TÉRMICA · AVIONES",
        ),
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
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f).toInt(), dp(14f).toInt(), dp(28f).toInt(), dp(14f).toInt())
        }
        col.addView(text("SIMULADOR DE SISTEMAS DE ARMAS", 20f, green, bold = true))
        col.addView(text("Realidad aumentada · elegí un sistema", 12f, Color.rgb(0x8C, 0xA8, 0x96)).apply {
            setPadding(0, dp(2f).toInt(), 0, dp(10f).toInt())
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, c) in cards.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) lp.marginStart = dp(12f).toInt()
            row.addView(card(c), lp)
        }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(text("Todos los modelos e imágenes son ilustraciones propias del juego.", 10f, Color.rgb(0x5E, 0x72, 0x66)).apply {
            setPadding(0, dp(10f).toInt(), 0, 0)
        })
        scroll.addView(col)
        root.addView(scroll)
        return root
    }

    private fun card(c: Card): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius = dp(10f)
                setStroke(dp(1.5f).toInt(), Color.argb(140, 0x39, 0xFF, 0x6A))
            }
            setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Jugar con ${c.weapon.title}"
            setOnClickListener { start(c.weapon) }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.75f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
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
        box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118f).toInt()))
        box.addView(text(c.weapon.title, 16f, green, bold = true).apply { setPadding(0, dp(8f).toInt(), 0, 0) })
        box.addView(text(c.tagline, 11f, amber, bold = true).apply { setPadding(0, dp(2f).toInt(), 0, dp(6f).toInt()) })
        box.addView(text(c.real, 10.5f, Color.rgb(0xC8, 0xD4, 0xCC)))
        box.addView(text("Ficción: ${c.fiction}", 10.5f, Color.rgb(0x8C, 0xA8, 0x96)).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
            setPadding(0, dp(4f).toInt(), 0, dp(6f).toInt())
        })
        box.addView(text(c.controls, 9.5f, Color.rgb(0x6F, 0x8A, 0x7A)))
        // empuja el botón al fondo de la tarjeta
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val btn = text("ENTRAR  ▶", 13f, bg, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(green); cornerRadius = dp(6f) }
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }
        box.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f).toInt()
        })
        return box
    }

    private fun start(w: Weapon) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(Weapon.EXTRA, w.name))
    }
}
