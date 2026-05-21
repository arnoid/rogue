# Contract: Shadow-Proxy Mesh Discovery

> A loader-level contract between asset authors, `AssetLoader.loadModel`
> (file-system resolver), and the per-cell shadow-triangle collector in
> `RoguelikeGame` / `MapEditor`. Mirrors the style of
> [`tile-quality-ssbo.md`](./tile-quality-ssbo.md) and
> [`shader-binding-table.md`](./shader-binding-table.md).
>
> Implements **FR-009** and tightens **FR-008** (see
> [`../spec.md`](../spec.md) and [`../data-model.md`](../data-model.md)
> §10).

## 1. Naming convention

For a visual mesh loaded from `<dir>/<name>.obj`, the loader MUST look
for a companion file at `<dir>/<name>.shadow.obj` in the **same
directory**, with the **same base name**, plus the literal infix
`.shadow` immediately before the `.obj` extension.

- **Case sensitivity**: lookups are case-sensitive on disk (matches the
  classpath-resource lookup the existing loader uses).
- **Extension handling**: only `.obj` visual meshes participate. Other
  formats (`.gltf`, etc.) are out of scope for this spec and MUST NOT
  trigger the `.shadow.obj` lookup.
- **Discovery is automatic**: no manifest, no per-asset registration
  call, no opt-in flag at the call site. `assetLoader.loadModel(name,
  path)` resolves the companion as part of its normal load flow.

Examples:

| Visual path | Resolved companion path |
|---|---|
| `models/vox/stairs/stairs_n.obj` | `models/vox/stairs/stairs_n.shadow.obj` |
| `models/vox/stairs/ladder_vertical_n.obj` | `models/vox/stairs/ladder_vertical_n.shadow.obj` |
| `models/vox/wall/wall_doorway_n.obj` | `models/vox/wall/wall_doorway_n.shadow.obj` |

## 2. Loader API surface

The companion is exposed via a **new return type** alongside the
existing one — the existing `fun loadModel(name, path): MeshData`
signature is preserved for backwards compatibility with the dozens of
call sites that only want the visual mesh. A new method returns the
pair:

```kotlin
// in AssetLoader.kt
data class MeshPair(
    /** The visual mesh, unchanged from spec-007 behaviour. */
    val visual: MeshData,
    /**
     * The shadow occluder mesh. Resolution order:
     *  1. If `<basename>.shadow.obj` exists alongside `<path>`, that
     *     file is loaded (with the same interior-face culling pass).
     *  2. Otherwise this is the same MeshData reference as `visual`
     *     (object identity; callers MAY use `visual === shadow` to
     *     detect the fall-back case without comparing arrays).
     */
    val shadow: MeshData,
)

/**
 * Same resolution rules as [loadModel], plus the `.shadow.obj`
 * companion lookup. On a missing companion AND a visual mesh whose
 * (post-interior-cull) tri count exceeds [PerfFlags.PER_CELL_SHADOW_TRI_CAP],
 * emits a single WARN per `name` to stderr.
 */
fun loadModelWithShadow(name: String, path: String): MeshPair
```

Rationale for **not** changing `loadModel`'s signature:

- 8 call sites in `RoguelikeGame.kt` (lines 395–402) plus more in
  `MapEditor.kt`. A breaking-signature change costs migration churn
  for zero benefit on call sites that don't emit shadow triangles
  (floor, ceiling, ambient props).
- The two real consumers today are the stairs and ladder load sites
  (lines 398–399). They migrate to `loadModelWithShadow`; everything
  else stays untouched.

## 3. Triangle budget

The companion mesh MUST be ≤ **16 triangles** (the value of
`PerfFlags.PER_CELL_SHADOW_TRI_CAP` introduced by this spec, see
[`../data-model.md`](../data-model.md) §10). The budget applies to
the **post-`cullInteriorFaces` tri count** — the same number that
ends up in the shadow SSBO — not the raw `f`-line count of the source
OBJ.

Enforcement at load time:

- If the `.shadow.obj` exists and its tri count is ≤ 16: load
  silently (info-level `[AssetLoader] Loaded shadow proxy …` line is
  acceptable, mirroring the existing per-load info print).
