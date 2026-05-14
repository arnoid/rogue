package com.roguelike.rendering

import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.ByteBuffer

/**
 * Result of sampling a rectangular pixel region from a rendered image.
 */
data class RegionStats(
    val avgR: Float,
    val avgG: Float,
    val avgB: Float,
    val avgBrightness: Float,
    val minBrightness: Float,
    val maxBrightness: Float
)

/**
 * Simple RGBA pixel data container replacing libGDX Pixmap.
 */
class PixelData(val width: Int, val height: Int, val pixels: ByteBuffer) {
    fun getPixel(x: Int, y: Int): Int {
        val idx = (y * width + x) * 4
        val r = pixels.get(idx).toInt() and 0xFF
        val g = pixels.get(idx + 1).toInt() and 0xFF
        val b = pixels.get(idx + 2).toInt() and 0xFF
        val a = pixels.get(idx + 3).toInt() and 0xFF
        return (r shl 24) or (g shl 16) or (b shl 8) or a
    }

    fun dispose() {
        // No-op for heap buffers; override for direct buffers
    }

    companion object {
        fun create(width: Int, height: Int): PixelData {
            return PixelData(width, height, ByteBuffer.allocate(width * height * 4))
        }
    }
}

/**
 * Utility for sampling pixel regions and making assertions about rendered images.
 * Uses region averaging with configurable tolerance to handle GPU/driver variations.
 */
class PixelSampler(
    private val pixelData: PixelData,
    private val tolerance: Int = 15
) {
    /**
     * Sample a rectangular region and compute average color/brightness statistics.
     */
    fun sampleRegion(x: Int, y: Int, w: Int, h: Int): RegionStats {
        var totalR = 0.0
        var totalG = 0.0
        var totalB = 0.0
        var minBright = Float.MAX_VALUE
        var maxBright = Float.MIN_VALUE
        var count = 0

        val clampedX = x.coerceIn(0, pixelData.width - 1)
        val clampedY = y.coerceIn(0, pixelData.height - 1)
        val endX = (x + w).coerceIn(0, pixelData.width)
        val endY = (y + h).coerceIn(0, pixelData.height)

        for (py in clampedY until endY) {
            for (px in clampedX until endX) {
                val pixel = pixelData.getPixel(px, py)
                val r = ((pixel ushr 24) and 0xFF).toFloat()
                val g = ((pixel ushr 16) and 0xFF).toFloat()
                val b = ((pixel ushr 8) and 0xFF).toFloat()
                totalR += r
                totalG += g
                totalB += b
                val brightness = (r + g + b) / 3f
                if (brightness < minBright) minBright = brightness
                if (brightness > maxBright) maxBright = brightness
                count++
            }
        }

        if (count == 0) return RegionStats(0f, 0f, 0f, 0f, 0f, 0f)

        val avgR = (totalR / count).toFloat()
        val avgG = (totalG / count).toFloat()
        val avgB = (totalB / count).toFloat()
        val avgBrightness = (avgR + avgG + avgB) / 3f

        return RegionStats(avgR, avgG, avgB, avgBrightness, minBright, maxBright)
    }

    /**
     * Assert that a region is lit (brightness above threshold).
     */
    fun assertLit(x: Int, y: Int, w: Int, h: Int, minBrightness: Float = 40f) {
        val stats = sampleRegion(x, y, w, h)
        assertTrue(
            stats.avgBrightness >= minBrightness,
            "Expected lit region at ($x,$y ${w}x${h}): avgBrightness=${stats.avgBrightness} < $minBrightness"
        )
    }

    /**
     * Assert that a region is in shadow (brightness below threshold).
     */
    fun assertShadowed(x: Int, y: Int, w: Int, h: Int, maxBrightness: Float = 25f) {
        val stats = sampleRegion(x, y, w, h)
        assertTrue(
            stats.avgBrightness <= maxBrightness,
            "Expected shadowed region at ($x,$y ${w}x${h}): avgBrightness=${stats.avgBrightness} > $maxBrightness"
        )
    }

    /**
     * Assert that the lit region is brighter than the shadow region by a given factor.
     */
    fun assertBrighterThan(
        litX: Int, litY: Int, litW: Int, litH: Int,
        shadowX: Int, shadowY: Int, shadowW: Int, shadowH: Int,
        factor: Float = 1.5f
    ) {
        val litStats = sampleRegion(litX, litY, litW, litH)
        val shadowStats = sampleRegion(shadowX, shadowY, shadowW, shadowH)
        assertTrue(
            litStats.avgBrightness > shadowStats.avgBrightness * factor,
            "Expected lit region (${litStats.avgBrightness}) > shadow region (${shadowStats.avgBrightness}) * $factor"
        )
    }

    /**
     * Assert that a region has the expected color within tolerance.
     */
    fun assertColor(
        x: Int, y: Int, w: Int, h: Int,
        expectedR: Float, expectedG: Float, expectedB: Float,
        colorTolerance: Int = tolerance
    ) {
        val stats = sampleRegion(x, y, w, h)
        val tol = colorTolerance.toFloat()
        assertTrue(
            kotlin.math.abs(stats.avgR - expectedR) <= tol &&
            kotlin.math.abs(stats.avgG - expectedG) <= tol &&
            kotlin.math.abs(stats.avgB - expectedB) <= tol,
            "Expected color (~$expectedR, ~$expectedG, ~$expectedB) ±$tol at ($x,$y ${w}x${h}), " +
            "got (${stats.avgR}, ${stats.avgG}, ${stats.avgB})"
        )
    }
}
