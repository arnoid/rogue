# Phase 1 — Quickstart

> A developer should be able to run an A/B perf capture in under 5
> minutes by following this file.

## 1. Build & launch

```powershell
cd C:\Users\w196818\work\workspace\rogue
.\gradlew.bat run --console=plain
```

(Or run `Main.kt` from your IDE.)

## 2. Load the reference perf scene

1. Main menu → "Arena".
2. World picker → select `saved-worlds/perf/dense-lights.wld`.
3. The player spawns at the `PERF_PROBE` node tag (lit room, ≈ 128
   candidate lights within range, ≈ 45 k shadow triangles).

If `dense-lights.wld` is missing, see `data-model.md` §7 for
creation steps (Phase 5 task).

## 3. Capture an A/B sample

The single `F11` toggle controls every change made in this spec.

```
Before  →  F11 (disables spec-008 changes)
After   →  F11 (enables them again — default state)
```

Capture procedure:

1. Stand on the `PERF_PROBE` tile.
2. Press F11 to disable. The HUD `driver=…` field should switch to
   `disabled`.
3. Slowly rotate the camera 360° using the mouse (5 seconds).
4. Take a screenshot (or just leave the console open — every
   `[Profile]` HUD print also lands in `logs.txt`).
5. Press F11 to re-enable.
6. Rotate again for 5 seconds.

Then in PowerShell:

```powershell
Select-String -Path logs.txt -Pattern "\[Profile\]" |
    Select-Object -Last 60 |
    ForEach-Object { $_.Line } |
    Out-File capture.txt -Encoding utf8
```

Open `capture.txt` and split into the two halves around the F11 toggle.

## 4. Pass criteria (mirror of spec §SC-001..SC-004)

| Criterion | "After" requirement (spec-008 enabled) |
|---|---|
| **SC-001** Frame-time floor | `min(fps) ≥ 30` and `p99(frame_ms) ≤ 33` |
| **SC-002** No visual regression | All tests in `src/test/kotlin/com/roguelike/rendering/` pass |
| **SC-003** CPU spike ceiling | `p99(uploadLighting) ≤ 10 ms` across 60 s |
| **SC-004** Measurable | Both before/after `[Profile]` halves present |

For SC-002 run:

```powershell
.\gradlew.bat test --tests "com.roguelike.rendering.*" --console=plain
```

For SC-001 / SC-003 the new test runner does it deterministically:

```powershell
.\gradlew.bat test --tests "com.roguelike.rendering.PerfRegressionTest" --console=plain
```

## 5. Local override

To start the game with the perf flag forced off (for capturing
baselines without manually toggling F11 every launch):

```properties
# local.properties
perf.flags.enabled=false
```

Restart the app — the HUD will read `driver=disabled` from frame 0.

## 6. Tuning knobs

If the centre-LOD region feels wrong:

| Knob | Default | Effect |
|---|---|---|
| `PerfFlags.centreFraction` | `0.40` | Fraction of `min(sw, sh)` that gets quality byte 2. Larger = bigger high-quality area = more cost. |
| `PerfFlags.PCF_TAPS_LOW` | `1` | Taps in the peripheral PCF. Compile-time constant; rebuild required. |
| `PerfFlags.MAX_PER_PIXEL_LIGHTS_LOW` | `3` | Max lights considered per fragment in low-quality tiles. Compile-time constant; rebuild required. |
| `WindowShiftHysteresis.cellThreshold` | `4` | Cells of movement needed before window re-anchors. Larger = fewer cache-miss storms, more chance a light drifts out. |
| `WindowShiftHysteresis.frameCooldown` | `8` | Frames between re-anchors. Independent ceiling on shift frequency. |

## 7. Validating visually

After enabling the flag:

1. Look at a wall in the **screen centre** — shadow terminator should
   match today's softness (5-tap PCF).
2. Look at the same wall **at the screen edge** by turning slightly —
   terminator should be visibly sharper (1-tap) but not blocky.
3. Walk through the scene — no shadow popping at the centre/periphery
   boundary as the camera moves.

If you see popping, raise `centreFraction` toward `0.60`.
If you don't see any perf gain, capture `gpu_ms` in the HUD and
compare; if `gpu_ms` is unchanged, the LOD path may not be active
(check `[Profile] driver=` field).

## 8. Rolling back

```
git checkout main -- src/main/resources/shaders/world_lit.frag.glsl
git checkout main -- src/main/kotlin/com/roguelike/ui/SimpleUI.kt
git checkout main -- src/main/kotlin/com/roguelike/RoguelikeGame.kt
```

Or simply set `perf.flags.enabled=false` in `local.properties` — the
default-on F11 toggle is fully reversible without git.

## 9. Capture protocol (T003 + T047, SC-004)

This is the **exact** procedure for filling
`specs/008-fps-fov-shadow-culling/baseline.log` and
`specs/008-fps-fov-shadow-culling/after.log`. Both files are committed
as empty headers; you fill them by following the checklist below. Both
captures must use **the same hardware and the same window resolution**
or the comparison is meaningless.

### Checklist (do twice — once for baseline, once for after)

1. Edit each log file's header line and fill in the `Hardware:` field
   (CPU model, GPU model, driver version, window resolution at launch).
