package com.roguelike.rendering

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 008 / US2 T033 + T039: deterministic perf-regression gate.
 *
 * Loads `saved-worlds/perf/dense-lights.wld` via the offscreen Vulkan
 * harness, spawns the player at the `PERF_PROBE` node tag, simulates
 * a 5-second 360° camera rotation, collects per-frame timings, and
 * asserts the spec-008 success criteria:
 *
 *   * SC-001  min(fps) ≥ 30  AND  p99(frame_ms) ≤ 33
 *   * SC-003  p99(uploadLighting) ≤ 10 ms
 *
 * The test self-disables under two conditions:
 *
 *   1. Vulkan unavailable — same gate as every other render test
 *      (see `VulkanAvailability`).
 *   2. `saved-worlds/perf/dense-lights.wld` is missing — that scene
 *      ships in T038, which requires hand-authoring in the in-game
 *      editor and is blocking. The `@Disabled` annotation can be
 *      removed once both gates pass.
 *
 * If you are reading this comment because the test surprised you
 * after T038 landed: drop `@Disabled`, run, and capture the
 * before/after [Profile] log into `after.log` per `quickstart.md` §9.
 *
 * Note: the test harness in `RenderTestHarness` is currently a
 * static-scene renderer — it does not run the full per-frame
 * `RoguelikeGame.render()` loop. Wiring the perf scene through it
 * requires extending the harness with a "load .wld + drive
 * uploadLighting + measure" entry point. That work is intentionally
 * scoped to T039 (the runner) so this file (T033) only contains the
 * test surface; the harness extension lands with T039.
 */
@Disabled("T033/T039: blocked on T038 (saved-worlds/perf/dense-lights.wld) and on " +
    "RenderTestHarness gaining a `simulateGameLoop` entry point. Remove this " +
    "annotation once both ship.")
class PerfRegressionTest {

    @Test
    fun `dense-lights scene meets SC-001 and SC-003`() {
        if (!VulkanAvailability.isAvailable()) {
            // The @Disabled annotation above already gates this, but
            // keep the explicit check so removing @Disabled on a
            // headless CI box prints a helpful message instead of
            // crashing inside Vulkan init.
            return
        }

        val sceneFile = locatePerfScene()
        if (sceneFile == null) {
            fail<Unit>("saved-worlds/perf/dense-lights.wld is missing — see T038 in tasks.md")
            return
        }

        // TODO(T039): replace the placeholder block below with the real
        // simulate-and-measure call once `RenderTestHarness.simulateGameLoop`
        // exists. The placeholder intentionally fails so that removing
        // @Disabled before the runner is wired produces a clear "not
        // yet implemented" message rather than a silent green.
        fail<Unit>("PerfRegressionTest runner not yet wired (T039). " +
            "Expected entry point: RenderTestHarness.simulateGameLoop($sceneFile, " +
            "spawnTag=\"PERF_PROBE\", durationSec=5.0f, rotateDegrees=360f).")
    }

    private fun locatePerfScene(): File? {
        // Test working dir is `src/main/resources` per build.gradle.kts.
        // Walk up to the repo root to find the perf scene.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "saved-worlds/perf/dense-lights.wld")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}


