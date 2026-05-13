# Quickstart: OpenGL ES 3.0 Mesh-Aware Shadow Lighting

**Feature**: `004-gles3-mesh-shadow-lighting` | **Date**: 2026-05-13

---

## US1: Mesh-Accurate Shadow Casting

**Goal**: Verify shadows follow mesh silhouettes with zero light bleed.

### Headless verification (automated)
```
./gradlew test
```
- `Gles3LightingShaderUniformTest` — shader loads, all uniform locations resolve (not -1)
- `GpuLightEnvironmentTest` — existing tests pass unchanged
- `ShadowRendererConstructionTest` — `buildEnvironment()` removed; replaced by `Gles3LightingShader` uniform-set tests

### Visual verification (manual)
1. `./gradlew run`
2. Enter a dungeon room with a single torch item equipped
3. Stand next to a wall and observe the shadow cast on the floor
4. Expected: shadow edge aligns with the wall mesh boundary
5. Move the player; shadow should move in real time

**Pass criterion**: No light visible on the far side of a wall from the torch position.

---

## US2: Per-Pixel Partial Mesh Lighting

**Goal**: Verify a single tile surface shows a brightness gradient, not binary on/off.

### Visual verification (manual)
1. `./gradlew run`
2. Hold a torch and stand at one corner of a large room
3. Look at the floor tiles closest to you vs. across the room
4. Expected: tiles near torch are visibly brighter than far tiles; gradient is smooth across a single tile face

**Pass criterion**: At least 3 distinct brightness levels visible across 3 consecutive floor tiles in a straight line from the torch.

---

## US3: Multiple Dynamic Point Lights

**Goal**: Verify 2+ torches each illuminate and cast shadows independently.

### Headless verification (automated)
- `Gles3LightingShaderUniformTest.pointLightUniforms` — 8 `u_pointLightPos` locations all valid
- `GpuLightEnvironmentTest.buildTruncatesTo8` — existing test

### Visual verification (manual)
1. `./gradlew run`
2. Drop one torch in a corridor, keep another equipped
3. Observe the corridor lit by both simultaneously
4. Expected: two distinct light pools visible; wall between them casts a shadow from each light independently

**Pass criterion**: Both torches visibly illuminate geometry; neither torch's shadow ignores the wall between them.

---

## US4: Directional Ambient Light

**Goal**: Verify fully occluded surfaces are dim but not pitch black.

### Headless verification (automated)
- `Gles3LightingShaderUniformTest.ambientUniform` — `u_ambientColor` location valid
- `GpuLightEnvironmentTest` — `ambientR/G/B` fields present

### Visual verification (manual)
1. `./gradlew run`
2. Drop all light items; stand in a room with no torch
3. Expected: walls and floor are dim grey (ambient) not absolute black
4. Modify ambient in `ShadowRenderer.fromActor()` to 0.0 and verify scene goes black as a control

**Pass criterion**: Unlit scene is visually dim (grey) not absent.

---

## Regression Tests

After all US1–US4 visuals are confirmed:

| Check | Command | Expected |
|-------|---------|----------|
| All unit tests pass | `./gradlew test` | BUILD SUCCESSFUL |
| No old lighting imports remain | `grep -r "DynamicLighting\|SurfaceLighting\|LightingSystem\|BvhOccluder" src/main --include="*.kt"` | zero output |
| Shader files present | `ls src/main/resources/shaders/` | `gles3_lighting.vert.glsl`, `gles3_lighting.frag.glsl` |
| Old shader deleted | `ls src/main/resources/shaders/point_shadow.frag.glsl` | file not found |
| FPS check | Observe in-game FPS overlay (if available) | ≥ 30 FPS with 8 torches |
