package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.roguelike.core.model.Tile
import com.roguelike.systems.DoorAnimationSystem
import com.roguelike.world.*

/**
 * Translates core [Tile] data objects into rendered [ModelInstance]s.
 *
 * ModelInstances are created lazily on first render and cached per tile instance.
 * Rendering metadata (Model, scale, center) is looked up from [TileRenderRegistry],
 * keeping tile classes free of any LibGDX state.
 */
class TileRenderer(
    private val registry: TileRenderRegistry,
    var doorAnimationSystem: DoorAnimationSystem? = null
) {

    /** Primary (closed-door / default) ModelInstance per tile. */
    private val instanceCache    = mutableMapOf<Any, ModelInstance>()
    /** Secondary (open-door) ModelInstance — only populated for DoorTile. */
    private val altInstanceCache = mutableMapOf<Any, ModelInstance>()

    private fun getInstance(tile: BaseTile): ModelInstance? {
        instanceCache[tile]?.let { return it }
        val data = registry[tile] ?: return null
        val inst = ModelInstance(data.model)
        instanceCache[tile] = inst
        return inst
    }

    private fun getAltInstance(tile: BaseTile): ModelInstance? {
        altInstanceCache[tile]?.let { return it }
        val data = registry[tile] ?: return null
        val altModel = data.altModel ?: return null
        val inst = ModelInstance(altModel)
        altInstanceCache[tile] = inst
        return inst
    }

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
        val renderData = registry[tile] ?: return

        // Doors always use the same model; the animation system rotates it open/closed
        val instanceToRender = when {
            tile is DoorTile -> getInstance(tile) ?: return
            else             -> getInstance(tile) ?: return
        }

        // Use animated quaternion rotation when available, otherwise fall back to instant
        val doorQuat: Quaternion? = if (tile is DoorTile) doorAnimationSystem?.getCurrentRotation(tile) else null
        val extraRotY = if (doorQuat != null) 0f else if (tile is DoorTile && tile.isOpen) -90f else 0f

        updateTransform(instanceToRender, tile, renderData, x, y, z, ignoreYRotation, extraRotY, doorQuat)
        updateColor(instanceToRender, tile)

        batch.render(instanceToRender, environment)
    }

    private fun updateTransform(
        instance: ModelInstance,
        tile: BaseTile,
        renderData: TileRenderData,
        x: Float, y: Float, z: Float,
        ignoreYRotation: Boolean,
        additionalRotY: Float = 0f,
        doorQuat: Quaternion? = null
    ) {
        var rotX = -90f
        var rotY = 0f
        var rotZ = 180f

        rotX += tile.rotationX
        rotY += tile.rotationY + additionalRotY
        rotZ += tile.rotationZ

        val baseZ = tile.fixedZ ?: z
        val tx = x + tile.xOffset
        val ty = y + tile.yOffset
        val tz = baseZ + tile.zOffset

        instance.transform.setToTranslation(tx, ty, tz)
        instance.transform.scale(renderData.scale, renderData.scale, renderData.scale)
        if (rotX != 0f)                       instance.transform.rotate(Vector3.X, rotX)
        if (!ignoreYRotation && rotY != 0f)   instance.transform.rotate(Vector3.Y, rotY)
        if (rotZ != 0f)                       instance.transform.rotate(Vector3.Z, rotZ)
        // Apply smooth door swing quaternion (slerp-interpolated by DoorAnimationSystem)
        if (doorQuat != null)                 instance.transform.rotate(doorQuat)
        instance.transform.translate(-renderData.center.x, -renderData.center.y, -renderData.center.z)
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
