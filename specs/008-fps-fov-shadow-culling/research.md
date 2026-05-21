# Phase 0 — Research: FPS in Multi-Light Scenes

> Skeptic's rule: every "this is slow" claim gets backed by a frame
> from `[Profile]` in `logs.txt` before any code changes.

## 1. Measured baseline

Sampled from `logs.txt` worst-frame extraction (`fps=` lines sorted
ascending):

| FPS  | frame ms | upload | render | ul.collect | ul.pack | ul.upload | lights | shadowTris | world      |
|------|---------:|-------:|-------:|-----------:|--------:|----------:|-------:|-----------:|------------|
| 10.6 |   94.1   |  4.3   |  0.5   |    1.1     |   1.0   |    0.5    |  75    |  33 504    | 63×63×12   |
| 10.9 |   92.1   | 10.8   |  1.1   |    2.4     |   5.0   |    1.1    |  73    |  45 792    | 63×63×15   |
| 11.4 |   88.0   | 38.9   |  2.7   |    8.7     |  10.1   |    1.1    |  35    |  21 456    | 63×63×9    |
| 11.8 |   84.9   |  6.3   |  0.7   |    1.9     |   1.4   |    0.9    | 128    |  84 456    | 63×63×15   |
| 12.5 |   80.2   |  4.5   |  0.4   |    1.2     |   1.4   |    0.4    |  44    |  28 572    | 63×63×12   |
| 13.1 |   76.6   |  5.4   |  0.6   |    1.7     |   1.4   |    0.8    |  66    |  47 376    | 63×63×15   |

### Interpretation

```
frame_ms ≈ Σ(cpu_phases) + gpu_ms
gpu_ms   ≈ frame_ms − interaction − procedural − collectLights − uploadLighting − renderWorld
```

| FPS  | Σcpu  | gpu_ms (derived) | CPU share | GPU share |
|------|------:|-----------------:|----------:|----------:|
| 10.6 |  5.0  |     89.1         |   5%      |  95%      |
| 10.9 | 12.0  |     80.1         |  13%      |  87%      |
| 11.4 | 52.7  |     35.3         |  60%      |  40%      |
| 11.8 |  7.2  |     77.7         |   8%      |  92%      |
| 12.5 |  5.0  |     75.2         |   6%      |  94%      |
| 13.1 |  6.2  |     70.4         |   8%      |  92%      |

**Conclusion**: in 5 of 6 worst frames the bottleneck is the
fragment shader (90 %+ of frame time). One outlier (fps=11.4) is a
**CPU spike** — `uploadLighting=38.9 ms` driven by `ul.pack=10.1 ms`
and `ul.collect=8.7 ms`. The two failure modes need different fixes.

## 2. Per-fragment cost model

Reading `world_lit.frag.glsl` end-to-end (lines 259–419) the shader
work per fragment is:

```
  cost ≈ K_lights × ( shadow_test + lighting )
       = K_lights × ( S_pcf_taps × D_dda_cells × T_tris_per_cell  +  O(1) )

  K_lights         = min(tileLightN, MAX_PER_PIXEL_LIGHTS=6)
  S_pcf_taps       = 5             (after spec-007 PCF fix)
  D_dda_cells      ≤ 40            (bounded by light-window horizon)
  T_tris_per_cell  ≈ 3             (after wall-mesh additions; was ~0.5)
```

For 1 080 p × ~50 % world coverage = ~1.0 M fragments × ≈ 2 700 ray-tri
tests per fragment = **2.7 G ray-triangle tests per frame**.
Empirical 80 ms / 2.7 G ≈ 30 ps per test — consistent with
integrated-GPU memory-bound throughput.

## 3. The five candidate options (recap from spec)

| # | Option | Attacks | Expected FPS lift | Cost (dev-days) |
|---|---|---|---|---|
| 1 | Per-tile shadow-quality LOD | `S_pcf_taps` + branch-out at K | **30→60+** in centre, much more in periphery | **2** |
| 2 | Frustum-clipped shadow-mesh SSBO upload | CPU `ul.collect/upload`; secondary cache pressure | +5–10 FPS | 0.5 |
| 3 | Half-res compute shadow pre-pass | `1/fragments` ratio | 3–4× lift, but bigger architecture | 5–8 |
| 4 | Temporal accumulation (TAA-style) | Re-uses prev frame | 4–10× when still | 8–12 |
| 5 | Spike bounding (window-shift hysteresis + tri cap) | The 38.9 ms outlier | Removes p99 spike | 2 |

