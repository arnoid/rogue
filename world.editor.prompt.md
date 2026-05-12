# Prompt: Build a 3D Tile-Based World Editor in Kotlin / libGDX

You are building a desktop **3D map editor** for a tile-based roguelike, written in **Kotlin** on top of **libGDX** with the **VisUI** widget library. The editor lets a designer paint floors, walls, doors, stairs, ladders, tags, and decoration props onto a finite 3D grid, then save/load the result as `.wld` files (libGDX JSON). Implement the editor as one `Screen` (`MapEditor`) plus a small set of supporting classes under a `com.roguelike.editor` package.

Assume the following data model already exists in `com.roguelike.core.model`:

- `enum class TileSlot { FLOOR, WALL_NORTH, WALL_SOUTH, WALL_EAST, WALL_WEST, STAIRS }`
- `interface Tile { val type: String; val slot: TileSlot; fun isBlocking(): Boolean; ... }`
- `class WorldNode(x, y, z)` with methods:
  - `setTile(tile)`, `getTile(slot)`, `removeTile(slot)`, `hasTile(slot)`, `hasFloor`
  - `tagAsDoor/untagDoor/isDoor(slot)`, `doorSlots: Set<TileSlot>`
  - `tagAsManualDoor/untagManualDoor/isManualDoor(slot)`, `manualDoorSlots`
  - `tagAsSocket/untagSocket/isSocket(slot)`, `socketSlots`
  - `tagAsLadder/untagLadder/isLadder(slot)`, `ladderSlots`
  - `items: MutableList<Item>`, `tags: MutableSet<String>`
  - `object Tags { PLAYER_SPAWN, ENEMY_SPAWN, ITEM_SPAWN, EXIT, DOOR_MANUAL, SOCKET, LADDER }`
- `class World(width, height, depth)`:
  - `getNode(x,y,z)`, `addTag/removeTag(node, tag)`, `addAssociation(...)`, `props: MutableList<Prop>`, `associations`
- `data class Prop(id, modelPath, name, x, y, z, rotationY, scale, collisionHalfSizeX, collisionHalfSizeY)`
  - `rotatedHalfSizes(): Pair<Float,Float>` returns AABB after applying scale and rotation
- Tile types created via `ModelLoader.createTile(typeName)`: `"FloorTile"`, `"WallNorthTile"`, `"WallSouthTile"`, `"WallEastTile"`, `"WallWestTile"`, `"DoorNorthTile"`, `"DoorSouthTile"`, `"DoorEastTile"`, `"DoorWestTile"`, `"StairsTile"`, `"LadderTile"`. Each rotated wall/door tile already has the correct internal rotation.
- `BaseTile` exposes mutable `rotationX/Y/Z` and `xOffset/yOffset/zOffset`.
- Serialization in `com.roguelike.serialization.WorldIO`: `loadWorld(path, worldFactory, tileFactory): World?` and `saveWorld(path, world)`. Prop `modelPath` is stored relative to the libGDX resource root.
- Rendering helpers exist: `TileRenderer`, `WorldRenderer`, `ItemRenderer`, `PropRenderer`. `PropRenderer.render(prop, batch, env, selected)` renders a single prop with `selected=true` tinting it cyan.

## High-level requirements

Build the following classes in `com.roguelike.editor`:

### 1. `EditorToolMode` (enum)

```kotlin
enum class EditorToolMode { NONE, FILL, ROOM }
```

### 2. `PaletteSelection` (sealed class) in `EditorPalettePanel.kt`

```kotlin
sealed class PaletteSelection {
    object FloorSel : PaletteSelection()
    object WallSel : PaletteSelection()
    object DoorSel : PaletteSelection()
    object StairsSel : PaletteSelection()
    object LadderSel : PaletteSelection()
    data class TagSel(val tag: String) : PaletteSelection()
    data class DecorationSel(val modelPath: String, val name: String) : PaletteSelection()
}
```

### 3. `EditorPalettePanel`

Right-hand-side scrollable panel containing two tabs: **Tiles** and **Props**.

