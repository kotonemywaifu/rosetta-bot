package me.liuli.rosetta.util

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

fun distanceXZ(dx: Int, dz: Int): Float {
    val x = abs(dx)
    val z = abs(dz)
    return abs(dx - dz) + min(dx, dz) * sqrt(2f)
}

fun distanceXZ(dx: Double, dz: Double): Double {
    val x = abs(dx)
    val z = abs(dz)
    return abs(dx - dz) + min(dx, dz) * sqrt(2.0)
}