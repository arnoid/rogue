package com.roguelike.core

import com.roguelike.core.systems.softConeFactor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.PI

class ConeUtilsTest {

    private val cosHard = cos(45.0 * PI / 180.0).toFloat()  // cos(45°) ≈ 0.7071
    private val cosSoft = cos(48.0 * PI / 180.0).toFloat()  // cos(48°) ≈ 0.6691  (feather = 3°)

    @Test
    fun `fully inside hard cone returns 1`() {
        val dot = 1.0f  // perfectly aligned with cone axis
        assertEquals(1.0f, softConeFactor(dot, cosHard, cosSoft), 0.001f)
    }

    @Test
    fun `at hard edge returns 1`() {
        assertEquals(1.0f, softConeFactor(cosHard, cosHard, cosSoft), 0.001f)
    }

    @Test
    fun `at soft edge returns 0`() {
        assertEquals(0.0f, softConeFactor(cosSoft, cosHard, cosSoft), 0.001f)
    }

    @Test
    fun `fully outside soft boundary returns 0`() {
        val dot = 0.0f  // far outside
        assertEquals(0.0f, softConeFactor(dot, cosHard, cosSoft), 0.001f)
    }

    @Test
    fun `midpoint of penumbra returns approximately 0_5`() {
        val mid = (cosHard + cosSoft) / 2f
        val result = softConeFactor(mid, cosHard, cosSoft)
        assertEquals(0.5f, result, 0.01f)
    }

    @Test
    fun `result is clamped to 0_1 range`() {
        val result1 = softConeFactor(2.0f, cosHard, cosSoft)
        val result2 = softConeFactor(-1.0f, cosHard, cosSoft)
        assertTrue(result1 <= 1.0f)
        assertTrue(result2 >= 0.0f)
    }

    @Test
    fun `zero feather degenerates to step function when cosHardEdge equals cosSoftEdge`() {
        // When feather = 0, cosHard == cosSoft — caller must guard against this.
        // softConeFactor with a tiny epsilon gap still produces step-like behavior.
        val eps = 1e-6f
        val cosEdge = cos(45.0 * PI / 180.0).toFloat()
        // Just inside: returns 1
        assertEquals(1.0f, softConeFactor(cosEdge, cosEdge, cosEdge - eps), 0.01f)
        // Just outside: returns 0
        assertEquals(0.0f, softConeFactor(cosEdge - eps * 2, cosEdge, cosEdge - eps), 0.01f)
    }
}
