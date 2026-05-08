package com.roguelike.core.math

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lightweight 3-component float vector with no LibGDX dependency.
 * Used throughout the core (logic) layer. Rendering code may convert
 * to/from LibGDX Vector3 via extension functions in Vec3GdxBridge.kt.
 */
data class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {

    /** Copy constructor. */
    constructor(other: Vec3) : this(other.x, other.y, other.z)

    val isZero: Boolean get() = x == 0f && y == 0f && z == 0f

    fun len(): Float = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    /** Normalise in place; no-op if zero-length. */
    fun nor(): Vec3 {
        val l = len()
        if (l != 0f) { x /= l; y /= l; z /= l }
        return this
    }

    /** Scale in place. */
    fun scl(f: Float): Vec3 { x *= f; y *= f; z *= f; return this }

    /** Signed unit indicating which direction x is pointing. */
    fun signX(): Float = sign(x)

    /** Signed unit indicating which direction y is pointing. */
    fun signY(): Float = sign(y)

    override fun toString() = "Vec3($x, $y, $z)"
}
