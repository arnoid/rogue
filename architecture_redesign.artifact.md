# Roguelike Architecture Redesign Plan

This document outlines the proposed architectural changes to the Roguelike project to adhere to SOLID principles and improve testability and maintainability.

## Current Weaknesses
- **Tight Coupling**: Core classes like `Tile`, `Actor`, and `World` depend directly on LibGDX graphics classes (`ModelInstance`, `Color`).
- **SRP Violations**: `RoguelikeGame` and `MapEditor` handle input, rendering, and logic simultaneously.
- **OCP Violations**: Adding new game elements often requires modifying central loaders or renderers.
- **Lack of Testability**: Business logic is entangled with the graphics pipeline.

## Proposed Architecture

### 1. Separation of Concerns (SRP)
We will split the project into three distinct layers:
- **Core (Logic)**: Pure Kotlin/Java. Contains the world state, entity rules, and interaction logic. No LibGDX dependencies.
- **Systems (Infrastructure)**: Bridges Core and LibGDX. Handles input mapping, asset loading, and world serialization.
- **Rendering (View)**: Translates the world state into pixels. Contains all `ModelBatch` and `ModelInstance` logic.

### 2. Entity-Component Pattern (DIP/ISP)
Replace inheritance-based actors with a composition-based system:
- **Component**: Pure data (e.g., `Position`, `Health`, `Inventory`).
- **Entity**: A collection of Components.
- **System**: Logic that operates on Entities with specific Components (e.g., `CollisionSystem`, `InteractionSystem`).

### 3. Decoupled Tile System (OCP)
- `Tile` will be a pure data class or enum.
- `TileRenderer` will map `Tile` types to `Model` assets using a `TileRegistry`.
- Logic like "Door Opening" will be handled by a `TileBehavior` or a specialized system.

### 4. Command Pattern for Input & Editor
- Translate user input (keys/mouse) into `Action` objects (e.g., `MoveAction`, `InteractAction`, `PlaceTileAction`).
- This allows for easy Undo/Redo in the editor and replay/network synchronization in the game.

### 5. Modular UI (MVP Pattern)
- Break `MapEditor` and `RoguelikeGame` into smaller UI components (e.g., `PaletteView`, `StatusBarView`).
- Use `Presenter` classes to handle logic between the World state and the UI.

## Detailed Package Structure
```
com.roguelike
├── core
│   ├── model       // World, Node, Tile, Item (Pure Logic)
│   ├── components  // Entity components
│   └── systems     // Logic systems (Interaction, Movement)
├── infra
│   ├── input       // Input mapping
│   ├── persistence // Serialization (WorldIO)
│   └── assets      // Asset management
└── view
    ├── renderers   // WorldRenderer, EntityRenderer
    └── ui          // Scene2D UI components
```

## Migration Steps
1. **Abstract Math**: Create or use a lightweight `Vector3` abstraction to remove `com.badlogic.gdx.math.Vector3` from the core.
2. **Pure Data Tiles**: Refactor `Tile` and `BaseTile` to remove `ModelInstance`.
3. **Core World**: Move `World` and `WorldNode` to a `core` package and remove LibGDX imports.
4. **Logic Systems**: Refactor `InteractionSystem` and `MovementSystem` to operate on core data only.
5. **Render Bridge**: Create a `RenderMapper` that maintains the link between core Entities/Tiles and LibGDX `ModelInstance` objects.
6. **Editor Decomposition**: Extract UI panels from `MapEditor.kt` into separate classes.
