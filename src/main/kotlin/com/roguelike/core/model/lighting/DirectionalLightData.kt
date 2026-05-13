package com.roguelike.core.model.lighting

data class DirectionalLightData(
    val directionX: Float,
    val directionY: Float,
    val directionZ: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val intensity: Float
)