**Tiles tab** contains sections (top-down): `TILES` header, then for each of `Floor / Wall / Door / Stairs / Ladder` a labeled card that:
- Renders a **3D preview** of the corresponding default tile (using a private `PerspectiveCamera` at `(0,0,2)` looking at origin, with a private `Environment` having an ambient light + one directional light, and re-using the main `ModelBatch` inside a scissor-clipped viewport). Implement an inner `TilePreviewActor(tile: Tile)` that does this by ending the 2D batch, setting `glScissor`/`glViewport`, calling `tileRenderer.render(tile, ...)`, restoring the viewport, then resuming the 2D batch.
- Has a click listener calling `toggleSelection(PaletteSelection.XxxSel)`.
- Is wrapped in an inner class `SelectionBorderGroup(isSelected: () -> Boolean) : VisTable` that draws a 3px **cyan border** around its children when `isSelected()` is true (using `VisUI.getSkin().getDrawable("white")` and `batch.setColor`).

Below tiles, render a `TAGS` section: a vertical list of toggle-style `VisTextButton`s for `PLAYER_SPAWN, ENEMY_SPAWN, ITEM_SPAWN, EXIT, DOOR_MANUAL, SOCKET, LADDER`. Selecting a tag sets `paletteSelection = TagSel(tag)`. Provide `refreshHighlights()` and `updateHighlightsForNode(node)` so that:
- `btn.isChecked = (selection is TagSel && selection.tag == tag) || node.hasTagOrEdgeFlag(tag)` — for per-edge tags (`DOOR_MANUAL`, `SOCKET`, `LADDER`) check the corresponding edge-slot sets; otherwise check `node.tags`.

