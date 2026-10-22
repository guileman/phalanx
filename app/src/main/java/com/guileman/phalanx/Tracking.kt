package com.guileman.phalanx

import android.graphics.RectF

/**
 * Objeto detectado por la cámara y seguido en el tiempo. Se ancla a una DIRECCIÓN del mundo,
 * así el recuadro queda fijo al entorno aunque se mueva el celular entre detecciones.
 */
class Track(
    val id: Int,
    val label: String,
    val dir: FloatArray,          // dirección unitaria en el mundo
    var angW: Float, var angH: Float,   // tamaño angular (rad): no depende del zoom
    var score: Float,
    var lastSeen: Long,
) {
    val rect = RectF()
    var visible = false
    /** Distancia estimada (m) a partir de la altura real típica de la clase. */
    var range = 0f
    var hits = 0
    var flashUntil = 0L
    var lastExplosion = 0L
    /** Se dejó de seguir (ya no existe). */
    var gone = false
}
