package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.roguelike.world.*

class TileRenderer {
    fun render(
            tile: Tile,
            batch: ModelBatch,
            environment: Environment,
            x: Float,
            y: Float,
            z: Float,
            ignoreYRotation: Boolean = false
    ) {
        if (tile is BaseTile) {
            val instanceToRender =
                    when {
                        tile is DoorTile && tile.isOpen -> tile.openInstance
                        else -> tile.instance
                    }

            // Calculate additional rotation for open doors
            // In X-Y ground, doors rotate around Z to open? Or around their "hinge" axis.
            // Let's keep it simple: rotate around local Z.
            val extraRotZ = if (tile is DoorTile && tile.isOpen) -90f else 0f

            // Update transform
            updateTransform(instanceToRender, tile, x, y, z, ignoreYRotation, extraRotZ)

            // If it's a door, keep the other instance synced for consistency
            if (tile is DoorTile) {
                val otherInstance = if (tile.isOpen) tile.instance else tile.openInstance
                val otherExtraRotZ = if (tile.isOpen) 0f else -90f
                updateTransform(otherInstance, tile, x, y, z, ignoreYRotation, otherExtraRotZ)
            }

            // Special handling for dynamic colors (e.g. DoorTile, ToggleTile)
            updateColor(instanceToRender, tile)

            batch.render(instanceToRender, environment)
        }
    }

    private fun updateTransform(
            instance: ModelInstance,
            tile: BaseTile,
            x: Float,
            y: Float,
            z: Float,
            ignoreYRotation: Boolean,
            additionalRotZ: Float = 0f
    ) {
        var rotX = -90f
        var rotY = 0f
        var rotZ = 180f
        var zOff = 0f
        var tx = x
        var ty = y
        var tz = z // z is layer

        when (tile) {
        // Per-tile adjustments live in tile.rotationX/Y/Z and tile.zOffset (set in ModelLoader)
        }

        // Add any tile-specific z offset
        zOff += tile.zOffset

        // Apply additional rotation for open state
        rotZ += additionalRotZ

        // Apply manual rotations from editor
        rotX += tile.rotationX
        rotY += tile.rotationY
        rotZ += tile.rotationZ

        instance.transform.setToTranslation(tx, ty, tz + zOff)
        instance.transform.scale(tile.scale, tile.scale, tile.scale)
        if (rotX != 0f) instance.transform.rotate(Vector3.X, rotX)
        if (!ignoreYRotation && rotY != 0f) instance.transform.rotate(Vector3.Y, rotY)
        if (rotZ != 0f) instance.transform.rotate(Vector3.Z, rotZ)
        instance.transform.translate(-tile.center.x, -tile.center.y, -tile.center.z)
    }

    private fun updateColor(instance: ModelInstance, tile: Tile) {
        if (instance.materials.size > 0) {
            val color =
                    when (tile) {
                        is DoorTile -> if (tile.isOpen) Color.GREEN else Color.RED
                        is ToggleTile ->
                                if (tile.linkedDoor?.isOpen == true) Color.GREEN else Color.RED
                        is FloorTile -> Color(0.6f, 0.4f, 0.2f, 1f)
                        else -> null
                    }
            color?.let { instance.materials.get(0).set(ColorAttribute.createDiffuse(it)) }
        }
    }
}