Skeptic's verdict (already in spec): **do 1 + 2 + 5**, park 3, skip 4.

## 4. Already-present optimisations we should not break

- **Light-sphere frustum cull** at CPU side
  (`RoguelikeGame.uploadLighting`, lines 694–696). Lights whose
  influence sphere doesn't touch the view frustum are already dropped.
- **Room-priority truncation** to `MAX_LIGHTS = 128` lights (lines
  706–710). The candidate set is already supplied in room-distance
  order; truncation keeps the prefix.
- **Per-cell shadow-mesh cache** (`shadowCellCache`, key includes
  door/stair/wall state) — most cells' triangles are reused frame to
  frame. The frustum-clipped SSBO change must not invalidate this cache.
- **Forward+ tile bins** (`tileLightCount`, `tileLightIndices`) —
  fragment shader already iterates only the tile's light subset, not
  the whole list. The new per-tile quality SSBO piggy-backs on this
  binning.
- **Per-pixel top-K (K=6)** insertion-sort that picks the 6 highest-
  contribution lights before the expensive ray-march. Already bounds
  the per-fragment cost loop.

## 5. Hardware assumptions and target

Reported 10 FPS is almost certainly on an integrated GPU class device.
The performance target (≥ 30 FPS / p99 ≤ 33 ms) is set on that same
class — if it is met there, it is automatically met on discrete GPUs.
A separate, looser target for low-power-mode laptops is out of scope
for this spec.

## 6. Decisions

| Decision | Rationale | Alternatives rejected |
|---|---|---|
| Implement Options 1 + 2 + 5 only | Best ROI given the measured GPU dominance | 3 too large; 4 wrong fit for camera pattern |
| Single F11 flag for all three | Deterministic A/B profiling, easy rollback | Per-option flags would be three knobs to chase |
| New SSBO binding 5 (not extra fields in existing UBO) | Per-tile quality is per-tile, naturally an SSBO | Packing into existing `tileLightCount` would force a 32-bit count, halving Forward+ headroom |
| Re-anchor window only every ≥ 4 cells + ≥ 8 frames | Empirically matches the spike pattern | Per-frame re-anchor (today) causes cache-miss storms; never-re-anchor would let lights drift out of the window |
| Default `MAX_PER_PIXEL_LIGHTS_LOW = 3` for quality byte 1 | Halves shader work in peripheral tiles | Setting to 1 caused light popping when a fragment moves between tiles |

## 7. Open questions for the implementer

1. **Where to bind the per-tile quality SSBO?** The shader already has
   bindings 0–4 on set 0; we propose binding 5. Confirm with
   `VkPhysicalDeviceLimits.maxPerStageDescriptorStorageBuffers` (Vulkan
   1.0 minimum is 4 per *stage*; we use 4 storage buffers already in
   the fragment stage, hitting the minimum). **Resolution**: a single
   additional SSBO is within the *per-stage* limit on every desktop
   GPU shipped since 2016; the minimum guarantee is 4 *per shader
   stage* but actual hardware exposes ≥ 8.
2. **Should the editor preview mirror the LOD?** No. The editor needs
   accurate WYSIWYG; only the `RoguelikeGame` path opts in. The
   feature flag governs the game path only.
3. **What about a discrete-GPU baseline?** Out of scope for this spec.
   Will be collected during Phase 6 validation and noted in
   `quickstart.md` if available.

## 8. References

- `logs.txt` (4 132 lines, captured 2026-05-20).
- `src/main/resources/shaders/world_lit.frag.glsl` (post spec-007 PCF
  changes).
- `src/main/kotlin/com/roguelike/RoguelikeGame.kt` (lines 684–1170,
  `uploadLighting` family).
- `src/main/kotlin/com/roguelike/ui/SimpleUI.kt` (lines 24–80,
  Forward+ tile constants).
- Spec 007 §FR-006/006b (per-pixel DDA contract).

