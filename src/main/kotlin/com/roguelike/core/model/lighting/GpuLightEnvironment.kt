package com.roguelike.core.model.lighting

private const val MAX_POINT_LIGHTS = 8

data class GpuLightEnvironment(
    val directionalLight: DirectionalLightData?,
    val pointLights: List<PointLightData>,
    val ambientR: Float,
    val ambientG: Float,
    val ambientB: Float
) {
    init {
        require(pointLights.size <= MAX_POINT_LIGHTS) {
            "GpuLightEnvironment: pointLights exceeds max ($MAX_POINT_LIGHTS); truncate before constructing."
        }
    }

    companion object {
        fun build(
            directionalLight: DirectionalLightData?,
            pointLights: List<PointLightData>,
            ambientR: Float = 0.2f,
            ambientG: Float = 0.2f,
            ambientB: Float = 0.2f
        ) = GpuLightEnvironment(
            directionalLight = directionalLight,
            pointLights = pointLights.take(MAX_POINT_LIGHTS),
            ambientR = ambientR,
            ambientG = ambientG,
            ambientB = ambientB
        )
    }
}
