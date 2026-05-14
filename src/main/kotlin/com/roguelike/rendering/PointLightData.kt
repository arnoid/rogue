package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Runtime representation of an active point light for the shadow volume pipeline.
 */
data class PointLightData(
    val position: Vector3f,
    val color: Vector4f,
    val intensity: Float,
    val radius: Float
)