**Props tab** contains a `DECORATIONS` header and a `+ Add Model` button. Clicking the button opens an **AWT FileDialog** (`java.awt.FileDialog`) filtering for `.obj`, `.g3db`, `.g3dj`. Adding a model persists the entry to `~/.roguelike-editor-decorations.json` (libGDX `Json.prettyPrint`).
- Each decoration entry is a card (`SelectionBorderGroup`) containing an inner `PropPreviewActor(modelPath)` that loads the model once via `AssetLoader.loadModel("prop_$path", path)`, normalizes its scale via its bounding-box `maxDim`, and renders it the same way as `TilePreviewActor` but with a rotation `rotate(X,-90).rotate(Z,180)` and translated to the model's centered origin.
- Left-click toggles `DecorationSel`. Right-click pops a VisUI `PopupMenu` with a **Delete** item that removes the entry (and clears selection if it was selected).
- Expose `syncDecorationsFromWorld(world)` to auto-add any missing entries after loading a world (using each prop's existing name or filename fallback).

Switching tabs clears `paletteSelection`. The active tab button is colored `Color.CYAN`, the other `Color.WHITE`.

### 4. `EditorStatusBar`

Bottom bar with VisUI labels and `-`/`+` buttons. Show **X / Y / Z** dimensions of the world plus a **Layer** indicator (`maxRenderZ`). Dimension `-`/`+` change by `3` (the world dimensions must be multiples of 3) and call back into `onResize(nx, ny, nz)`. Layer `-`/`+` adjusts `maxRenderZ` (clamped to `[0, depth-1]`).

### 5. `EditorInputHandler` — the core editing logic

Constructor params: `getWorld: () -> World, modelLoader, palette, onCameraOrbit, onCameraPan, onCameraZoom, onUpdatePaletteHighlights`.

Public state:
- `selectedX/Y/Z: Int = -1`, `selectedEdge: TileSlot? = null`, `selectedProp: Prop? = null`
- `toolMode: EditorToolMode = NONE`
- Room-drag fields: `roomDragStartX/Y, roomDragEndX/Y, isRoomDragging, isRoomSubtract`

`handleInput(delta, hoveredX, hoveredY, hoveredZ, hoveredEdge: TileSlot?)` — called every frame **after** the parent screen has computed hover. Behavior, in order:

1. **Camera controls (early return):**
   - Middle-mouse drag → `onCameraPan`
   - Right-mouse drag → `onCameraOrbit`

2. **Fill tool** — when `toolMode == FILL`, the palette has `FloorSel`, LMB was just pressed, and a node is hovered:
   - If Ctrl is **not** held: **flood-fill floor tiles** starting at the hovered node on `hoveredZ`. BFS spreads N/S/E/W. The spread is blocked when **either** the current node has a wall on the outgoing edge **or** the neighbor has a wall on the incoming edge. Place a `FloorTile` on every reached cell that doesn't already have one. Do **not** cross Z levels.
   - If Ctrl **is** held: **flood-erase floors** with the same wall-bounded spread, but only entering nodes that already have a `FLOOR` tile, removing it on visit.
   - `return`.

3. **Room tool** — when `toolMode == ROOM` and palette has `WallSel`:
   - On `justTouched` with LMB pressed: capture `roomDragStart = (hoveredX, hoveredY)`, set `isRoomDragging = true`, and `isRoomSubtract = isCtrlHeld`.
   - While dragging: keep updating `roomDragEnd = hovered`.
   - On release: compute `(minX..maxX, minY..maxY)` clamped to the world. Then:
     - **Addition (no Ctrl):** Remove walls on **internal** shared edges of the rectangle (so the inside is hollow). Then walk the perimeter and place outward-facing walls (`WallNorthTile` along top row, `WallSouthTile` along bottom row, `WallEastTile` along right column, `WallWestTile` along left column). **Skip placing a perimeter wall** (and remove any existing wall on the shared edge with the outside) whenever the **outside-adjacent node has a floor** — this merges into a neighboring room.
     - **Subtraction (Ctrl held):** Remove all walls and floors inside the rectangle. Also remove neighbor walls that face into the rectangle. Then seal the cut: for each perimeter side, if the **outside** node has a floor, place a wall on that outside node facing **inward** toward the carved space.
   - Provide visual feedback (drawn by `MapEditor` using `inputHandler.isRoomDragging`, `isRoomSubtract`, drag bounds, and current Z): a rectangle outline in **magenta** for Addition and **red** for Subtraction.

4. **Ctrl + LMB erase** — when palette has a selection and Ctrl+LMB is pressed:
   - `FloorSel` → remove `FLOOR` tile
   - `WallSel`/`DoorSel` → remove tile at `hoveredEdge` and untag door
   - `StairsSel` → remove `STAIRS` tile
   - `LadderSel` → if `hoveredEdge` is a wall slot, remove `STAIRS` tile
   - `TagSel(tag)` → for per-edge tags untag the hovered edge, otherwise `world.removeTag`
   - `DecorationSel` → find prop at `(hoveredX, hoveredY, hoveredZ)` via `findPropAt` (AABB check using `prop.rotatedHalfSizes() + 0.5f` slack) and remove it.

5. **Ctrl + LMB with no palette selection** — delete the hovered prop (if any).

6. **Normal LMB paint/select** — switch on `palette.paletteSelection`:
   - `FloorSel` → create `FloorTile` on hovered node if missing.
   - `WallSel` → require `hoveredEdge` to be a wall slot; create the matching `WallXxxTile` if that slot is empty.
   - `DoorSel` → require wall slot; remove any existing tile in that slot, create the matching `DoorXxxTile`, call `node.tagAsDoor(slot)`.
   - `StairsSel` → create `StairsTile` if the `STAIRS` slot is empty.
   - `LadderSel` → require wall slot; create a `LadderTile`, set its `rotationY` based on slot (`N=0, E=90, S=180, W=270`), put in `STAIRS` slot.
   - `TagSel(tag)`:
     - `DOOR_MANUAL` only on a hovered edge that is already a door — toggle on press / off on `justTouched`.
     - `SOCKET` only on the **outer** boundary edges of the world — toggle.
     - `LADDER` — toggle per-edge.
     - Other tags — toggle on whole node.
     - After any change call `onUpdatePaletteHighlights()`.
   - `null`:
     - On `justTouched`, first try `findPropAt(...)`; if a prop is found, `selectedProp = prop`. Else clear `selectedProp` and set `selectedX/Y/Z = hovered`, `selectedEdge = hoveredEdge`.
     - Call `onUpdatePaletteHighlights()`.
   - `DecorationSel`:
     - On `justTouched`, create a new `Prop(modelPath, name, x=hoveredX.toFloat(), y=hoveredY.toFloat(), z=hoveredZ.toFloat())`, add to `world.props`, set `selectedProp`.
   - Track `lastPaintX/Y/Z` so that drag-painting only triggers once per node.

7. **Keyboard helpers:**
   - With a node hovered whose `STAIRS` tile is a `BaseTile`: `Q`/`E` rotate `rotationY` by ±90°.
   - When `selectedProp != null`: `W/A/S/D` move ±0.05 in Y/X, `R/F` adjust Z ±0.05, `Q/E` rotate ±15°, `Z/X` scale ±0.05 (min `0.05`).

Implement a `companion object` with helpers: `findPropAt(world,x,y,z)`, `wallTypeForSlot`, `doorTypeForSlot`, `ladderRotationForEdge`.

### 6. `MapEditor : Screen` — the main editor screen

Owns:
- A 3D `PerspectiveCamera` (orbit camera) with spherical position computed from `cameraTarget`, `cameraDistance`, `cameraPitch`, `cameraYaw`. Provide `updateCamera()`. Default pitch `60°`, yaw `180°`. Pitch clamped to `[-89, 90]`.
- `ModelBatch`, `ShapeRenderer`, `AssetLoader`, `ModelLoader`, renderers (`TileRenderer`, `WorldRenderer`, `ItemRenderer`, `PropRenderer`).
- A `World(6,6,3)` default world.
- VisUI `Stage` + a fill-parent `rootTable` divided into: **menu bar** (top), **left toolbar column**, **viewport (VisSplitPane left)**, **palette scroll (VisSplitPane right)**, **status bar** (bottom).
- An `OrientationGizmo` actor in the top-left of the viewport that resets pitch/yaw and re-centers the camera on click.
- Hover tracking: `hoveredX/Y/Z`, `hoveredEdge`. Compute every frame by ray-picking against AABBs on the **current edit Z level** (`maxRenderZ`). Pick the closest node with content, falling back to the closest empty node. Then `detectHoveredEdge` returns the closest face (N/S/E/W) of that node hit by the ray.

**Menu bar** (`File` menu): `New`, `Open`, recent files (persisted in `Gdx.app.getPreferences("MapEditorPrefs")`, max 5), `Save`, `Save As...`, `Exit` (→ `MainMenuScreen`). Use `PlatformUtils.chooseFile("wld")` and `PlatformUtils.chooseFileName("world.wld")` on a background thread to avoid blocking the GL thread, then `Gdx.app.postRunnable` to apply results.

**Left toolbar column** (32×32 icon toggle buttons, dark background `Color(0.15, 0.15, 0.15)`). All icons are loaded as PNGs but **inverted at load time** (per-pixel: invert RGB, keep alpha) so they appear white on the dark background — write a `makeToolbarButton(iconPath, onClick)` helper using `Pixmap` to do the inversion before wrapping in a `TextureRegionDrawable` and `VisImageButton`. Buttons in order:
1. `icons/cursor-default-outline.png` → `inputHandler.toolMode = NONE` (deselect tools)
2. `icons/view-grid-outline.png` → toggle `showFrames`
3. `icons/rotate-counter-clockwise.png` → `rotateWorld(clockwise=false)`
4. `icons/rotate-clockwise.png` → `rotateWorld(clockwise=true)`
5. `icons/format-color-fill.png` → toggle `toolMode` ⇄ `FILL`
6. `icons/vector-square.png` → toggle `toolMode` ⇄ `ROOM`

**`resizeWorld(nx, ny, nz)`** rounds each dimension up to the nearest multiple of 3 (min 3) and copies over tiles, tags, all per-edge slot sets, items, and props from the old world.

**`rotateWorld(clockwise)`** uses `SubmapTemplate.fromWorld("editor", world).rotatedCW90()` (one CW step for clockwise, three for CCW) to rotate the world. After rotation, rebuild a fresh `World` by re-creating tiles through `modelLoader.createTile(type)` so that they re-register in the render registry; copy props with `prop.copy()`.

**`render(delta)`** does, in order:
1. Update hover, call `inputHandler.handleInput(...)`.
2. Compute the viewport rect (viewport sub-area inside the split pane) using back-buffer pixels.
3. Render the world inside a scissored sub-viewport:
   - `worldRenderer.render(world, modelBatch, environment, maxRenderZ)`
   - Render every `world.props` whose `z.toInt() <= maxRenderZ` via `propRenderer.render(prop, ..., selected = prop == inputHandler.selectedProp)`
   - If `showFrames`, draw a white wireframe cube on every node up to `maxRenderZ` except the hovered/selected ones (use a `ModelBuilder.line` cube created once).
   - Draw a **yellow** wireframe cube on the hovered node, **cyan** on the selected node.
   - Edge highlights: orange wireframe for `hoveredEdge`, cyan for `selectedEdge` — use a `drawEdge(x,y,z, slot)` helper that draws a 4-line rectangle on the corresponding face of the node cube.
   - **Green** wireframe rectangles for every `node.doorSlots` edge.
   - **Light-blue** arrows for every `StairsTile` showing facing direction (`drawStairsArrow`).
   - **Green** vertical "up" arrows on every `ladderSlots` edge (`drawLadderUpArrow`).
   - **Room tool drag preview**: if `inputHandler.isRoomDragging`, draw a rectangle at the current `hoveredZ` around the drag bounds (`minX-0.5..maxX+0.5`, `minY-0.5..maxY+0.5`). Color: `MAGENTA` for Addition, `RED` for Subtraction.
   - **Active-tool indicator**: if `toolMode != NONE` and a node is hovered, draw a 2px wireframe square slightly larger than the node (`0.55` half-size) — yellow for `FILL`, magenta for `ROOM`.
   - **Tag visuals**: a small white sphere + label rendered with a `SpriteBatch + BitmapFont` for: any node with `tags`, every per-edge `manualDoorSlots`, `socketSlots`, `ladderSlots`. Position labels via `camera.project(...)`.
   - A small crosshair in the viewport center.
4. `stage.act(delta); stage.draw()`.

**Scroll wheel handler**: install an `InputAdapter` before the `Stage` in an `InputMultiplexer`. If the mouse is over a `ScrollPane` in the stage, forward to it; otherwise translate `cameraTarget` along the camera's forward direction (zoom in/out).

**Window/UI styling**: use VisUI (`VisUI.load()`), `VisTable`, `VisLabel`, `VisTextButton`, `VisImageButton`, `VisSplitPane`, `VisScrollPane`, `Menu`, `MenuBar`, `MenuItem`.

### 7. File format

Save/load via the existing `WorldIO`. The `.wld` file is libGDX-flavored JSON containing world dimensions, an array of `nodes` (each with `x/y/z/tags/tiles/items/doorSlots/manualDoorSlots/socketSlots/ladderSlots`), `associations`, and `props`. Prop `modelPath` is stored **relative to the resource root**; convert absolute paths on save and back to absolute on load using the libGDX working directory.

## Style and conventions

- Idiomatic Kotlin: `data class`es, sealed classes, expression bodies, `apply { }`, `when` exhaustiveness.
- All UI is built in code (no `.skin` JSON beyond VisUI's default skin).
- Keep input handling, palette, status bar, and tool mode in `com.roguelike.editor`. The main screen `MapEditor` lives in `com.roguelike`.
- No third-party libraries beyond libGDX, VisUI, and the standard library.
- Cleanly `dispose()` everything in `dispose()`.

## Required icon assets

Place these PNG icons under `src/main/resources/icons/`:

```
cursor-default-outline.png
view-grid-outline.png
rotate-clockwise.png
rotate-counter-clockwise.png
format-color-fill.png
vector-square.png
rotate-orbit.png      (used by OrientationGizmo)
axis-arrow.png        (used by OrientationGizmo)
```

Icons may be any color — the toolbar inverts them at load time so they appear white on the dark background.

## Acceptance criteria

- Designer can paint and erase floors, walls, doors, stairs, ladders, tags, and props on a 3D grid.
- Floor flood-fill respects wall boundaries; Ctrl+click in Fill mode flood-erases floors.
- Room tool (Wall selected) drag-creates rectangular rooms; merges with adjacent rooms; Ctrl+drag carves and seals.
- World can be rotated CW/CCW, resized in steps of 3, and saved/loaded as `.wld`.
- Props can be added from arbitrary file-system locations, persist between sessions, can be selected (cyan tint), moved/rotated/scaled with keyboard.
- Camera orbits around the world (right-drag), pans (middle-drag), and zooms (wheel). Orientation gizmo resets the camera.
- Toolbar icons render in white on dark gray; the cursor button deselects any active tool mode.

