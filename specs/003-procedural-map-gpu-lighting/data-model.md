# Data Model: Procedural Map Generator and GPU Lighting Engine

**Feature**: `003-procedural-map-gpu-lighting`
**Date**: 2026-05-13

---

## System A — Procedural Map Generator

All entities below already exist in `com.roguelike.generation`. This document captures the authoritative model for test writing.

### Vector3Int
**File**: `src/main/kotlin/com/roguelike/generation/Vector3Int.kt`
**Package**: `com.roguelike.generation`

| Field | Type | Description |
|-------|------|-------------|
| x | Int | Grid coordinate / direction component |
| y | Int | Grid coordinate / direction component |
| z | Int | Grid coordinate / direction component |

**Operations**: `+`, `-`, `*`, `negate()`, `allRotations()` (4 variants around Z).

**Invariant**: Direction normals use cardinal unit vectors (e.g., `Vector3Int(0,1,0)` = North).

**Companion constants**: `ZERO`, `NORTH`, `SOUTH`, `EAST`, `WEST`, `UP`, `DOWN`.

---

### Socket
**File**: `src/main/kotlin/com/roguelike/generation/Socket.kt`
**Package**: `com.roguelike.generation`

| Field | Type | Description |
|-------|------|-------------|
| localPosition | Vector3Int | Position in Base Unit coordinates within the template |
| direction | Vector3Int | Outward-facing normal (cardinal unit vector) |
| tag | String | Must match exactly to connect |
| state | SocketState | Lifecycle state (mutable) |

**State transitions**:
```
OPEN → CONNECTED  (when a compatible template is placed at this socket)
OPEN → SEALED     (when no compatible template fits; allows wall/door spawn)
```

**Connection rule**: Two sockets connect iff `a.tag == b.tag` AND `a.direction == b.direction.negate()`.

---

### SocketState (enum)
**File**: `src/main/kotlin/com/roguelike/generation/Socket.kt`

| Value | Meaning |
|-------|---------|
| OPEN | Available for connection |
| CONNECTED | Paired with a socket on an adjacent placed submap |
| SEALED | No compatible template found; treated as solid wall/door |

---

### SubmapTemplate
**File**: `src/main/kotlin/com/roguelike/generation/SubmapTemplate.kt`
**Package**: `com.roguelike.generation`

| Field | Type | Description |
|-------|------|-------------|
| name | String | Unique template identifier |
| footprint | Vector3Int | Dimensions in Base Unit coordinates |
| sockets | List\<Socket\> | All connection points on template faces |
| rotation | Int | 0, 90, 180, or 270 degrees around Z axis |

**Multi-Socket Rule**: A face of width W Base Units exposes W distinct sockets (one per Base Unit). A 3×3 Base Unit face has 9 sockets.

**Derived**: `allRotations()` returns 4 variants (or fewer if symmetric).

---

### PlacedSubmap
**File**: `src/main/kotlin/com/roguelike/generation/PlacedSubmap.kt`
**Package**: `com.roguelike.generation`

| Field | Type | Description |
|-------|------|-------------|
| template | SubmapTemplate | The source template |
| origin | Vector3Int | Absolute Base Unit grid position of the (0,0,0) corner |
| sockets | List\<Socket\> | Instance-owned copies with resolved state |

**Occupied cells**: Every `(origin.x + dx, origin.y + dy, origin.z + dz)` for `0 ≤ dx < footprint.x`, `0 ≤ dy < footprint.y`, `0 ≤ dz < footprint.z`.

---

### MapGenerator
**File**: `src/main/kotlin/com/roguelike/generation/MapGenerator.kt`
**Package**: `com.roguelike.generation`

| Field | Type | Description |
|-------|------|-------------|
| occupiedGrid | MutableSet\<Vector3Int\> | Currently occupied Base Unit cells |
| placedSubmaps | MutableList\<PlacedSubmap\> | All committed placements |
| debugChannel | Channel\<DebugCandidate\> | Sends a candidate before commitment (debug mode) |
| decisionChannel | Channel\<DebugDecision\> | Receives CONFIRM or REJECT from UI (debug mode) |
| listener | GenerationListener? | Callback for placement events |

