package com.roguelike.core.perf

/**
 * Derived perf-metric classifier. No new measurement here — all input
 * numbers already exist in the existing `[Profile]` line. We just label
 * the frame so a human (or a test, see `PerfHudClassifierTest`) can see
 * at a glance whether the slow frame was GPU-bound, an upload spike,
 * or a cache thrash.
 *
 * Labels (see spec FR-006):
 *   `disabled`     — `PerfFlags.enabled == false`; reported regardless
 *                    of measurements so an A/B capture is self-labelling.
 *   `gpu_bound`    — `gpu_ms > 50 %` of frame time.
 *   `upload_spike` — `uploadLighting > 10 ms` (NFR-002 ceiling).
 *   `cache_miss`   — `cache_hit_rate < 0.5`.
 *   `steady`       — none of the above; frame is healthy.
 */
object PerfHud {
    /**
     * Classify a single frame into one of five buckets.
     *
     * @param frameMs        total frame time
     * @param cpuPhases      summed CPU-side phase times (interaction +
     *                       procedural + collectLights + uploadLighting +
     *                       renderWorld). `gpu_ms = frameMs - cpuPhases`.
     * @param uploadMs       uploadLighting phase time, for the spike test.
     * @param cacheHitRate   `1 - misses / touched`. Pass 1.0 when no
     *                       cells were touched this frame.
     * @param flagEnabled    `PerfFlags.enabled`. When false, returns
     *                       `"disabled"` regardless of every other input.
     */
    fun classify(
        frameMs: Float,
        cpuPhases: Float,
        uploadMs: Float,
        cacheHitRate: Float,
        flagEnabled: Boolean
    ): String {
        if (!flagEnabled) return "disabled"
        // Order matters: an upload spike that pushes cpuPhases above the
        // 50 % mark should still be reported as upload_spike (the more
        // actionable label) rather than gpu_bound (which would be wrong
        // — the GPU isn't the bottleneck on a spike frame).
        if (uploadMs > 10.0f) return "upload_spike"
        if (cacheHitRate < 0.5f) return "cache_miss"
        val gpuMs = frameMs - cpuPhases
        if (frameMs > 0.0f && gpuMs > frameMs * 0.5f) return "gpu_bound"
        return "steady"
    }
}

