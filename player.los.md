# Prompt: Player Inventory Light Sources and LOS Lighting System

Act as an expert Kotlin / libGDX developer working in this existing roguelike project.

## Context

The project is a Kotlin desktop roguelike using libGDX.

Relevant existing files and concepts:

- `src/main/kotlin/com/roguelike/core/model/Actor.kt`
  - `Actor` already has `position`, `facingDirection`, and `inventory: MutableList<Item>`.
- `src/main/kotlin/com/roguelike/core/model/Item.kt`
  - Currently contains the base `Item` interface and `KeyItem`.
- `src/main/kotlin/com/roguelike/rendering/InventoryUI.kt`
  - Currently displays the player's inventory as rows with a color square and label.
- `src/main/kotlin/com/roguelike/rendering/ItemRenderer.kt`
  - Currently renders `KeyItem` world models.
- `src/main/kotlin/com/roguelike/RoguelikeGame.kt`
  - Currently creates a libGDX `Environment` with ambient light and a directional light. This must change: there should be no global/world light sources except light emitted from lit light-source items.
- World content is tile/grid based. Floors/walls/doors/props/items can be rendered as models.

## Goal

Implement an inventory-driven light source system.

Some inventory items are tagged as `light_source`. A `light_source` item can additionally be tagged as `light_source_lit`.

When an actor owns a lit light-source item in its inventory, the actor emits light according to that item's light behavior.

Clicking an item in the inventory should toggle/cycle that item between lit and unlit states when the item is a light source.

There must be no non-item light sources in the game world.

## Required item catalog file

Create a resource file that describes every item type in the game.

Use this file path unless there is a strong project convention for another location:

`src/main/resources/items/items.json`

The catalog must include at least these item definitions:

```json
[
  {
	"type": "Key",
	"name": "Key",
	"tags": [],
	"model": "models/tiles/obj/key.obj",
	"blocksLight": true
  },
  {
	"type": "Candle",
	"name": "Candle",
	"tags": ["light_source"],
	"unlitModel": "models/vox/items/candle.obj",
	"litModel": "models/vox/items/candle_lit.obj",
	"blocksLight": true,
	"light": {
	  "shape": "cone",
	  "attachTo": "owner",
	  "direction": "owner_facing",
	  "range": 8.0,
	  "coneDegrees": 90.0,
	  "color": "ffd27aff",
	  "intensity": 1.0
	}
  },
  {
	"type": "Torch",
	"name": "Torch",
	"tags": ["light_source"],
	"unlitModel": "models/vox/items/torch.obj",
	"litModel": "models/vox/items/torch_lit.obj",
	"blocksLight": true,
	"light": {
	  "shape": "sphere",
	  "attachTo": "owner",
	  "direction": "omnidirectional",
	  "range": 7.0,
	  "color": "ff9f45ff",
	  "intensity": 1.0
	}
  }
]
```

Notes:

- The user-facing model paths are:
  - Candle unlit: `vox/items/candle.obj`
  - Candle lit: `vox/items/candle_lit.obj`
  - Torch unlit: `vox/items/torch.obj`
  - Torch lit: `vox/items/torch_lit.obj`
- In code/resources, follow the existing model path convention used by the project. If other model paths are prefixed with `models/`, store them as `models/vox/items/...`.
- The catalog must be the single source of truth for item display name, tags, models, light behavior, and whether the model blocks light.

## Item model changes

Extend the item model in a clean, data-driven way.

Requirements:

1. `Item` instances must support:
   - `id: String`
   - `type: String`
   - `name: String`
   - `colorHex: String` or another existing UI color field if already used
   - `tags: MutableSet<String>` or equivalent
   - access to item definition/catalog data
2. Add constants or a central object for item tags:
   - `light_source`
   - `light_source_lit`
3. Add utility functions such as:
   - `isLightSource()`
   - `isLitLightSource()`
   - `toggleLit()` / `setLit(Boolean)`
4. Clicking an inventory row for a non-light-source item should do nothing or preserve existing behavior.
5. Clicking an inventory row for a light-source item should cycle:
   - unlit (`light_source`) -> lit (`light_source` + `light_source_lit`)
   - lit (`light_source` + `light_source_lit`) -> unlit (`light_source`)

## Required items

Implement these game items:

### Key

- Existing item type.
- Should be included in the item catalog.
- Model: keep current key model path unless project conventions require moving it into the catalog.
- `blocksLight: true`
- Not a light source.

### Candle

- Type: `Candle`
- Tags when unlit: `light_source`
- Tags when lit: `light_source`, `light_source_lit`
- Unlit model: `models/vox/items/candle.obj`
- Lit model: `models/vox/items/candle_lit.obj`
- `blocksLight: true`
- When lit and in the player's inventory, the player emits a wide light cone in the direction of `player.facingDirection`.
- The cone should update every frame as the player moves or changes facing direction.

### Torch

- Type: `Torch`
- Tags when unlit: `light_source`
- Tags when lit: `light_source`, `light_source_lit`
- Unlit model: `models/vox/items/torch.obj`
- Lit model: `models/vox/items/torch_lit.obj`
- `blocksLight: true`
- When lit and in the player's inventory, the player emits a wide spherical light around the player.
- The torch light is independent of the player's facing direction.

## Light source behavior

Implement item-driven lights with these rules:

1. Only lit light-source items emit light.
2. If a lit light-source item is in an actor's inventory, the actor emits that light from the actor position.
3. If multiple lit light-source items are in the same inventory, all applicable lights may contribute unless that causes rendering or performance problems. If choosing one light instead, document and implement deterministic priority.
4. There should be no ambient light, directional light, or other default world light in gameplay.
5. The editor preview/palette may keep its own preview lighting if needed, but the runtime game world must only be lit by lit items.
6. Light must be blocked/occluded by models or grid geometry marked as `blocksLight`.
7. All item models must block light, including the key, candle, and torch models.

