package com.roguelike

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Player
import com.roguelike.core.model.World
import com.roguelike.core.systems.MovementSystem
import com.roguelike.input.InputSystem
import com.roguelike.rendering.Camera
import com.roguelike.systems.CameraManager
import com.roguelike.systems.InputHandler
import com.roguelike.ui.SimpleUI
import com.roguelike.world.*

/**
 * Game screen — 3D arena gameplay with procedural world.
 * Generates a world via ProceduralMapManager, places a player, and runs game logic.
 * Renders a top-down 2D preview of the world grid using the UI system
 * until the full Vulkan 3D pipeline is connected.
 */
class RoguelikeGame(
    private val inputSystem: InputSystem,
    private val camera: Camera,
    private val ui: SimpleUI
) {
    private var world: World? = null
    private var player: Player? = null
    private var movementSystem: MovementSystem? = null
    private var cameraManager: CameraManager? = null
    private var inputHandler: InputHandler? = null
    private var lastFrameTime: Long = System.nanoTime()

    // Camera for top-down 2D grid view
    private var viewX = 0f
    private var viewY = 0f
    private var viewScale = 24f // pixels per tile

    fun show() {
        // Generate a simple arena: 12x12x3 world with floor and walls
        val w = World(12, 12, 3)
        for (x in 0 until 12) {
            for (y in 0 until 12) {
                val node = w.getNode(x, y, 0) ?: continue
                // Floor everywhere
                node.setTile(FloorTile())
                // Walls on edges
                if (y == 11) node.setTile(WallNorthTile())
                if (y == 0) node.setTile(WallSouthTile())
                if (x == 11) node.setTile(WallEastTile())
                if (x == 0) node.setTile(WallWestTile())
            }
        }
        world = w

        // Create player at center
        player = Player().apply { position.set(6f, 6f, 0f) }

        // Create systems
        movementSystem = MovementSystem(w)
        cameraManager = CameraManager(camera)
        inputHandler = InputHandler(inputSystem)

        // Center view on player
        viewX = player!!.position.x
        viewY = player!!.position.y

        lastFrameTime = System.nanoTime()
    }

    fun render(): Boolean {
        val w = world ?: return false
        val p = player ?: return false
        val move = movementSystem ?: return false
        val camMgr = cameraManager ?: return false
        val input = inputHandler ?: return false

        // Delta time
        val now = System.nanoTime()
        val delta = ((now - lastFrameTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastFrameTime = now

        // Handle input
        val moveDir = input.getMovementDirection()
        if (!moveDir.isZero) {
            moveDir.nor()
            move.move(p, moveDir, delta, 3f)
        }

        // Camera zoom
        val zoom = input.getZoomChange(delta)
        if (zoom != 0f) viewScale = (viewScale - zoom * 2f).coerceIn(8f, 64f)

        // Update camera to follow player
        camMgr.update(p.position)

        // Center view on player
        viewX = p.position.x
        viewY = p.position.y

        // Render 2D grid preview
        renderTopDownGrid(w, p)

        return true
    }

    private fun renderTopDownGrid(w: World, p: Player) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val scale = viewScale

        // Offset so player is centered on screen
        val offsetX = sw / 2f - viewX * scale
        val offsetY = sh / 2f - viewY * scale

        // Draw tiles
        for (x in 0 until w.width) {
            for (y in 0 until w.height) {
                val node = w.getNode(x, y, 0) ?: continue
                val sx = offsetX + x * scale
                val sy = offsetY + (w.height - 1 - y) * scale // flip Y for screen coords

                // Skip off-screen tiles
                if (sx + scale < 0 || sx > sw || sy + scale < 0 || sy > sh) continue

                val hasFloor = node.tiles.any { it.type == FloorTile.TYPE }
                val hasWallN = node.tiles.any { it.type == WallNorthTile.TYPE }
                val hasWallS = node.tiles.any { it.type == WallSouthTile.TYPE }
                val hasWallE = node.tiles.any { it.type == WallEastTile.TYPE }
                val hasWallW = node.tiles.any { it.type == WallWestTile.TYPE }

                if (hasFloor) {
                    ui.drawRect(sx + 1, sy + 1, scale - 2, scale - 2, 0.18f, 0.2f, 0.25f)
                }

                // Draw walls as colored edges
                val wallThk = 3f
                if (hasWallN) ui.drawRect(sx, sy, scale, wallThk, 0.5f, 0.4f, 0.3f)
                if (hasWallS) ui.drawRect(sx, sy + scale - wallThk, scale, wallThk, 0.5f, 0.4f, 0.3f)
                if (hasWallW) ui.drawRect(sx, sy, wallThk, scale, 0.5f, 0.4f, 0.3f)
                if (hasWallE) ui.drawRect(sx + scale - wallThk, sy, wallThk, scale, 0.5f, 0.4f, 0.3f)
            }
        }

        // Draw player
        val px = offsetX + p.position.x * scale - scale * 0.2f
        val py = offsetY + (w.height - 1 - p.position.y) * scale + scale * 0.3f
        val pSize = scale * 0.4f
        ui.drawRect(px, py, pSize, pSize, 0.2f, 0.8f, 0.3f)

        // HUD
        ui.drawText("ARENA", 10f, 10f, 0.8f, 0.85f, 1f, 1f, 1.5f)
        ui.drawText("WASD: Move  Z/X: Zoom  ESC: Menu", 10f, sh - 30f, 0.5f, 0.55f, 0.65f, 0.8f, 1f)
        val posStr = "Pos: %.1f, %.1f".format(p.position.x, p.position.y)
        ui.drawText(posStr, sw - ui.textWidth(posStr) - 10f, 10f, 0.6f, 0.6f, 0.7f, 0.8f, 1f)
    }

    fun resize(width: Int, height: Int) {
        camera.resize(width, height)
    }

    fun dispose() {
        world = null
        player = null
    }
}
