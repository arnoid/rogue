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
import com.roguelike.core.model.WorldNode
import com.roguelike.core.model.isLit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Per-cell illumination map for a single Z-slice.
 *
 * Pure data: stores additive RGB intensity per cell (0..1+).
 * Higher values mean more light.
 */
class LightMap(val width: Int, val height: Int, val z: Int) {
    private val r = FloatArray(width * height)
    private val g = FloatArray(width * height)
    private val b = FloatArray(width * height)

    private fun idx(x: Int, y: Int) = y * width + x

    fun inBounds(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height

    fun getR(x: Int, y: Int): Float = if (inBounds(x, y)) r[idx(x, y)] else 0f
    fun getG(x: Int, y: Int): Float = if (inBounds(x, y)) g[idx(x, y)] else 0f
    fun getB(x: Int, y: Int): Float = if (inBounds(x, y)) b[idx(x, y)] else 0f

    /** Brightness 0..1 (clamped) suitable for color tinting. */
    fun brightness(x: Int, y: Int): Float {
        if (!inBounds(x, y)) return 0f
        val rr = r[idx(x, y)]
        val gg = g[idx(x, y)]
        val bb = b[idx(x, y)]
        return max(rr, max(gg, bb)).coerceIn(0f, 1f)
    }

    fun isLit(x: Int, y: Int): Boolean = brightness(x, y) > 0.001f

    fun add(x: Int, y: Int, rr: Float, gg: Float, bb: Float) {
        if (!inBounds(x, y)) return
        val i = idx(x, y)
        r[i] += rr
        g[i] += gg
        b[i] += bb
    }

    fun clear() {
        r.fill(0f); g.fill(0f); b.fill(0f)
    }

    /** Returns the tint color (r,g,b clamped) for the given cell. */
    fun tint(x: Int, y: Int, out: FloatArray = FloatArray(3)): FloatArray {
        if (!inBounds(x, y)) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return out }
        val i = idx(x, y)
        out[0] = r[i].coerceIn(0f, 1f)
        out[1] = g[i].coerceIn(0f, 1f)
        out[2] = b[i].coerceIn(0f, 1f)
        return out
    }
}

/**
 * Multi-Z illumination map. Each Z slice is a [LightMap], allocated lazily on
 * first access so unused layers cost nothing.
 *
 * The "source" Z (where the actor is standing) is exposed via [sourceZ] for
 * renderers that want to highlight the player's plane.
 */
class LightMap3D(val width: Int, val height: Int, val depth: Int, val sourceZ: Int) {
    private val slices = HashMap<Int, LightMap>()

    /** Returns the slice for layer [z], creating an empty one if needed. */
    fun slice(z: Int): LightMap {
        if (z !in 0 until depth) return EMPTY
        return slices.getOrPut(z) { LightMap(width, height, z) }
    }

    /** Returns the slice for [z] or null if no light was written to it. */
    fun sliceOrNull(z: Int): LightMap? = slices[z]

    fun isLit(x: Int, y: Int, z: Int): Boolean =
        slices[z]?.isLit(x, y) == true

    fun brightness(x: Int, y: Int, z: Int): Float =
        slices[z]?.brightness(x, y) ?: 0f

    fun tint(x: Int, y: Int, z: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val s = slices[z]
        if (s == null) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return out }
        return s.tint(x, y, out)
    }

    fun add(x: Int, y: Int, z: Int, r: Float, g: Float, b: Float) {
        if (z !in 0 until depth) return
        slice(z).add(x, y, r, g, b)
    }

    companion object {
        /** Sentinel empty slice returned for out-of-bounds Z queries. */
        private val EMPTY = LightMap(0, 0, -1)
    }
}

/**
 * Computes a per-frame [LightMap3D] for an actor's surroundings by iterating
 * each lit light-source item in the actor's inventory.
 *
 * Pure Kotlin / no LibGDX dependency: testable in isolation.
 *
 * Lighting rules implemented:
 *  1. Only items whose catalog entry has a [LightDef] AND the item carries the
 *     `light_source_lit` tag emit light.
 *  2. Light originates from the actor's grid cell at the actor's Z.
 *  3. Spheres illuminate cells within `range` (3D Euclidean) and with
 *     unobstructed grid LOS.
 *  4. Cones additionally require the cell's normalized direction vector to fall
 *     within `coneDegrees / 2` of the actor's [Actor.facingDirection].
 *     Cones are planar by default (facing has z=0); cells above/below the
 *     player are excluded from the cone unless the player is actually facing
 *     up/down.
 *  5. Walls/doors (per-edge), and any cell whose tiles or items mark themselves
 *     as light-blocking, block the LOS ray.
 *  6. Floors block vertical light unless the upper cell has a STAIRS tile or a
 *     ladder is present — letting light fall down stairwells/ladder shafts.
 *  7. Distance falloff is linear (1 - d/range), clamped to 0..1, multiplied by
 *     [LightDef.intensity].
 */
