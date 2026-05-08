# Roguelike Architecture Redesign Plan

This document outlines the proposed architectural changes to the Roguelike project to adhere to SOLID principles and improve testability and maintainability.

## ✅ Completed Migration Steps

### Step 1: Abstract Math (DONE — prior)
- `com.roguelike.core.math.Vec3` — lightweight pure Kotlin vector, no LibGDX dependency.
- `com.roguelike.utils.Vec3GdxBridge` — extension functions converting between core `Vec3` and LibGDX `Vector3`.

### Step 2: Pure Data Tiles (DONE)
- **`world/Tiles.kt`** — `BaseTile` and all subclasses (`FloorTile`, `WallTile`, `DoorTile`, etc.) are now pure data classes with zero LibGDX imports. Removed `Model`, `ModelInstance`, `Color`, and `applyColor()`.
- Duplicate `Tile` interface removed from `world` package; all tiles implement `core.model.Tile` directly.
- `CoreAliases.kt` — removed the redundant `Tile` typealias.

### Step 3: Core World (DONE — prior)
- `core.model.World`, `WorldNode`, `Actor`, `Player`, `Item`, `KeyItem` — all LibGDX-free.

### Step 4: Logic Systems (DONE)
- **`core.systems.InteractionSystem`** — moved from `com.roguelike.systems` to `com.roguelike.core.systems`. Replaced `Gdx.app.log` with injectable `GameLogger` interface. Zero LibGDX imports.
- **`core.systems.MovementSystem`** — already pure (no LibGDX). Duplicate in `com.roguelike.systems` replaced with a backward-compatibility typealias.
- **`core.model.GameLogger`** — new `fun interface` for logging, with `NOOP` companion for tests.

### Step 5: Render Bridge (DONE)
- **`rendering/TileRenderRegistry`** — maps tile instances → `TileRenderData(model, scale, center, altModel?)`.
- **`rendering/TileRenderer`** — now takes `TileRenderRegistry` as constructor parameter; looks up `Model`, `scale`, and `center` from the registry instead of from tile fields.
- **`utils/ModelLoader`** — now takes optional `TileRenderRegistry`; registers render data for each tile it creates. Pure-data tiles are returned.

### Step 6: Editor Decomposition (DONE)
- **`editor/EditorPalettePanel`** — extracted tile/item/tag palette UI, `TilePreviewActor`, `SelectionBorderGroup`, and `PaletteSelection` sealed class.
- **`editor/EditorStatusBar`** — extracted bottom bar with X/Y/Z dimension and layer controls.
- **`editor/EditorInputHandler`** — extracted all paint/erase/select/association input handling.
- `MapEditor` remains the orchestrator but the building blocks are modular and independently testable.

## Architecture Summary

```
com.roguelike
├── core                          # Pure Kotlin — NO LibGDX
│   ├── math/Vec3                 # Lightweight 3D vector
│   ├── model/                    # World, WorldNode, Actor, Player, Tile, Item, GameLogger
│   └── systems/                  # MovementSystem, InteractionSystem
├── world                         # Concrete tile data classes (BaseTile, FloorTile, DoorTile, …)
│   ├── Tiles.kt                  # Pure data — implements core.model.Tile
│   ├── CoreAliases.kt            # Backward-compat typealiases (to be removed)
│   └── WorldGenerator.kt
├── rendering                     # LibGDX view layer
│   ├── TileRenderRegistry.kt    # Maps tiles → Model/scale/center
│   ├── TileRenderer.kt          # Creates & caches ModelInstances via registry
│   ├── WorldRenderer.kt
│   ├── ItemRenderer.kt
│   ├── InventoryUI.kt
│   └── OrientationGizmo.kt
├── editor                        # Decomposed editor components
│   ├── EditorPalettePanel.kt
│   ├── EditorStatusBar.kt
│   └── EditorInputHandler.kt
├── systems                       # LibGDX infrastructure (input, camera)
│   ├── InputHandler.kt
│   ├── CameraManager.kt
│   ├── MovementSystem.kt         # typealias → core.systems
│   └── InteractionSystem.kt      # typealias → core.systems
├── serialization/                # WorldIO, WorldData
├── utils/                        # AssetLoader, ModelLoader, Vec3GdxBridge
├── MapEditor.kt                  # Orchestrator screen
├── RoguelikeGame.kt             # Game screen
├── MainMenuScreen.kt
├── RoguelikeLauncher.kt
└── Main.kt
```
