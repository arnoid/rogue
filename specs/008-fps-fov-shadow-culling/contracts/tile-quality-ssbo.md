# Contract: Per-Tile Shadow Quality SSBO

> A binding-level contract between `RoguelikeGame.uploadLightTiles`
> (writer) and `world_lit.frag.glsl` (reader). Any deviation between
> the two MUST be treated as a build-breaker.

## Binding

- **Descriptor set**: `0` (same as all other lit-pass bindings).
- **Binding number**: `5`.
- **Type**: `VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`.
- **Stage flags**: `VK_SHADER_STAGE_FRAGMENT_BIT`.
- **Buffer name in GLSL**: `tileQuality`.
- **Buffer name in `SimpleUI`**: `tileQualitySsboBuffer` /
  `tileQualitySsboAlloc` (mirror the existing `tileLightCount*` naming).

## Buffer size

`max(MAX_LIGHT_TILES, 16)` bytes, rounded up to 16-byte alignment
required by `VK_PHYSICAL_DEVICE_LIMITS.minStorageBufferOffsetAlignment`
(typically 16 or 64 on desktop). Today `MAX_LIGHT_TILES = 16 384`, so
the SSBO is exactly 16 KiB.

Allocated once at `SimpleUI` init via `VMA_MEMORY_USAGE_CPU_TO_GPU`
host-visible mapping, mirroring the existing `tileLightCountSsboBuffer`
allocator. No per-frame `vmaCreateBuffer`/`vmaDestroyBuffer` calls.

## Layout

Conceptually one `uint8` per tile, packed 4 per `uint32` in little-
endian order:

```
uint32 word[i]:
   bits  0- 7 : quality for tile (i*4 + 0)
   bits  8-15 : quality for tile (i*4 + 1)
   bits 16-23 : quality for tile (i*4 + 2)
   bits 24-31 : quality for tile (i*4 + 3)
```

## Quality byte semantics

| Value | Meaning | Shader behaviour |
|---|---|---|
| `0` | Empty tile (no lights). | Skip the top-K + lighting accumulation loop. Output ambient-only. |
| `1` | Low quality (peripheral or low-light tile). | `MAX_PER_PIXEL_LIGHTS = PerfFlags.MAX_PER_PIXEL_LIGHTS_LOW (3)`; `shadowVisibility` uses centre tap only (1 ray). |
| `2` | Full quality. | Today's spec-007 behaviour: 6 lights, 5-tap PCF. |
| `3-255` | **Reserved**. Shader MUST treat as `2`. |

## Writer signature (host side)

```kotlin
/**
 * Upload per-tile shadow quality bytes for this frame.
 *
 * @param qualities  byte array of length >= `tileCount`. Entries
 *                   outside [0,2] are clamped to 2 to satisfy the
 *                   reserved-value rule.
 * @param tileCount  number of tiles actually used this frame
 *                   (`tilesX * tilesY`). Bytes [tileCount, end)
 *                   are zeroed.
 */
fun updateTileQuality(qualities: ByteArray, tileCount: Int)
```

Called **after** `updateLightTiles` in `RoguelikeGame.uploadLighting`
so the writer can read `tileLightCount` and decide qualities in one
pass.

## Reader signature (shader side)

```glsl
layout(set = 0, binding = 5) readonly buffer TileQuality {
    uint packed[];
} tileQuality;

uint readTileQuality(int tIdx) {
    uint w = tileQuality.packed[uint(tIdx) >> 2u];
    uint shift = uint(tIdx & 3) * 8u;
    return (w >> shift) & 0xFFu;
}
```

## Invariants

1. **Stride match**: host writes raw bytes, shader reads packed uints.
   Endianness is little-endian on all supported platforms (x86_64,
   ARM64) — relying on host-mapped memory's native order is safe.
2. **Bounds**: `tIdx < tileCount`. Out-of-bounds reads return
   undefined; the shader MUST clamp `tIdx` to
   `min(tIdx, MAX_LIGHT_TILES - 1)` before calling `readTileQuality`.
3. **Default**: when `PerfFlags.enabled = false`, the host writes
   `2` to every tile so the shader's branch is dead-code-equivalent
   to spec-007. This is testable: `PerfFlagsDisabledTest` will assert
   that a known scene renders identical pixels with the flag off vs
   no spec-008 code at all (after the work lands).
4. **Resize**: when the swap chain resizes, `tilesX * tilesY` may
   change; the SSBO size never does (we always allocate for
   `MAX_LIGHT_TILES`). The writer's tail-zeroing keeps the SSBO clean.

## Validation hooks

- `VulkanDebug` validation layer MUST stay silent after the new
  descriptor is wired. Any error message mentioning binding 5 fails
  the merge.
- A new unit test `TileQualityPackingTest` asserts the bit-packing
  encode/decode round-trip for the writer.
- A new integration test `TileQualityIntegrationTest` writes a known
  pattern, renders a 16×16-px frame, samples four tiles, and verifies
  the quality byte propagated correctly (centre = 2, corner = 1,
  empty = 0).

## Forward-compatibility

The reserved range `[3, 255]` leaves room for future quality levels
(e.g. `3` = 3-tap PCF if we ever need an intermediate step). New
quality values MUST be added at the high end and MUST keep the
"higher value = at least as much work" monotonicity, so the shader's
branch table can stay as a simple `if (q >= …)` ladder.

