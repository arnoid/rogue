package com.roguelike.utils

import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Tile
import com.roguelike.rendering.TileRenderData
import com.roguelike.rendering.TileRenderRegistry
import com.roguelike.world.*


/** Handles the loading and instantiation of tiles with their corresponding models. */
class ModelLoader(val assetLoader: AssetLoader, val renderRegistry: TileRenderRegistry = TileRenderRegistry()) {

    private val factories = mutableMapOf<String, () -> Tile>()

    init {
        register(FloorTile.TYPE)         { createFloorTile() }
        register(WallNorthTile.TYPE)     { createWallNorthTile() }
        register(WallSouthTile.TYPE)     { createWallSouthTile() }
        register(WallEastTile.TYPE)      { createWallEastTile() }
        register(WallWestTile.TYPE)      { createWallWestTile() }
        register(DoorNorthTile.TYPE)     { createDoorNorthTile() }
        register(DoorSouthTile.TYPE)     { createDoorSouthTile() }
        register(DoorEastTile.TYPE)      { createDoorEastTile() }
        register(DoorWestTile.TYPE)      { createDoorWestTile() }
        register(StairsTile.TYPE)        { createStairsTile() }
    }

    fun register(typeName: String, factory: () -> Tile) {
        factories[typeName] = factory
    }

    /** Computes normalised scale and geometric centre from a model's bounding box. */
    private fun getModelData(model: Model): Pair<Float, Vec3> {
        val box = BoundingBox()
        model.calculateBoundingBox(box)
        val maxDim = maxOf(box.width, maxOf(box.height, box.depth))
        val scale  = if (maxDim > 0f) 1.0f / maxDim else 1.0f
        val gdxCenter = com.badlogic.gdx.math.Vector3()
        box.getCenter(gdxCenter)
        return scale to Vec3(gdxCenter.x, gdxCenter.y, gdxCenter.z)
    }

    fun createFloorTile(): FloorTile {
        val model = assetLoader.loadModel("floor", "models/vox/floor/floor.obj")
        val (scale, center) = getModelData(model)
        val tile = FloorTile().also { it.zOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    // ── Wall tiles (one per cardinal direction) ──────────────────────────

    fun createWallNorthTile(): WallNorthTile {
        val model = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(model)
        val tile = WallNorthTile().also { it.yOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallSouthTile(): WallSouthTile {
        val model = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(model)
        val tile = WallSouthTile().also { it.rotationY = 180f; it.yOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallEastTile(): WallEastTile {
        val model = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(model)
        val tile = WallEastTile().also { it.rotationY = -90f; it.xOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallWestTile(): WallWestTile {
        val model = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(model)
        val tile = WallWestTile().also { it.rotationY = 90f; it.xOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    // ── Door tiles (closed = door closed model, open = door open model) ───

    fun createDoorNorthTile(): DoorNorthTile {
        val closedModel = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openModel = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameModel = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedModel)
        val tile = DoorNorthTile().also { it.yOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(closedModel, scale, center, altModel = openModel, frameModel = frameModel))
        return tile
    }

    fun createDoorSouthTile(): DoorSouthTile {
        val closedModel = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openModel = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameModel = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedModel)
        val tile = DoorSouthTile().also { it.rotationY = 180f; it.yOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(closedModel, scale, center, altModel = openModel, frameModel = frameModel))
        return tile
    }

    fun createDoorEastTile(): DoorEastTile {
        val closedModel = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openModel = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameModel = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedModel)
        val tile = DoorEastTile().also { it.rotationY = -90f; it.xOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(closedModel, scale, center, altModel = openModel, frameModel = frameModel))
        return tile
    }

    fun createDoorWestTile(): DoorWestTile {
        val closedModel = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openModel = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameModel = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedModel)
        val tile = DoorWestTile().also { it.rotationY = 90f; it.xOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(closedModel, scale, center, altModel = openModel, frameModel = frameModel))
        return tile
    }

    // ── Stairs tile ────────────────────────────────────────────────────────

    fun createStairsTile(): StairsTile {
        val model = assetLoader.loadModel("stairs_n", "models/vox/stairs/stairs_n.obj")
        val (scale, center) = getModelData(model)
        val tile = StairsTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createTile(typeName: String): Tile? = factories[typeName]?.invoke()

    fun createKeyItem(colorHex: String, name: String): KeyItem = KeyItem(colorHex = colorHex, name = name)
}
