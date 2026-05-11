package com.roguelike

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.roguelike.core.model.GameLogger
import com.roguelike.core.systems.InteractionSystem
import com.roguelike.core.systems.MovementSystem
import com.roguelike.rendering.InventoryUI
import com.roguelike.rendering.ItemRenderer
import com.roguelike.rendering.TileRenderer
import com.roguelike.rendering.WorldRenderer
import com.roguelike.serialization.WorldIO
import com.roguelike.systems.CameraManager
import com.roguelike.systems.InputHandler
import com.roguelike.core.model.WorldNode.Tags as NodeTags
import com.roguelike.utils.*
import com.roguelike.world.*
import com.roguelike.generation.*
import kotlinx.coroutines.*

class RoguelikeGame(private val game: Game, val worldPath: String? = null) : Screen {
    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment

    // Player logic (pure core) + its visual representation (view layer)
    private lateinit var player: Player
    private lateinit var playerInstance: ModelInstance   // owned by this screen, not by Player
    private val moveSpeed = 5f

    // Systems & Renderers
    private lateinit var world: World
    private lateinit var worldRenderer: WorldRenderer
    private lateinit var itemRenderer: ItemRenderer
    private lateinit var movementSystem: MovementSystem
    private lateinit var interactionSystem: InteractionSystem
    private val inputHandler = InputHandler()
    private lateinit var cameraManager: CameraManager

    // UI
    private lateinit var stage: Stage
    private lateinit var skin: Skin
    private lateinit var inventoryUI: InventoryUI

    // Assets
    private val assetLoader = AssetLoader()
    private val modelLoader = ModelLoader(assetLoader)

    // Procedural generation
    private var mapManager: ProceduralMapManager? = null
    private var generationDebugUI: GenerationDebugUI? = null

    // Debug
    private var debugMode = false
    private lateinit var axesInstance: ModelInstance
    private lateinit var debugFrameModel: Model
    private lateinit var debugFrameInstance: ModelInstance
    private lateinit var debugShapeRenderer: com.badlogic.gdx.graphics.glutils.ShapeRenderer
    private lateinit var debugSpriteBatch: com.badlogic.gdx.graphics.g2d.SpriteBatch
    private lateinit var debugFont: com.badlogic.gdx.graphics.g2d.BitmapFont

    /** Bridge GameLogger that delegates to LibGDX Gdx.app.log(). */
    private val gdxLogger = GameLogger { tag, msg -> Gdx.app?.log(tag, msg) }

    override fun show() {
        modelBatch = ModelBatch()
        itemRenderer = ItemRenderer(assetLoader)
        val tileRenderer = TileRenderer(modelLoader.renderRegistry)
        worldRenderer = WorldRenderer(tileRenderer, itemRenderer)

        // --- World Generation or Loading ---
        if (worldPath != null) {
            loadWorldProcedural(worldPath)
        } else {
            world = World(9, 9, 3)
            WorldGenerator(world).generate()
        }

        movementSystem = MovementSystem(world)
        interactionSystem = InteractionSystem(world, gdxLogger)

        camera = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.near = 0.1f
        camera.far = 1000f
        camera.update()

        cameraManager = CameraManager(camera)

        environment = Environment()
        environment.set(ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f))
        environment.add(DirectionalLight().set(0.8f, 0.8f, 0.8f, -1f, -1f, -1f))

        // UI Setup
        stage = Stage(ScreenViewport())
        skin = Skin()
        val font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        skin.add("default", font)
        val pixmap = com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        skin.add("white", com.badlogic.gdx.graphics.Texture(pixmap))
        val labelStyle = com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(font, Color.WHITE)
        skin.add("default", labelStyle)

        inventoryUI = InventoryUI(skin)
        inventoryUI.setFillParent(true)
        stage.addActor(inventoryUI)

        // Generation debug UI (created early so it's available for procedural loading)
        generationDebugUI = GenerationDebugUI(stage, skin)

        // Player — pure logic object, no LibGDX
        player = Player()
        interactionSystem.actors.add(player)

        // Player visual — sphere mesh owned here in the view layer
        val modelBuilder = ModelBuilder()
        val sphereModel = modelBuilder.createSphere(
            0.8f, 0.8f, 0.8f, 20, 20,
            Material(ColorAttribute.createDiffuse(Color.BLUE)),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        )
        playerInstance = ModelInstance(sphereModel)

        // Debug Axes
        modelBuilder.begin()
        val part = modelBuilder.part(
            "axes", GL20.GL_LINES,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material()
        )
        part.setColor(Color.RED);   part.line(0f, 0f, 0f, 2f, 0f, 0f)
        part.setColor(Color.GREEN); part.line(0f, 0f, 0f, 0f, 2f, 0f)
        part.setColor(Color.BLUE);  part.line(0f, 0f, 0f, 0f, 0f, 2f)
        axesInstance = ModelInstance(modelBuilder.end()!!)