2. Launch the game: `.\gradlew.bat run --console=plain` from
   `C:\Users\w196818\work\workspace\rogue`.
3. Main menu → **Arena**. (Once T038 lands, prefer
   `saved-worlds/perf/dense-lights.wld` for determinism.)
4. Walk to the densest-light area you can reach. Stand on the
   `PERF_PROBE` tile if the dense-lights scene exists.
5. **Baseline capture (F11 = DISABLED)**:
   - Press **F11** once. The console must print
     `[PerfFlags] enabled=false`.
   - The HUD `driver=` field (once T035 lands) must read `disabled`.
   - **Start a timer.** For exactly 60 seconds, slowly rotate the
     camera 360° (mouse look) and walk a short loop (≈ 5 m). Do **not**
     teleport or change rooms — keep the candidate-light set roughly
     stable so the `[Profile]` numbers are comparable to the after
     pass.
   - Stop after 60 s. Exit the game cleanly (ESC → Menu → Quit) so
     stdout is flushed.
6. **After capture (F11 = ENABLED)**:
   - Relaunch the game (so `PerfFlags.enabled` starts at its default
     `true`). Do **not** touch F11 this time.
   - Confirm the HUD `driver=` field does **not** read `disabled`.
   - Repeat the 60-second walk/rotate procedure from the same starting
     tile, with the same mouse-look pattern.
7. Extract the two halves from `logs.txt`:

   ```powershell
   Select-String -Path logs.txt -Pattern "\[Profile\]" |
       Select-Object -Last 240 |
       ForEach-Object { $_.Line } |
       Out-File capture.txt -Encoding utf8
   ```

   (`-Last 240` ≈ 4 minutes of once-per-second prints, ample for
   capturing both 60 s windows plus startup noise.)
8. Manually split `capture.txt` at the `[PerfFlags] enabled=...` line(s)
   and the relaunch boundary. Paste each 60-second block under the
   corresponding header in `baseline.log` / `after.log`.
9. Sanity-check: each file should contain ≈ 60 lines (one
   `[Profile]` per second). If a line shows `frame=` > 100 ms, that's
   the slow-frame outlier — keep it; SC-001 / SC-003 care about p99.

### Why two relaunches instead of one F11 toggle in-session?

- The shader's SPIR-V cache + light-window hysteresis warm up during
  the first 30 s of play; A/B-toggling mid-session contaminates the
  second window with the first window's cache state.
- Restarting also resets the `[Profile]` smoothed-FPS EMA, so the
  first line of each capture is comparable.

## 10. Authoring shadow proxies (US5 / FR-009)

> Full binding contract:
> [`contracts/shadow-proxy-discovery.md`](./contracts/shadow-proxy-discovery.md).
> Data-model entry: [`data-model.md`](./data-model.md) §10.

### Convention in one sentence

For a visual mesh `models/foo/bar.obj`, drop a companion file at
`models/foo/bar.shadow.obj` next to it; the loader auto-substitutes
it as the shadow occluder. No code changes, no manifest edits — the
next launch picks it up.

### Budget

- **≤ 16 triangles**, measured after Assimp triangulation. This is
  `PerfFlags.PER_CELL_SHADOW_TRI_CAP`; the same constant the per-
  cell collector clamps against (FR-008 backstop).
- The companion's silhouette MUST conservatively contain the visual
  mesh's silhouette from every light direction — a ~10 % AABB
  inflate is fine; do NOT under-approximate or light will leak.
- UVs / materials / normals / vertex colours are ignored. Only
  positions and indices matter. The `.shadow.obj` may omit `vt`,
  `vn`, `usemtl`, `mtllib` lines entirely.

### What happens if you forget

If a visual mesh exceeds 16 triangles AND no companion exists:

```
[AssetLoader] spec 008 FR-009: shadow proxy missing for
  'models/vox/stairs/stairs_n.obj' (132 visual tris > 16-tri cap);
  falling back to visual mesh. Author a
  'models/vox/stairs/stairs_n.shadow.obj' to silence.
```

Startup continues, the visual mesh is used as its own occluder, and
the FR-008 per-cell backstop at `RoguelikeGame.kt:1257` clamps the
list — you will also see one `[RoguelikeGame] spec 008: per-cell
shadow-tri cap (16) hit at cell=…` line per offending cell.

### Quick recipe (Blender)

1. Open the visual `.obj` in Blender.
2. Duplicate the mesh; on the duplicate, decimate / replace with a
   hand-modelled wedge or box that conservatively contains the
   visual silhouette. Target ≤ 16 triangles.
3. Hide / delete the visual original on the duplicate file.
4. Export the duplicate as `<name>.shadow.obj` next to the visual.
   Untick "Write Materials" and "Include UVs" — they're not used.
5. Drop the file into `src/main/resources/models/…/` and relaunch.

### Audit

Run from the repo root:

```powershell
.\count_tris.ps1   # writes over16.txt: every .obj with > 16 raw tris
```

Then cross-reference against the actually-shadow-emitting asset list
in [`contracts/shadow-proxy-discovery.md`](./contracts/shadow-proxy-discovery.md)
§6 — most of the 470-odd over-budget OBJs are decorative props that
never enter the shadow buffer and therefore don't need a companion.