**Loop invariant**: A placement is committed to `occupiedGrid` and `placedSubmaps` only after all collision checks pass and (in debug mode) the debug decision is CONFIRM.

---

### DebugCandidate / DebugDecision
**File**: `src/main/kotlin/com/roguelike/generation/MapGenerator.kt`

```kotlin
data class DebugCandidate(val openSocket: Socket, val template: SubmapTemplate, val origin: Vector3Int)
enum class DebugDecision { CONFIRM, REJECT }
```

---

## System B — GPU Lighting

All entities below are **new** for this feature.

### DirectionalLightData
**Planned file**: `src/main/kotlin/com/roguelike/core/model/lighting/DirectionalLightData.kt`
**Package**: `com.roguelike.core.model.lighting`
**No LibGDX imports.**

| Field | Type | Description |
|-------|------|-------------|
| directionX | Float | World-space direction X component (normalized) |
| directionY | Float | World-space direction Y component (normalized) |
| directionZ | Float | World-space direction Z component (normalized) |
| r | Float | Light color red channel [0..1] |
| g | Float | Light color green channel [0..1] |
| b | Float | Light color blue channel [0..1] |
| intensity | Float | Multiplier applied to color |

---

### PointLightData
**Planned file**: `src/main/kotlin/com/roguelike/core/model/lighting/PointLightData.kt`
**Package**: `com.roguelike.core.model.lighting`
**No LibGDX imports.**

| Field | Type | Description |
|-------|------|-------------|
| x | Float | World-space position X |
| y | Float | World-space position Y |
| z | Float | World-space position Z |
| r | Float | Light color red channel [0..1] |
| g | Float | Light color green channel [0..1] |
| b | Float | Light color blue channel [0..1] |
| intensity | Float | Attenuation radius / intensity |

---

### GpuLightEnvironment
**Planned file**: `src/main/kotlin/com/roguelike/core/model/lighting/GpuLightEnvironment.kt`
**Package**: `com.roguelike.core.model.lighting`
**No LibGDX imports.**

| Field | Type | Description |
|-------|------|-------------|
| directionalLight | DirectionalLightData? | Global directional light; null if none active |
| pointLights | List\<PointLightData\> | All active point lights (max 8 per SC-007) |
| ambientR | Float | Ambient color red channel |
| ambientG | Float | Ambient color green channel |
| ambientB | Float | Ambient color blue channel |

**Factory**: Built each frame from the active `World`'s light sources (via `LightEnvironment` / item catalog).

---

### ShadowRenderer
**Planned file**: `src/main/kotlin/com/roguelike/rendering/ShadowRenderer.kt`
**Package**: `com.roguelike.rendering`
**LibGDX imports allowed.**

Responsibilities:
1. Own `DirectionalShadowLight` (depth FBO + orthographic camera).
2. Own `FboCubemap` pool (one per point light up to 8).
3. Execute **Depth Pass**: render all geometry into shadow map(s).
4. Execute **Main Pass**: render all geometry with the assembled `Environment` (directional shadow map + point lights).
5. Dispose all GPU resources on `dispose()`.

**Does NOT**: compute visibility (that remains `DynamicLighting`'s domain until it is replaced). Initially wires all point lights unconditionally; LOS culling is a future optimization.

---

## Entity Relationships

```
GpuLightEnvironment
  └── DirectionalLightData (0..1)
  └── PointLightData (0..8)

ShadowRenderer
  └── converts GpuLightEnvironment → LibGDX Environment + shadow maps
  └── owns DirectionalShadowLight (1)
  └── owns FboCubemap[] (0..8)

MapGenerator
  └── uses SubmapTemplate[] (library)
  └── produces PlacedSubmap[]
       └── occupies Vector3Int cells in GridMap

PlacedSubmap
  └── owns Socket[] (instance copies)
       └── each Socket: SocketState lifecycle

WorldStamper
  └── consumes PlacedSubmap[]
  └── writes into World (existing game world model)
```
