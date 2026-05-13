package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3

/**
 * Runtime representation of an active point light for the shadow volume pipeline.
 */
data class PointLightData(
    val position: Vector3,
    val color: Color,
    val intensity: Float,
    val radius: Float
)

