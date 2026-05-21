package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PerfHudTest {

    @Test
    fun `disabled flag always reports disabled`() {
        assertEquals("disabled",
            PerfHud.classify(frameMs = 50f, cpuPhases = 5f, uploadMs = 50f, cacheHitRate = 0.1f, flagEnabled = false))
    }

    @Test
    fun `healthy frame reports steady`() {
        // 16 ms frame, 10 ms CPU → 6 ms GPU = 37 % of frame; not gpu_bound.
        assertEquals("steady",
            PerfHud.classify(frameMs = 16f, cpuPhases = 10f, uploadMs = 2f, cacheHitRate = 0.95f, flagEnabled = true))
    }

    @Test
    fun `gpu dominated frame reports gpu_bound`() {
        // 33 ms frame, 5 ms CPU → 28 ms GPU >> 50 % of frame
        assertEquals("gpu_bound",
            PerfHud.classify(frameMs = 33f, cpuPhases = 5f, uploadMs = 3f, cacheHitRate = 0.9f, flagEnabled = true))
    }

    @Test
    fun `upload spike beats gpu_bound`() {
        // CPU phases > 50 % of frame but the spike label is more actionable.
        assertEquals("upload_spike",
            PerfHud.classify(frameMs = 40f, cpuPhases = 30f, uploadMs = 25f, cacheHitRate = 0.9f, flagEnabled = true))
    }

    @Test
    fun `cache miss reported when hit rate below half`() {
        assertEquals("cache_miss",
            PerfHud.classify(frameMs = 20f, cpuPhases = 4f, uploadMs = 3f, cacheHitRate = 0.3f, flagEnabled = true))
    }
}


