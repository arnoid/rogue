package com.roguelike.rendering

import com.roguelike.core.model.lighting.GpuLightEnvironment
import com.roguelike.core.model.lighting.PointLightData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShadowRendererConstructionTest {

    @Test
    fun `GpuLightEnvironment build with no lights produces ambient-only env`() {
        val gpuEnv = GpuLightEnvironment(
            directionalLight = null,
            pointLights = emptyList(),
            ambientR = 0.2f, ambientG = 0.2f, ambientB = 0.2f
        )
        assertNull(gpuEnv.directionalLight)
        assertTrue(gpuEnv.pointLights.isEmpty())
        assertEquals(0.2f, gpuEnv.ambientR, 0.001f)
    }

    @Test
    fun `GpuLightEnvironment build with point lights retains them`() {
        val pt = PointLightData(5f, 5f, 5f, 1f, 0.6f, 0.1f, 15f)
        val gpuEnv = GpuLightEnvironment.build(null, listOf(pt))
        assertEquals(1, gpuEnv.pointLights.size)
        assertEquals(pt, gpuEnv.pointLights[0])
    }

    @Test
    fun `ShadowRenderer fromActor with empty inventory returns zero point lights`() {
        val actor = com.roguelike.core.model.Player()
        val env = ShadowRenderer.fromActor(actor)
        assertTrue(env.pointLights.isEmpty())
    }
}
