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
import com.roguelike.rendering.InventoryUI
import com.roguelike.rendering.ItemRenderer
import com.roguelike.rendering.WorldRenderer
import com.roguelike.serialization.WorldIO
import com.roguelike.systems.*
import com.roguelike.utils.*
import com.roguelike.world.*

class RoguelikeGame(private val game: Game, val worldPath: String? = null) : Screen {
    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment

    // Player
    private lateinit var player: Player
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

    // Debug
    private var debugMode = false
    private lateinit var axesInstance: ModelInstance

    override fun show() {
        modelBatch = ModelBatch()
        itemRenderer = ItemRenderer(assetLoader)
        worldRenderer = WorldRenderer(itemRenderer = itemRenderer)

        // --- World Generation or Loading ---
        if (worldPath != null) {
            loadWorld(worldPath)
        } else {
            world = World(10, 10, 1)
            WorldGenerator(world, modelLoader).generate()
        }

        movementSystem = MovementSystem(world)
        interactionSystem = InteractionSystem(world)

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
        // We need a font and white texture for InventoryUI (similar to MapEditor)
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

        // Create Player
        val modelBuilder = ModelBuilder()
        val sphereModel = modelBuilder.createSphere(
            0.8f, 0.8f, 0.8f, 20, 20,
            Material(ColorAttribute.createDiffuse(Color.BLUE)),
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
        )
        player = Player(ModelInstance(sphereModel))

        // Create Debug Axes
        modelBuilder.begin()
        val part = modelBuilder.part("axes", GL20.GL_LINES, 
            (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorPacked).toLong(), Material())
        part.setColor(Color.RED)
        part.line(0f, 0f, 0f, 2f, 0f, 0f)
        part.setColor(Color.GREEN)
        part.line(0f, 0f, 0f, 0f, 2f, 0f)
        part.setColor(Color.BLUE)
        part.line(0f, 0f, 0f, 0f, 0f, 2f)
        axesInstance = ModelInstance(modelBuilder.end()!!)

        // Spawn player
        world.getNodesWithTag(WorldNode.Tags.PLAYER_SPAWN).firstOrNull()?.let { node ->
            player.position.set(node.x.toFloat(), node.y.toFloat(), node.z.toFloat())
        }
    }

    private fun loadWorld(path: String) {
        val loadedWorld = WorldIO.loadWorld(path, { w, h, d -> World(w, h, d) }, { type -> modelLoader.createTile(type) })
        if (loadedWorld != null) {
            world = loadedWorld
        } else {
            world = World(10, 10, 1)
            WorldGenerator(world, modelLoader).generate()
        }
    }

    override fun render(delta: Float) {
        // Input & Logic
        val moveDir = inputHandler.getMovementDirection(camera)
        movementSystem.move(player, moveDir, delta, moveSpeed)

        cameraManager.cameraYaw += inputHandler.getCameraYawChange(delta)
        cameraManager.update(player.position)

        if (inputHandler.isDebugToggleJustPressed()) debugMode = !debugMode
        
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.screen = MainMenuScreen(game)
        }

        if (inputHandler.isInteractionJustPressed()) {
            interactionSystem.interact(player, camera)
        }


        player.update(delta)
        inventoryUI.update(player)

        // Rendering
        Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        modelBatch.begin(camera)
        worldRenderer.render(world, modelBatch, environment)
        player.modelInstance?.let { modelBatch.render(it, environment) }
        
        if (debugMode) {
            axesInstance.transform.setTranslation(player.position)
            modelBatch.render(axesInstance)
        }
        modelBatch.end()

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
    }

    override fun hide() {}
    override fun pause() {}
    override fun resume() {}
}
