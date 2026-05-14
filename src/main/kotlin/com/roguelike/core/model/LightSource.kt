package com.roguelike.core.model

import java.util.UUID

/**
 * A point light source placed in the world.
 * Uses exact float coordinates (like [Prop]).
 * No LibGDX dependencies.
 */
data class LightSource(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    /** Light intensity (brightness multiplier). */
    var intensity: Float = 5f,
    /** Light radius (how far the light reaches). */
    var radius: Float = 5f,
    /** Light color as RGB hex (e.g. "ffcc88"). */
    var colorHex: String = "ffcc88"
) {
    /** Parse colorHex to RGB floats. */
    fun colorR(): Float = colorHex.safeHex(0) / 255f
    fun colorG(): Float = colorHex.safeHex(2) / 255f
    fun colorB(): Float = colorHex.safeHex(4) / 255f

    private fun String.safeHex(offset: Int): Float =
        if (length >= offset + 2) substring(offset, offset + 2).toIntOrNull(16)?.toFloat() ?: 255f
        else 255f
}
