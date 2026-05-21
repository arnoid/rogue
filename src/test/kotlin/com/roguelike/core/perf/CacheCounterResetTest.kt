package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec 008 / US2 T037: pin the invariant that
 * `RoguelikeGame.shadowCellCacheMissCount` and `shadowCellsTouchedCount`
 * are reset at the top of `uploadLighting`, not accumulated across
 * frames forever.
 *
 * The counters live behind a private mutable in `RoguelikeGame`, and
 * the class itself is hard to instantiate from a JUnit test (Vulkan
 * UI, asset loaders, swap-chain). Rather than spin all that up, this
 * test inspects the source file directly:
 *
 *   1. The two declarations exist.
 *   2. They are zeroed at the top of `uploadLighting` (i.e. the
 *      assignment to 0 appears textually before the `// ── 1.` step
 *      header that begins the per-frame work).
 *
 * If a future refactor moves the counters out of `RoguelikeGame`,
 * delete this file and replace it with a real per-frame loop test.
 *
 * The "100 frames bounded" simulation also lives here — running
 * `PerfHud.classify` 100 times with a synthetic cache-hit ratio that
 * stays the same proves the classifier doesn't accumulate state of
 * its own (it is stateless by design, but pinning it lets future
 * `@Volatile` additions ring a bell).
 */
class CacheCounterResetTest {

    private val sourceFile: File = run {
        // Test working dir is `src/main/resources` per build.gradle.kts.
        // Walk up to the repo root and pick the source file.
        val cwd = File(".").absoluteFile
        var dir: File? = cwd
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/roguelike/RoguelikeGame.kt")
            if (candidate.isFile) return@run candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate RoguelikeGame.kt from working dir $cwd")
    }

    @Test
    fun `counters declared in RoguelikeGame`() {
        val src = sourceFile.readText()
        assertTrue(src.contains("shadowCellCacheMissCount"),
            "RoguelikeGame must declare shadowCellCacheMissCount (spec 008 / T035, T037)")
        assertTrue(src.contains("shadowCellsTouchedCount"),
            "RoguelikeGame must declare shadowCellsTouchedCount (spec 008 / T035, T037)")
    }

    @Test
    fun `counters reset at top of uploadLighting`() {
        val src = sourceFile.readText()
        val fnIdx = src.indexOf("private fun uploadLighting(")
        assertTrue(fnIdx >= 0, "uploadLighting() not found")

        // The reset block must appear before the first "// ── 1." header
        // (the start of the frustum-cull step). If the reset moves to
        // the end of the function or is dropped entirely, the
        // hit-rate the HUD shows will drift toward 1.0 over time and
        // PerfHud.classify will stop firing `cache_miss` even on bad
        // frames.
        val step1Idx = src.indexOf("// ── 1.", startIndex = fnIdx)
        assertTrue(step1Idx > fnIdx, "Step 1 header not found inside uploadLighting()")

        val prefix = src.substring(fnIdx, step1Idx)
        assertTrue(
            prefix.contains("shadowCellCacheMissCount = 0") &&
                prefix.contains("shadowCellsTouchedCount = 0"),
            "Counters must be zeroed at the top of uploadLighting() (spec 008 / T037). " +
                "Found prefix:\n$prefix"
        )
    }

    @Test
    fun `classifier is stateless across 100 frames`() {
        // Feed the classifier the same numbers 100 times; the label
        // must never drift. Catches accidental @Volatile state in
        // PerfHud if added later.
        val frames = (1..100).map {
            PerfHud.classify(
                frameMs = 33f,
                cpuPhases = 5f,
                uploadMs = 4f,
                cacheHitRate = 0.3f,   // → cache_miss
                flagEnabled = true
            )
        }
        assertTrue(frames.all { it == "cache_miss" },
            "Classifier drifted across 100 frames: distinct labels = ${frames.toSet()}")
    }
}



