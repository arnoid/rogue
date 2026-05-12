Act as an expert Kotlin developer and game engine architect using **libGDX**. I need you to implement a **Socket-Based 3D Procedural Map Generator** that grows a tile-based 3D world at runtime by stitching together hand-authored "submap" prefabs through directional connection points. The generator must integrate with an existing tile/wall data model (see §0) and live in a `com.roguelike.generation` package.

## 0. Existing data model (assume these are provided)

The runtime game uses these types in `com.roguelike.core.model`:
* `enum class TileSlot { FLOOR, WALL_NORTH, WALL_SOUTH, WALL_EAST, WALL_WEST, STAIRS }`
* `interface Tile { val type: String; val slot: TileSlot; ... }` plus an open `class BaseTile : Tile` exposing mutable `rotationX/Y/Z` and `xOffset/yOffset/zOffset`.
* `class WorldNode(x, y, z)` with: `setTile`, `getTile`, `removeTile`, `hasTile`, `tags: MutableSet<String>`, `items`, plus per-edge slot sets and toggles for `doorSlots / manualDoorSlots / socketSlots / ladderSlots` (each with `tagAsX / untagX`). The socket slots on a node's outer walls are what becomes a socket.
* `class World(width, height, depth)` containing a flat 3D grid of `WorldNode`, plus `props: MutableList<Prop>` and `associations`. World dimensions **must be divisible by 3**. World provides `getNode(x,y,z)` and `ensureSize(w,h,d)` to grow on demand.
* `WorldNode.Tags` contains `PLAYER_SPAWN`, `ENEMY_SPAWN`, `ITEM_SPAWN`, `EXIT`, `DOOR_MANUAL`, `SOCKET`, `LADDER`.
* Serialization: `com.roguelike.serialization.WorldIO.loadWorld(path, worldFactory, tileFactory): World?` and `saveWorld(path, world)`. Worlds are saved as libGDX-flavored JSON `.wld` files containing nodes, tiles, tags, edge-slot sets, items, props, and associations.
* Tile types are created by name through a `tileFactory: (String) -> Tile?`, e.g. `"FloorTile"`, `"WallNorthTile" / WallSouthTile / WallEastTile / WallWestTile`, `"DoorNorthTile" …`, `"StairsTile"`, `"LadderTile"`. The wall/door factories already apply the correct internal rotation for their cardinal slot.

## 1. Core architecture & constraints

* **The grid:** uniform 3D voxel grid, +X=East, +Y=North, +Z=Up.
* **Base unit:** the fundamental spatial unit is a **3×3×3 block**. All template dimensions must be divisible by 3.
* **Footprints:** stored in voxels (e.g. `Vector3Int(9, 6, 3)`) and additionally as **base units** (e.g. `3×2×1`) via a `baseUnitFootprint` derived getter.
* **Occupancy:** a single `MutableSet<Vector3Int>` (`occupiedGrid`) of **base-unit** coordinates tracks placed submaps. A candidate placement is rejected if any of its base-unit cells is already occupied, **or** if the candidate origin is negative on any axis (the world grows only in positive directions).
* **Sockets are derived, not authored separately:** any `WorldNode` with one or more `socketSlots` on an outer-boundary wall produces one or more sockets — one per socket slot. **Multi-socket rule:** a 9×9 face of a template therefore contains exactly nine 3×3-cell sockets, one per boundary node.
* **Socket tag:** a node may carry an optional `socket:<tag>` text tag in its `tags` set; this becomes the socket tag. Otherwise the tag defaults to `"default"`. Two sockets are compatible only when their tags **match exactly**.
* **Rotation:** templates are pre-expanded into all 4 rotations around Z (0°, 90°, 180°, 270° CW). Rotation is *structural*: tiles change cardinal direction, props rotate with the grid, socket edges are re-derived from the rotated outer boundary, and rotation count is stored on the template (0..3).

## 2. Data models (Kotlin)

### `Vector3Int`
Immutable data class with `x, y, z` and these members:
* `operator fun plus / minus`, `operator fun times(scalar: Int)`, `fun negate()`.
* `fun rotateCW90(): Vector3Int` — `(x,y,z) -> (y, -x, z)` (90° CW around Z).
* `fun rotateCW(steps: Int)` — apply `steps and 3` times.
* Companion constants: `ZERO`, `UP`, `DOWN`, `NORTH`, `SOUTH`, `EAST`, `WEST`, and `ALL_DIRECTIONS`.

