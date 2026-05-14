# World Editor — Tools Palette Specification

> **Location**: Right edge of the editor window, full height below the menu bar.
> **Source**: `MapEditor.kt` → `renderToolsPalette()`

## Layout

| Property          | Value |
|-------------------|-------|
| Right edge        | Flush with right edge of window |
| Width             | `toolsPaletteWidth` (default **200 px**, user-resizable) |
| Min width         | 120 px (`toolsPaletteMinWidth`) |
| Max width         | 500 px (`toolsPaletteMaxWidth`) |
| Top edge          | Below menu bar (24 px) |
| Bottom edge       | Window bottom |
| Background        | RGBA(0.12, 0.13, 0.17, 0.95) |
| Left border       | 1 px, RGBA(0.3, 0.35, 0.45, 0.7) |

## Draggable Resize Handle

A vertical splitter handle sits immediately left of the tools palette.

| Property    | Value |
|-------------|-------|
| Width       | 6 px (`toolsPaletteHandleWidth`) |
| Position    | `toolsPaletteLeft − 6` to `toolsPaletteLeft` |
| Normal colour  | RGBA(0.25, 0.30, 0.40, 0.8) |
| Hover/drag colour | RGBA(0.50, 0.55, 0.65, 0.8) |

### Drag Behaviour

1. Mouse-down on the handle sets `draggingToolsPaletteHandle = true`.
2. While dragging, `toolsPaletteWidth = (screenWidth − mouseX)`, clamped to `[120, 500]`.
3. Mouse-up ends the drag.
4. During drag, all viewport inputs are blocked (`uiBlocking = true`).

## Content Sections

### Header

| Position | Content |
|----------|---------|
| (palX + 8, barH + 8) | "TOOLS PALETTE" — scale 1.3, RGBA(0.7, 0.75, 0.85, 1.0) |

### Tile Tool Buttons

A vertical list of clickable buttons for selecting the active tile placement tool.
Each button stretches to `toolsPaletteWidth − 16 px` wide, **24 px** tall, with **4 px** spacing.

| # | Label     | Tool Enum   | Keyboard shortcut |
|---|-----------|-------------|-------------------|
| 1 | Floor     | `FLOOR`     | 1 |
| 2 | Wall N    | `WALL_N`    | 2 |
| 3 | Wall S    | `WALL_S`    | 3 |
| 4 | Wall E    | `WALL_E`    | 4 |
| 5 | Wall W    | `WALL_W`    | 5 |
| 6 | Erase     | `ERASE`     | 6 |
| 7 | Light     | `LIGHT`     | 7 |

#### Visual States

| State    | Background RGBA              |
|----------|------------------------------|
| Normal   | (0.15, 0.17, 0.22, 0.60)    |
| Hovered  | (0.20, 0.26, 0.38, 0.70)    |
| Selected | (0.30, 0.42, 0.65, 0.90)    |

Text: RGBA(0.82, 0.82, 0.90, 1.0), scale 1.1, left-padded 6 px from button left.

Clicking a button sets `currentTool` to the corresponding `EditorTool` enum value.

### Layer Info

Below the tile buttons (after 12 px gap):

| Line | Content | Style |
|------|---------|-------|
| 1    | `Layer: <currentZ> / <depth−1>` | RGBA(0.6, 0.65, 0.75, 1.0), scale 1.1 |
| 2    | `Z/X to change layer`           | RGBA(0.45, 0.5, 0.6, 0.7), scale 1.0 |

## Input Blocking

When the mouse cursor is over the tools palette area or its drag handle
(`mx ≥ toolsPaletteLeft − toolsPaletteHandleWidth && my > barH`),
all viewport inputs are suppressed via the `uiBlocking` flag.

## State Variables

| Variable                    | Type    | Default | Description |
|-----------------------------|---------|---------|-------------|
| `toolsPaletteWidth`         | Float   | 200     | Current width in pixels. |
| `draggingToolsPaletteHandle`| Boolean | false   | True while the user is dragging the resize handle. |
| `currentTool`               | EditorTool | FLOOR | Active tile placement tool. |


