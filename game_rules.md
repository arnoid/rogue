# Game Rules Document — Movement, Collision & Physics

## 1. World Structure

The game world is a bounded 3D grid of dimensions `WIDTH × HEIGHT × DEPTH`.

| Term | Definition |
|------|-----------|
| **Node** | A single cell at integer coordinates `(X, Y, Z)`. Each node holds up to one tile per *slot* (Floor, Wall, Door, Interaction). |
| **Z-axis** | Vertical height. `Z = 0` is the ground floor; each increment is one storey. |
| **Actor** | A moving entity with a continuous `(x, y, z)` position and a square collision box of half-size `collisionSize` (default `0.3`). |

### 1.1 Tile Slots & Types

| Slot | Tile Types | Blocking? |
|------|-----------|-----------|
| FLOOR | `FloorTile`, `GenericTile` | No |
| FLOOR | `StairsNTile`, `StairsSTile`, `StairsETile`, `StairsWTile` | No |
| WALL | `WallHorizontalTile`, `WallVerticalTile`, `WallCrossingTile` | **Yes** |
| WALL | `CornerNETile`, `CornerSETile`, `CornerSWTile`, `CornerNWTile` | **Yes** |
| WALL | `WallTsplitN/E/S/WTile` | **Yes** |
| WALL | `WallDoorwayHorizontalTile`, `WallDoorwayVerticalTile` | No (passable) |
| DOOR | `DoorHorizontalTile`, `DoorVerticalTile` | **Yes when closed**, No when open |
| INTERACTION | `ToggleTile` | No |

---

## 2. Movement Logic (per frame)

Every frame, when the player provides directional input:

```
FUNCTION move(actor, moveDir, delta, speed):
    IF moveDir is zero → RETURN (no input)

    1. Normalise moveDir → dir
    2. Compute step = dir × delta × speed
    3. Set actor.facingDirection = (dir.x, dir.y, 0)
    4. Compute candidate position:
         nextX = actor.x + step.x
         nextY = actor.y + step.y

    5. [Door Escape] Find any closed door the actor's box currently
       overlaps AND the actor is moving AWAY from → skipNode

    6. [Collision — Full Move]
       IF canMove(nextX, nextY, actor.z, skipNode):
           actor.x = nextX
           actor.y = nextY
       ELSE [Axis Slide — X only]
           IF canMove(nextX, actor.y, actor.z, skipNode):
               actor.x = nextX
           ELSE [Axis Slide — Y only]
               IF canMove(actor.x, nextY, actor.z, skipNode):
                   actor.y = nextY
               // ELSE: fully blocked, no movement

    7. resolveZ(actor)   // stairs + gravity
```

### 2.1 Collision Check — `canMove`

The actor's collision box produces **four corners**:

```
corners = [
    (targetX − size, targetY − size),   // SW
    (targetX + size, targetY − size),   // SE
    (targetX − size, targetY + size),   // NW
    (targetX + size, targetY + size)    // NE
]
```

Each corner maps to a grid node via `round()`. For each corner `(cx, cy)`:

```
zFloor = floor(actor.z)
zCeil  = ceil(actor.z)
onStairs = (zCeil ≠ zFloor)   -- fractional Z means actor is on stairs

1. IF (cx, cy) is outside world bounds → BLOCKED

2. IF onStairs (actor is climbing between levels):
   Check node at (cx, cy, zCeil) only:
   - The base level (zFloor) is ignored — walls at the level the actor
     is climbing OUT OF should not block lateral movement.
   - IF node at zCeil has any blocking tile → BLOCKED
   - IF node is empty or non-blocking → PASSABLE

3. IF NOT onStairs (actor on flat ground):
   Check node at (cx, cy, zFloor):
   - IF node has any blocking tile → BLOCKED
   - IF node is empty or non-blocking → PASSABLE
```

**Key rule:** When the actor has fractional Z (climbing stairs), only the **destination level** (`zCeil`) is checked for collision. The base level (`zFloor`) is the level the actor is leaving, so walls there do not block. When the actor is on flat ground (integer Z), only the current level is checked.

### 2.2 `isPassable` Summary

```
FUNCTION isPassable(cx, cy, z, skipNode):
    node = world.getNode(cx, cy, z)
    IF node is null             → PASSABLE  (open air, gravity handles)
    IF node is skipNode         → PASSABLE  (door escape)
    IF node has no tiles        → PASSABLE  (empty)
    IF any tile is blocking     → BLOCKED
    ELSE                        → PASSABLE
```

