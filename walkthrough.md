# Realtime 2D Roguelike Implementation Walkthrough

I have successfully built the foundation for your real-time 2D roguelike game! Here is a summary of what has been implemented and how you can run it.

## Architecture & Code Structure

The game was built using **Kotlin** and **LibGDX**, following modern standard game development practices.

*   **`Main.kt`**: The desktop launcher. Configures a 800x600 window running at 60Hz.
*   **`RoguelikeGame.kt`**: The main game class that initializes the `SpriteBatch` and sets up the screen.
*   **`TextureGenerator.kt`**: To guarantee everything works perfectly without needing external files or worrying about path issues, the sprites (player, civilians, walls, floor, switches, door) are procedurally generated in-memory using LibGDX's `Pixmap`. This gives it a clean, classic blocky look (similar to very early prototypes of Streets of Rogue).
*   **`world/GameMap.kt`**: Defines the predefined level. A 25x18 grid containing outer walls and an inner 10x10 room with a gap for a door.
*   **`GameScreen.kt`**: The core loop. Handles drawing the map, updating all entities, and processing solid collision logic.

## Entity System & Mechanics

I built a flexible base `Entity` class and derived the specific features you asked for:

*   **Player**: Placed outside the room. Move using **W, A, S, D** or the **Arrow Keys**.
*   **Civilians**: 5 civilians are placed inside the room. They use a simple wandering AI timer to pick random directions and wander around, properly colliding with the walls.
*   **Switches & Door**: There are two switches placed on the map. They activate when any entity (you or a civilian) steps on them. The `Switch.kt` logic checks if *all* switches are green. If so, the `Door` changes its state to "open" (brown folded texture) and removes its collision box, allowing you to walk into the room.

## How to Play / Verify

To run the game on your Mac, simply execute this command in your terminal from the workspace directory (`/Users/sarnaut/work/workspace/roguelike`):

```bash
./gradlew run
```

> [!TIP]
> The macOS specific JVM argument `-XstartOnFirstThread` required by LibGDX has already been configured for you in `build.gradle.kts`.

### Controls
*   **W/A/S/D** or **Arrows** to move the blue player character.
*   Walk over the grey square pads to turn them green.
*   Once both pads are green, the solid door (black square inside a brown frame) will open, allowing you access to the civilians!
