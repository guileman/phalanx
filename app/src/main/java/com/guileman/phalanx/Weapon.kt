package com.guileman.phalanx

/** Sistemas de armas simulados. */
enum class Weapon(val title: String) {
    PHALANX("PHALANX CIWS"),
    GEPARD("GEPARD 35 mm"),
    JAVELIN("JAVELIN"),
    TAM("TAM 105 mm"),
    OBUS("OBÚS CITER 155");

    /** Cañón automático (usa GunController). */
    val isGun get() = this == PHALANX || this == GEPARD
    /** Cañón de tiro a tiro con carga manual (tanque u obús). */
    val isCannon get() = this == TAM || this == OBUS
    /** Dispara proyectiles balísticos (todo menos el misil). */
    val usesBallistics get() = isGun || isCannon

    companion object {
        const val EXTRA = "weapon"
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: PHALANX
    }
}

/**
 * Modo de fuego.
 * @param burst  tiros por ráfaga automática (0 = continuo mientras se mantenga el botón)
 * @param ahead  munición de espoleta programable (estalla a la distancia del telémetro)
 */
data class FireMode(val label: String, val rpm: Double, val burst: Int = 0, val ahead: Boolean = false)

/** Parámetros de un cañón. */
class GunConfig(
    val spinUpTime: Double,       // s hasta poder disparar
    val spinDownTime: Double,     // s al cortar el fuego
    val brakeTime: Double,        // s al soltar antes de disparar
    val magazine: Int,
    val reloadTime: Double,
    val heatPerShot: Float,
    val muzzleVelocity: Float,    // m/s (munición de contacto)
    val aheadVelocity: Float,     // m/s (munición AHEAD)
    val damage: Int,              // daño por impacto directo contra aviones
    val modes: List<FireMode>,
    val maxSlewDeg: Int,          // velocidad máxima de la torreta (0 = no se muestra límite)
    /** Bocas de fuego en coordenadas del dispositivo (x derecha, y arriba, z hacia el usuario); se alternan. */
    val muzzles: List<FloatArray>,
) {
    companion object {
        /** Phalanx Block 1B: M61A1 de 20 mm, 6 cañones, accionado neumáticamente. */
        val PHALANX = GunConfig(
            spinUpTime = 0.4, spinDownTime = 0.5, brakeTime = 0.3,
            magazine = 1550, reloadTime = 4.0, heatPerShot = 0.0009f,
            muzzleVelocity = 1100f, aheadVelocity = 1100f, damage = 1,
            modes = listOf(
                FireMode("4500", 4500.0), FireMode("3000", 3000.0),
                FireMode("R60", 4500.0, burst = 60), FireMode("R100", 4500.0, burst = 100),
            ),
            maxSlewDeg = 115,
            muzzles = listOf(floatArrayOf(0.12f, -0.35f, -0.25f)),
        )

        /**
         * Gepard: dos cañones Oerlikon KDA de 35 mm (550 disparos/min cada uno, se alternan).
         * Ficción: munición AHEAD de espoleta programable (la del Skynex).
         */
        val GEPARD = GunConfig(
            spinUpTime = 0.06, spinDownTime = 0.15, brakeTime = 0.06,
            magazine = 640, reloadTime = 6.0, heatPerShot = 0.006f,
            muzzleVelocity = 1175f, aheadVelocity = 1050f, damage = 3,
            modes = listOf(FireMode("HE", 1100.0), FireMode("AHEAD", 1100.0, ahead = true)),
            maxSlewDeg = 0,
            muzzles = listOf(floatArrayOf(-0.9f, -0.25f, -0.3f), floatArrayOf(0.9f, -0.25f, -0.3f)),
        )
    }
}
