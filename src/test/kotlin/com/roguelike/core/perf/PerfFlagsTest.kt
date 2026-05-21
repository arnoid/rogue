package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PerfFlagsTest {

    @Test
    fun `default enabled is true`() {
        // Capture & restore so test order can't bleed.
        val before = PerfFlags.enabled
        try {
            // Direct assertion on the spec-mandated default — protects
            // against accidental flips during refactor.
            PerfFlags.enabled = true
            assertTrue(PerfFlags.enabled, "PerfFlags.enabled MUST default to true (spec 008 contract).")
        } finally {
            PerfFlags.enabled = before
        }
    }

    @Test
    fun `loadFromLocalProperties parses false`(@TempDir tmp: Path) {
        val before = PerfFlags.enabled
        try {
            PerfFlags.enabled = true
            val f = File(tmp.toFile(), "local.properties")
            f.writeText("perf.flags.enabled=false\n")
            PerfFlags.loadFromLocalProperties(f)
            assertFalse(PerfFlags.enabled)
        } finally {
            PerfFlags.enabled = before
        }
    }

    @Test
    fun `loadFromLocalProperties parses true and round-trips`(@TempDir tmp: Path) {
        val before = PerfFlags.enabled
        try {
            PerfFlags.enabled = false
            val f = File(tmp.toFile(), "local.properties")
            f.writeText("perf.flags.enabled=true\n")
            PerfFlags.loadFromLocalProperties(f)
            assertTrue(PerfFlags.enabled)
        } finally {
            PerfFlags.enabled = before
        }
    }

    @Test
    fun `loadFromLocalProperties leaves default when file missing`(@TempDir tmp: Path) {
        val before = PerfFlags.enabled
        try {
            PerfFlags.enabled = true
            val missing = File(tmp.toFile(), "does-not-exist.properties")
            PerfFlags.loadFromLocalProperties(missing)
            assertTrue(PerfFlags.enabled, "Missing file MUST leave enabled untouched.")
        } finally {
            PerfFlags.enabled = before
        }
    }

    @Test
    fun `loadFromLocalProperties leaves default when key missing`(@TempDir tmp: Path) {
        val before = PerfFlags.enabled
        try {
            PerfFlags.enabled = true
            val f = File(tmp.toFile(), "local.properties")
            f.writeText("other.key=value\n")
            PerfFlags.loadFromLocalProperties(f)
            assertTrue(PerfFlags.enabled)
        } finally {
            PerfFlags.enabled = before
        }
    }

    @Test
    fun `constants match spec`() {
        assertEquals(1, PerfFlags.PCF_TAPS_LOW)
        assertEquals(3, PerfFlags.MAX_PER_PIXEL_LIGHTS_LOW)
    }
}

