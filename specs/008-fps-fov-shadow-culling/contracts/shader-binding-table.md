# Contract: Descriptor Set / Shader Binding Table

> Cross-cutting contract: any change to descriptor-set 0 must be
> reflected in **both** `SimpleUI.litDescriptorSetLayout` (host) and
> the `layout(set = 0, binding = N)` lines in `world_lit.frag.glsl`
> (shader). This document is the canonical map.

## Descriptor set 0 (post spec 008)

| Binding | Type | Stage(s) | Owner | Purpose | Introduced by |
|---|---|---|---|---|---|
| 0 | `UNIFORM_BUFFER` | `FRAGMENT` | `lightingUboBuffer` | Lights, ambient, grid origin, screen params | spec 007 |
| 1 | `STORAGE_BUFFER` | `FRAGMENT` | `occupancySsboBuffer` | 3-D occupancy grid + per-cell shadow-tri range | spec 007 |
| 2 | `STORAGE_BUFFER` | `FRAGMENT` | `shadowTriSsboBuffer` | Packed shadow triangles (vec4 × 3 per tri) | spec 007 |
| 3 | `STORAGE_BUFFER` | `FRAGMENT` | `tileLightCountSsboBuffer` | Forward+ per-tile light counts | spec 007 |
| 4 | `STORAGE_BUFFER` | `FRAGMENT` | `tileLightIndicesSsboBuffer` | Forward+ per-tile light indices | spec 007 |
| **5** | **`STORAGE_BUFFER`** | **`FRAGMENT`** | **`tileQualitySsboBuffer`** | **Per-tile quality byte (1 byte/tile, packed 4-per-uint)** | **spec 008 (new)** |

Total bindings: **6** (was 5). Vulkan 1.0 per-stage minimum guarantee
is 4 storage buffers + 12 uniform buffers + 16 samplers per stage; we
are well under the limit. Reference hardware exposes ≥ 8 storage
buffers per fragment stage.

## Host-side touch points

When binding 5 is added, the following sites MUST be updated **in the
same commit** (otherwise Vulkan validation will fire):

1. `SimpleUI.createLitDescriptorSetLayout` — append one
   `VkDescriptorSetLayoutBinding` with `binding=5`,
   `descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER`,
   `descriptorCount=1`,
   `stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT`.
2. `SimpleUI.createLitDescriptorPool` — bump `STORAGE_BUFFER` pool
   size from 4 to 5.
3. `SimpleUI.createLitDescriptorSet` (or the equivalent allocate +
   update site) — add a `VkWriteDescriptorSet` for binding 5 pointing
   to `tileQualitySsboBuffer`. Update on swap-chain resize alongside
   the existing tile-light SSBOs.
4. `SimpleUI.close()` / `dispose()` — destroy `tileQualitySsboBuffer`
   via VMA. AutoCloseable chain MUST be kept tight.

## Shader-side touch points

`src/main/resources/shaders/world_lit.frag.glsl`:

1. Add the new layout block at the top, **immediately after** the
   existing `TileLightIndices` block (binding 4) so the binding
   numbers read in source order:
   ```glsl
   layout(set = 0, binding = 5) readonly buffer TileQuality {
       uint packed[];
   } tileQuality;
   ```
2. Add the `readTileQuality(int tIdx)` helper (see
   `tile-quality-ssbo.md`).
3. Inside `main()`, read the quality byte **once** right after the
   tile-light lookup, then use it to:
   - skip the entire lights loop when `q == 0`,
   - call `shadowVisibility` with `1` tap when `q == 1`,
   - keep the existing 5-tap behaviour when `q == 2`.

## Migration / rollback guarantees

- **If binding 5 is added but the writer never calls
  `updateTileQuality`**: the SSBO contents are zero, so every tile
  reads `q == 0` and the scene renders ambient-only. This is
  catastrophic — protect against it with a debug assert in
  `SimpleUI` (`require(tileQualityEverWritten)` once per second).
- **If `PerfFlags.enabled == false`**: the writer pushes `2` to every
  byte, the shader's branch reduces to today's code. Pixel-identical
  to spec 007.
- **Rollback (post-merge)**: setting
  `perf.flags.enabled=false` in `local.properties` returns the entire
  pipeline to spec-007 behaviour without recompiling shaders.

## Validation hooks

After the host changes land:

```powershell
.\gradlew.bat compileKotlin     # must succeed
.\gradlew.bat test --tests "com.roguelike.rendering.*"  # must pass
```

Validation layer at runtime (`VulkanDebug`) MUST stay silent. Any
`VUID-VkDescriptorSetLayoutBinding-*` or `VUID-vkUpdateDescriptorSets-*`
message fails the merge.

## Cross-references

- Host layout creator: `SimpleUI.kt` (search for
  `litDescriptorSetLayout`).
- Existing five-binding-table comment: `world_lit.frag.glsl` lines
  16–67.
- Spec defining bindings 0–4: `specs/007-replace-libgdx-vulkan/spec.md`
  §FR-006.