object LightingSystem {

    /** Maximum search radius (cells) used to find lit items dropped in the world. */
    private const val WORLD_LIGHT_SEARCH_RADIUS = 16

    /**
     * Computes a [LightMap3D] for the actor's neighborhood across all Z layers
     * within range, given the actor's inventory.
     *
     * Also accumulates light from any lit light-source items that have been
     * dropped onto world nodes nearby (so a candle the player set on the floor
     * keeps lighting up the area).
     */
    fun compute(world: World, actor: Actor): LightMap3D {
        val z = round(actor.position.z).toInt().coerceIn(0, world.depth - 1)
        return computeAt(world, actor.position.x, actor.position.y, z, actor.facingDirection, actor.inventory)
    }

    /** Lower-level entry point usable from tests. */
    fun computeAt(
        world: World,
        ax: Float, ay: Float, z: Int,
        facing: Vec3,
        inventory: Collection<Item>
    ): LightMap3D {
        val map = LightMap3D(world.width, world.height, world.depth, sourceZ = z)
        val sx = round(ax).toInt().coerceIn(0, world.width - 1)
        val sy = round(ay).toInt().coerceIn(0, world.height - 1)

        // 1. Inventory lights — emit from the actor's position using the actor's facing.
        for (item in inventory) {
            if (!item.isLit()) continue
            val light = item.definition?.light ?: continue
            applyLight(world, map, sx, sy, z, facing, light)
        }

        // 2. World lights — scan nodes for lit items dropped on the floor and
        //    emit using each item's stored facing. Capped by a search radius so
        //    arbitrarily large worlds don't pay per-frame O(N).
        val searchRadius = WORLD_LIGHT_SEARCH_RADIUS
        val wlMinX = (sx - searchRadius).coerceAtLeast(0)
        val wlMaxX = (sx + searchRadius).coerceAtMost(world.width - 1)
        val wlMinY = (sy - searchRadius).coerceAtLeast(0)
        val wlMaxY = (sy + searchRadius).coerceAtMost(world.height - 1)
        val wlMinZ = (z - searchRadius).coerceAtLeast(0)
        val wlMaxZ = (z + searchRadius).coerceAtMost(world.depth - 1)
        val itemFacing = Vec3()
        for (wz in wlMinZ..wlMaxZ) {
            for (wy in wlMinY..wlMaxY) {
                for (wx in wlMinX..wlMaxX) {
                    val node = world.getNode(wx, wy, wz) ?: continue
                    if (node.items.isEmpty()) continue
                    for (item in node.items) {
                        if (!item.isLit()) continue
                        val light = item.definition?.light ?: continue
                        itemFacing.set(item.facingX, item.facingY, 0f)
                        applyLight(world, map, wx, wy, wz, itemFacing, light)
                    }
                }
            }
        }
        return map
    }

    /** Public for tests: apply one light to a 3D map. */
    fun applyLight(
        world: World,
        map: LightMap3D,
        sx: Int, sy: Int, sz: Int,
        facing: Vec3,
        light: LightDef
    ) {
        val (lr, lg, lb) = parseRgb(light.colorHex)
        val intensity = light.intensity
        val range = light.range
        val rangeCeil = kotlin.math.ceil(range).toInt()
        // Half-angle in radians for cone tests
        val halfAngleRad = Math.toRadians((light.coneDegrees / 2.0).coerceIn(0.0, 180.0))
        val cosHalf = cos(halfAngleRad).toFloat()

        // Pre-normalize facing dir (full 3D for cone math)
        var fx = facing.x
        var fy = facing.y
        var fz = facing.z
        val flen = sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
        if (flen > 0f) { fx /= flen; fy /= flen; fz /= flen } else { fx = 0f; fy = 1f; fz = 0f }

        val minX = (sx - rangeCeil).coerceAtLeast(0)
        val maxX = (sx + rangeCeil).coerceAtMost(world.width - 1)
        val minY = (sy - rangeCeil).coerceAtLeast(0)
        val maxY = (sy + rangeCeil).coerceAtMost(world.height - 1)
        val minZ = (sz - rangeCeil).coerceAtLeast(0)
        val maxZ = (sz + rangeCeil).coerceAtMost(world.depth - 1)

        // Always light the source cell itself.
        addLight(map, sx, sy, sz, lr, lg, lb, intensity)

        for (tz in minZ..maxZ) {
            for (ty in minY..maxY) {
                for (tx in minX..maxX) {
                    if (tx == sx && ty == sy && tz == sz) continue
                    val dx = (tx - sx).toFloat()
                    val dy = (ty - sy).toFloat()
                    val dz = (tz - sz).toFloat()
                    val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    if (dist > range) continue

                    if (light.shape == LightShape.CONE && light.direction == LightDirection.OWNER_FACING) {
                        val nx = dx / dist
                        val ny = dy / dist
                        val nz = dz / dist
                        val dot = nx * fx + ny * fy + nz * fz
                        if (dot < cosHalf) continue
                    }

                    if (!losClear3D(world, sx, sy, sz, tx, ty, tz)) continue

                    val falloff = (1f - dist / range).coerceIn(0f, 1f)
                    val mul = falloff * intensity
                    addLight(map, tx, ty, tz, lr, lg, lb, mul)
                }
            }
        }
    }

