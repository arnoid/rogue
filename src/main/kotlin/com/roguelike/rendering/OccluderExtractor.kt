package com.roguelike.rendering

import org.joml.Vector3f
import com.roguelike.core.model.TileSlot
import com.roguelike.world.*

/**
 * Extracts occluder triangle geometry from the world's wall tiles.
 *
 * Each wall is represented as a thin quad (two triangles) positioned at the
 * cell edge corresponding to the wall's cardinal direction. This geometry
 * is used by [ShadowVolumeBuilder] to construct shadow volumes.
 *
 * Walls span the full cell width (1.0 unit) and height (1.0 unit),
 * placed at ±0.5 along the wall's axis.
 */
object OccluderExtractor {

    private const val HALF = 0.5f

    /**
     * Extract all wall occluder triangles from the world within the given Z range.
     *
     * @return A single list of triangles representing all blocking walls.
     *         Returns one list (not per-wall) to reduce shadow volume build calls.
     */
    fun extractWallTriangles(
        world: com.roguelike.core.model.World,
        minZ: Int = 0,
        maxZ: Int = world.depth - 1
    ): List<ShadowVolumeBuilder.Triangle> {
        val triangles = mutableListOf<ShadowVolumeBuilder.Triangle>()

        val zLo = minZ.coerceIn(0, world.depth - 1)
        val zHi = maxZ.coerceAtMost(world.depth - 1)

        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in zLo..zHi) {
                    val node = world.getNode(x, y, z) ?: continue
                    val fx = x.toFloat()
                    val fy = y.toFloat()
                    val fz = z.toFloat()

                    for (tile in node.tiles) {
                        if (!tile.isBlocking()) continue

                        when (tile.slot) {
                            TileSlot.WALL_NORTH -> addNorthWall(triangles, fx, fy, fz)
                            TileSlot.WALL_SOUTH -> addSouthWall(triangles, fx, fy, fz)
                            TileSlot.WALL_EAST -> addEastWall(triangles, fx, fy, fz)
                            TileSlot.WALL_WEST -> addWestWall(triangles, fx, fy, fz)
                            else -> {} // Floors, stairs etc. don't occlude light
                        }
                    }
                }
            }
        }
        return triangles
    }

    // North wall: at y + 0.5, spans x ± 0.5, z ± 0.5
    // The quad faces south (normal pointing -Y) on the near side
    private fun addNorthWall(tris: MutableList<ShadowVolumeBuilder.Triangle>, x: Float, y: Float, z: Float) {
        val yp = y + HALF
        // Front face (facing -Y / south)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x - HALF, yp, z - HALF),
            Vector3f(x + HALF, yp, z - HALF),
            Vector3f(x + HALF, yp, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x - HALF, yp, z - HALF),
            Vector3f(x + HALF, yp, z + HALF),
            Vector3f(x - HALF, yp, z + HALF)
        ))
        // Back face (facing +Y / north)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x + HALF, yp, z - HALF),
            Vector3f(x - HALF, yp, z - HALF),
            Vector3f(x - HALF, yp, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x + HALF, yp, z - HALF),
            Vector3f(x - HALF, yp, z + HALF),
            Vector3f(x + HALF, yp, z + HALF)
        ))
    }

    // South wall: at y - 0.5
    private fun addSouthWall(tris: MutableList<ShadowVolumeBuilder.Triangle>, x: Float, y: Float, z: Float) {
        val yn = y - HALF
        // Front face (facing +Y / north)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x + HALF, yn, z - HALF),
            Vector3f(x - HALF, yn, z - HALF),
            Vector3f(x - HALF, yn, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x + HALF, yn, z - HALF),
            Vector3f(x - HALF, yn, z + HALF),
            Vector3f(x + HALF, yn, z + HALF)
        ))
        // Back face (facing -Y / south)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x - HALF, yn, z - HALF),
            Vector3f(x + HALF, yn, z - HALF),
            Vector3f(x + HALF, yn, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(x - HALF, yn, z - HALF),
            Vector3f(x + HALF, yn, z + HALF),
            Vector3f(x - HALF, yn, z + HALF)
        ))
    }

    // East wall: at x + 0.5
    private fun addEastWall(tris: MutableList<ShadowVolumeBuilder.Triangle>, x: Float, y: Float, z: Float) {
        val xp = x + HALF
        // Front face (facing -X / west)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xp, y + HALF, z - HALF),
            Vector3f(xp, y - HALF, z - HALF),
            Vector3f(xp, y - HALF, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xp, y + HALF, z - HALF),
            Vector3f(xp, y - HALF, z + HALF),
            Vector3f(xp, y + HALF, z + HALF)
        ))
        // Back face (facing +X / east)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xp, y - HALF, z - HALF),
            Vector3f(xp, y + HALF, z - HALF),
            Vector3f(xp, y + HALF, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xp, y - HALF, z - HALF),
            Vector3f(xp, y + HALF, z + HALF),
            Vector3f(xp, y - HALF, z + HALF)
        ))
    }

    // West wall: at x - 0.5
    private fun addWestWall(tris: MutableList<ShadowVolumeBuilder.Triangle>, x: Float, y: Float, z: Float) {
        val xn = x - HALF
        // Front face (facing +X / east)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xn, y - HALF, z - HALF),
            Vector3f(xn, y + HALF, z - HALF),
            Vector3f(xn, y + HALF, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xn, y - HALF, z - HALF),
            Vector3f(xn, y + HALF, z + HALF),
            Vector3f(xn, y - HALF, z + HALF)
        ))
        // Back face (facing -X / west)
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xn, y + HALF, z - HALF),
            Vector3f(xn, y - HALF, z - HALF),
            Vector3f(xn, y - HALF, z + HALF)
        ))
        tris.add(ShadowVolumeBuilder.Triangle(
            Vector3f(xn, y + HALF, z - HALF),
            Vector3f(xn, y - HALF, z + HALF),
            Vector3f(xn, y + HALF, z + HALF)
        ))
    }
}
