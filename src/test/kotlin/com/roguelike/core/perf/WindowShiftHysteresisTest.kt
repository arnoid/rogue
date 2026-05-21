package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WindowShiftHysteresisTest {

    @Test
    fun `first call always shifts to desired`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)
        val r = h.resolve(Triple(10, 20, 30))
        assertEquals(Triple(10, 20, 30), r)
    }

    @Test
    fun `tiny moves below threshold stay put`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 0)
        h.resolve(Triple(100, 100, 100)) // anchor
        // 3 cells of drift on the biggest axis — below threshold of 4.
        repeat(20) {
            val r = h.resolve(Triple(103, 100, 100))
            assertEquals(Triple(100, 100, 100), r,
                "Sub-threshold drift MUST hold the anchor.")
        }
    }

    @Test
    fun `move at threshold shifts when cooldown elapsed`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)
        h.resolve(Triple(0, 0, 0)) // anchor (frame 0)
        // Frames 1..8 — request a 5-cell shift, but cooldown not yet elapsed.
        for (frame in 1..7) {
            val r = h.resolve(Triple(5, 0, 0))
            assertEquals(Triple(0, 0, 0), r,
                "Cooldown not elapsed at frame=$frame — must hold.")
        }
        // Frame 8 — cooldown elapsed, threshold met.
        val r = h.resolve(Triple(5, 0, 0))
        assertEquals(Triple(5, 0, 0), r)
    }

    @Test
    fun `forceShift overrides cooldown`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 100)
        h.resolve(Triple(0, 0, 0)) // anchor
        // Next frame: force a shift despite cooldown being miles off.
        val r = h.resolve(Triple(50, 0, 0), forceShift = true)
        assertEquals(Triple(50, 0, 0), r)
    }

    @Test
    fun `axis with biggest delta drives the threshold`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 0)
        h.resolve(Triple(0, 0, 0))
        // dx=1, dy=1, dz=5 — Z axis trips the threshold.
        val r = h.resolve(Triple(1, 1, 5))
        assertEquals(Triple(1, 1, 5), r)
    }
}