### `SocketState`
`enum class SocketState { OPEN, CONNECTED, SEALED }`.

### `Socket`
```kotlin
data class Socket(
    val localPosition: Vector3Int, // node coords relative to the template's (0,0,0)
    val direction: Vector3Int,     // outward normal (NORTH/SOUTH/EAST/WEST; UP/DOWN allowed)
    val tag: String,
    var state: SocketState = SocketState.OPEN
)
```

### `SubmapTemplate`
```kotlin
data class SubmapTemplate(
    val name: String,
    val footprint: Vector3Int,         // in voxels (must be divisible by 3)
    val sockets: List<Socket>,
    val worldData: World,
    val rotation: Int = 0              // 0..3 = number of 90° CW rotations applied
) {
    val baseUnitFootprint: Vector3Int  // footprint / 3
    fun rotatedCW90(): SubmapTemplate
    fun allRotations(): List<SubmapTemplate> // [r0, r1, r2, r3]
    companion object {
        fun fromWorld(name: String, world: World): SubmapTemplate
        fun slotToDirection(slot: TileSlot): Vector3Int
        fun rotateSlot(slot: TileSlot): TileSlot         // WALL_NORTH -> WALL_WEST etc.
        fun rotateTileType(type: String): String         // WallNorthTile -> WallWestTile etc.
    }
}
```

`fromWorld`:
* Scans every node; for each `socketSlot` on a node creates a socket with `localPosition = (x,y,z)`, `direction = slotToDirection(slot)`, and `tag = node.tags.firstOrNull{ it.startsWith("socket:") }?.removePrefix("socket:") ?: "default"`.
* Returns a `SubmapTemplate` with rotation `0`.

`rotatedCW90`:
* New footprint swaps X and Y, keeps Z.
* Map every source node `(sx,sy,sz)` to destination `(srcH-1-sy, sx, sz)` (standard 90° CW remap).
* For each tile, unwrap any existing `RotatedTileRef` to recover the original tile, then re-wrap into a new `RotatedTileRef` that records:
  * `rotatedType = rotateTileType(tile.type)` (`WallNorthTile -> WallWestTile`, etc.)
  * `rotatedSlot = rotateSlot(tile.slot)`
  * `useFactoryDefaults` = whether the type actually changed (true for walls/doors). For non-directional tiles (floors, stairs) the type stays the same and we accumulate an additional Y rotation of `-90°`.
* Door / manual-door / ladder edge tags are remapped with `rotateSlot`. Socket slots are **re-derived** from the rotated node's position on the new outer boundary (so `x==0 -> WALL_WEST`, `x==newW-1 -> WALL_EAST`, `y==0 -> WALL_SOUTH`, `y==newH-1 -> WALL_NORTH`).
* Props rotate: `(x, y) -> (srcH-1-y, x)` and `rotationY -= 90°`.
* After the world is rebuilt, sockets are re-derived from socket slots on the rotated world.
* Returned template's `rotation = (rotation + 1) % 4`.

### `RotatedTileRef : Tile`
A lightweight `Tile` proxy used inside rotated template worlds:
```kotlin
class RotatedTileRef(
    val originalTile: Tile,
    val rotatedType: String,
    val rotatedSlot: TileSlot,
    val useFactoryDefaults: Boolean,
    val additionalRotY: Float
) : Tile { /* delegate type/slot/isBlocking/properties */ }
```

### `PlacedSubmap`
```kotlin
data class PlacedSubmap(
    val template: SubmapTemplate,
    val origin: Vector3Int,        // absolute voxel coordinate of template's (0,0,0)
    val sockets: List<Socket>      // fresh copies with mutable state
) {
    fun occupiedBaseUnits(): Set<Vector3Int>     // base-unit cells this instance occupies
    fun absoluteSocketPosition(socket: Socket): Vector3Int  // origin + socket.localPosition
}
```

## 3. The generation algorithm (`MapGenerator`)