        // Debug wireframe box for node frames
        modelBuilder.begin()
        val framePart = modelBuilder.part(
            "frame", GL20.GL_LINES,
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material()
        )
        val s = 0.5f
        framePart.setColor(Color.WHITE)
        // Bottom face
        framePart.line(-s, -s, -s, s, -s, -s)
        framePart.line(s, -s, -s, s, s, -s)
        framePart.line(s, s, -s, -s, s, -s)
        framePart.line(-s, s, -s, -s, -s, -s)
        // Top face
        framePart.line(-s, -s, s, s, -s, s)
        framePart.line(s, -s, s, s, s, s)
        framePart.line(s, s, s, -s, s, s)
        framePart.line(-s, s, s, -s, -s, s)
        // Verticals
        framePart.line(-s, -s, -s, -s, -s, s)
        framePart.line(s, -s, -s, s, -s, s)
        framePart.line(s, s, -s, s, s, s)
        framePart.line(-s, s, -s, -s, s, s)
        debugFrameModel = modelBuilder.end()!!
        debugFrameInstance = ModelInstance(debugFrameModel)

        // Debug text and shape renderers
        debugShapeRenderer = com.badlogic.gdx.graphics.glutils.ShapeRenderer()
        debugSpriteBatch = com.badlogic.gdx.graphics.g2d.SpriteBatch()
        debugFont = com.badlogic.gdx.graphics.g2d.BitmapFont()

