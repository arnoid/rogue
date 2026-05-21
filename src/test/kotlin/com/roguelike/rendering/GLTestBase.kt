package com.roguelike.rendering

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import com.roguelike.core.perf.PerfFlags

/**
 * Base class for visual rendering tests that require a GL context.
 * Skips all tests gracefully if GL initialization fails (e.g., no GPU in CI).
 *
 * spec 008 (FPS recovery): the legacy visual-regression suite pins
 * spec-007 pixel behaviour. Per the perf-flags contract
 * (specs/008-fps-fov-shadow-culling/contracts/perf-flags.md), all
 * spec-008 changes short-circuit when `PerfFlags.enabled = false`,
 * giving pixel-identical output. We force the flag off for the
 * lifetime of this test class so peripheral-tile LOD, frustum-cull,
 * and window-shift hysteresis can't accidentally drift these pinned
 * pixel values. Spec-008-specific visual tests will run with the
 * flag explicitly on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class GLTestBase {
    protected lateinit var harness: RenderTestHarness
    private var glAvailable = false
    private var savedPerfFlag: Boolean = true

    @BeforeAll
    fun setupGL() {
        savedPerfFlag = PerfFlags.enabled
        PerfFlags.enabled = false
        harness = RenderTestHarness()
        glAvailable = harness.initialize()
    }

    @AfterAll
    fun teardownGL() {
        if (glAvailable) {
            harness.dispose()
        }
        PerfFlags.enabled = savedPerfFlag
    }

    /** Call at the start of each test to skip if GL is not available. */
    protected fun requireGL() {
        Assumptions.assumeTrue(glAvailable, "GL context not available — skipping visual test")
    }
}

