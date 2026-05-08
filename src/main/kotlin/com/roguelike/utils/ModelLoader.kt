package com.roguelike.utils


import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.math.Vec3
import com.roguelike.world.*

/** Handles the loading and instantiation of tiles with their corresponding models. */
class ModelLoader(val assetLoader: AssetLoader) {

    private val factories = mutableMapOf<String, () -> Tile>()

    init {
        register(FloorTile.TYPE)          { createFloorTile() }
        register(WallHorizontalTile.TYPE) { createWallHorizontalTile() }
        register(WallVerticalTile.TYPE)   { createWallVerticalTile() }
        register(DoorHorizontalTile.TYPE) { createDoorHorizontalTile() }
        register(DoorVerticalTile.TYPE)   { createDoorVerticalTile() }
        register(ToggleTile.TYPE)         { createToggleTile() }
        register(CornerNETile.TYPE)       { createCornerNETile() }
        register(CornerESTile.TYPE)       { createCornerESTile() }
        register(CornerSWTile.TYPE)       { createCornerSWTile() }
        register(CornerWNTile.TYPE)       { createCornerWNTile() }
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
        return FloorTile(model, scale, center).also { it.zOffset = 0f }
    }

    fun createWallHorizontalTile(): WallHorizontalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        return WallHorizontalTile(model, scale, center)
    }

    fun createWallVerticalTile(): WallVerticalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        return WallVerticalTile(model, scale, center).also { it.rotationZ = 90f }
    }

    fun createDoorHorizontalTile(): DoorHorizontalTile {
        val closed = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val open   = assetLoader.loadModel("door_open",   "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(closed)
        return DoorHorizontalTile(closed, open, scale, center)
    }

    fun createDoorVerticalTile(): DoorVerticalTile {
        val closed = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val open   = assetLoader.loadModel("door_open",   "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(closed)
        return DoorVerticalTile(closed, open, scale, center).also { it.rotationY = 90f }
    }

    fun createToggleTile(): ToggleTile {
        val model = assetLoader.loadModel("toggle", "models/tiles/obj/box_small.obj")
        val box = BoundingBox()
        model.calculateBoundingBox(box)
        val gdxCenter = com.badlogic.gdx.math.Vector3()
        box.getCenter(gdxCenter)
        val center = Vec3(gdxCenter.x, gdxCenter.y, gdxCenter.z)
        val scale  = 0.5f / center.len()
        return ToggleTile(model, scale, center)
    }

    fun createCornerNETile(): CornerNETile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        return CornerNETile(model, scale, center)
    }

    fun createCornerESTile(): CornerESTile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        return CornerESTile(model, scale, center)
    }

    fun createCornerSWTile(): CornerSWTile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        return CornerSWTile(model, scale, center)
    }

    fun createCornerWNTile(): CornerWNTile {
        val model = assetLoader.loadModel("wall_corner", "models/tiles/obj/wall_corner.obj")
        val (scale, center) = getModelData(model)
        return CornerWNTile(model, scale, center)
    }

    fun createGenericTile(modelFile: String): GenericTile {
        val model = assetLoader.loadModel(modelFile, "models/tiles/obj/$modelFile")
        val (scale, center) = getModelData(model)
        return GenericTile(modelFile, model, scale, center)
    }

    fun createTile(typeName: String): Tile? {
        if (typeName.startsWith("Generic:")) {
            return createGenericTile(typeName.substringAfter("Generic:"))
        }
        return factories[typeName]?.invoke()
    }

    fun createKeyItem(colorHex: String, name: String): KeyItem = KeyItem(colorHex = colorHex, name = name)
}
