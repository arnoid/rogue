package com.roguelike.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Spec 008 / US2 T034: feed the classifier the worst-six frames sampled
 * in `research.md` §1 (the captured `[Profile]` lines from `logs.txt`)
 * and assert it picks the right label for each. Pinning this against
 * the actual captured numbers — instead of synthetic ones — protects
 * against future drift in PerfHud thresholds: if someone bumps the
 * 50 % gpu-bound cutoff or the 10 ms upload spike ceiling, the
 * historical worst frames must continue to classify the same way.
 *
 * `PerfHudTest` (T010) covers the trivial cases; this file covers the
 * real-world ones.
 */
class PerfHudClassifierTest {

    /**
     * Each row mirrors one line from `logs.txt`. The classifier inputs
     * are computed exactly the way `RoguelikeGame` computes them:
     *   cpuPhases = interaction + procedural + collectLights + uploadLighting + renderWorld
     *   gpuMs     = frameMs - cpuPhases   (derived; classifier re-derives)
     *
     * Expected labels follow `research.md` §1's interpretation table:
     *   - 5/6 worst frames are GPU-dominated → "gpu_bound"
     *   - 1/6 (the fps=11.4 outlier) is the upload-spike → "upload_spike"
     *
     * `cacheHitRate` is set to a healthy 0.9 for every row — none of
     * these captured frames triggered a cache_miss-class event according
     * to the historical analysis. (When the perf scene actually ships in
     * T038 and US2 captures real misses, we'll add a dedicated
     * cache-miss row.)
     */
    private data class Sample(
        val name: String,
        val frameMs: Float,
        val cpuPhases: Float,
        val uploadMs: Float,
        val expected: String
    )

    private val samples = listOf(
        // fps=10.6, frame=94.1, Σcpu=5.0, upload=4.3 → 95 % GPU
        Sample("fps10.6_gpu", 94.1f, 5.0f, 4.3f, "gpu_bound"),
        // fps=10.9, frame=92.1, Σcpu=12.0, upload=10.8 → upload just above 10 ms ceiling
        // Order in PerfHud.classify: upload_spike beats gpu_bound, so this is "upload_spike".
        Sample("fps10.9_upload_edge", 92.1f, 12.0f, 10.8f, "upload_spike"),
        // fps=11.4, frame=88.0, Σcpu=52.7, upload=38.9 → the CPU spike outlier
        Sample("fps11.4_upload_spike", 88.0f, 52.7f, 38.9f, "upload_spike"),
        // fps=11.8, frame=84.9, Σcpu=7.2, upload=6.3 → 92 % GPU
        Sample("fps11.8_gpu", 84.9f, 7.2f, 6.3f, "gpu_bound"),
        // fps=12.5, frame=80.2, Σcpu=5.0, upload=4.5 → 94 % GPU
        Sample("fps12.5_gpu", 80.2f, 5.0f, 4.5f, "gpu_bound"),
        // fps=13.1, frame=76.6, Σcpu=6.2, upload=5.4 → 92 % GPU
        Sample("fps13.1_gpu", 76.6f, 6.2f, 5.4f, "gpu_bound"),
    )

    @TestFactory
    fun `worst-six historical frames classify correctly`(): List<DynamicTest> =
        samples.map { s ->
            DynamicTest.dynamicTest(s.name) {
                val actual = PerfHud.classify(
                    frameMs = s.frameMs,
                    cpuPhases = s.cpuPhases,
                    uploadMs = s.uploadMs,
                    cacheHitRate = 0.9f,
                    flagEnabled = true
                )
                assertEquals(s.expected, actual,
                    "Frame ${s.name} (frame=${s.frameMs}ms cpu=${s.cpuPhases}ms upload=${s.uploadMs}ms)")
            }
        }
}