    private fun addLight(map: LightMap3D, x: Int, y: Int, z: Int, r: Float, g: Float, b: Float, mul: Float) {
        map.add(x, y, z, r * mul, g * mul, b * mul)
    }

    // ── 2D LOS (kept for tests / planar consumers) ───────────────────────────

    /**
     * Planar LOS using Bresenham within a single Z layer. Delegates to the 3D
     * variant.
     */
    fun losClear(world: World, sx: Int, sy: Int, tx: Int, ty: Int, z: Int): Boolean =
        losClear3D(world, sx, sy, z, tx, ty, z)

    // ── 3D LOS ───────────────────────────────────────────────────────────────

    /**
     * 3D supercover line check from `(sx,sy,sz)` to `(tx,ty,tz)`.
     *
     * Uses an integer-stepped sampler along the dominant axis. Between every
     * consecutive pair of cells `(prev) -> (cur)` we check:
     *  - Same-Z horizontal moves use [isWallBetween] (walls + closed doors).
     *  - Z-only moves are blocked if the upper cell has a FLOOR tile, UNLESS a
     *    STAIRS tile or any ladder slot exists on either the upper or lower
     *    cell (stairwells/ladder shafts let light through).
     *  - Mixed moves (both horizontal and vertical step) check the two
     *    sub-edges (horizontal-first via the intermediate cell, vertical-first
     *    via the other intermediate) and pass only if at least one path is
     *    clear.
     *  - Mid-line cells whose contents block light also block the ray, except
     *    the target cell itself (so a wall/item *is* lit).
     */
    fun losClear3D(
        world: World,
        sx: Int, sy: Int, sz: Int,
        tx: Int, ty: Int, tz: Int
    ): Boolean {
        if (sx == tx && sy == ty && sz == tz) return true

        val dx = tx - sx
        val dy = ty - sy
        val dz = tz - sz
        val adx = abs(dx); val ady = abs(dy); val adz = abs(dz)
        val steps = maxOf(adx, ady, adz)
        if (steps == 0) return true

        val sxf = sx + 0.5f
        val syf = sy + 0.5f
        val szf = sz + 0.5f
        val stepXf = dx.toFloat() / steps
        val stepYf = dy.toFloat() / steps
        val stepZf = dz.toFloat() / steps

        var prevX = sx; var prevY = sy; var prevZ = sz
        for (i in 1..steps) {
            val cx = (sxf + stepXf * i)
            val cy = (syf + stepYf * i)
            val cz = (szf + stepZf * i)
            val curX = kotlin.math.floor(cx).toInt()
            val curY = kotlin.math.floor(cy).toInt()
            val curZ = kotlin.math.floor(cz).toInt()
            if (curX == prevX && curY == prevY && curZ == prevZ) continue

            if (!edgeClear(world, prevX, prevY, prevZ, curX, curY, curZ)) return false

            // Reached target — don't block on target contents.
            if (curX == tx && curY == ty && curZ == tz) return true

            // Mid-line cell contents may block.
            if (isCellOpaque(world, curX, curY, curZ)) return false

            prevX = curX; prevY = curY; prevZ = curZ
        }
        return true
    }