```kotlin
class MapGenerator(
    templates: List<SubmapTemplate>,
    private val debugMode: Boolean = false,
    val adjacentSealProbability: Float = 0.25f
)
```

Internal state:
* `allTemplates`: every input template expanded via `allRotations()` and de-duplicated by `Triple(name, rotation, socketSignature)`.
* `occupiedGrid: MutableSet<Vector3Int>` (base-unit coordinates).
* `placedSubmaps: MutableList<PlacedSubmap>`.
* `debugChannel: Channel<DebugCandidate>` and `decisionChannel: Channel<DebugDecision>` — both `RENDEZVOUS`.
* `listener: GenerationListener?` with optional `onSubmapPlaced`, `onSocketSealed`, `onGenerationComplete`.

### Public API

* `fun placeInitial(template: SubmapTemplate, origin: Vector3Int = Vector3Int.ZERO): PlacedSubmap` — places the seed submap, registers occupancy, and fires `onSubmapPlaced`.
* `suspend fun generate(maxIterations: Int = 1000)` — main loop:
  1. Find the next `OPEN` socket across all placed submaps (depth-first scan).
  2. Compute the **absolute target node coordinate** for the neighbor:
     ```
     absolutePos = placed.origin + socket.localPosition
     oppositeDir = -socket.direction
     ```
  3. Filter `allTemplates` to those that have a socket with `direction == oppositeDir && tag == socket.tag`. Shuffle.
  4. For each candidate, iterate its **matching sockets** (also shuffled). For each `matchSocket` compute the candidate's origin so the two sockets sit on adjacent nodes facing each other:
     ```
     // The matching socket's node should occupy `absolutePos + socket.direction`
     candidateOrigin = absolutePos + socket.direction - matchSocket.localPosition
     ```
     This is the coordinate-math heart of the algorithm — see the inline comment requirement below.
  5. Call `canPlace(candidate, candidateOrigin)`:
     * Reject if any axis of `candidateOrigin` is negative.
     * Convert to base units and reject if any required cell is already in `occupiedGrid`.
  6. **(Debug pause point — see §4.)** If `debugMode`, send a `DebugCandidate` and `await` a `DebugDecision`. On `REJECT`, try the next match.
  7. On accept: call `placeSubmap(candidate, candidateOrigin)`, mark both `socket` and the matching socket inside the freshly placed submap as `CONNECTED`, fire `onSubmapPlaced`, then run `resolveAdjacentSockets(placed)`.
  8. If no candidate fits at all, set the original socket to `SEALED` and fire `onSocketSealed`.
* `suspend fun generateNeighbors(target: PlacedSubmap)` — the same loop but only over `target`'s own `OPEN` sockets. Used for **on-demand** expansion around the player (see §5). Emits identical debug events and listener calls. Add diagnostic `println` lines reporting candidate counts, accepted placements, and seal/connect events.
* `fun getSubmapAt(absolutePosition: Vector3Int): PlacedSubmap?` — returns the placed submap whose footprint AABB contains the position.

### `resolveAdjacentSockets(newlyPlaced)`

After a placement, scan its sockets that are still `OPEN`. For each one compute the neighbor node it points at; if **another** already-placed submap has an `OPEN` socket at that exact position facing back at us, treat that pair as a bonus adjacency:
* With probability `adjacentSealProbability`, set **both** sockets to `SEALED` and fire `onSocketSealed` on each.
* Otherwise set **both** to `CONNECTED` (no new submap is placed — the connection is implicit through shared geometry).

### `DebugCandidate / DebugDecision`

```kotlin
data class DebugCandidate(
    val template: SubmapTemplate,
    val origin: Vector3Int,
    val sourceSocket: Socket,
    val targetSocket: Socket
)
enum class DebugDecision { CONFIRM, REJECT }
```

### Inline comment requirement

