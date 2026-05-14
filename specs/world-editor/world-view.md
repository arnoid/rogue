# World Editor — World View Specification

> **Location**: Central area between the editor modes (left) and tools palette (right), below the menu bar.
> **Source**: `MapEditor.kt` → `renderGrid()`, `drawCubeOutline()`, `drawGimbalCube()`, `updateOrbitalCamera()`, `updateCursorFromMouse()`

## Layout

| Property     | Value |
|--------------|-------|
| Left edge    | `editorModesWidth` (40 px) |
| Right edge   | `screenWidth − toolsPaletteWidth` (default 200 px from right) |
| Top edge     | `menuBar.barHeight` (24 px) |
| Bottom edge  | Window bottom |

The viewport fills the remaining space and adapts when the tools palette is resized.

## Orbital Camera

The 3D world is viewed through a perspective camera orbiting a focal point.

| Parameter      | Variable        | Default | Range        | Description |
|----------------|-----------------|---------|--------------|-------------|
| Azimuth        | `azimuth`       | 0°      | unbounded    | Rotation around Z axis (degrees). |
| Elevation      | `elevation`     | 60°     | 5° – 89°    | Angle above the XY plane. |
| Distance       | `distance`      | auto    | 3 – 200      | Distance from orbit centre (dolly). |
| Orbit centre X | `orbitCenterX`  | w/2     | unbounded    | Focus point X. |
| Orbit centre Y | `orbitCenterY`  | h/2     | unbounded    | Focus point Y. |
| Orbit centre Z | `orbitCenterZ`  | 0       | unbounded    | Focus point Z. |

Camera position is computed from spherical coordinates in `updateOrbitalCamera()`.
The camera's up vector is always `(0, 0, 1)`.

### Controls

| Key / Input     | Action |
|-----------------|--------|
| **A / D**       | Rotate azimuth (90°/sec) |
| **W / S**       | Increase / decrease elevation / pitch (90°/sec) |
| **Q / E**       | Dolly closer / further (15 units/sec) |
| **Shift+A / D** | Strafe orbit centre left/right along camera-local right vector (10 units/sec) |
| **Shift+W / S** | Move orbit centre forward/backward along camera direction projected onto the XY plane (10 units/sec) |
| **Scroll wheel**| Zoom (±2 units per tick) |
| **Z / X**       | Decrease / increase current Z layer |
| **1–6**         | Select tile tool (Floor, Wall N/S/E/W, Erase) |
| **Ctrl+S**      | Save (delegates to File > Save) |

All keyboard/mouse viewport controls are suppressed when `uiBlocking` is true
(mouse over menu bar, editor modes, tools palette, or tools palette drag handle).

## Rendering

### Cube Rendering

Tiles are rendered as unit cubes (1×1×1 world units) using the painter's algorithm:

1. All tiles from layer Z=0 to Z=`currentZ` are collected.
2. Sorted by squared distance to camera (farthest first).
3. Each cube's visible faces are drawn as `SimpleUI.drawQuad()` calls with back-face culling.

#### Back-face Culling

Per-cube: the view direction vector is computed from the cube centre to the camera position.
A face is visible when `dot(faceNormal, viewDir) ≥ 0`.

#### Face Shading

| Face       | Normal   | Shade multiplier |
|------------|----------|-----------------|
| Top (Z+)   | (0,0,1)  | 1.00 |
| Bottom (Z−)| (0,0,−1) | 0.35 |
| North (Y+) | (0,1,0)  | 0.70 |
| South (Y−) | (0,−1,0) | 0.55 |
| East (X+)  | (1,0,0)  | 0.60 |
| West (X−)  | (−1,0,0) | 0.50 |

#### Tile Colours

| Content | Base RGB |
|---------|----------|
| Floor   | (0.25, 0.30, 0.40) |
| Wall    | (0.55, 0.42, 0.30) |

#### Layer Dimming

| Layer            | Dimming factor | Edge alpha |
|------------------|---------------|------------|
| Active (`currentZ`) | 1.0        | 0.7        |
| Lower layers     | 0.45          | 0.7        |