## LOS / light blocking requirements

Implement a practical line-of-sight/light-visibility layer suitable for the current grid world.

The implementation may be approximate if full mesh shadowing is too expensive, but it must be consistent and testable.

Minimum required behavior:

1. Walls and doors block light.
2. Any tile, prop, or item definition with `blocksLight: true` blocks light.
3. Candle cone lighting only affects nodes/cells inside:
   - the configured range
   - the configured cone angle
   - an unobstructed LOS path from the owner/player to the target cell
4. Torch spherical lighting only affects nodes/cells inside:
   - the configured range
   - an unobstructed LOS path from the owner/player to the target cell
5. Use stable grid math. Suggested approach:
   - Convert actor position to the current grid cell.
   - Iterate candidate cells within light range.
   - For each candidate cell, run a 2D/3D grid raycast/Bresenham/DDA line from source cell to candidate cell.
   - Reject cells if the ray crosses a blocking wall/door/tile/prop/item.
   - For cone lights, also reject cells whose normalized direction vector is outside the cone by dot product comparison with actor `facingDirection`.
6. Ensure bounds checks for world edges and different Z-levels.

## Rendering requirements

Use the existing libGDX rendering architecture where possible.

Implement one of these approaches, choosing the one that fits the current renderer best:

### Preferred: calculated visible-light overlay / tinting

- Maintain a per-frame or cached light map of visible/illuminated cells.
- Render unlit cells dark or black.
- Render lit cells/items/actors normally, with intensity falloff based on distance.
- This approach makes LOS blocking deterministic and does not depend on expensive real-time shadow maps.

### Alternative: libGDX dynamic lights

- Add `PointLight` for torch and `SpotLight` or cone-like approximation for candle.
- Remove runtime ambient/directional lights.
- Still add grid LOS masking/occlusion if libGDX lights do not block through walls/models automatically.

Whichever approach is used, the final runtime result must satisfy:

- No light appears without a lit light-source item.
- Unlit candle/torch models do not emit light.
- Lit candle emits a forward cone from the player.
- Lit torch emits a sphere around the player.
- Walls/doors/blocking objects prevent light from passing through.

## Inventory UI requirements

Update `InventoryUI` so that:

1. Inventory rows are clickable.
2. Clicking a light-source item toggles `light_source_lit`.
3. Lit state is visible in the UI, for example:
   - append `" (lit)"` or `" (unlit)"` to the label, or
   - change icon color/border.
4. The UI update must not create duplicate click listeners every frame in a way that causes repeated toggles or memory leaks. Prefer rebuilding rows safely or using stable widgets/callbacks.
5. The UI should expose a callback such as `onItemClicked: (Item) -> Unit` or handle toggling through an injected function, keeping core item logic out of rendering if possible.

## World item rendering requirements

Update item rendering to use catalog models:

1. Existing `KeyItem` must continue rendering.
2. Candle and torch must render the unlit or lit model depending on `light_source_lit`.
3. Models should be cached by model path/type/state to avoid loading every frame.
4. If world pickups exist for candle/torch, their rendered model should match their current lit state.

## Serialization / save-load requirements

If items are serialized anywhere, update the serialization so item tags and lit state persist.

Minimum requirement:

- An inventory item lit before save should still be lit after load.
- An unlit light source should remain unlit after load.
- Item catalog definitions should not be duplicated into save files; save only item instance state such as id/type/tags if possible.

## No other light sources

Remove or disable gameplay-world default lights.

Specifically inspect `RoguelikeGame.kt` for:

```kotlin
environment.set(ColorAttribute(ColorAttribute.AmbientLight, ...))
environment.add(DirectionalLight().set(...))
```

The runtime gameplay environment must not use ambient or directional lights as world lighting. Replace this with item-driven lighting only.

If some minimum shader visibility is technically required to render black/unlit geometry, keep it isolated and ensure visually it does not act as world illumination. Document the decision in code comments.

## Tests / verification

Add tests where practical, especially for pure grid/light math.

At minimum verify:

1. Light-source tag toggling:
   - Candle starts unlit with `light_source` only.
   - Clicking/toggling adds `light_source_lit`.
   - Clicking/toggling again removes `light_source_lit`.
2. Candle cone:
   - A cell in front of the player and within range is lit.
   - A cell behind the player is not lit.
   - A cell outside cone angle is not lit.
3. Torch sphere:
   - A cell within range is lit regardless of facing.
   - A cell outside range is not lit.
4. Occlusion:
   - A wall/door/blocking object between source and target prevents light.
5. No default world light:
   - With no lit inventory item, no cells are illuminated except any explicitly allowed self/player debug rendering.

Run existing tests/build after implementation.

## Acceptance criteria

The implementation is complete when:

- `src/main/resources/items/items.json` exists and describes all game items: Key, Candle, Torch.
- Candle and torch item classes/instances exist and can be placed in the player's inventory.
- Inventory clicking toggles candle/torch lit state.
- Lit candle uses `models/vox/items/candle_lit.obj`; unlit candle uses `models/vox/items/candle.obj`.
- Lit torch uses `models/vox/items/torch_lit.obj`; unlit torch uses `models/vox/items/torch.obj`.
- Lit candle makes the player emit a wide cone in `player.facingDirection`.
- Lit torch makes the player emit an omnidirectional sphere.
- All item models block light.
- Walls/doors/objects with `blocksLight` block item light.
- Runtime world has no ambient/directional/default lights; only lit inventory items emit light.
- Existing key item behavior continues to work.
- Project compiles and relevant tests pass.

