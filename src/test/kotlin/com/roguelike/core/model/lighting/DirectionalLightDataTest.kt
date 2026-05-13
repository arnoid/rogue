package com.roguelike.core.model.lighting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DirectionalLightDataTest {

    @Test
    fun `DirectionalLightData holds all field values correctly`() {
        val d = DirectionalLightData(
            directionX = 0f, directionY = -1f, directionZ = 0f,
            r = 1f, g = 1f, b = 1f, intensity = 0.8f
        )
        assertEquals(0f, d.directionX)
        assertEquals(-1f, d.directionY)
        assertEquals(0f, d.directionZ)
        assertEquals(0.8f, d.intensity)
    }

    @Test
    fun `DirectionalLightData with zero direction is valid data class state`() {
        val d = DirectionalLightData(0f, 0f, 0f, 1f, 1f, 1f, 1f)
        assertEquals(0f, d.directionX)
        assertEquals(0f, d.directionY)
        assertEquals(0f, d.directionZ)
    }

    @Test
    fun `DirectionalLightData is a value type with equals`() {
        val a = DirectionalLightData(-1f, -1f, -0.5f, 0.8f, 0.8f, 0.8f, 1f)
        val b = DirectionalLightData(-1f, -1f, -0.5f, 0.8f, 0.8f, 0.8f, 1f)
        assertEquals(a, b)
    }
}
