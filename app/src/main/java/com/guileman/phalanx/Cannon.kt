package com.guileman.phalanx

/**
 * Cañón de tiro a tiro: TAM (105 mm) u obús CITER (155 mm).
 * Maneja munición, recarga y, en el obús, las cargas de proyección.
 */
class CannonSystem(val weapon: Weapon) {

    /** Tipo de munición con sus parámetros balísticos. */
    class Ammo(
        val label: String,
        val longName: String,
        val kind: Int,
        val speed: Float,       // m/s
        val drag: Float,        // 1/s
        val damage: Int,        // impacto directo
        val blastRadius: Float, // m (0 = sin daño en área)
        val blastDamage: Int,
        var count: Int,
    )

    companion object {
        /**
         * Cargas del obús (velocidad de salida). Escaladas para que los blancos queden a 1–3,5 km
         * y se puedan ver con la cámara; el CITER real tira mucho más lejos.
         */
        val CHARGES = floatArrayOf(110f, 140f, 170f, 200f, 230f)
        const val OBUS_DRAG = 0.012f
    }

    val ammo: List<Ammo> = if (weapon == Weapon.TAM) listOf(
        Ammo("APFSDS", "PERFORANTE (FLECHA)", Ballistics.K_105_AP, 1450f, 0.02f, 12, 0f, 0, 25),
        Ammo("HE", "EXPLOSIVA", Ballistics.K_105_HE, 730f, 0.035f, 6, 9f, 4, 25),
    ) else listOf(
        Ammo("HE", "EXPLOSIVA 155", Ballistics.K_155_HE, CHARGES[2], OBUS_DRAG, 12, 22f, 8, 40),
    )

    var selected = 0
        private set
    val current get() = ammo[selected]

    /** Carga del obús (0..4 → CARGA 1..5). */
    var charge = 2
        private set

    val reloadTime = if (weapon == Weapon.TAM) 5f else 7f
    /** Tiempo que falta para tener el cañón cargado (0 = listo). */
    var reloadLeft = 1.5f
        private set
    /** Se acaba de completar la recarga en este frame (para el sonido del cierre). */
    var justLoaded = false
        private set
    var shots = 0
        private set

    val ready get() = reloadLeft <= 0f && current.count > 0

    /** Velocidad de salida del próximo disparo. */
    val muzzleSpeed get() = if (weapon == Weapon.OBUS) CHARGES[charge] else current.speed

    fun cycleAmmo(): Ammo {
        selected = (selected + 1) % ammo.size
        // cambiar de munición obliga a descargar y cargar otra granada
        if (weapon == Weapon.TAM && reloadLeft <= 0f) reloadLeft = reloadTime * 0.6f
        return current
    }

    fun cycleCharge(): Int {
        charge = (charge + 1) % CHARGES.size
        return charge + 1
    }

    fun update(dt: Float) {
        justLoaded = false
        if (reloadLeft > 0f) {
            reloadLeft -= dt
            if (reloadLeft <= 0f) { reloadLeft = 0f; justLoaded = current.count > 0 }
        }
    }

    /** Consume un disparo. @return la munición disparada o null si no está listo. */
    fun fire(): Ammo? {
        if (!ready) return null
        val a = current
        a.count--
        shots++
        reloadLeft = reloadTime
        return a
    }

    val totalLeft get() = ammo.sumOf { it.count }
}