---

## 3. Stair Interaction

### 3.1 Stair Model

Each stair tile occupies one node at its **base Z level** and has a direction indicating which edge is the "top" (high end):

| Stair Type | Low Edge | High Edge | Interpolation |
|-----------|----------|-----------|---------------|
| `StairsNTile` | South (Y−) | North (Y+) | `t = offsetY + 0.5` |
| `StairsSTile` | North (Y+) | South (Y−) | `t = 0.5 − offsetY` |
| `StairsETile` | West (X−) | East (X+) | `t = offsetX + 0.5` |
| `StairsWTile` | East (X+) | West (X−) | `t = 0.5 − offsetX` |

Where `offsetX/Y` is the actor's position minus the node centre (`−0.5` to `+0.5`).

`t` is clamped to `[0, 1]`. The actor's Z becomes: **`baseZ + t`**.

### 3.2 Entering from the Bottom (Walking Up)

```
1. Actor at Z=0 walks onto a StairsNTile at node (5, 5, 0).
2. resolveZ detects stairs at floor(Z)=0.
3. applyStairs interpolates: as actor moves from south edge (t=0) to
   north edge (t=1), Z rises from 0.0 → 1.0.
4. Actor exits north edge → enters node (5, 6, 1).
5. No stairs at (5,6,1) → gravity checks → floor at Z=1 → Z snaps to 1.
```

### 3.3 Entering from the Top (Walking Down)

```
1. Actor at Z=1 walks onto the stair node from the high edge.
2. resolveZ: floor(1.0)=1, no stairs at Z=1.
3. Gravity scans downward from Z=1 → finds stairs at Z=0 (walkable) → Z=0.
4. Post-gravity stair check: stairs at Z=0 → applyStairs.
5. Actor is at the high edge → t≈1.0 → Z=0+1.0=1.0 (stays at top).
6. As actor moves toward low edge, Z decreases toward 0.
```

### 3.4 Falling onto Stairs from Above

```
1. Actor at Z=2 steps into air above a stair node.
2. resolveZ: no stairs at floor(2)=2.
3. Gravity scans: Z=2 empty, Z=1 empty, Z=0 has stairs (walkable) → Z=0.
4. Post-gravity: stairs at Z=0 → applyStairs → Z = 0 + t.
5. Actor is positioned on stairs based on XY offset, can walk off normally.
```

---

## 4. Gravity & Falling

### 4.1 Resolution Order

```
FUNCTION resolveZ(actor):
    nodeX = round(actor.x)
    nodeY = round(actor.y)
    baseZ = floor(actor.z)

    // PRIORITY 1: If already on stairs, let stairs handle Z
    IF hasStairs(nodeX, nodeY, baseZ):
        applyStairs(actor, nodeX, nodeY, baseZ)
        RETURN

    // PRIORITY 2: Apply gravity
    applyGravity(actor, nodeX, nodeY)

    // PRIORITY 3: If gravity landed us on stairs, apply stair interpolation
    landedZ = floor(actor.z)
    IF hasStairs(nodeX, nodeY, landedZ):
        applyStairs(actor, nodeX, nodeY, landedZ)
```

### 4.2 Gravity Scan

```
FUNCTION applyGravity(actor, nodeX, nodeY):
    z = round(actor.z)

    WHILE z >= 0:
        node = world.getNode(nodeX, nodeY, z)

        IF node exists AND has tiles:
            IF no tile is blocking:
                // Walkable surface (floor, open door, stairs)
                actor.z = z
                RETURN
            ELSE:
                // Solid obstacle (wall, corner, closed door)
                // Actor stands ON TOP of it
                actor.z = z + 1
                RETURN

        z = z − 1   // Empty node, keep falling

    // Fell through everything — clamp to ground
    actor.z = 0
```

### 4.3 Gravity Rules Summary

| Node Found | Action | Result |
|-----------|--------|--------|
| Walkable (floor, stairs, open door) | Stand on it | `Z = nodeZ` |
| Blocking (wall, corner, closed door) | Stand on top | `Z = nodeZ + 1` |
| Empty / null | Continue falling | Check `Z − 1` |
| Nothing found (all empty) | Clamp | `Z = 0` |

---

## 5. Door Interaction

### 5.1 Closed Door Blocking

Closed doors (`DoorHorizontalTile`, `DoorVerticalTile` with `isOpen = false`) are blocking tiles. They prevent movement like walls.

