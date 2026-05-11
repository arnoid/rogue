Act as an expert Kotlin developer and game engine architect. I need you to write the core logic for a Socket-Based 3D Procedural Map Generator.

### 1. Core Architecture & Constraints
*   **The Grid:** The map is generated on a uniform 3D voxel grid.
*   **The Base Unit:** The fundamental unit of spatial measurement is a 3x3x3 block.
*   **Dimensions:** Submap dimensions are arbitrary but strictly divisible by 3 (e.g., 3x3x3, 6x3x9, 12x12x6). Internally, store their footprint in "Base Units" (e.g., a 9x6x3 room has a grid footprint of 3x2x1).
*   **Spatial Dictionary:** Use a `GridMap` (e.g., a HashMap with `Vector3Int` keys) to track which Base Unit coordinates are currently occupied to prevent overlapping.

### 2. Data Models Required (Use Kotlin Data Classes)
*   `Vector3Int`: A simple 3D integer vector class for grid coordinates and direction normals.
*   `Socket`: Represents a connection point.
    *   `localPosition`: `Vector3Int` (relative to the submap's origin).
    *   `direction`: `Vector3Int` (the outward-facing normal: Up, Down, North, South, East, West).
    *   `tag`: `String` (e.g., "dungeon_hall" - must match exactly to connect).
    *   `state`: Enum (`OPEN`, `CONNECTED`, `SEALED`).
*   `SubmapTemplate`: Represents the loaded map data. Contains its dimensional footprint (`Vector3Int`) and a list of `Socket` objects.
*   **Multi-Socket Rule:** Submaps do not have one giant socket on large faces. A 9x9 face must contain exactly nine distinct 3x3 sockets.

### 3. The Generation Algorithm
Write a class `MapGenerator` with an asynchronous generation loop (using Kotlin Coroutines) that starts from an initial predefined submap at (0,0,0) and follows these steps:
1.  **Find Open Sockets:** Scan the current active structure for any sockets in the `OPEN` state.
2.  **Filter & Match:** For a given open socket, search a provided `List<SubmapTemplate>` for candidates that have a socket with the *exact opposite direction* and a *matching tag*.
3.  **Collision Check:** Before placing a candidate, calculate the absolute grid coordinates its footprint would occupy based on the socket alignment. Query the `GridMap`. If *any* required Base Unit is already occupied, reject the candidate.
4.  **Connect:** If it fits, register all its occupied coordinates in the `GridMap`, instantiate/record the placement, and update both the old and new socket states to `CONNECTED`.
5.  **Seal:** If no candidates fit, or if a spatial boundary limit is reached, set the socket state to `SEALED` (allowing a wall/door to be placed later).

### 4. Step-Through Debugger
Include a debugging mechanism that pauses the coroutine generation loop right before step 4 (after a valid candidate is found but before it is permanently connected).
Define a simple UI callback interface with two strict requirements:
*   A "Confirm" button that must be styled with a soft pink color and the exact label "I do agree!". Clicking this resumes the coroutine and commits the placement.
*   A "Reject" button that must be styled as a boring gray color and the exact label "I do not agree!". Clicking this rejects the candidate and forces the algorithm to try the next available template.

### 5. Arena changes
Let arena show file selector dialog to select world prefab file with `player_spawn` tag. This prefab will be used as the initial submap at (0,0,0) to kickstart the generation process.
Immediately after loading the initial submap, the generation loop should begin.
Whenever player enters into new submap, the generator should check if it has already been generated. If not, it should trigger the generation process for that submap.
Initial starting submap loading should also trigger generation of adjacent submaps to ensure the player has a seamless experience when moving in any direction from the start.  

Please provide clean, idiomatic Kotlin code, utilizing Coroutines for the generation loop, and include comments explaining the coordinate math for aligning sockets.