    /**
     * True if the transition from cell `a` to cell `b` (adjacent in 3D — at
     * most 1 step on each axis) is not blocked by walls or floors.
     */
    private fun edgeClear(
        world: World,
        ax: Int, ay: Int, az: Int,
        bx: Int, by: Int, bz: Int
    ): Boolean {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val horizontal = (dx != 0 || dy != 0)
        val vertical = dz != 0

        if (horizontal && !vertical) {
            return !isWallBetween(world, ax, ay, bx, by, az)
        }
        if (vertical && !horizontal) {
            return !isFloorBetweenZ(world, ax, ay, az, bz)
        }
        // Mixed: succeed if either L-path is clear.
        // Path 1: horizontal then vertical via (bx, by, az)
        val viaHV = !isWallBetween(world, ax, ay, bx, by, az) &&
                    !isFloorBetweenZ(world, bx, by, az, bz)
        if (viaHV) return true
        // Path 2: vertical then horizontal via (ax, ay, bz)
        val viaVH = !isFloorBetweenZ(world, ax, ay, az, bz) &&
                    !isWallBetween(world, ax, ay, bx, by, bz)
        return viaVH
    }

    /**
     * True if a floor (or world boundary) blocks vertical light between
     * cells `(x, y, az)` and `(x, y, bz)` (abs(bz-az) == 1 typically).
     *
     * Floors live on a cell with the FLOOR tile slot. The floor sits at the
     * bottom of its cell, so traveling from `z` to `z+1` would cross the floor
     * of the upper cell (`z+1`). Stairs or ladders on either side bypass this.
     */
    fun isFloorBetweenZ(world: World, x: Int, y: Int, az: Int, bz: Int): Boolean {
        if (az == bz) return false
        val upperZ = maxOf(az, bz)
        val lowerZ = minOf(az, bz)
        val upper = world.getNode(x, y, upperZ) ?: return true
        val lower = world.getNode(x, y, lowerZ) ?: return true

        // Floors block, unless a stair or ladder pierces the boundary.
        if (!upper.hasFloor) return false
        val hasStairs = upper.hasTile(TileSlot.STAIRS) || lower.hasTile(TileSlot.STAIRS)
        val hasLadder = upper.ladderSlots.isNotEmpty() || lower.ladderSlots.isNotEmpty()
        return !(hasStairs || hasLadder)
    }

    /**
     * Returns true if a wall (closed door = blocking; open door = not) sits on
     * the edge between adjacent cells `(ax,ay)` and `(bx,by)` on layer [z].
     *
     * Diagonal moves are decomposed into the two cardinal half-edges to ensure
     * corners formed by perpendicular walls also block.
     */
    fun isWallBetween(world: World, ax: Int, ay: Int, bx: Int, by: Int, z: Int): Boolean {
        if (ax == bx && ay == by) return false
        val dx = bx - ax
        val dy = by - ay
        if (dx != 0 && dy != 0) {
            val viaX = isWallCardinal(world, ax, ay, dx, 0, z) ||
                       isWallCardinal(world, ax + dx, ay, 0, dy, z)
            val viaY = isWallCardinal(world, ax, ay, 0, dy, z) ||
                       isWallCardinal(world, ax, ay + dy, dx, 0, z)
            return viaX && viaY
        }
        return isWallCardinal(world, ax, ay, dx, dy, z)
    }

    /** Cardinal-only check. dx/dy must be one of (±1,0) or (0,±1). */
    private fun isWallCardinal(world: World, ax: Int, ay: Int, dx: Int, dy: Int, z: Int): Boolean {
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

    /** True if the cell's contents (items, props at this cell) block light. */
    fun isCellOpaque(world: World, x: Int, y: Int, z: Int): Boolean {
        val node = world.getNode(x, y, z) ?: return true // out-of-bounds = blocked
        // Items whose catalog entry says blocksLight=true block the ray.
        for (item in node.items) {
            val def = ItemCatalog[item.type] ?: continue
            if (def.blocksLight) return true
        }
        // Props occupying this cell (within 0.5f of cell center on x/y) also block.
        for (prop in world.props) {
            val pz = round(prop.z).toInt()
            if (pz != z) continue
            val (hsX, hsY) = prop.rotatedHalfSizes()
            if (kotlin.math.abs(prop.x - x) < hsX + 0.5f &&
                kotlin.math.abs(prop.y - y) < hsY + 0.5f) {
                return true
            }
        }
        return false
    }

    /** Decode an "rrggbbaa" hex color into floats (0..1). Alpha ignored. */
    private fun parseRgb(hex: String): Triple<Float, Float, Float> {
        val cleaned = hex.removePrefix("#")
        if (cleaned.length < 6) return Triple(1f, 1f, 1f)
        return try {
            val r = cleaned.substring(0, 2).toInt(16) / 255f
            val g = cleaned.substring(2, 4).toInt(16) / 255f
            val b = cleaned.substring(4, 6).toInt(16) / 255f
            Triple(r, g, b)
        } catch (_: NumberFormatException) {
            Triple(1f, 1f, 1f)
        }
    }
}

