package com.roguelike.core.perf

import kotlin.math.abs

/**
 * State machine controlling when `RoguelikeGame.uploadLighting` re-anchors
 * the player-centred grid window. Pure logic — no Vulkan dependency, fully
 * unit-testable.
 *
 * Why hysteresis exists: the observed 38.9 ms `uploadLighting` outlier
 * (see `specs/008-fps-fov-shadow-culling/research.md`) correlates with
 * the frame where the player crosses a cell boundary and the lighting
 * window re-anchors, causing a cache-miss storm in `shadowCellCache`.
 * If the player is sliding back and forth across a boundary, we'd pay
 * that cost every frame; by requiring [cellThreshold] cells of movement
 * AND [frameCooldown] frames since the last shift, we collapse the
 * sliding-across-boundary worst case to one re-anchor per "real" move.
 *
 * Override the cooldown with `forceShift = true` when the per-frame
 * light count jumps by > 20 % (e.g. a new room just popped into the
 * candidate set) so a room can't go dark for [frameCooldown] frames
 * just because the player happened to enter while shifting.
 */
class WindowShiftHysteresis(
    val cellThreshold: Int = 4,
    val frameCooldown: Int = 8
) {
    private var currentOriginX: Int = Int.MIN_VALUE
    private var currentOriginY: Int = Int.MIN_VALUE
    private var currentOriginZ: Int = Int.MIN_VALUE
    private var framesSinceShift: Int = Int.MAX_VALUE

    /**
     * Returns the origin to actually use this frame: either the supplied
     * [desired] (a shift happened) or the previously frozen origin
     * (held by hysteresis). Updates internal state.
     *
     * The first call always shifts (initialisation case).
     *
     * @param desired      the origin the caller WOULD use absent hysteresis
     * @param forceShift   override hysteresis (e.g. light-count jumped >20%)
     */
    fun resolve(
        desired: Triple<Int, Int, Int>,
        forceShift: Boolean = false
    ): Triple<Int, Int, Int> {
        framesSinceShift = if (framesSinceShift == Int.MAX_VALUE) Int.MAX_VALUE
                           else framesSinceShift + 1

        val firstCall = currentOriginX == Int.MIN_VALUE
        if (firstCall) {
            currentOriginX = desired.first
            currentOriginY = desired.second
            currentOriginZ = desired.third
            framesSinceShift = 0
            return desired
        }

        if (forceShift) {
            currentOriginX = desired.first
            currentOriginY = desired.second
            currentOriginZ = desired.third
            framesSinceShift = 0
            return desired
        }

        val dx = abs(desired.first - currentOriginX)
        val dy = abs(desired.second - currentOriginY)
        val dz = abs(desired.third - currentOriginZ)
        val biggestStep = maxOf(dx, dy, dz)

        val cooldownExpired = framesSinceShift >= frameCooldown
        val moved = biggestStep >= cellThreshold

        return if (moved && cooldownExpired) {
            currentOriginX = desired.first
            currentOriginY = desired.second
            currentOriginZ = desired.third
            framesSinceShift = 0
            desired
        } else {
            Triple(currentOriginX, currentOriginY, currentOriginZ)
        }
    }

    /** Internal state inspection — for tests only. */
    fun debugState(): Triple<Int, Int, Int> = Triple(currentOriginX, currentOriginY, currentOriginZ)
    fun debugFramesSinceShift(): Int = framesSinceShift
}

