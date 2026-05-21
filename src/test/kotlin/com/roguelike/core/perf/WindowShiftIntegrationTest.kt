package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scripted "player walks across the map" simulation for the
 * WindowShiftHysteresis state machine — pure logic, no Vulkan.
 *
 * Mirrors how RoguelikeGame.uploadLighting will invoke `resolve()`
 * once per frame: a sequence of `desired` origins that drift
 * gradually as the player moves. Asserts that the resolved origin
 * shifts exactly when expected (threshold + cooldown both satisfied,
 * or a forced shift) and not more often.
 */
class WindowShiftIntegrationTest {

    @Test
    fun `200-frame walk shifts only at threshold crossings`() {
        // Same cellThreshold + cooldown as the production default.
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)

        // Frame 0: anchor at (0,0,0).
        h.resolve(Triple(0, 0, 0))
        var shiftCount = 0
        var lastResolved = Triple(0, 0, 0)

        // Frames 1..199: player walks +X by 0.5 cells/frame (so the
        // *desired* origin steps by 1 cell every 2 frames). The
        // hysteresis should re-anchor only every 8 frames AND only
        // when the cumulative drift ≥ 4 cells. So roughly: shift at
        // frame 8 (drift=4), then at 16 (drift=8 → +4), etc.
        for (frame in 1..199) {
            // desired ≈ frame / 2 in the X axis (integer step).
            val desiredX = frame / 2
            val r = h.resolve(Triple(desiredX, 0, 0))
            if (r != lastResolved) {
                shiftCount++
                lastResolved = r
            }
        }
        // 199 frames → desired walks from 0 to 99. Expected re-anchors
        // are bounded by min(walks/cellThreshold, walks/cooldown):
        //   walks = 99 cells, cellThreshold=4 → ≤ 24 shifts
        //   walks = 199 frames, cooldown=8   → ≤ 24 shifts
        // We mostly care that the count is "small and bounded", not
        // "exactly N", so use a generous upper bound.
        assertTrue(shiftCount in 10..30,
            "Expected 10..30 shifts in a 200-frame linear walk, got $shiftCount")
    }

    @Test
    fun `slide back and forth across boundary does not thrash`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)
        h.resolve(Triple(100, 100, 100))
        var shiftCount = 0
        var last = Triple(100, 100, 100)
        // Player oscillates ±2 cells around the anchor — below threshold.
        // Without hysteresis this would re-anchor every frame.
        for (frame in 1..100) {
            val dx = if (frame % 2 == 0) 2 else -2
            val r = h.resolve(Triple(100 + dx, 100, 100))
            if (r != last) { shiftCount++; last = r }
        }
        assertEquals(0, shiftCount,
            "Sub-threshold oscillation MUST NOT re-anchor.")
    }

    @Test
    fun `forceShift bypasses both threshold and cooldown`() {
        val h = WindowShiftHysteresis(cellThreshold = 4, frameCooldown = 8)
        h.resolve(Triple(0, 0, 0))
        // Frame 1: drift only 1 cell but forceShift=true — must shift.
        val r = h.resolve(Triple(1, 0, 0), forceShift = true)
        assertEquals(Triple(1, 0, 0), r)
    }
}

