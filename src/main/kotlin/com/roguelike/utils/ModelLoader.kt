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

    companion object {
        const val WALL_TSPLIT_OFFSET = 0.19f
    }

    init {
        register(FloorTile.TYPE)          { createFloorTile() }
        register(WallHorizontalTile.TYPE) { createWallHorizontalTile() }
        register(WallVerticalTile.TYPE)   { createWallVerticalTile() }
        register(DoorHorizontalTile.TYPE) { createDoorHorizontalTile() }
        register(DoorVerticalTile.TYPE)   { createDoorVerticalTile() }
        register(ToggleTile.TYPE)         { createToggleTile() }
        register(CornerNETile.TYPE)       { createCornerNETile() }
        register(CornerSETile.TYPE)       { createCornerSETile() }
        register(CornerSWTile.TYPE)       { createCornerSWTile() }
        register(CornerNWTile.TYPE)       { createCornerNWTile() }
        register(WallDoorwayHorizontalTile.TYPE) { createWallDoorwayHorizontalTile() }
        register(WallDoorwayVerticalTile.TYPE)   { createWallDoorwayVerticalTile() }
        register(WallCrossingTile.TYPE)   { createWallCrossingTile() }
        register(WallTsplitNTile.TYPE)    { createWallTsplitNTile() }
        register(WallTsplitETile.TYPE)    { createWallTsplitETile() }
        register(WallTsplitSTile.TYPE)    { createWallTsplitSTile() }
        register(WallTsplitWTile.TYPE)    { createWallTsplitWTile() }
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
        val model = assetLoader.loadModel("floor", "models/tiles/obj/floor_dirt_large.obj")
        val (scale, center) = getModelData(model)
        val tile = FloorTile().also { it.zOffset = 0f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallHorizontalTile(): WallHorizontalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        val tile = WallHorizontalTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallVerticalTile(): WallVerticalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        val tile = WallVerticalTile().also { it.rotationY = -90f}
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createDoorHorizontalTile(): DoorHorizontalTile {
        val model = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val (scale, center) = getModelData(model)
        val tile = DoorHorizontalTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createDoorVerticalTile(): DoorVerticalTile {
        val model = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val (scale, center) = getModelData(model)
        val tile = DoorVerticalTile().also { it.rotationY = 90f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createToggleTile(): ToggleTile {
        val model = assetLoader.loadModel("toggle", "models/tiles/obj/box_small.obj")
        val box = BoundingBox()
        model.calculateBoundingBox(box)
        val gdxCenter = com.badlogic.gdx.math.Vector3()
        box.getCenter(gdxCenter)
        val center = Vec3(gdxCenter.x, gdxCenter.y, gdxCenter.z)
        val scale  = 0.5f / center.len()
        val tile = ToggleTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createCornerNETile(): CornerNETile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        val tile = CornerNETile().also { it.xOffset = WALL_TSPLIT_OFFSET; it.yOffset = WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createCornerSETile(): CornerSETile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        val tile = CornerSETile().also { it.rotationY = 90f; it.xOffset = WALL_TSPLIT_OFFSET; it.yOffset = -WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createCornerSWTile(): CornerSWTile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        val tile = CornerSWTile().also { it.rotationY = 180f; it.xOffset = -WALL_TSPLIT_OFFSET; it.yOffset = -WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createCornerNWTile(): CornerNWTile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        val tile = CornerNWTile().also { it.rotationY = -90f; it.xOffset = -WALL_TSPLIT_OFFSET; it.yOffset = WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallDoorwayHorizontalTile(): WallDoorwayHorizontalTile {
        val model = assetLoader.loadModel("wall_doorway", "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(model)
        val tile = WallDoorwayHorizontalTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallDoorwayVerticalTile(): WallDoorwayVerticalTile {
        val model = assetLoader.loadModel("wall_doorway", "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(model)
        val tile = WallDoorwayVerticalTile().also { it.rotationY = -90f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallCrossingTile(): WallCrossingTile {
        val model = assetLoader.loadModel("wall_crossing", "models/tiles/obj/wall_crossing.obj")
        val (scale, center) = getModelData(model)
        val tile = WallCrossingTile()
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallTsplitNTile(): WallTsplitNTile {
        val model = assetLoader.loadModel("wall_Tsplit", "models/tiles/obj/wall_Tsplit.obj")
        val (scale, center) = getModelData(model)
        val tile = WallTsplitNTile().also { it.yOffset = 0.19f }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallTsplitETile(): WallTsplitETile {
        val model = assetLoader.loadModel("wall_Tsplit", "models/tiles/obj/wall_Tsplit.obj")
        val (scale, center) = getModelData(model)
        val tile = WallTsplitETile().also { it.rotationY = 90f; it.xOffset = WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallTsplitSTile(): WallTsplitSTile {
        val model = assetLoader.loadModel("wall_Tsplit", "models/tiles/obj/wall_Tsplit.obj")
        val (scale, center) = getModelData(model)
        val tile = WallTsplitSTile().also { it.rotationY = 180f; it.yOffset = -WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createWallTsplitWTile(): WallTsplitWTile {
        val model = assetLoader.loadModel("wall_Tsplit", "models/tiles/obj/wall_Tsplit.obj")
        val (scale, center) = getModelData(model)
        val tile = WallTsplitWTile().also { it.rotationY = -90f; it.xOffset = -WALL_TSPLIT_OFFSET }
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createGenericTile(modelFile: String): GenericTile {
        val model = assetLoader.loadModel(modelFile, "models/tiles/obj/$modelFile")
        val (scale, center) = getModelData(model)
        val tile = GenericTile(modelFile)
        renderRegistry.register(tile, TileRenderData(model, scale, center))
        return tile
    }

    fun createTile(typeName: String): Tile? {
        if (typeName.startsWith("Generic:")) {
            return createGenericTile(typeName.substringAfter("Generic:"))
        }
        return factories[typeName]?.invoke()
    }

    fun createKeyItem(colorHex: String, name: String): KeyItem = KeyItem(colorHex = colorHex, name = name)
}