        // Spawn player at tagged node
        world.getNodesWithTag(NodeTags.PLAYER_SPAWN).firstOrNull()?.let { node ->
            player.position.set(node.x.toFloat(), node.y.toFloat(), node.z.toFloat())
        }
    }

    private fun loadWorld(path: String) {
        val loadedWorld = WorldIO.loadWorld(
            path,
            { w, h, d -> World(w, h, d) },
            { type -> modelLoader.createTile(type) }
        )
        world = loadedWorld ?: run {
            World(9, 9, 3).also { WorldGenerator(it).generate() }
        }
    }

    /**
     * Loads the selected file as the initial submap for procedural generation.
     * Also loads any templates from the same directory.
     */
    private fun loadWorldProcedural(path: String) {
        val manager = ProceduralMapManager(
            tileFactory = { type -> modelLoader.createTile(type) },
            worldFactory = { w, h, d -> World(w, h, d) }
        )
        mapManager = manager

        // Enable debug step-through and wire the UI callback
        manager.debugEnabled = false
        manager.debugCallback = generationDebugUI

        // Load templates from the default-submaps directory (sibling of starting-submaps)
        val templateDir = java.io.File(path).parentFile?.parentFile?.resolve("default-submaps")?.absolutePath
        if (templateDir != null) {
            manager.loadTemplates(templateDir)
        }

        // Initialize with the selected file as the starting submap
        val generatedWorld = manager.initialize(path)
        if (generatedWorld != null) {
            world = generatedWorld
        } else {
            // Fallback: load directly as a static world
            loadWorld(path)
        }
    }

    override fun render(delta: Float) {
        // ── Input & Logic ────────────────────────────────────────────────────
        val moveDir = inputHandler.getMovementDirection()
        movementSystem.move(player, moveDir, delta, moveSpeed)

        // Notify procedural map manager of player movement
        mapManager?.onPlayerMove(player.position.x, player.position.y, player.position.z)

        cameraManager.cameraYaw += inputHandler.getCameraYawChange(delta)
        cameraManager.zoom(inputHandler.getZoomChange(delta))
        cameraManager.update(player.position)

        if (inputHandler.isDebugToggleJustPressed()) debugMode = !debugMode

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.screen = MainMenuScreen(game)
        }

        if (inputHandler.isInteractionJustPressed()) {
            // Use the player's facing direction (updated by movement) for interaction
            interactionSystem.interact(player, player.facingDirection)
        }

        player.update(delta)
        inventoryUI.update(player)

        // ── Rendering ────────────────────────────────────────────────────────
        Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // Sync player visual to logic position
        playerInstance.transform.setTranslation(player.position.x, player.position.y, player.position.z)

        modelBatch.begin(camera)
        val playerZ = Math.ceil(player.position.z.toDouble()).toInt()
        worldRenderer.render(world, modelBatch, environment, maxZ = playerZ)
        modelBatch.render(playerInstance, environment)

        if (debugMode) {
            axesInstance.transform.setTranslation(player.position.x, player.position.y, player.position.z)
            modelBatch.render(axesInstance)
        }
        modelBatch.end()

        if (debugMode) {
            val playerNodeX = Math.round(player.position.x)
            val playerNodeY = Math.round(player.position.y)
            val playerNodeZ = Math.floor(player.position.z.toDouble()).toInt()

            // ── Node frames via ShapeRenderer ─────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            val s = 0.5f
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    val z = playerNodeZ
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tiles.isEmpty()) continue

                        val color = when {
                            x == playerNodeX && y == playerNodeY && z == playerNodeZ -> Color.BLUE
                            node.doorSlots.isNotEmpty() -> Color.GREEN
                            else -> Color(1f, 1f, 1f, 0.3f)
                        }
                        debugShapeRenderer.color = color
                        val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat()
                        // Bottom
                        debugShapeRenderer.line(fx-s,fy-s,fz-s, fx+s,fy-s,fz-s)
                        debugShapeRenderer.line(fx+s,fy-s,fz-s, fx+s,fy+s,fz-s)
                        debugShapeRenderer.line(fx+s,fy+s,fz-s, fx-s,fy+s,fz-s)
                        debugShapeRenderer.line(fx-s,fy+s,fz-s, fx-s,fy-s,fz-s)
                        // Top
                        debugShapeRenderer.line(fx-s,fy-s,fz+s, fx+s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy-s,fz+s, fx+s,fy+s,fz+s)
                        debugShapeRenderer.line(fx+s,fy+s,fz+s, fx-s,fy+s,fz+s)
                        debugShapeRenderer.line(fx-s,fy+s,fz+s, fx-s,fy-s,fz+s)
                        // Verticals
                        debugShapeRenderer.line(fx-s,fy-s,fz-s, fx-s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy-s,fz-s, fx+s,fy-s,fz+s)
                        debugShapeRenderer.line(fx+s,fy+s,fz-s, fx+s,fy+s,fz+s)
                        debugShapeRenderer.line(fx-s,fy+s,fz-s, fx-s,fy+s,fz+s)
                }
            }
            debugShapeRenderer.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Player facing direction arrow ─────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glLineWidth(3f)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            debugShapeRenderer.color = Color.YELLOW
            val px = player.position.x
            val py = player.position.y
            val pz = player.position.z
            val fd = player.facingDirection
            debugShapeRenderer.line(px, py, pz, px + fd.x * 0.8f, py + fd.y * 0.8f, pz)
            debugShapeRenderer.end()
            Gdx.gl.glLineWidth(1f)
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Stairs direction arrows (light blue) ─────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glLineWidth(3f)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            debugShapeRenderer.color = Color(0.5f, 0.8f, 1f, 1f)
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    val z = playerNodeZ
                    val node = world.getNode(x, y, z) ?: continue
                    val stairsTile = node.getTile(com.roguelike.core.model.TileSlot.STAIRS)
                    if (stairsTile is com.roguelike.world.StairsTile) {
                        val facing = stairsTile.facingDirection()
                        val dx: Float; val dy: Float
                        when (facing) {
                            com.roguelike.core.model.TileSlot.WALL_NORTH -> { dx = 0f; dy = 1f }
                            com.roguelike.core.model.TileSlot.WALL_SOUTH -> { dx = 0f; dy = -1f }
                            com.roguelike.core.model.TileSlot.WALL_EAST  -> { dx = 1f; dy = 0f }
                            com.roguelike.core.model.TileSlot.WALL_WEST  -> { dx = -1f; dy = 0f }
                            else -> continue
                        }
                        val sx = x.toFloat(); val sy = y.toFloat(); val sz = z.toFloat()
                        val len = 0.4f; val headLen = 0.15f
                        val tipX = sx + dx * len; val tipY = sy + dy * len
                        debugShapeRenderer.line(sx - dx * len, sy - dy * len, sz, tipX, tipY, sz)
                        debugShapeRenderer.line(tipX, tipY, sz, tipX - dx * headLen + dy * headLen, tipY - dy * headLen - dx * headLen, sz)
                        debugShapeRenderer.line(tipX, tipY, sz, tipX - dx * headLen - dy * headLen, tipY - dy * headLen + dx * headLen, sz)
                    }
                }
            }
            debugShapeRenderer.end()
            Gdx.gl.glLineWidth(1f)
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Ladder up-arrows (green) ─────────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glLineWidth(3f)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            debugShapeRenderer.color = Color(0.3f, 1f, 0.3f, 1f)
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    val z = playerNodeZ
                    val node = world.getNode(x, y, z) ?: continue
                    for (slot in node.ladderSlots) {
                        val offset = when (slot) {
                            com.roguelike.core.model.TileSlot.WALL_NORTH -> com.badlogic.gdx.math.Vector3(0f, 0.5f, 0f)
                            com.roguelike.core.model.TileSlot.WALL_SOUTH -> com.badlogic.gdx.math.Vector3(0f, -0.5f, 0f)
                            com.roguelike.core.model.TileSlot.WALL_EAST  -> com.badlogic.gdx.math.Vector3(0.5f, 0f, 0f)
                            com.roguelike.core.model.TileSlot.WALL_WEST  -> com.badlogic.gdx.math.Vector3(-0.5f, 0f, 0f)
                            else -> continue
                        }
                        val ex = x + offset.x
                        val ey = y + offset.y
                        val ez = z.toFloat()
                        val len = 0.4f; val headLen = 0.15f
                        val tipZ = ez + len
                        debugShapeRenderer.line(ex, ey, ez - len, ex, ey, tipZ)
                        debugShapeRenderer.line(ex, ey, tipZ, ex + headLen, ey, tipZ - headLen)
                        debugShapeRenderer.line(ex, ey, tipZ, ex - headLen, ey, tipZ - headLen)
                    }
                }
            }
            debugShapeRenderer.end()
            Gdx.gl.glLineWidth(1f)
            Gdx.gl.glDisable(GL20.GL_BLEND)

            // ── Tag text labels — projected to screen 2D ──────────────────────
            val viewW = Gdx.graphics.width.toFloat()
            val viewH = Gdx.graphics.height.toFloat()
            val projPos = com.badlogic.gdx.math.Vector3()
            debugSpriteBatch.projectionMatrix = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, viewW, viewH)
            debugSpriteBatch.begin()
            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    val z = playerNodeZ
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isNotEmpty()) {
                            projPos.set(x.toFloat(), y.toFloat() + 0.45f, z.toFloat())
                            camera.project(projPos)
                            if (projPos.z in 0f..1f) {
                                val label = node.tags.joinToString("\n")
                                debugFont.draw(debugSpriteBatch, label, projPos.x - 30f, projPos.y + 4f)
                            }
                        }
                        // Render door_manual at edge positions
                        for (slot in node.manualDoorSlots) {
                            val off = edgeOffsetFor(slot)
                            projPos.set(x + off.x, y + off.y + 0.45f, z + off.z)
                            camera.project(projPos)
                            if (projPos.z in 0f..1f) {
                                debugFont.draw(debugSpriteBatch, com.roguelike.core.model.WorldNode.Tags.DOOR_MANUAL, projPos.x - 30f, projPos.y + 4f)
                            }
                        }
                }
            }
            debugSpriteBatch.end()

            // ── Association lines ─────────────────────────────────────────────
            Gdx.gl.glEnable(GL20.GL_BLEND)
            debugShapeRenderer.projectionMatrix = camera.combined
            debugShapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line)
            world.associations.forEach { assoc ->
                // Only show associations where at least one end is on the player's level
                if (assoc.source.z != playerNodeZ && assoc.target.z != playerNodeZ) return@forEach
                // Skip key associations where the key has been picked up
                if (assoc.type == "key" && assoc.target.items.none { it is com.roguelike.core.model.KeyItem }) return@forEach

                val color = when (assoc.type) {
                    "toggle" -> Color.YELLOW
                    "key"    -> Color.CYAN
                    else     -> Color.WHITE
                }
                debugShapeRenderer.color = color
                debugShapeRenderer.line(
                    assoc.source.x.toFloat(), assoc.source.y.toFloat(), assoc.source.z.toFloat(),
                    assoc.target.x.toFloat(), assoc.target.y.toFloat(), assoc.target.z.toFloat()
                )
            }
            debugShapeRenderer.end()
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        modelBatch.dispose()
        assetLoader.dispose()
        stage.dispose()
        skin.dispose()
        debugShapeRenderer.dispose()
        debugSpriteBatch.dispose()
        debugFont.dispose()
        mapManager?.dispose()
    }

    private fun edgeOffsetFor(slot: com.roguelike.core.model.TileSlot): com.badlogic.gdx.math.Vector3 = when (slot) {
        com.roguelike.core.model.TileSlot.WALL_NORTH -> com.badlogic.gdx.math.Vector3(0f, 0.5f, 0f)
        com.roguelike.core.model.TileSlot.WALL_SOUTH -> com.badlogic.gdx.math.Vector3(0f, -0.5f, 0f)
        com.roguelike.core.model.TileSlot.WALL_EAST  -> com.badlogic.gdx.math.Vector3(0.5f, 0f, 0f)
        com.roguelike.core.model.TileSlot.WALL_WEST  -> com.badlogic.gdx.math.Vector3(-0.5f, 0f, 0f)
        else -> com.badlogic.gdx.math.Vector3(0f, 0f, 0f)
    }

    override fun hide() {}
    override fun pause() {}
    override fun resume() {}
}
