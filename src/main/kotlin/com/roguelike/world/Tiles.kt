package com.roguelike.world

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.roguelike.core.math.Vec3

interface Tile {
    val type: String
    fun isBlocking(): Boolean = false
    fun onInteract() {}
    val properties: Map<String, Any>
        get() = emptyMap()
}

/**
 * Base for all tile types.  Now holds only metadata (scale, center, rotations)
 * and the raw [Model] asset — NOT a ModelInstance.
 * ModelInstances are created lazily by [com.roguelike.rendering.TileRenderer].
 */
abstract class BaseTile(val model: Model, val scale: Float, val center: Vec3) : Tile {
    var rotationX = 0f
    var rotationY = 0f
    var rotationZ = 0f
    var zOffset   = 0f  // extra Z translation applied before rotation (node-local)

    /** Colour helper used in subclass init blocks only. */
    protected fun applyColor(color: Color, instance: ModelInstance) {
        if (instance.materials.size > 0) {
            instance.materials.get(0).set(ColorAttribute.createDiffuse(color))
        }
    }
}


class FloorTile(model: Model, scale: Float, center: Vec3) : BaseTile(model, scale, center) {
    companion object { const val TYPE = "FloorTile" }
    override val type: String get() = TYPE
}

abstract class WallTile(model: Model, scale: Float, center: Vec3) : BaseTile(model, scale, center) {
    override fun isBlocking(): Boolean = true
}

class WallHorizontalTile(model: Model, scale: Float, center: Vec3) : WallTile(model, scale, center) {
    companion object { const val TYPE = "WallHorizontalTile" }
    override val type: String get() = TYPE
}

class WallVerticalTile(model: Model, scale: Float, center: Vec3) : WallTile(model, scale, center) {
    companion object { const val TYPE = "WallVerticalTile" }
    override val type: String get() = TYPE
}

/**
 * Door tile.  Holds both the closed and open Model assets so the renderer
 * can create/cache the appropriate ModelInstances.
 * No ModelInstance is created here.
 */
abstract class DoorTile(
    modelClosed: Model,
    val modelOpen: Model,
    scale: Float,
    center: Vec3,
    var isOpen: Boolean = false
) : BaseTile(modelClosed, scale, center) {
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() { isOpen = !isOpen }
}

class DoorHorizontalTile(
    modelClosed: Model, modelOpen: Model, scale: Float, center: Vec3, isOpen: Boolean = false
) : DoorTile(modelClosed, modelOpen, scale, center, isOpen) {
    companion object { const val TYPE = "DoorHorizontalTile" }
    override val type: String get() = TYPE
}

class DoorVerticalTile(
    modelClosed: Model, modelOpen: Model, scale: Float, center: Vec3, isOpen: Boolean = false
) : DoorTile(modelClosed, modelOpen, scale, center, isOpen) {
    companion object { const val TYPE = "DoorVerticalTile" }
    override val type: String get() = TYPE
}

class ToggleTile(model: Model, scale: Float, center: Vec3, var linkedDoor: DoorTile? = null) :
        BaseTile(model, scale, center) {
    companion object { const val TYPE = "ToggleTile" }
    override val type: String get() = TYPE
    override fun onInteract() { linkedDoor?.onInteract() }
}

abstract class CornerTile(model: Model, scale: Float, center: Vec3) : BaseTile(model, scale, center) {
    override fun isBlocking(): Boolean = true
}

class CornerNETile(model: Model, scale: Float, center: Vec3) : CornerTile(model, scale, center) {
    companion object { const val TYPE = "CornerNETile" }
    override val type: String get() = TYPE
}

class CornerESTile(model: Model, scale: Float, center: Vec3) : CornerTile(model, scale, center) {
    companion object { const val TYPE = "CornerESTile" }
    override val type: String get() = TYPE
}

class CornerSWTile(model: Model, scale: Float, center: Vec3) : CornerTile(model, scale, center) {
    companion object { const val TYPE = "CornerSWTile" }
    override val type: String get() = TYPE
}

class CornerWNTile(model: Model, scale: Float, center: Vec3) : CornerTile(model, scale, center) {
    companion object { const val TYPE = "CornerWNTile" }
    override val type: String get() = TYPE
}

class GenericTile(val modelName: String, model: Model, scale: Float, center: Vec3) :
        BaseTile(model, scale, center) {
    override val type: String get() = "Generic:$modelName"
}