In `generate()` / `generateNeighbors()`, leave a clear comment block at the candidate-origin calculation showing why `absolutePos + socket.direction - matchSocket.localPosition` is correct (the matching socket's local node must land on the cell one step past `absolutePos` in `socket.direction`, so we subtract its local offset from that absolute target).

## 4. Step-Through Debugger

Implement an interface:
```kotlin
interface DebugUICallback {
    fun showCandidate(candidate: DebugCandidate, onConfirm: () -> Unit, onReject: () -> Unit)
    fun hideDebugUI()
}
```

Provide a libGDX/scene2d implementation `GenerationDebugUI(stage: Stage, skin: Skin)` that:
* Adds a hidden full-parent `Table` anchored bottom-center with 50px bottom padding.
* Builds two `TextButton`s once with custom 1×1 Pixmap-backed styles:
  * **Confirm** label `"I do agree!"` with soft pink background `Color(1f, 0.7f, 0.8f, 1f)` (lighter `0.8/0.9` on over, darker `0.5/0.6` on down).
  * **Reject** label `"I do not agree!"` with `Color.GRAY` background (`LIGHT_GRAY` over, `DARK_GRAY` down).
* `showCandidate` re-binds click listeners, shows the table; clicking either button calls the corresponding callback and then `hideDebugUI()`.

The generator's debug pause happens **before** committing the placement (between steps 5 and 7 in §3). The coroutine bridge that connects `debugChannel`/`decisionChannel` to the UI is built outside the generator (see `ProceduralMapManager` in §5).

## 5. Runtime integration (`ProceduralMapManager`)

A `ProceduralMapManager(tileFactory, worldFactory)` orchestrates loading, generation, and stamping into the live `World` that the gameplay screen renders:

* `var activeWorld: World?` — the live world, grown on demand.
* `var generator: MapGenerator?`
* `var debugEnabled: Boolean` and `var debugCallback: DebugUICallback?`
* Internal: `loadedTemplates`, `stampedSubmaps: Set<Vector3Int>`, `neighborsGenerated: Set<Vector3Int>`, a `CoroutineScope(Dispatchers.Default + SupervisorJob())`, and a `WorldStamper`.

### `loadTemplates(directory: String)`

Recursively walks the directory for `.wld` files, loads each via `WorldIO.loadWorld(...)`, and converts to `SubmapTemplate.fromWorld(file.nameWithoutExtension, world)`. Skips templates with **zero sockets**. Logs each load.

### `initialize(initialPath: String): World?`

The Arena entry point:

1. **Load the initial submap** from the user-selected `.wld` file via `WorldIO.loadWorld`.
2. **Validate**: verify the world contains at least one node tagged `PLAYER_SPAWN`. Abort with an error log otherwise.
3. Build a `SubmapTemplate.fromWorld("initial", initialWorld)`.
4. Pick an initial offset of `Vector3Int(30, 30, 0)` so the world has room to grow in every horizontal direction.
5. Create an `activeWorld` sized to comfortably hold the initial submap plus growth margin (round each dimension up to a multiple of 3).
6. Construct `MapGenerator(loadedTemplates, debugEnabled)` (the initial submap is **not** added to the random template pool — only seeds the world).
7. If `debugEnabled && debugCallback != null`, launch a long-running coroutine that loops:
   * `val candidate = generator.debugChannel.receive()`
   * Create a `CompletableDeferred<DebugDecision>`, `postRunnable` on the GL thread to call `debugCallback.showCandidate(candidate, ...)`, then `await` the decision and send it back through `generator.decisionChannel`.
8. `generator.placeInitial(initialTemplate, initialOffset)` and immediately `stamper.stamp(placed, activeWorld)`; for any pre-`CONNECTED` socket call `stamper.openConnection(...)`.
9. Mark the initial origin in `neighborsGenerated` and `stampedSubmaps`.
10. **Kick off neighbor generation immediately** so all four adjacent rooms (and any extras the algorithm finds) are ready before the player moves: launch a coroutine that runs `generator.generateNeighbors(placed)`, then `postRunnable { stampNewSubmaps() }`.

### `onPlayerMove(playerX, playerY, playerZ)`

Called every gameplay frame (cheap):

* Compute the integer voxel position and look up `currentSubmap = generator.getSubmapAt(pos)`.
* If the current submap exists, still has any `OPEN` sockets, and its origin is **not** yet in `neighborsGenerated`, add the origin to `neighborsGenerated` and launch a coroutine that calls `generator.generateNeighbors(currentSubmap)` then `postRunnable { stampNewSubmaps() }`.

### `@Synchronized stampNewSubmaps()`

Walks `generator.placedSubmaps`; for each new origin not yet in `stampedSubmaps`:

* Add to `stampedSubmaps`.
* `world.ensureSize` to fit the new footprint (`origin + footprint`).
* `stamper.stamp(placed, world)`.
* For each socket: if `CONNECTED` call `stamper.openConnection(...)`; if `SEALED` call `stamper.sealConnection(...)`. Log each action.

After processing new submaps, do a second pass over **all** placed submaps to seal any sockets that became `SEALED` since their submap was first stamped (e.g. the initial submap's bonus-adjacent seals).

### `dispose()` cancels the `generationScope`.

## 6. `WorldStamper`

Copies a `PlacedSubmap.template.worldData` into the live world with an offset, **always re-creating tiles through the `tileFactory`** so the render registry stays consistent.

```kotlin
class WorldStamper(private val tileFactory: (String) -> Tile?) {
    fun stamp(placed: PlacedSubmap, target: World)
    fun openConnection(placed: PlacedSubmap, socket: Socket, target: World)
    fun sealConnection(placed: PlacedSubmap, socket: Socket, target: World)
    companion object {
        fun directionToSlot(direction: Vector3Int): TileSlot?
        fun slotToWallType(slot: TileSlot): String
    }
}
```

### `stamp` rules
* Iterate every source node, compute `target = origin + source` and skip if out of bounds.
* For each source tile:
  * Create a fresh tile via `tileFactory(tile.type)`.
  * If the new tile and the original are both `BaseTile`, propagate `rotationX/Y/Z` and `xOffset/yOffset/zOffset`.
  * **`RotatedTileRef` handling**:
    * If `useFactoryDefaults == true` (wall/door whose type changed — e.g. `WallNorthTile -> WallWestTile`): keep the factory's pre-set rotation/offset, only copy `zOffset` from the underlying original.
    * Otherwise (non-directional tile, e.g. stairs/floor): copy all rotation/offset from the underlying original and **add** `additionalRotY` to `rotationY`.
* Re-apply all per-edge slot tags (`doorSlots / manualDoorSlots / socketSlots / ladderSlots`), general tags, and items.
* Translate every prop: `prop.copy(x = prop.x + origin.x, y = …, z = …)`.

### `openConnection`
For a `CONNECTED` socket, remove the wall tile on the socket's edge of its node and the opposite-edge wall on the neighbor node. Also `untagSocket` on both sides.

### `sealConnection`
For a `SEALED` socket, place a fresh wall tile of the matching cardinal type on the socket's edge **only if the slot is empty**, then `untagSocket`.

## 7. Conventions

* Idiomatic Kotlin: `data class`, sealed/enum classes, expression bodies, coroutines.
* No locking beyond `@Synchronized` on `stampNewSubmaps()`.
* Plenty of `println("[MapGenerator] …")` / `Gdx.app?.log("ProceduralMapManager", …)` diagnostics — they double as the manual-test trail.
* Negative-origin rejection in `canPlace` is mandatory; the world must only grow into positive space.
* Always work in **base units** for occupancy (`origin / 3`), but in **voxels** for stamping and socket alignment.

## 8. Acceptance criteria

* Given any directory of `.wld` template prefabs, `ProceduralMapManager.loadTemplates(dir)` registers every template that has at least one socket.
* Calling `initialize(path)` on a `.wld` containing a `player_spawn` node yields a live `World` with the initial room stamped at `(30, 30, 0)` and at least its directly adjacent rooms already generated and stamped before the player can move.
* As the player crosses into a new placed submap, the next ring of rooms is generated in the background and stamped on the GL thread without freezing.
* All four rotation variants of each template are considered when matching sockets; `name`, `rotation`, and socket signature de-duplicate identical rotations of symmetric templates.
* Two adjacent rooms sometimes get a bonus opening between them (probability `1 - adjacentSealProbability`), otherwise both faces are walled off.
* When `debugEnabled == true`, every candidate placement pauses generation until the UI delivers `CONFIRM` (soft-pink **"I do agree!"**) or `REJECT` (gray **"I do not agree!"**).
* `WorldStamper` re-creates tiles through the factory and preserves rotation/offset for both directional (wall/door) and non-directional (floor/stairs) tiles across rotated templates.
