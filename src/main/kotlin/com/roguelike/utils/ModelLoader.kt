package com.roguelike.utils

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.world.*

/** Handles the loading and instantiation of tiles with their corresponding models and scales. */
class ModelLoader(val assetLoader: AssetLoader) {

    private val factories = mutableMapOf<String, () -> Tile>()

    init {
        register(FloorTile.TYPE) { createFloorTile() }
        register(WallHorizontalTile.TYPE) { createWallHorizontalTile() }
        register(WallVerticalTile.TYPE) { createWallVerticalTile() }
        register(DoorHorizontalTile.TYPE) { createDoorHorizontalTile() }
        register(DoorVerticalTile.TYPE) { createDoorVerticalTile() }
        register(ToggleTile.TYPE) { createToggleTile() }
        register(CornerNETile.TYPE) { createCornerNETile() }
        register(CornerESTile.TYPE) { createCornerESTile() }
        register(CornerSWTile.TYPE) { createCornerSWTile() }
        register(CornerWNTile.TYPE) { createCornerWNTile() }
    }

    fun register(typeName: String, factory: () -> Tile) {
        factories[typeName] = factory
    }

    private fun getModelData(model: Model): Pair<Float, Vector3> {
        val box = BoundingBox()
        model.calculateBoundingBox(box)
        val scale = 1.0f / box.width
        val center = Vector3()
        box.getCenter(center)
        return scale to center
    }

    fun createFloorTile(): FloorTile {
        val model = assetLoader.loadModel("floor", "models/tiles/obj/floor_dirt_large.obj")
        val (scale, center) = getModelData(model)
        return FloorTile(model, scale, center)
    }

    fun createWallHorizontalTile(): WallHorizontalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        return WallHorizontalTile(model, scale, center)
    }

    fun createWallVerticalTile(): WallVerticalTile {
        val model = assetLoader.loadModel("wall", "models/tiles/obj/wall.obj")
        val (scale, center) = getModelData(model)
        return WallVerticalTile(model, scale, center)
    }

    fun createDoorHorizontalTile(): DoorHorizontalTile {
        val closed = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val open = assetLoader.loadModel("door_open", "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(closed)
        return DoorHorizontalTile(closed, open, scale, center)
    }

    fun createDoorVerticalTile(): DoorVerticalTile {
        val closed = assetLoader.loadModel("door_closed", "models/tiles/obj/wall_doorway_door.obj")
        val open = assetLoader.loadModel("door_open", "models/tiles/obj/wall_doorway.obj")
        val (scale, center) = getModelData(closed)
        val tile = DoorVerticalTile(closed, open, scale, center)
        tile.rotationY = 90f
        return tile
    }

    fun createToggleTile(): ToggleTile {
        val model = assetLoader.loadModel("toggle", "models/tiles/obj/box_small.obj")
        val box = BoundingBox()
        model.calculateBoundingBox(box)
        val center = Vector3()
        box.getCenter(center)
        val scale = 0.5f / center.len()
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

    fun createKeyItem(color: Color, name: String): KeyItem {
        return KeyItem(color = color, name = name)
    }
}
