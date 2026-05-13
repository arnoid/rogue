package com.roguelike.core.model.lighting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PointLightOcclusionDataTest {

    @Test
    fun `GpuLightEnvironment build factory truncates to 8 point lights`() {
        val lights = (1..12).map { PointLightData(it.toFloat(), 0f, 0f, 1f, 1f, 1f, 10f) }
        val env = GpuLightEnvironment.build(null, lights)
        assertEquals(8, env.pointLights.size)
    }

    @Test
    fun `GpuLightEnvironment build with exactly 8 lights keeps all`() {
        val lights = (1..8).map { PointLightData(it.toFloat(), 0f, 0f, 1f, 1f, 1f, 10f) }
        val env = GpuLightEnvironment.build(null, lights)
        assertEquals(8, env.pointLights.size)
    }

    @Test
    fun `GpuLightEnvironment constructor throws on more than 8 point lights`() {
        val lights = (1..9).map { PointLightData(it.toFloat(), 0f, 0f, 1f, 1f, 1f, 10f) }
        assertThrows(IllegalArgumentException::class.java) {
            GpuLightEnvironment(null, lights, 0.2f, 0.2f, 0.2f)
        }
    }
}
