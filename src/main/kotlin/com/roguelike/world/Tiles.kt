package com.roguelike.world

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3

interface Tile {
    val type: String
    fun isBlocking(): Boolean = false
    fun onInteract() {}
    val properties: Map<String, Any>
        get() = emptyMap()
}

abstract class BaseTile(val model: Model, val scale: Float, val center: Vector3) : Tile {
    val instance = ModelInstance(model)
    var rotationX = 0f
    var rotationY = 0f
    var rotationZ = 0f
    var zOffset   = 0f  // extra Z translation applied before rotation (node-local)

    protected fun setColor(color: Color) {
        if (instance.materials.size > 0) {
            instance.materials.get(0).set(ColorAttribute.createDiffuse(color))
        }
    }
}


class FloorTile(model: Model, scale: Float, center: Vector3) : BaseTile(model, scale, center) {
    companion object {
        const val TYPE = "FloorTile"
    }
    override val type: String
        get() = TYPE
    init {
        setColor(Color.DARK_GRAY)
    }
}

abstract class WallTile(model: Model, scale: Float, center: Vector3) :
        BaseTile(model, scale, center) {
    init {
        setColor(Color.GRAY)
    }
    override fun isBlocking(): Boolean = true
}

class WallHorizontalTile(model: Model, scale: Float, center: Vector3) :
        WallTile(model, scale, center) {
    companion object {
        const val TYPE = "WallHorizontalTile"
    }
    override val type: String
        get() = TYPE
}

class WallVerticalTile(model: Model, scale: Float, center: Vector3) :
        WallTile(model, scale, center) {
    companion object {
        const val TYPE = "WallVerticalTile"
    }
    override val type: String
        get() = TYPE
}

abstract class DoorTile(
        modelClosed: Model,
        val modelOpen: Model,
        scale: Float,
        center: Vector3,
        var isOpen: Boolean = false
) : BaseTile(modelClosed, scale, center) {
    val openInstance = ModelInstance(modelOpen)
    
    override fun isBlocking(): Boolean = !isOpen
    override fun onInteract() {
        isOpen = !isOpen
    }
}

class DoorHorizontalTile(
        modelClosed: Model,
        modelOpen: Model,
        scale: Float,
        center: Vector3,
        isOpen: Boolean = false
) : DoorTile(modelClosed, modelOpen, scale, center, isOpen) {
    companion object {
        const val TYPE = "DoorHorizontalTile"
    }
    override val type: String
        get() = TYPE
}

class DoorVerticalTile(
        modelClosed: Model,
        modelOpen: Model,
        scale: Float,
        center: Vector3,
        isOpen: Boolean = false
) : DoorTile(modelClosed, modelOpen, scale, center, isOpen) {
    companion object {
        const val TYPE = "DoorVerticalTile"
    }
    override val type: String
        get() = TYPE
}

class ToggleTile(model: Model, scale: Float, center: Vector3, var linkedDoor: DoorTile? = null) :
        BaseTile(model, scale, center) {
    companion object {
        const val TYPE = "ToggleTile"
    }
    override val type: String
        get() = TYPE
    override fun onInteract() {
        linkedDoor?.onInteract()
    }
}

abstract class CornerTile(model: Model, scale: Float, center: Vector3) :
        BaseTile(model, scale, center) {
    init {
        setColor(Color.LIGHT_GRAY)
    }
    override fun isBlocking(): Boolean = true
}

class CornerNETile(model: Model, scale: Float, center: Vector3) : CornerTile(model, scale, center) {
    companion object {
        const val TYPE = "CornerNETile"
    }
    override val type: String
        get() = TYPE
}

class CornerESTile(model: Model, scale: Float, center: Vector3) : CornerTile(model, scale, center) {
    companion object {
        const val TYPE = "CornerESTile"
    }
    override val type: String
        get() = TYPE
}

class CornerSWTile(model: Model, scale: Float, center: Vector3) : CornerTile(model, scale, center) {
    companion object {
        const val TYPE = "CornerSWTile"
    }
    override val type: String
        get() = TYPE
}

class CornerWNTile(model: Model, scale: Float, center: Vector3) : CornerTile(model, scale, center) {
    companion object {
        const val TYPE = "CornerWNTile"
    }
    override val type: String
        get() = TYPE
}

class GenericTile(val modelName: String, model: Model, scale: Float, center: Vector3) :
        BaseTile(model, scale, center) {
    override val type: String
        get() = "Generic:$modelName"
}
