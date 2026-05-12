package com.roguelike.core.model

import java.util.UUID

/**
 * A freely-placed decoration / furniture prop in the world.
 * Unlike tiles, props are NOT snapped to the grid — they use exact float coordinates.
 */
data class Prop(
    val id: String = UUID.randomUUID().toString(),
    /** Path to the model file (relative to assets). */
    val modelPath: String,
    /** Display name for the palette. */
    val name: String = modelPath.substringAfterLast("/").substringBeforeLast("."),
    /** World-space position. */
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    /** Rotation around Y axis in degrees. */
    var rotationY: Float = 0f,
    /** Uniform scale factor. */
    var scale: Float = 1f,
    /** Half-size for collision bounding box (X axis, before rotation). */
    var collisionHalfSizeX: Float = 0.25f,
    /** Half-size for collision bounding box (Y axis, before rotation). */
    var collisionHalfSizeY: Float = 0.25f
) {
    /** Backward-compatible single half-size (max of X and Y). */
    var collisionHalfSize: Float
        get() = maxOf(collisionHalfSizeX, collisionHalfSizeY)
        set(value) { collisionHalfSizeX = value; collisionHalfSizeY = value }

    /**
     * Returns the axis-aligned half-sizes (hsX, hsY) after applying [scale] and [rotationY].
     * This gives the AABB of the scaled, rotated rectangle.
     */
    fun rotatedHalfSizes(): Pair<Float, Float> {
        val scaledHsX = collisionHalfSizeX * scale
        val scaledHsY = collisionHalfSizeY * scale
        val rad = Math.toRadians(rotationY.toDouble())
        val cosA = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val sinA = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val aabbHsX = scaledHsX * cosA + scaledHsY * sinA
        val aabbHsY = scaledHsX * sinA + scaledHsY * cosA
        return aabbHsX to aabbHsY
    }
}