- If the `.shadow.obj` exists but exceeds 16 tris: emit a **one-shot**
  WARN naming the path and the observed count. The loader **MUST NOT
  reject** the asset — FR-008's per-cell backstop in
  `RoguelikeGame.kt:1257` will clamp the per-cell list, and FR-009's
  goal is "best-effort lower than the cap", not a hard reject.
- If the `.shadow.obj` is absent AND the visual mesh's tri count
  exceeds 16: emit a one-shot WARN naming the **visual** path (so the
  author knows what to author) and fall back to using the visual mesh
  as the occluder. **Startup MUST NOT fail.**

Warning format (stable; tests may grep for the prefix):

```
[AssetLoader] spec 008 FR-009: shadow proxy missing for 'models/vox/stairs/stairs_n.obj' (132 visual tris > 16-tri cap); falling back to visual mesh. Author a 'models/vox/stairs/stairs_n.shadow.obj' to silence.
[AssetLoader] spec 008 FR-009: shadow proxy oversized for 'models/vox/stairs/stairs_n.shadow.obj' (24 tris > 16-tri cap); the per-cell backstop in RoguelikeGame:1257 will clamp.
```

## 4. Geometric correctness

The companion's silhouette SHOULD conservatively contain the visual
mesh's silhouette when projected along any light direction (i.e. it
is an **over-approximation** of the occluder). Practical rules of
thumb for asset authors:

- A tight convex wedge / box matching the AABB of the visual mesh is
  the safest baseline.
- A ~10 % AABB inflate is acceptable for stairs and ladders (the
  shadow softens fractionally; not visible at the spec-006 PCF tap
  count).
- The companion MUST NOT be **smaller** than the visual silhouette in
  any axis — that would let light "leak around" the occluder and
  regress spec-007's no-light-bleed invariant.

This is **not** a build-checkable invariant. There is no SAT solver,
no AABB-containment unit test. It is the asset author's
responsibility, enforced by code review.

## 5. Visual identity

The companion is **never** rendered to the colour buffer. It is only
read by:

- the host-side per-cell shadow-tri collector (`collectShadowTriangles`
  in `RoguelikeGame.kt:1676` and `MapEditor.kt:1177`), which copies
  positions into the `shadowTriSsboBuffer` (binding 2);
- the fragment shader's `hitsShadowMesh` loop in
  `world_lit.frag.glsl` (lines 104–126 read the per-cell range via
  `getShadowTriRange`).

Consequences for asset authors:

- **UVs, materials, vertex colours, normals, texture references are
  IGNORED.** Only vertex positions and triangle indices matter.
- The `.shadow.obj` MAY omit `vt`, `vn`, `usemtl`, `mtllib` lines
  entirely. The loader reuses the same Assimp pipeline (the missing
  fields are filled with zeros by `aiProcess_GenNormals`) — extra
  fields are harmless but wasted bytes.
- A degenerate (zero-area) triangle in the companion produces a
  no-op in `hitsShadowMesh` — not a correctness bug, just a wasted
  loop iteration. Authors SHOULD strip them.

## 6. Migration / open authoring work

The contract takes effect the moment FR-009 lands in code, but the
**code path** is fully tolerant of missing companions (warn + fall
back). That means the code work and the asset-authoring work are
independent and can land separately. The asset work is **blocked
outside this repository** (requires Blender / MeshLab / similar).

### Shadow-emitting assets in the repo today

Only meshes that are actually emitted into the per-cell shadow
buffer matter — non-shadow props are out of scope. The shadow
emitters live at `RoguelikeGame.kt:395–402`:

| Visual asset | Raw OBJ tris | Post-cull at runtime | Authoring priority |
|---|---:|---:|---|
| `models/vox/floor/floor.obj` | 12 | ~12 | None — under budget |
| `models/vox/ceiling/ceiling.obj` | 12 | ~12 | None — under budget |
| `models/vox/wall/wall.obj` | 12 | ~12 | None — under budget |
| `models/vox/door/door_n_closed.obj` | 12 | ~12 | None — under budget |
| `models/vox/door/door_n_open.obj` | 12 | ~12 | None — under budget |
| **`models/vox/stairs/stairs_n.obj`** | **132** | **~36 (observed in logs)** | **P0 — this is SC-005's offender** |
| **`models/vox/stairs/ladder_vertical_n.obj`** | **144** | **(likely > 16)** | **P0 — same root cause** |
| `models/vox/wall/wall_doorway_n.obj` | 44 | (unmeasured) | P1 — measure first; may already be ≤ 16 after culling |

