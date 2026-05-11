package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.roguelike.core.model.Tile
import com.roguelike.world.*

/**
 * Translates core [Tile] data objects into rendered [ModelInstance]s.
 *
 * ModelInstances are created lazily on first render and cached per tile instance.
 * Rendering metadata (Model, scale, center) is looked up from [TileRenderRegistry],
 * keeping tile classes free of any LibGDX state.
 */
class TileRenderer(
    private val registry: TileRenderRegistry
) {

    /** Primary (closed-door / default) ModelInstance per tile. */
    private val instanceCache = mutableMapOf<Any, ModelInstance>()
    /** Alternate (open-door) ModelInstance per tile. */
    private val altInstanceCache = mutableMapOf<Any, ModelInstance>()
    /** Frame (wall_doorway) ModelInstance per tile. */
    private val frameInstanceCache = mutableMapOf<Any, ModelInstance>()

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

    private fun getFrameInstance(tile: BaseTile): ModelInstance? {
        frameInstanceCache[tile]?.let { return it }
        val data = registry[tile] ?: return null
        val frameModel = data.frameModel ?: return null
        val inst = ModelInstance(frameModel)
        frameInstanceCache[tile] = inst
        return inst
    }

    private fun isDoorTile(tile: Tile): Boolean =
        tile is DoorNorthTile || tile is DoorSouthTile || tile is DoorEastTile || tile is DoorWestTile

    private fun isDoorOpen(tile: Tile): Boolean = when (tile) {
        is DoorNorthTile -> tile.isOpen
        is DoorSouthTile -> tile.isOpen
        is DoorEastTile  -> tile.isOpen
        is DoorWestTile  -> tile.isOpen
        else -> false
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

        // For door tiles, pick the correct model based on open/closed state
        val instance = if (isDoorTile(tile) && isDoorOpen(tile)) {
            getAltInstance(tile) ?: getInstance(tile) ?: return
        } else {
            getInstance(tile) ?: return
        }

        updateTransform(instance, tile, renderData, x, y, z, ignoreYRotation)
        updateColor(instance, tile)

        batch.render(instance, environment)

        // Render wall_doorway frame alongside door tiles
        if (isDoorTile(tile) && renderData.frameModel != null) {
            val frameInst = getFrameInstance(tile)
            if (frameInst != null) {
                updateTransform(frameInst, tile, renderData, x, y, z, ignoreYRotation)
                // Apply wall color to the frame
                if (frameInst.materials.size > 0) {
                    frameInst.materials.get(0).set(ColorAttribute.createDiffuse(Color.GRAY))
                }
                batch.render(frameInst, environment)
            }
        }
    }

    private fun updateTransform(
        instance: ModelInstance,
        tile: BaseTile,
        renderData: TileRenderData,
        x: Float, y: Float, z: Float,
        ignoreYRotation: Boolean
    ) {
        var rotX = -90f
        var rotY = 0f
        var rotZ = 180f

        rotX += tile.rotationX
        rotY += tile.rotationY
        rotZ += tile.rotationZ

        val baseZ = tile.fixedZ ?: z
        val tx = x + tile.xOffset
        val ty = y + tile.yOffset
        val tz = baseZ + tile.zOffset

        instance.transform.setToTranslation(tx, ty, tz)
        val sx = renderData.scaleX ?: renderData.scale
        val sy = renderData.scaleY ?: renderData.scale
        val sz = renderData.scaleZ ?: renderData.scale
        instance.transform.scale(sx, sy, sz)
        if (rotX != 0f)                       instance.transform.rotate(Vector3.X, rotX)
        if (!ignoreYRotation && rotY != 0f)   instance.transform.rotate(Vector3.Y, rotY)
        if (rotZ != 0f)                       instance.transform.rotate(Vector3.Z, rotZ)

        instance.transform.translate(-renderData.center.x, -renderData.center.y, -renderData.center.z)
    }

    private fun updateColor(instance: ModelInstance, tile: Tile) {
        if (instance.materials.isEmpty) return
        val color: Color? = when (tile) {
            is FloorTile -> Color(0.6f, 0.4f, 0.2f, 1f)
            is WallNorthTile, is WallSouthTile, is WallEastTile, is WallWestTile -> Color.GRAY
            is DoorNorthTile, is DoorSouthTile, is DoorEastTile, is DoorWestTile -> Color.BROWN
            is StairsTile -> Color(0.5f, 0.5f, 0.4f, 1f)
            else -> null
        }
        color?.let { instance.materials.get(0).set(ColorAttribute.createDiffuse(it)) }
    }
}
