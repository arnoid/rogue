# World Editor — Editor Modes Specification

> **Location**: Left edge of the editor window, below the menu bar.
> **Source**: `MapEditor.kt` → `renderEditorModes()`, `drawModeButton()`, `drawCursorIcon()`, `drawGridIcon()`

## Layout

| Property        | Value   |
|-----------------|---------|
| Width           | 40 px (fixed, `editorModesWidth`) |
| Top edge        | Below the menu bar (`menuBar.barHeight`, 24 px) |
| Bottom edge     | Window bottom |
| Background      | RGBA(0.12, 0.13, 0.17, 0.95) |
| Right border    | 1 px, RGBA(0.3, 0.35, 0.45, 0.7) |

## Buttons

Each button is a **32×32 px** square with **4 px** padding between buttons,
starting 4 px from the top of the column.

### Selection Group

Buttons form a **mutually exclusive selection group** — only one button can be
selected at any time. Tracked by the `selectedEditorMode` enum (`EditorMode`).

Clicking a button selects it (and deselects the previously selected one).
Some buttons also perform a toggle side-effect (e.g. grid visibility).

### Visual States

| State    | Background RGBA              |
|----------|------------------------------|
| Normal   | (0.16, 0.18, 0.24, 0.85)    |
| Hovered  | (0.22, 0.28, 0.40, 0.80)    |
| Selected | (0.30, 0.45, 0.70, 0.90)    |

All buttons have a 1 px border; selected buttons use a brighter border
(base 0.6) while unselected use base 0.3.

### Current Buttons (top-to-bottom)

| # | Icon | Icon file reference           | Enum value      | Behaviour |
|---|------|-------------------------------|-----------------|-----------|
| 1 | Cursor arrow (procedural)     | `cursor-default-outline.png`  | `NORMAL`        | Pointer / no-op mode. Selecting this deselects other modes. |
| 2 | 3×3 grid (procedural)         | `view-grid-outline.png`       | `GRID_TOGGLE`   | Toggles `showWireframes`. When wireframes are off, a red diagonal strike-through is drawn over the grid icon. |
| 3 | Lightbulb (procedural)        | `lightbulb-on-outline.png`    | `LIGHTS`        | Toggles `lightPreviewEnabled`. When enabled, light sources project dynamic light with volumetric shadows. Light rays radiate from the icon. |

### Procedural Icons

Icons are drawn programmatically using `DebugRenderer.drawLine()` and `SimpleUI.drawQuad()`:

- **Cursor icon** (`drawCursorIcon`): A triangular arrow pointer with a short stem line.
- **Grid icon** (`drawGridIcon`): A 3×3 grid of lines (outer border + 2 horizontal + 2 vertical inner lines). When `showWireframes` is false, the grid is drawn dimmer and a red diagonal strike-through line is overlaid.

> **Note**: The icon PNG files exist in `src/main/resources/icons/` but are not
> used for rendering because `SimpleUI` does not support textured icon drawing.
> The procedural icons visually match the referenced icon designs.

## Input Blocking

When the mouse cursor is inside the editor modes area (`mx < editorModesWidth && my > barH`),
all viewport inputs (camera controls, tile placement, cursor ray-cast) are suppressed via the `uiBlocking` flag.

## State Variables

| Variable             | Type       | Default  | Description |
|----------------------|------------|----------|-------------|
| `selectedEditorMode` | EditorMode | `NORMAL` | Currently selected mode in the editor modes panel (selection group). |
| `showWireframes`     | Boolean    | `true`   | Controls debug wireframe rendering for empty tiles across all layers. |
| `lightPreviewEnabled`| Boolean    | `false`  | When true, light sources project dynamic volumetric lighting with shadows. |