### Open authoring work (outside this repo)

- **P0 — required for SC-005**:
  - `models/vox/stairs/stairs_n.shadow.obj` — author a ≤ 6-triangle
    wedge that contains the staircase silhouette. Hand-modelled in
    Blender (or by hand in a text editor; the file is trivially
    small).
  - `models/vox/stairs/ladder_vertical_n.shadow.obj` — author a
    ≤ 4-triangle box matching the ladder's AABB.
- **P1 — once stairs/ladder land, measure then decide**:
  - `models/vox/wall/wall_doorway_n.shadow.obj` — only required if
    `wall_doorway_n.obj`'s post-cull tri count exceeds 16 in
    practice. Verify with a quick log after FR-009 lands; if the
    new FR-008 backstop never warns for doorway cells, skip.
- **Out of scope for spec 008**: the ~470 other `.obj` files in
  `src/main/resources/models/` that exceed 16 raw triangles. None
  of them are currently emitted into the shadow SSBO (they're either
  prop / decoration meshes rendered in the colour pass only, or
  unused legacy assets). If a future spec adds prop-mesh shadows,
  authoring proxies for the worst offenders (e.g.
  `tiles/obj/stairs_wood_decorated.obj` at 1611 tris,
  `tiles/obj/wall_cracked.obj` at 1463 tris) becomes a follow-up.

A bulk audit script lives at the repo root as `count_tris.ps1` —
run it any time a new visual mesh is added to a shadow-emitting
slot, and confirm a companion is checked in beside it before
merging.

## 7. Test contract

Two automated gates from [`../plan.md`](../plan.md) US5 validate
this contract:

1. **`StairsCellShadowTriCountTest`** — unit test, deterministic, no
   Vulkan. Loads a synthetic 1×1×1 world holding a single
   `StairsTile`, builds the per-cell shadow buffer, asserts the
   per-cell triangle count is **≤ `PerfFlags.PER_CELL_SHADOW_TRI_CAP`
   (= 16)**. To stay green even before the `.shadow.obj` files
   land, the test injects an in-memory wedge mesh through the asset
   loader (e.g. a test-only `AssetLoader.preload(name, MeshData)`
   hook) — it does not depend on the on-disk `.shadow.obj` files.
2. **`StairsLandingGpuMsTest`** (sibling of `PerfRegressionTest`) —
   perf-regression assertion. Loads
   `saved-worlds/double-staircase-3x3x6.wld`, positions the camera
   at the top landing facing down the stairs, renders 100 frames,
   asserts `gpu_ms ≤ 20` on the reference hardware (SC-005). Ships
   `@Disabled` until the SC-005 capture protocol is run by hand;
   un-disabling is part of the implementation phase.

Both tests live under `src/test/kotlin/com/roguelike/rendering/`.

## 8. Validation hooks

- The first time a `.shadow.obj` is missing for a > 16-tri visual
  mesh, the WARN MUST appear in `logs.txt` exactly once per JVM run
  (not per frame, not per cell). Repeated warns are a regression.
- The first time a `.shadow.obj` is oversized, the WARN MUST appear
  exactly once per offending asset.
- `VulkanDebug` validation layer behaviour is unchanged — this
  contract is host-side only; no new descriptors, no new shader
  bindings.

## 9. Forward-compatibility

- If future specs introduce additional asset formats (`.gltf`,
  `.glb`) the same naming convention applies (`foo.gltf` →
  `foo.shadow.gltf`). Each format's loader is responsible for the
  same warn-and-fall-back rule.
- If a future spec needs **per-LOD** shadow proxies (e.g.
  `foo.shadow.lod1.obj`), this contract is extended; today only the
  base `foo.shadow.<ext>` slot is defined.
- The `PerfFlags.PER_CELL_SHADOW_TRI_CAP` constant is the single
  source of truth for both the loader's WARN threshold and the
  per-cell collector's clamp. Raising the cap requires updating both
  the constant and this contract's §3 wording.

