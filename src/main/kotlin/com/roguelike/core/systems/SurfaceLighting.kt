package com.roguelike.core.systems

import com.roguelike.core.math.Vec3
import com.roguelike.core.model.Actor
import com.roguelike.core.model.Item
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.LightDef
import com.roguelike.core.model.LightDirection
import com.roguelike.core.model.LightShape
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.isLit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Ray-traced lighting for individual world surfaces.
 *
 * Unlike [LightingSystem] (which fills a per-cell uniform tint), this system
 * computes a separate color per *surface*:
 *
 *  - floor face of a cell (centered, top of `z-0.5`)
 *  - 4 wall faces of a cell (at its edge midpoint)
 *  - centered surface (stairs, ladders, props, items)
 *
 * For each rendered surface, the system iterates all lights, computes the
 * vector from the light origin to the surface sample point, applies cone/range
 * tests, and performs a *continuous DDA* occlusion ray-march through the grid.
 * Walls block their respective edges; floors block vertical transitions; cell
 * contents marked `blocksLight` block when traversed (but not at the endpoints).
 *
 * Pure Kotlin / no LibGDX dependency — testable.
 */
class SurfaceLighting(
    private val world: World,
    private val lights: List<LightSource>
) {

    /** A single light emitter located in the world. */
    data class LightSource(
        val def: LightDef,
        /** Continuous world-space origin (cell-centered convention). */
        val ox: Float, val oy: Float, val oz: Float,
        /** Source cell coordinates for occlusion start. */
        val cx: Int, val cy: Int, val cz: Int,
        /** Planar facing direction (unit vector in XY). z is 0 by convention. */
        val fx: Float, val fy: Float, val fz: Float = 0f
    )

    private val out = FloatArray(3)

    /** Diagnostics: have we already logged the "DDA start voxel mismatch" warning this instance? */
    private var leakWarned = false

    // ── Surface query API ──────────────────────────────────────────────────
    //
    // *** Coordinate convention ***
    // The rest of the game uses CELL-CENTERED coords: cell (x,y,z) is centered
    // at world (x,y,z). Walls between cell N and cell N+1 sit at world coord N+0.5.
    // Sample points below are in that frame; the DDA in [rayClear] applies a
    // +0.5 shift internally so voxel boundaries align with game walls.

    /** Tint for a floor surface at the bottom of cell (x,y,z) (world z = z - 0.5). */
    fun floor(x: Int, y: Int, z: Int, dst: FloatArray = out): FloatArray =
        sample(x.toFloat(), y.toFloat(), z - 0.5f + 0.02f, x, y, z, dst)

    /** Tint for a wall surface on edge [slot] of cell (x,y,z).
     *
     *  A wall is two-sided: the inward face is visible from inside the owning
     *  cell, the outward face from the adjacent cell. We pick the face on the
     *  side of each light, so the wall lights up correctly regardless of which
     *  cell the light is in.
     */
    fun wall(x: Int, y: Int, z: Int, slot: TileSlot, dst: FloatArray = out): FloatArray {
        return sampleWall(x, y, z, slot, dst)
    }

    /** Tint for a centered cell-bound surface (stairs, ladders, props, items). */
    fun cell(x: Int, y: Int, z: Int, dst: FloatArray = out): FloatArray =
        sample(x.toFloat(), y.toFloat(), z.toFloat(), x, y, z, dst)

    // ── Core: light sampling per-surface ───────────────────────────────────

    private fun sample(
        // Surface world-space sample point (cell-centered convention)
        wpx: Float, wpy: Float, wpz: Float,
        // Surface's owning cell — never blocks its own light at endpoint
        sCellX: Int, sCellY: Int, sCellZ: Int,
        dst: FloatArray
    ): FloatArray {
        var rSum = 0f; var gSum = 0f; var bSum = 0f
        for (light in lights) {
            // Vector from light → surface
            val dx = wpx - light.ox
            val dy = wpy - light.oy
            val dz = wpz - light.oz
            val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (dist > light.def.range) continue
            if (dist < 1e-5f) {
                // Surface coincides with light origin (e.g. lit item at same cell).
                val (lr, lg, lb) = parseRgb(light.def.colorHex)
                val mul = light.def.intensity
                rSum += lr * mul; gSum += lg * mul; bSum += lb * mul
                continue
            }
            val nx = dx / dist; val ny = dy / dist; val nz = dz / dist

            // Cone test (planar dot product with stored facing; z component handled too).
            if (light.def.shape == LightShape.CONE && light.def.direction == LightDirection.OWNER_FACING) {
                val halfDeg = (light.def.coneDegrees / 2.0).coerceIn(0.0, 180.0)
                val cosHalf = cos(Math.toRadians(halfDeg)).toFloat()
                val dot = nx * light.fx + ny * light.fy + nz * light.fz
                if (dot < cosHalf) continue
            }

            // Occlusion ray: light origin → surface sample point.
            if (!rayClear(
                    light.ox, light.oy, light.oz,
                    wpx, wpy, wpz,
                    light.cx, light.cy, light.cz,
                    sCellX, sCellY, sCellZ
                )
            ) continue

            val falloff = (1f - dist / light.def.range).coerceIn(0f, 1f)
            val mul = falloff * light.def.intensity
            val (lr, lg, lb) = parseRgb(light.def.colorHex)
            rSum += lr * mul; gSum += lg * mul; bSum += lb * mul
        }
        dst[0] = rSum.coerceIn(0f, 1f)
        dst[1] = gSum.coerceIn(0f, 1f)
        dst[2] = bSum.coerceIn(0f, 1f)
        return dst
    }

    // ── Geometry helpers ───────────────────────────────────────────────────

    /**
     * Per-light wall sampling: for each light, choose the wall face on the
     * light's side of the wall plane. That way, light from inside cell (x,y,z)
     * tests the inward face and light from the adjacent cell tests the outward
     * face — both face checks use a sample point reachable WITHOUT crossing the
     * wall itself.
     */
    private fun sampleWall(
        x: Int, y: Int, z: Int, slot: TileSlot, dst: FloatArray
    ): FloatArray {
        val cx = x.toFloat(); val cy = y.toFloat(); val cz = z.toFloat()
        // Wall plane coord on its normal axis + per-side sample positions.
        // (axis: 'X' or 'Y'; planeCoord at world; inwardOffset is signed eps;
        //  outwardOffset is signed eps on the other side.)
        data class WallFaces(val axis: Char, val planeCoord: Float, val inward: Float, val outward: Float)
        val w = when (slot) {
            TileSlot.WALL_NORTH -> WallFaces('Y', y + 0.5f, y + 0.5f - 0.01f, y + 0.5f + 0.01f)
            TileSlot.WALL_SOUTH -> WallFaces('Y', y - 0.5f, y - 0.5f + 0.01f, y - 0.5f - 0.01f)
            TileSlot.WALL_EAST  -> WallFaces('X', x + 0.5f, x + 0.5f - 0.01f, x + 0.5f + 0.01f)
            TileSlot.WALL_WEST  -> WallFaces('X', x - 0.5f, x - 0.5f + 0.01f, x - 0.5f - 0.01f)
            else -> return sample(cx, cy, cz, x, y, z, dst)
        }

        var rSum = 0f; var gSum = 0f; var bSum = 0f
        for (light in lights) {
            // Pick the face on the light's side of the wall plane.
            val onInwardSide = when (w.axis) {
                'X' -> if (slot == TileSlot.WALL_EAST) light.ox <= w.planeCoord
                       else /* WEST */                  light.ox >= w.planeCoord
                else /* 'Y' */ -> if (slot == TileSlot.WALL_NORTH) light.oy <= w.planeCoord
                                  else /* SOUTH */                 light.oy >= w.planeCoord
            }
            val (wpx, wpy, wpz) = when (w.axis) {
                'X' -> Triple(if (onInwardSide) w.inward else w.outward, cy, cz)
                else -> Triple(cx, if (onInwardSide) w.inward else w.outward, cz)
            }

            val dx = wpx - light.ox
            val dy = wpy - light.oy
            val dz = wpz - light.oz
            val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (dist > light.def.range) continue
            if (dist < 1e-5f) {
                val (lr, lg, lb) = parseRgb(light.def.colorHex)
                val mul = light.def.intensity
                rSum += lr * mul; gSum += lg * mul; bSum += lb * mul
                continue
            }
            val nx = dx / dist; val ny = dy / dist; val nz = dz / dist
            if (light.def.shape == LightShape.CONE && light.def.direction == LightDirection.OWNER_FACING) {
                val halfDeg = (light.def.coneDegrees / 2.0).coerceIn(0.0, 180.0)
                val cosHalf = cos(Math.toRadians(halfDeg)).toFloat()
                val dot = nx * light.fx + ny * light.fy + nz * light.fz
                if (dot < cosHalf) continue
            }

            // For the LOS source/target cells we use the cell that the chosen
            // sample point actually lies inside. The inward face is inside
            // (x,y,z); the outward face is inside the adjacent cell.
            val (tcX, tcY) = if (onInwardSide) (x to y) else when (slot) {
                TileSlot.WALL_NORTH -> x to (y + 1)
                TileSlot.WALL_SOUTH -> x to (y - 1)
                TileSlot.WALL_EAST  -> (x + 1) to y
                TileSlot.WALL_WEST  -> (x - 1) to y
                else                -> x to y
            }
            if (!rayClear(light.ox, light.oy, light.oz, wpx, wpy, wpz,
                    light.cx, light.cy, light.cz, tcX, tcY, z)) continue

            val falloff = (1f - dist / light.def.range).coerceIn(0f, 1f)
            val mul = falloff * light.def.intensity
            val (lr, lg, lb) = parseRgb(light.def.colorHex)
            rSum += lr * mul; gSum += lg * mul; bSum += lb * mul
        }
        dst[0] = rSum.coerceIn(0f, 1f)
        dst[1] = gSum.coerceIn(0f, 1f)
        dst[2] = bSum.coerceIn(0f, 1f)
        return dst
    }

    /** Legacy single-face wall sample (kept for tests / external callers). */
    @Suppress("unused")
    private fun wallSamplePoint(x: Int, y: Int, z: Int, slot: TileSlot): Triple<Float, Float, Float> {
        val cx = x.toFloat(); val cy = y.toFloat(); val cz = z.toFloat()
        return when (slot) {
            TileSlot.WALL_NORTH -> Triple(cx, y + 0.5f - 0.01f, cz)
            TileSlot.WALL_SOUTH -> Triple(cx, y - 0.5f + 0.01f, cz)
            TileSlot.WALL_EAST  -> Triple(x + 0.5f - 0.01f, cy, cz)
            TileSlot.WALL_WEST  -> Triple(x - 0.5f + 0.01f, cy, cz)
            else                -> Triple(cx, cy, cz)
        }
    }

    // ── Continuous DDA occlusion ───────────────────────────────────────────

    /**
     * 3D DDA ray clear-check from `(ox,oy,oz)` to `(tx,ty,tz)`.
     *
     * Returns true if no occluder blocks the segment. The source cell
     * `(scX,scY,scZ)` and target cell `(tcX,tcY,tcZ)` never have their own
     * cell-content treated as an occluder (the surface itself is what we're
     * lighting).
     *
     * Blocking rules:
     *  - Walls on edges that the ray crosses (using `isWallBetween`).
     *  - Floors on Z transitions (using `isFloorBetweenZ`).
     *  - Cell content (`blocksLight` items/props) when traversed mid-line.
     */
    fun rayClear(
        oxIn: Float, oyIn: Float, ozIn: Float,
        txIn: Float, tyIn: Float, tzIn: Float,
        scX: Int, scY: Int, scZ: Int,
        tcX: Int, tcY: Int, tcZ: Int
    ): Boolean {
        // Shift continuous coords into the DDA frame so voxel boundaries align
        // with the game's cell-centered walls (wall between cell N and N+1 is
        // at world coord N+0.5 → DDA boundary at integer N+1 after the shift).
        val ox = oxIn + 0.5f; val oy = oyIn + 0.5f; val oz = ozIn + 0.5f
        val tx = txIn + 0.5f; val ty = tyIn + 0.5f; val tz = tzIn + 0.5f

        if (LightingDiagnostics.enabled && !leakWarned) {
            val voxX = kotlin.math.floor(ox).toInt()
            val voxY = kotlin.math.floor(oy).toInt()
            val voxZ = kotlin.math.floor(oz).toInt()
            if (voxX != scX || voxY != scY || voxZ != scZ) {
                println("[LIGHTLOG] SurfaceLighting.rayClear: origin(world)=(% .3f,% .3f,% .3f) ".format(oxIn, oyIn, ozIn) +
                    "stored cell=($scX,$scY,$scZ) vs DDA start vox(shifted)=($voxX,$voxY,$voxZ) " +
                    "→ light origin / declared source cell disagree even after +0.5 shift.")
                leakWarned = true
            }
        }
        val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
        val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (dist < 1e-5f) return true

        // Direction
        val rdx = dx / dist; val rdy = dy / dist; val rdz = dz / dist

        // Voxel coords + step direction per axis
        var vx = kotlin.math.floor(ox).toInt()
        var vy = kotlin.math.floor(oy).toInt()
        var vz = kotlin.math.floor(oz).toInt()
        val stepX = if (rdx > 0) 1 else if (rdx < 0) -1 else 0
        val stepY = if (rdy > 0) 1 else if (rdy < 0) -1 else 0
        val stepZ = if (rdz > 0) 1 else if (rdz < 0) -1 else 0

        val INF = Float.POSITIVE_INFINITY
        // Next integer boundary along an axis given current coord and step sign.
        fun nextBoundary(coord: Float, step: Int): Float = when {
            step > 0 -> kotlin.math.floor(coord).toFloat() + 1f
            step < 0 -> {
                val f = kotlin.math.floor(coord).toFloat()
                // If coord IS exactly on an int boundary and we step negative,
                // the next boundary is one below.
                if (f == coord) f - 1f else f
            }
            else -> 0f
        }

        // tMaxX/Y/Z = parametric t (in distance units, since rdx is normalized)
        // until we cross the next voxel boundary on each axis.
        var tMaxX = if (stepX == 0) INF else (nextBoundary(ox, stepX) - ox) / rdx
        var tMaxY = if (stepY == 0) INF else (nextBoundary(oy, stepY) - oy) / rdy
        var tMaxZ = if (stepZ == 0) INF else (nextBoundary(oz, stepZ) - oz) / rdz

        val tDeltaX = if (stepX == 0) INF else (1f / abs(rdx))
        val tDeltaY = if (stepY == 0) INF else (1f / abs(rdy))
        val tDeltaZ = if (stepZ == 0) INF else (1f / abs(rdz))

        // Safety cap to avoid infinite loops on pathological inputs.
        val maxSteps = (abs(dx) + abs(dy) + abs(dz)).toInt() * 4 + 8

        for (step in 0 until maxSteps) {
            // Already reached target cell — surface is in this cell, no further blocking.
            if (vx == tcX && vy == tcY && vz == tcZ) return true

            // Pick smallest tMax to determine which voxel face we cross next.
            val tNext = minOf(tMaxX, tMaxY, tMaxZ)
            if (tNext > dist) {
                // The next boundary is beyond the segment — ray finished cleanly.
                return true
            }

            val prevX = vx; val prevY = vy; val prevZ = vz
            // Advance the voxel index and tMax for the chosen axis.
            // If multiple axes tie (corner crossing), advance all of them — this
            // corresponds to passing exactly through a corner; we treat it as
            // simultaneously crossing both edges/faces.
            val crossX = tMaxX == tNext
            val crossY = tMaxY == tNext
            val crossZ = tMaxZ == tNext
            if (crossX) { vx += stepX; tMaxX += tDeltaX }
            if (crossY) { vy += stepY; tMaxY += tDeltaY }
            if (crossZ) { vz += stepZ; tMaxZ += tDeltaZ }

            // 1. Horizontal wall edge crossing (same Z layer the ray is currently on).
            //    Use the prev layer's Z if a horizontal move happens while still on prevZ.
            if (crossX || crossY) {
                val checkZ = prevZ
                val mid = wallBlockedBetween(prevX, prevY, vx, vy, checkZ)
                if (mid) return false
            }
            // 2. Vertical floor crossing.
            if (crossZ) {
                if (isFloorBetweenZ(prevX, prevY, prevZ, vz)) return false
            }

            // 3. Cell content opacity for cells we've ENTERED, except source and target cells.
            if ((vx != scX || vy != scY || vz != scZ) &&
                (vx != tcX || vy != tcY || vz != tcZ)) {
                if (isCellOpaque(vx, vy, vz)) return false
            }
        }
        return true
    }

    private fun wallBlockedBetween(ax: Int, ay: Int, bx: Int, by: Int, z: Int): Boolean {
        if (ax == bx && ay == by) return false
        val dx = bx - ax
        val dy = by - ay
        if (dx != 0 && dy != 0) {
            // Diagonal crossing (corner) — block only if both cardinal sub-edges are walled
            val viaX = isWallCardinal(ax, ay, dx, 0, z) ||
                       isWallCardinal(ax + dx, ay, 0, dy, z)
            val viaY = isWallCardinal(ax, ay, 0, dy, z) ||
                       isWallCardinal(ax, ay + dy, dx, 0, z)
            return viaX && viaY
        }
        return isWallCardinal(ax, ay, dx, dy, z)
    }

    private fun isWallCardinal(ax: Int, ay: Int, dx: Int, dy: Int, z: Int): Boolean {
        val (selfSlot, oppSlot) = when {
            dx == 1  && dy == 0 -> TileSlot.WALL_EAST  to TileSlot.WALL_WEST
            dx == -1 && dy == 0 -> TileSlot.WALL_WEST  to TileSlot.WALL_EAST
            dx == 0  && dy == 1 -> TileSlot.WALL_NORTH to TileSlot.WALL_SOUTH
            dx == 0  && dy == -1 -> TileSlot.WALL_SOUTH to TileSlot.WALL_NORTH
            else -> return false
        }
        val self = world.getNode(ax, ay, z)
        if (self != null && self.isWallBlocking(selfSlot)) return true
        val nb = world.getNode(ax + dx, ay + dy, z)
        if (nb != null && nb.isWallBlocking(oppSlot)) return true
        return false
    }

    /** True if a floor blocks the vertical transition between (x,y,az) and (x,y,bz). */
    private fun isFloorBetweenZ(x: Int, y: Int, az: Int, bz: Int): Boolean {
        if (az == bz) return false
        val upperZ = max(az, bz)
        val lowerZ = if (upperZ == az) bz else az
        val upper = world.getNode(x, y, upperZ) ?: return true
        val lower = world.getNode(x, y, lowerZ) ?: return true
        if (!upper.hasFloor) return false
        val hasStairs = upper.hasTile(TileSlot.STAIRS) || lower.hasTile(TileSlot.STAIRS)
        val hasLadder = upper.ladderSlots.isNotEmpty() || lower.ladderSlots.isNotEmpty()
        return !(hasStairs || hasLadder)
    }

    /** True if a cell's contents (props, items with blocksLight, stairs) block light. */
    private fun isCellOpaque(x: Int, y: Int, z: Int): Boolean {
        val node = world.getNode(x, y, z) ?: return true
        // Stairs occupy a solid sloped block in the cell → block horizontal light.
        // Ladders are slim rails and do NOT block light.
        val stairs = node.getTile(TileSlot.STAIRS)
        if (stairs != null && stairs !is com.roguelike.world.LadderTile) return true
        for (item in node.items) {
            val def = ItemCatalog[item.type] ?: continue
            if (def.blocksLight) return true
        }
        for (prop in world.props) {
            val pz = round(prop.z).toInt()
            if (pz != z) continue
            val (hsX, hsY) = prop.rotatedHalfSizes()
            if (abs(prop.x - x) < hsX + 0.5f && abs(prop.y - y) < hsY + 0.5f) return true
        }
        return false
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun parseRgb(hex: String): Triple<Float, Float, Float> {
        val s = hex.removePrefix("#")
        if (s.length < 6) return Triple(1f, 1f, 1f)
        return try {
            Triple(
                s.substring(0, 2).toInt(16) / 255f,
                s.substring(2, 4).toInt(16) / 255f,
                s.substring(4, 6).toInt(16) / 255f
            )
        } catch (_: NumberFormatException) {
            Triple(1f, 1f, 1f)
        }
    }

    companion object {
        /** Maximum search radius (cells) used to find lit items dropped in the world. */
        private const val WORLD_LIGHT_SEARCH_RADIUS = 16

        /**
         * Builds a [SurfaceLighting] for an actor frame: actor inventory lights +
         * world-placed lit items nearby.
         */
        fun build(world: World, actor: Actor): SurfaceLighting {
            LightingDiagnostics.logFrame(world, actor)
            val lights = mutableListOf<LightSource>()
            val acx = round(actor.position.x).toInt().coerceIn(0, world.width - 1)
            val acy = round(actor.position.y).toInt().coerceIn(0, world.height - 1)
            val acz = round(actor.position.z).toInt().coerceIn(0, world.depth - 1)

            // Inventory lights — origin at actor position.
            val f = actor.facingDirection
            val fLen = sqrt((f.x * f.x + f.y * f.y).toDouble()).toFloat()
            val fx = if (fLen > 0f) f.x / fLen else 0f
            val fy = if (fLen > 0f) f.y / fLen else 1f
            for (item in actor.inventory) {
                if (!item.isLit()) continue
                val def = item.definition?.light ?: continue
                lights.add(
                    LightSource(
                        def,
                        actor.position.x, actor.position.y, actor.position.z,
                        acx, acy, acz,
                        fx, fy
                    )
                )
            }

            // World lights — scan a bounded box around the actor.
            val r = WORLD_LIGHT_SEARCH_RADIUS
            val minX = (acx - r).coerceAtLeast(0)
            val maxX = (acx + r).coerceAtMost(world.width - 1)
            val minY = (acy - r).coerceAtLeast(0)
            val maxY = (acy + r).coerceAtMost(world.height - 1)
            val minZ = (acz - r).coerceAtLeast(0)
            val maxZ = (acz + r).coerceAtMost(world.depth - 1)
            for (wz in minZ..maxZ) {
                for (wy in minY..maxY) {
                    for (wx in minX..maxX) {
                        val node = world.getNode(wx, wy, wz) ?: continue
                        if (node.items.isEmpty()) continue
                        for (item in node.items) {
                            if (!item.isLit()) continue
                            val def = item.definition?.light ?: continue
                            val ifx = item.facingX
                            val ify = item.facingY
                            val il = sqrt((ifx * ifx + ify * ify).toDouble()).toFloat()
                            val nfx = if (il > 0f) ifx / il else 0f
                            val nfy = if (il > 0f) ify / il else 1f
                            lights.add(
                                LightSource(
                                    def,
                                    // Cell-centered world coords: cell (wx,wy,wz) is at
                                    // world (wx,wy,wz); place item just above its floor.
                                    wx.toFloat(), wy.toFloat(), wz - 0.1f,
                                    wx, wy, wz,
                                    nfx, nfy
                                )
                            )
                        }
                    }
                }
            }
            return SurfaceLighting(world, lights)
        }
    }
}











