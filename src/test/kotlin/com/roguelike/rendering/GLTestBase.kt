package com.roguelike.rendering

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Base class for visual rendering tests that require a GL context.
 * Skips all tests gracefully if GL initialization fails (e.g., no GPU in CI).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class GLTestBase {
    protected lateinit var harness: RenderTestHarness
    private var glAvailable = false

    @BeforeAll
    fun setupGL() {
        harness = RenderTestHarness()
        glAvailable = harness.initialize()
    }

    @AfterAll
    fun teardownGL() {
        if (glAvailable) {
            harness.dispose()
        }
    }

    /** Call at the start of each test to skip if GL is not available. */
    protected fun requireGL() {
        Assumptions.assumeTrue(glAvailable, "GL context not available — skipping visual test")
    }
}

