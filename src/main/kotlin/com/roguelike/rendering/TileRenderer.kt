package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.roguelike.world.*

/**
 * Translates core [Tile] data objects into rendered [ModelInstance]s.
 *
 * ModelInstances are created lazily on first render and cached per tile instance
 * in [instanceCache] / [altInstanceCache].  This keeps [BaseTile] (and all its
 * concrete subclasses) free of any ModelInstance state.
 */
class TileRenderer {

    /** Primary (closed-door / default) ModelInstance per tile. */
    private val instanceCache    = mutableMapOf<BaseTile, ModelInstance>()
    /** Secondary (open-door) ModelInstance — only populated for DoorTile. */
    private val altInstanceCache = mutableMapOf<DoorTile, ModelInstance>()

    private fun getInstance(tile: BaseTile): ModelInstance =
        instanceCache.getOrPut(tile) { ModelInstance(tile.model) }

    private fun getAltInstance(door: DoorTile): ModelInstance =
        altInstanceCache.getOrPut(door) { ModelInstance(door.modelOpen) }

    fun render(
        tile: Tile,
        batch: ModelBatch,
        environment: Environment,
        x: Float,
        y: Float,
        z: Float,
        ignoreYRotation: Boolean = false
    ) {
        if (tile !is BaseTile) return

        val instanceToRender = when {
            tile is DoorTile && tile.isOpen -> getAltInstance(tile)
            else                            -> getInstance(tile)
        }

        val extraRotZ = if (tile is DoorTile && tile.isOpen) -90f else 0f

        updateTransform(instanceToRender, tile, x, y, z, ignoreYRotation, extraRotZ)

        // Keep the other door instance in sync (so switching open↔closed is seamless)
        if (tile is DoorTile) {
            val other       = if (tile.isOpen) getInstance(tile) else getAltInstance(tile)
            val otherRotZ   = if (tile.isOpen) 0f else -90f
            updateTransform(other, tile, x, y, z, ignoreYRotation, otherRotZ)
        }

        updateColor(instanceToRender, tile)

        batch.render(instanceToRender, environment)
    }

    private fun updateTransform(
        instance: ModelInstance,
        tile: BaseTile,
        x: Float, y: Float, z: Float,
        ignoreYRotation: Boolean,
        additionalRotZ: Float = 0f
    ) {
        var rotX = -90f
        var rotY = 0f
        var rotZ = 180f

        rotX += tile.rotationX
        rotY += tile.rotationY
        rotZ += tile.rotationZ + additionalRotZ

        val tz = z + tile.zOffset

        instance.transform.setToTranslation(x, y, tz)
        instance.transform.scale(tile.scale, tile.scale, tile.scale)
        if (rotX != 0f)                       instance.transform.rotate(Vector3.X, rotX)
        if (!ignoreYRotation && rotY != 0f)   instance.transform.rotate(Vector3.Y, rotY)
        if (rotZ != 0f)                       instance.transform.rotate(Vector3.Z, rotZ)
        instance.transform.translate(-tile.center.x, -tile.center.y, -tile.center.z)
    }

    private fun updateColor(instance: ModelInstance, tile: Tile) {
        if (instance.materials.isEmpty) return
        val color: Color? = when (tile) {
            is DoorTile    -> if (tile.isOpen) Color.GREEN else Color.RED
            is ToggleTile  -> if (tile.linkedDoor?.isOpen == true) Color.GREEN else Color.RED
            is FloorTile   -> Color(0.6f, 0.4f, 0.2f, 1f)
            is WallTile    -> Color.GRAY
            is CornerTile  -> Color.LIGHT_GRAY
            else           -> null
        }
        color?.let { instance.materials.get(0).set(ColorAttribute.createDiffuse(it)) }
    }
}
