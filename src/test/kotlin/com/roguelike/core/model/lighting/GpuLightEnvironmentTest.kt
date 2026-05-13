package com.roguelike.core.model.lighting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GpuLightEnvironmentTest {

    @Test
    fun `DirectionalLightData holds direction and color fields`() {
        val d = DirectionalLightData(
            directionX = -1f, directionY = -1f, directionZ = -0.5f,
            r = 0.8f, g = 0.8f, b = 0.8f, intensity = 1f
        )
        assertEquals(-1f, d.directionX)
        assertEquals(-1f, d.directionY)
        assertEquals(-0.5f, d.directionZ)
        assertEquals(0.8f, d.r)
        assertEquals(1f, d.intensity)
    }

    @Test
    fun `PointLightData holds position and color fields`() {
        val p = PointLightData(x = 10f, y = 5f, z = 10f, r = 1f, g = 0.6f, b = 0.1f, intensity = 15f)
        assertEquals(10f, p.x)
        assertEquals(5f, p.y)
        assertEquals(10f, p.z)
        assertEquals(15f, p.intensity)
    }

    @Test
    fun `GpuLightEnvironment holds directionalLight and pointLights`() {
        val dir = DirectionalLightData(-1f, -1f, 0f, 1f, 1f, 1f, 0.8f)
        val pt = PointLightData(0f, 0f, 0f, 1f, 0.5f, 0.1f, 10f)
        val env = GpuLightEnvironment(
            directionalLight = dir,
            pointLights = listOf(pt),
            ambientR = 0.2f, ambientG = 0.2f, ambientB = 0.2f
        )
        assertNotNull(env.directionalLight)
        assertEquals(1, env.pointLights.size)
        assertEquals(0.2f, env.ambientR)
    }

    @Test
    fun `GpuLightEnvironment with null directional light is valid`() {
        val env = GpuLightEnvironment(
            directionalLight = null,
            pointLights = emptyList(),
            ambientR = 0.1f, ambientG = 0.1f, ambientB = 0.1f
        )
        assertNull(env.directionalLight)
        assertTrue(env.pointLights.isEmpty())
    }

    @Test
    fun `GpuLightEnvironment build factory coerces pointLights to max 8`() {
        val lights = (1..12).map { PointLightData(it.toFloat(), 0f, 0f, 1f, 1f, 1f, 10f) }
        val env = GpuLightEnvironment.build(directionalLight = null, pointLights = lights)
        assertEquals(8, env.pointLights.size)
    }

    @Test
    fun `GpuLightEnvironment constructor rejects more than 8 point lights`() {
        val lights = (1..12).map { PointLightData(it.toFloat(), 0f, 0f, 1f, 1f, 1f, 10f) }
        assertThrows(IllegalArgumentException::class.java) {
            GpuLightEnvironment(directionalLight = null, pointLights = lights, ambientR = 0.2f, ambientG = 0.2f, ambientB = 0.2f)
        }
    }
}
