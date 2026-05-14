package com.roguelike.utils

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
        register(LadderTile.TYPE)        { createLadderTile() }
    }

    fun register(typeName: String, factory: () -> Tile) { factories[typeName] = factory }

    private fun getModelData(meshData: MeshData): Pair<Float, Vec3> {
        return meshData.scale to Vec3(meshData.center.x, meshData.center.y, meshData.center.z)
    }

    fun createFloorTile(): FloorTile {
        val data = assetLoader.loadModel("floor", "models/vox/floor/floor.obj")
        val (scale, center) = getModelData(data)
        val tile = FloorTile().also { it.zOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    // ── Wall tiles (one per cardinal direction) ──────────────────────────

    fun createWallNorthTile(): WallNorthTile {
        val data = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(data)
        val tile = WallNorthTile().also { it.yOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    fun createWallSouthTile(): WallSouthTile {
        val data = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(data)
        val tile = WallSouthTile().also { it.rotationY = 180f; it.yOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    fun createWallEastTile(): WallEastTile {
        val data = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(data)
        val tile = WallEastTile().also { it.rotationY = -90f; it.xOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    fun createWallWestTile(): WallWestTile {
        val data = assetLoader.loadModel("wall_n", "models/vox/wall/wall_n.obj")
        val (scale, center) = getModelData(data)
        val tile = WallWestTile().also { it.rotationY = 90f; it.xOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    // ── Door tiles (closed = door closed model, open = door open model) ───

    fun createDoorNorthTile(): DoorNorthTile {
        val closedData = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openData = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameData = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedData)
        val tile = DoorNorthTile().also { it.yOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(closedData, scale, center, altModel = openData, frameModel = frameData))
        return tile
    }

    fun createDoorSouthTile(): DoorSouthTile {
        val closedData = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openData = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameData = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedData)
        val tile = DoorSouthTile().also { it.rotationY = 180f; it.yOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(closedData, scale, center, altModel = openData, frameModel = frameData))
        return tile
    }

    fun createDoorEastTile(): DoorEastTile {
        val closedData = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openData = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameData = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedData)
        val tile = DoorEastTile().also { it.rotationY = -90f; it.xOffset = 0.5f }
        renderRegistry.register(tile, TileRenderData(closedData, scale, center, altModel = openData, frameModel = frameData))
        return tile
    }

    fun createDoorWestTile(): DoorWestTile {
        val closedData = assetLoader.loadModel("door_n_closed", "models/vox/door/door_n_closed.obj")
        val openData = assetLoader.loadModel("door_n_open", "models/vox/door/door_n_open.obj")
        val frameData = assetLoader.loadModel("wall_doorway_n", "models/vox/wall/wall_doorway_n.obj")
        val (scale, center) = getModelData(closedData)
        val tile = DoorWestTile().also { it.rotationY = 90f; it.xOffset = -0.5f }
        renderRegistry.register(tile, TileRenderData(closedData, scale, center, altModel = openData, frameModel = frameData))
        return tile
    }

    // ── Stairs tile ────────────────────────────────────────────────────────

    fun createStairsTile(): StairsTile {
        val data = assetLoader.loadModel("stairs_n", "models/vox/stairs/stairs_n.obj")
        val (scale, center) = getModelData(data)
        val tile = StairsTile()
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    // ── Ladder tile ──────────────────────────────────────────────────────────

    fun createLadderTile(): LadderTile {
        val data = assetLoader.loadModel("ladder_vertical_n", "models/vox/stairs/ladder_vertical_n.obj")
        val (scale, center) = getModelData(data)
        val tile = LadderTile()
        renderRegistry.register(tile, TileRenderData(data, scale, center))
        return tile
    }

    fun createTile(typeName: String): Tile? = factories[typeName]?.invoke()

    fun createKeyItem(colorHex: String, name: String): KeyItem = KeyItem(colorHex = colorHex, name = name)
}
