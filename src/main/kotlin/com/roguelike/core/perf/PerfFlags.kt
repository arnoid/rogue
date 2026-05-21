package com.roguelike.core.perf

import java.io.File
import java.util.Properties

/**
 * Single feature-flag object governing every behaviour change made by
 * spec 008 ("FPS Recovery in Multi-Light Scenes"). One toggle, one
 * keybinding (F11), one persistence key (`perf.flags.enabled` in
 * `local.properties`). All call sites MUST read [enabled] once per
 * frame, never inside a tight loop.
 *
 * Why a singleton (not three booleans):
 *   The spec deliberately ships per-tile LOD + frustum-clipped shadow
 *   upload + window-shift hysteresis behind one master switch. The
 *   three optimisations interact (LOD depends on tile bins which
 *   depend on the window) so an A/B comparison that toggles only
 *   "frustum cull" without "LOD" would mis-attribute the perf win.
 *
 * Pixel-identity guarantee:
 *   With `enabled = false`, every change site short-circuits to the
 *   spec-007 code path. `PerfFlagsDisabledTest` (when authored)
 *   asserts pixel-identical rendering vs the pre-spec-008 baseline.
 *
 * See `specs/008-fps-fov-shadow-culling/contracts/perf-flags.md`.
 */
object PerfFlags {
    /**
     * Master toggle for all spec-008 changes. Default `true` so
     * end-users get the perf gains without configuration; capture
     * sessions disable via `perf.flags.enabled=false` in
     * `local.properties`, and dev experimentation toggles via F11.
     */
    @Volatile var enabled: Boolean = true

    /**
     * Fraction of the screen's smaller axis treated as the
     * "centre / high-quality" region. Tiles whose centre is within
     * `centreFraction * min(sw, sh) / 2` of the screen midpoint get
     * quality byte 2; everything else gets quality byte 1.
     */
    @Volatile var centreFraction: Float = 0.40f

    /** Number of taps used by `shadowVisibility` at quality byte 1.
     *  Quality byte 2 keeps the spec-007 5-tap behaviour. */
    const val PCF_TAPS_LOW: Int = 1

    /** Per-pixel light cap at quality byte 1. Quality byte 2 keeps
     *  MAX_PER_PIXEL_LIGHTS = 6 (the shader constant). */
    const val MAX_PER_PIXEL_LIGHTS_LOW: Int = 3

    /**
     * Read the `perf.flags.enabled` key from a `local.properties`-style
     * file. Missing file or missing key leaves [enabled] at its default.
     * Designed for one-shot call from `Main.start()` before the first
     * render frame.
     */
    @JvmStatic
    fun loadFromLocalProperties(file: File) {
        if (!file.isFile) return
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val raw = props.getProperty("perf.flags.enabled") ?: return
        val parsed = raw.trim().lowercase()
        if (parsed == "true" || parsed == "false") {
            enabled = parsed.toBoolean()
        }
    }
}

