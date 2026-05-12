**Context:**
I am building a 3D map editor in Java using libGDX. The editor operates on a 3D grid of nodes. Tiles (like floors) occupy a node, while Walls occupy the specific edges (North, South, East, West) of a node.

**Task:**
I need to implement a complex "Room Tool" that supports boolean-style operations (Addition and Subtraction) on the grid to create complex room shapes.

**Feature: Advanced Room Tool**
* **UI:** Add a toggle button with the icon `vector-square.png` to the left UI column.
* **State:** When active, the editor enters "Room Mode".
* **Base Interaction:** The user left-clicks and drags to define a rectangular selection of nodes on the current Z-level. Execution happens upon releasing the mouse button (`touchUp`).

**Mode 1: Addition / Merging (Standard Drag)**
* **Goal:** Create a new room, or merge the new rectangle into an existing room if they intersect or touch.
* **Logic:** 1. Define the bounds of the drawn rectangle (`minX`, `maxX`, `minY`, `maxY`).
  2. **Clear Interiors:** Iterate through all nodes *inside* this rectangle. Delete any existing walls on these internal nodes to hollow out the space.
  3. **Draw Perimeter:** Iterate along the perimeter of the new rectangle. Add outward-facing walls to these perimeter edges **UNLESS** the adjacent node outside the rectangle is already part of an existing room interior. (e.g., If the North edge of the new rectangle touches the interior of an existing room, do not place a North wall there; let the spaces merge).

**Mode 2: Subtraction / Carving (Ctrl + Drag)**
* **Goal:** Remove space from an existing room, effectively cutting a chunk out of it and sealing the exposed edges.
* **Interaction:** The user holds the `Ctrl` key while dragging and releasing.
* **Logic:**
  1. Define the bounds of the drawn rectangle.
  2. **Clear Everything:** Delete **all** walls (both interior and perimeter) that fall inside the bounds of this subtracted rectangle.
  3. **Seal the Cut:** Iterate along the perimeter of the subtracted rectangle. Look at the nodes immediately *outside* this perimeter. If an outside node is part of an existing room (e.g., it has a floor tile or is enclosed), you must add a wall facing *inward* toward the subtracted space to seal the breach.

**Requirements:**
Please provide the InputProcessor logic to handle the `Ctrl` modifier state during the drag-and-drop. Then, provide the grid-math logic to handle the perimeter vs. interior wall detection for both the Add and Subtract operations. Ensure edge cases (like drawing entirely inside an existing room, or barely clipping the corner of a room) are handled gracefully.