package com.roguelike.rendering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Pure-logic round-trip of the 4-bytes-per-uint packing the host writes
 * and the shader reads. No Vulkan. Mirrors the GLSL helper:
 *
 *   uint readQuality(int tIdx) {
 *       uint w = tileQuality.packed[uint(tIdx) >> 2u];
 *       uint shift = uint(tIdx & 3) * 8u;
 *       return (w >> shift) & 0xFFu;
 *   }
 *
 * Encoding is "raw bytes written to a host-mapped buffer; shader reads
 * them as little-endian uints". So a Kotlin port reads the same buffer
 * back as little-endian ints and applies the bit math.
 *
 * See specs/008-fps-fov-shadow-culling/contracts/tile-quality-ssbo.md.
 */
class TileQualityPackingTest {

    /** Shader-side reader, ported to Kotlin. */
    private fun readQuality(packed: IntArray, tIdx: Int): Int {
        val w = packed[tIdx ushr 2]
        val shift = (tIdx and 3) * 8
        return (w ushr shift) and 0xFF
    }

    @Test
    fun `round-trip preserves every byte`() {
        val tileCount = 17 // intentionally not a multiple of 4
        val raw = ByteArray(((tileCount + 3) / 4) * 4)
        val expected = IntArray(tileCount) { it % 3 } // 0,1,2,0,1,2…
        for (i in 0 until tileCount) raw[i] = expected[i].toByte()

        // Host-mapped little-endian view.
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val packed = IntArray(raw.size / 4)
        for (i in packed.indices) packed[i] = bb.getInt(i * 4)

        for (i in 0 until tileCount) {
            assertEquals(expected[i], readQuality(packed, i),
                "tile $i quality mismatch")
        }
    }

    @Test
    fun `random fuzz round-trip`() {
        val rnd = Random(42)
        val tileCount = 1000
        val raw = ByteArray(((tileCount + 3) / 4) * 4)
        val expected = IntArray(tileCount) { rnd.nextInt(0, 3) }
        for (i in 0 until tileCount) raw[i] = expected[i].toByte()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val packed = IntArray(raw.size / 4)
        for (i in packed.indices) packed[i] = bb.getInt(i * 4)
        for (i in 0 until tileCount) {
            assertEquals(expected[i], readQuality(packed, i))
        }
    }

    @Test
    fun `out-of-range bytes can be read raw (clamp is writer responsibility)`() {
        // The writer clamps to [0,2]; if the writer EVER writes 0xAB we should
        // still round-trip that byte verbatim so the bug is visible.
        val raw = byteArrayOf(0xAB.toByte(), 0, 0, 0)
        val packed = IntArray(1)
        packed[0] = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        assertEquals(0xAB, readQuality(packed, 0))
    }
}