### 5.2 Door Escape Rule

**Problem:** When a door closes while the actor overlaps its node, the actor would become permanently stuck.

**Solution:** If the actor's collision box overlaps a closed door AND the actor is moving **away** from that door, the door node is temporarily excluded from collision checks for that frame.

```
FUNCTION findOverlappingClosedDoorNode(actor, moveDir):
    FOR each node overlapped by actor's collision box at round(actor.z):
        IF node has a closed door tile:
            // Check movement direction relative to door orientation
            IF door is Horizontal AND actor is moving away on Y-axis → skip this node
            IF door is Vertical AND actor is moving away on X-axis → skip this node
    RETURN null  // No escape needed
```

**"Moving away" means:**
- Actor is on the positive side of the door AND moving in the positive direction, OR
- Actor is on the negative side of the door AND moving in the negative direction.

---

## 6. Edge Cases & Safeguards

### 6.1 Edge Case: Gravity-Stairs Oscillation

**Scenario:** Actor is on stairs at Z=0.5. Gravity runs and snaps Z to 0. Next frame, stair interpolation sets Z back to 0.5. The actor visually vibrates and cannot move.

**Safeguard:** `resolveZ` checks for stairs **before** gravity. If the actor's `floor(z)` level has stairs, gravity is skipped entirely — stair interpolation owns the Z value.

```
IF hasStairs at floor(actor.z) → applyStairs, skip gravity
ELSE → applyGravity, then check landing for stairs
```

### 6.2 Edge Case: Walking from Stairs into Adjacent Wall

**Scenario:** Actor is on stairs at Z=0.3 (lower half) and walks sideways into an adjacent node that has a wall at Z=0 but a floor at Z=1.

**Safeguard:** When on stairs (fractional Z), `canMove` only checks `zCeil` (the destination level). The wall at `zFloor=0` is ignored because the actor is climbing out of that level. The floor at `zCeil=1` is passable, so movement is allowed — gravity will then handle the actor's landing.

### 6.3 Edge Case: Out-of-Bounds Movement

**Scenario:** Actor walks to the edge of the world grid.

**Safeguard:** `canMove` rejects any corner coordinate where `cx < 0 || cx >= world.width || cy < 0 || cy >= world.height`. The actor cannot leave the grid.

### 6.4 Edge Case: Falling Through Empty World

**Scenario:** An entirely empty column of nodes — no floor, no walls, all the way down.

**Safeguard:** `applyGravity` has a fallback: if the scan reaches `z < 0` without finding any node, the actor's Z is clamped to `0`. The actor never falls below the world.

### 6.5 Edge Case: Standing on Top of Walls

**Scenario:** Actor is at Z=1 and walks over a wall at Z=0 that has no floor at Z=1.

**Safeguard:** Gravity detects the wall at Z=0 as a blocking node and places the actor at `Z = 0 + 1 = 1`, effectively standing on top of the wall. The actor can walk freely across wall tops without a floor tile.

### 6.6 Edge Case: Door Closes on Actor

**Scenario:** A toggle opens/closes a remote door. If the door closes while the actor is inside its node, the actor becomes trapped — they overlap a blocking tile.

**Safeguard:** The door-escape system detects this overlap and allows the actor to move **away** from the door. The actor can slide out in the direction perpendicular to the door. Movement **toward** the door (deeper in) is blocked.

### 6.7 Edge Case: Stair Node with Wall on Same Z Level

**Scenario:** A level design has a wall and stairs on adjacent nodes at the same Z. When the actor climbs the stairs (fractional Z), their collision box might extend into the wall's column.

**Safeguard:** At the `zFloor` level, nodes containing stairs get the `allowStairs = true` bypass — they are always passable. Adjacent wall nodes (no stairs) are still checked normally and will block. At the `zCeil` level, all blocking tiles block without bypass, preventing the actor from clipping through walls at the destination floor.

---

## 7. Frame Execution Order

```
EACH FRAME:
  1. Read player input → moveDir
  2. IF moveDir ≠ 0:
       a. Compute candidate position (nextX, nextY)
       b. Check door-escape eligibility
       c. Resolve XY collision (full move → X slide → Y slide → blocked)
       d. resolveZ:
            i.   IF on stairs → applyStairs (Z interpolation)
            ii.  ELSE → applyGravity (scan downward)
            iii. IF landed on stairs → applyStairs
  3. Update actor visual position
  4. Render world up to maxZ = ceil(player.z)
```