### Wireframe Debug Cubes

Empty tiles (no floor or walls) are rendered as translucent wireframe cubes
when `showWireframes` is `true` (toggled by the **G** button in the editor modes).

| Layer        | Wire colour multiplier | Wire alpha |
|--------------|----------------------|------------|
| Active       | 1.0 × (0.2, 0.25, 0.3) | 0.25   |
| Lower layers | 0.45 × (0.2, 0.25, 0.3) | 0.12  |

### Cursor Highlight

The tile under the mouse cursor at `(cursorX, cursorY, currentZ)` is highlighted
with a bright yellow wireframe cube (RGB 1,1,0 / alpha 0.9 / thickness 2 px).
Cursor position is computed by ray-casting the mouse through the camera into the
Z=`currentZ` plane.

### Cube Edge Outlines

Solid cubes have dark edge outlines drawn only on edges adjacent to at least one
camera-facing face (prevents stray floating lines on back edges).

### Gimbal Orientation Cube

A small wireframe cube is drawn in the top-right area of the viewport (left of the tools palette)
showing the current viewing orientation:

- Position: `(screenWidth − toolsPaletteWidth − 60, 60)` (centre)
- Size: 40 px
- Rotates with azimuth and elevation
- Non-axis edges are drawn in a dim colour: RGBA(0.4, 0.45, 0.6, 0.6)
- **Axis edges** originate from the corner vertex at `(−0.5, −0.5, −0.5)` and
  run along the three edges of the cube:
  - Edge 0→1 (+X direction) = **red** (1, 0.3, 0.3)
  - Edge 0→3 (+Y direction) = **green** (0.3, 1, 0.3)
  - Edge 0→4 (+Z direction) = **blue** (0.3, 0.5, 1)
- Text labels "X", "Y", "Z" are placed at the end of each coloured edge

### HUD Overlay

| Position | Content |
|----------|---------|
| Top-left of viewport | `Tool: <name>  Layer: <z>` |
| Top-right of viewport | `<filename>  Cursor: x, y  WxHxD  Az: ° El: ° Dist:` |
| Bottom-left of viewport | Keyboard shortcut help text |

## Projection

`proj(x, y, z)` → always returns a screen-space `Vector3f` (no distance culling).
The `SimpleUI.drawQuad()` handles arbitrary winding via automatic diagonal-split detection.

## Light Sources

### Placement

When the **Light** tool is selected in the tools palette (key 7), left-clicking on a tile
places a `LightSource` at `(cursorX + 0.5, cursorY + 0.5, currentZ + 0.8)` with
default intensity 5.0 and warm colour `ffcc88`. Uses single-click placement
(`isMouseButtonJustPressed`) to avoid duplicates.

### Visual Representation

Light sources are **always** rendered (regardless of light preview toggle):

| Element | Description |
|---------|-------------|
| Filled sphere | Semitransparent billboard circle, radius 0.15 world units, alpha 0.4, coloured by `LightSource.colorHex` |
| Wireframe sphere | 3-ring wireframe (XY, XZ, YZ planes), radius 0.15, alpha 0.8 |
| Radius indicator | Shown only when `lightPreviewEnabled` — larger wireframe sphere at `intensity × 0.5` radius, dim, alpha 0.15 |

### Dynamic Lighting (Light Preview Mode)

When `lightPreviewEnabled` is `true` (toggled by the lightbulb editor modes button):

1. Each cube face's colour is modulated by accumulated light contributions.
2. **Ambient term**: 0.15 (minimum illumination).
3. **Per-light contribution**: Lambertian diffuse (`max(0, N·L)`) with distance attenuation
   `intensity / (1 + dist² × 0.1)` and light colour.
4. **Volumetric shadow occlusion**: A ray is marched from the face centre to the light source
   using 3D DDA (2 samples per world unit, max 50 steps). If any occupied cube is hit,
   the light is fully blocked for that face. Occupied = has any tile (floor or wall).
5. Final face colour: `baseColour × clamp(sumOfLightContributions, 0, 1)`.
