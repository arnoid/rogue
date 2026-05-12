package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.model.Tile
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
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
        ignoreYRotation: Boolean = false,
        tint: Color? = null
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
        updateColor(instance, tile, tint)

        batch.render(instance, environment)

        // Render wall_doorway frame alongside door tiles
        if (isDoorTile(tile) && renderData.frameModel != null) {
            val frameInst = getFrameInstance(tile)
            if (frameInst != null) {
                updateTransform(frameInst, tile, renderData, x, y, z, ignoreYRotation)
                // Apply wall color to the frame (with optional tint)
                if (frameInst.materials.size > 0) {
                    val c = if (tint != null) {
                        Color(Color.GRAY.r * tint.r, Color.GRAY.g * tint.g, Color.GRAY.b * tint.b, 1f)
                    } else Color.GRAY
                    frameInst.materials.get(0).set(ColorAttribute.createDiffuse(c))
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
        var tx = x + tile.xOffset
        var ty = y + tile.yOffset
        val tz = baseZ + tile.zOffset

        // Shift ladder model toward the wall it faces
        if (tile is LadderTile) {
            when (tile.facingDirection()) {
                TileSlot.WALL_NORTH -> ty += 0.49f
                TileSlot.WALL_SOUTH -> ty -= 0.49f
                TileSlot.WALL_EAST  -> tx += 0.49f
                TileSlot.WALL_WEST  -> tx -= 0.49f
                else -> {}
            }
        }

        if (tile is StairsTile) {
            rotY += 180f
        }

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

    /**
     * Computes world-space [BoundingBox] for every blocking tile in [world].
     * Called once per world load (static geometry) or per frame (dynamic doors/props).
     * Open doors are excluded because their [Tile.isBlocking] returns false.
     */
    fun worldSpaceBoxes(world: World): List<BoundingBox> {
        val result = mutableListOf<BoundingBox>()
        for (z in 0 until world.depth) {
            for (y in 0 until world.height) {
                for (x in 0 until world.width) {
                    val node = world.getNode(x, y, z) ?: continue
                    for (tile in node.tiles) {
                        if (tile !is BaseTile || !tile.isBlocking()) continue
                        val renderData = registry[tile] ?: continue
                        val inst = getInstance(tile) ?: continue
                        updateTransform(inst, tile, renderData, x.toFloat(), y.toFloat(), z.toFloat(), false)
                        inst.calculateTransforms()
                        val box = BoundingBox()
                        inst.calculateBoundingBox(box)
                        result.add(box)
                    }
                }
            }
        }
        return result
    }

    private fun updateColor(instance: ModelInstance, tile: Tile, tint: Color? = null) {
        if (instance.materials.isEmpty) return
        // Every tile type gets a base diffuse color so the per-cell light tint
        // can multiply against it. Unknown tile types fall back to white so they
        // still go fully dark in unlit cells (white * 0 = black).
        val baseColor: Color = when (tile) {
            is FloorTile -> Color(0.6f, 0.4f, 0.2f, 1f)
            is WallNorthTile, is WallSouthTile, is WallEastTile, is WallWestTile -> Color.GRAY
            is DoorNorthTile, is DoorSouthTile, is DoorEastTile, is DoorWestTile -> Color.BROWN
            is StairsTile -> Color(0.5f, 0.5f, 0.4f, 1f)
            is LadderTile -> Color(0.55f, 0.35f, 0.15f, 1f) // wood
            else -> Color.WHITE
        }
        val finalColor = if (tint != null) {
            Color(baseColor.r * tint.r, baseColor.g * tint.g, baseColor.b * tint.b, 1f)
        } else baseColor
        instance.materials.get(0).set(ColorAttribute.createDiffuse(finalColor))
    }
}
