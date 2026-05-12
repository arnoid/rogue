**Context:**
I am building a 3D map editor and world generator in Java using libGDX. The current system uses presets based on a 3D grid (dimensions divisible by 3, smallest is 3x3x3). Currently, I can edit walls, floors, ladders, stairs, and doors which snap to these grid nodes. I can save and load these presets.

**Task:**
I need to implement a new "Props" system to allow the placement of furniture, lighting, and decorations into the map presets. These entities differ from structural elements because they feature free-placement (not snapped to the grid).

**Feature Specifications:**

**1. UI & Palette**
* Add a new "Decorations" tab to the existing right-side palette.
* Add a "Decorations" button to the top menu bar. Clicking this opens a native file selector dialog.
* When a 3D model is selected via the file dialog, add it to the "Decorations" palette list.
* Right-clicking an item in the Decorations palette should open a context menu with a "Delete" option to remove it from the list.
* **Persistence:** The list of available decoration models in the palette must be saved to a local configuration file (e.g., JSON) so it persists between application restarts.

**2. Placement & Interaction Logic**
* **Mode Toggle:** When the "Decorations" tab is active, the editor enters "Prop Mode". Only decoration models can be selected or interacted with on the map.
* **Spawning:** Selecting a model from the palette allows the user to click anywhere on the *current Z-level* of the map to place it. It must use free placement based on the mouse coordinate projected into 3D space, completely ignoring grid/node snapping.
* **Selection:** Left-clicking an existing decoration on the current Z-level selects it.
* **Movement:** * Dragging a selected decoration with the Left Mouse Button moves it freely around the Z-level.
    * When a decoration is selected, pressing `W`, `A`, `S`, `D` performs precise, micro-movements along the X and Y axes.
* **Deletion:** Ctrl + Left Click on a placed decoration deletes it from the map.

**3. Gameplay & Data Logic**
* **Collision:** Props must generate collision bounds (e.g., bounding boxes) that block actor movement in the game world.
  * Make props acting as boxes with collision bounds that prevent actors from moving through them. This may involve integrating with the existing collision system or creating a new one specific to props.
* **Serialization:** The placed props (their model ID/path, exact X/Y/Z coordinates, and rotation) must be saved into the map preset file alongside the existing grid node data, and reconstructed properly when the preset is loaded.

Please provide the architecture, required libGDX classes (e.g., InputProcessor handling, Raycasting for placement), and the code to implement these features.