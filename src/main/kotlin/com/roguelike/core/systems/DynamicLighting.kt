package com.roguelike.core.systems

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.PointLight
import com.badlogic.gdx.math.Vector3
import com.roguelike.core.model.Actor
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.LightDef
import com.roguelike.core.model.LightDirection
import com.roguelike.core.model.LightShape
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.isLit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Per-frame GPU-light driver.
 *
 * Builds libGDX [PointLight]/[SpotLight] instances from currently-lit
 * light-source items (carried by an actor or dropped in the world), so that
 * the default shader can apply per-pixel attenuation and cone falloff.
 *
 * Grid-based LOS is still used for *visibility filtering* — the renderer asks
 * [environmentFor] for the [Environment] to use for a given cell, and gets one
 * pre-built environment containing only the lights that actually reach that
 * cell (occluded lights are masked out). That gives us correct shadowing past
 * walls/doors/floors without the cost of custom shadow shaders.
 */
class DynamicLighting private constructor(
    private val world: World,
    /** Visible lights this frame, in stable index order. */
    val lights: List<GpuLight>,
    private val ambient: Color
) {

    /** A built-up gpu light with its world-space position, direction, and grid cell. */
    data class GpuLight(
        val def: LightDef,
        val color: Color,
        /** World-space origin. */
        val pos: Vector3,
        /** Source grid cell — used for LOS queries. */
        val cx: Int, val cy: Int, val cz: Int,
        /** For spot lights — normalized planar direction. */
        val dirX: Float, val dirY: Float, val dirZ: Float
    )

    /**
     * libGDX intensity (a single float). We approximate by scaling the
     * configured [LightDef.intensity] for the simple inverse-square attenuation
     * the default shader uses. Higher values reach further.
     */
    private fun gpuIntensity(def: LightDef): Float {
        // The default shader does color * intensity / dist^2. To get a visible
        // sphere of ~`range` cells we scale intensity by range^2.
        val r = def.range
        return def.intensity * r * r
    }

    /**
     * Cache of visibility bitmasks keyed by surface identifier. We keep separate
     * caches per surface kind so corner cases (a wall face vs the cell's floor)
     * can resolve to different lights.
     */
    private val floorMaskCache = HashMap<Long, Int>()
    private val cellMaskCache  = HashMap<Long, Int>()
    private val wallMaskCache  = HashMap<Long, Int>() // key encodes slot

    /** Map of bitmask → prebuilt [Environment]. */
    private val envCache = HashMap<Int, Environment>()

    /** Diagnostics: have we already logged the "DDA start voxel mismatch" warning this build? */
    private var leakWarned = false

    // ── Public per-surface API ────────────────────────────────────────────

    /** Environment for the floor surface at (x,y,z). */
    fun environmentForFloor(x: Int, y: Int, z: Int): Environment =
        envFor(floorMaskCache, packKey(x, y, z, 0)) { computeMaskMultiSample(floorSamples(x, y, z), x, y, z) }

    /** Environment for the wall surface on edge [slot] of cell (x,y,z). */
    fun environmentForWall(x: Int, y: Int, z: Int, slot: TileSlot): Environment {
        val slotBits = when (slot) {
            TileSlot.WALL_NORTH -> 1
            TileSlot.WALL_SOUTH -> 2
            TileSlot.WALL_EAST  -> 3
            TileSlot.WALL_WEST  -> 4
            else                -> 0
        }
        return envFor(wallMaskCache, packKey(x, y, z, slotBits)) { computeMaskMultiSample(wallSamples(x, y, z, slot), x, y, z) }
    }

    /** Environment for a centered cell surface (items / props / stairs / ladders). */
    fun environmentForCell(x: Int, y: Int, z: Int): Environment =
        envFor(cellMaskCache, packKey(x, y, z, 0)) { computeMaskMultiSample(cellSamples(x, y, z), x, y, z) }

    /** Legacy single-point alias used by the player sphere. Delegates to cell. */
    fun environmentFor(x: Int, y: Int, z: Int): Environment = environmentForCell(x, y, z)

    private inline fun envFor(cache: HashMap<Long, Int>, key: Long, compute: () -> Int): Environment {
        val mask = cache.getOrPut(key, compute)
        return envCache.getOrPut(mask) { buildEnv(mask) }
    }

    // ── Surface sample points (in world space) ────────────────────────────
    //
    // We sample multiple points per surface so a light that can only reach a
    // corner or edge of the surface still marks it as lit. The GPU then handles
    // per-pixel attenuation, so the lit portion fades naturally with distance.
    //
    // *** Coordinate convention ***
    // The rest of the game uses CELL-CENTERED coords: cell (x,y,z) is centered
    // at world (x,y,z) and occupies [x-0.5..x+0.5] × [y-0.5..y+0.5] × [z-0.5..z+0.5].
    // Walls at the cell-(N)/cell-(N+1) border therefore sit at world coord N+0.5.
    // All sample-point formulas below use that convention. The DDA in [rayClear]
    // applies a +0.5 shift on each axis before voxelising so DDA voxel boundaries
    // align with game wall positions.

    private val EPS = 0.02f
    private val HALF = 0.5f

    /** Floor surface = 5 points (4 corners + center) on the floor plane of cell z (world z = z - 0.5). */
    private fun floorSamples(x: Int, y: Int, z: Int): FloatArray {
        val zf = z - HALF + EPS // just above the cell's floor
        return floatArrayOf(
            x - HALF + EPS,     y - HALF + EPS,     zf,
            x + HALF - EPS,     y - HALF + EPS,     zf,
            x - HALF + EPS,     y + HALF - EPS,     zf,
            x + HALF - EPS,     y + HALF - EPS,     zf,
            x.toFloat(),        y.toFloat(),        zf
        )
    }

    /** Wall surface = sample points on BOTH faces of the wall (inward + outward).
     *
     *  A wall is a two-sided surface: the inward face is visible from inside the
     *  owning cell, the outward face from the adjacent cell. We want the wall to
     *  be lit whenever a light can reach EITHER face. The wall lives on the
     *  cell-(N)/cell-(N+1) boundary at world coord N+0.5, so we offset the
     *  samples by ±EPS along the wall normal.
     */
    private fun wallSamples(x: Int, y: Int, z: Int, slot: TileSlot): FloatArray {
        val zBottom = z - HALF + EPS
        val zTop    = z + HALF - EPS
        val zMid    = z.toFloat()
        // Cardinal: the cell-aligned coord pair (a, b) inside the wall plane,
        //           plus the two perpendicular world coords (inner, outer).
        return when (slot) {
            TileSlot.WALL_NORTH -> {
                val inner = y + HALF - EPS   // inside cell (x,y)
                val outer = y + HALF + EPS   // inside cell (x,y+1)
                floatArrayOf(
                    // inward face
                    x - HALF + EPS, inner, zMid,
                    x + HALF - EPS, inner, zMid,
                    x.toFloat(),    inner, zBottom,
                    x.toFloat(),    inner, zTop,
                    x.toFloat(),    inner, zMid,
                    // outward face
                    x - HALF + EPS, outer, zMid,
                    x + HALF - EPS, outer, zMid,
                    x.toFloat(),    outer, zBottom,
                    x.toFloat(),    outer, zTop,
                    x.toFloat(),    outer, zMid
                )
            }
            TileSlot.WALL_SOUTH -> {
                val inner = y - HALF + EPS
                val outer = y - HALF - EPS
                floatArrayOf(
                    x - HALF + EPS, inner, zMid,
                    x + HALF - EPS, inner, zMid,
                    x.toFloat(),    inner, zBottom,
                    x.toFloat(),    inner, zTop,
                    x.toFloat(),    inner, zMid,
                    x - HALF + EPS, outer, zMid,
                    x + HALF - EPS, outer, zMid,
                    x.toFloat(),    outer, zBottom,
                    x.toFloat(),    outer, zTop,
                    x.toFloat(),    outer, zMid
                )
            }
            TileSlot.WALL_EAST -> {
                val inner = x + HALF - EPS
                val outer = x + HALF + EPS
                floatArrayOf(
                    inner, y - HALF + EPS, zMid,
                    inner, y + HALF - EPS, zMid,
                    inner, y.toFloat(),    zBottom,
                    inner, y.toFloat(),    zTop,
                    inner, y.toFloat(),    zMid,
                    outer, y - HALF + EPS, zMid,
                    outer, y + HALF - EPS, zMid,
                    outer, y.toFloat(),    zBottom,
                    outer, y.toFloat(),    zTop,
                    outer, y.toFloat(),    zMid
                )
            }
            TileSlot.WALL_WEST -> {
                val inner = x - HALF + EPS
                val outer = x - HALF - EPS
                floatArrayOf(
                    inner, y - HALF + EPS, zMid,
                    inner, y + HALF - EPS, zMid,
                    inner, y.toFloat(),    zBottom,
                    inner, y.toFloat(),    zTop,
                    inner, y.toFloat(),    zMid,
                    outer, y - HALF + EPS, zMid,
                    outer, y + HALF - EPS, zMid,
                    outer, y.toFloat(),    zBottom,
                    outer, y.toFloat(),    zTop,
                    outer, y.toFloat(),    zMid
                )
            }
            else -> cellSamples(x, y, z)
        }
    }

    /** Centered cell surface = corners + center at mid-height (cell-centered). */
    private fun cellSamples(x: Int, y: Int, z: Int): FloatArray {
        val zMid = z.toFloat()
        return floatArrayOf(
            x - HALF + EPS,     y - HALF + EPS,     zMid,
            x + HALF - EPS,     y - HALF + EPS,     zMid,
            x - HALF + EPS,     y + HALF - EPS,     zMid,
            x + HALF - EPS,     y + HALF - EPS,     zMid,
            x.toFloat(),        y.toFloat(),        zMid
        )
    }

    /**
     * Returns a bitmask of lights that can reach AT LEAST ONE of the given
     * surface sample points. The points array is [x0,y0,z0, x1,y1,z1, ...].
     *
     * The surface's owning cell `(scX,scY,scZ)` is treated as the target cell
     * for the LOS algorithm so the surface's own cell contents never occlude
     * its own light.
     */
    private fun computeMaskMultiSample(points: FloatArray, scX: Int, scY: Int, scZ: Int): Int {
        var m = 0
        for ((i, l) in lights.withIndex()) {
            if (i >= 30) break
            // Same cell as the light → automatically visible.
            if (l.cx == scX && l.cy == scY && l.cz == scZ) { m = m or (1 shl i); continue }
            // Pre-compute cone half-angle cosine for CONE lights. Surfaces
            // outside the cone are excluded BEFORE the LOS ray test, since the
            // default libGDX shader does not support spot lights and we render
            // cones as PointLights — we therefore must enforce the cone shape
            // ourselves in the visibility mask.
            val isCone = l.def.shape == LightShape.CONE
            val coneCos = if (isCone) {
                kotlin.math.cos(Math.toRadians((l.def.coneDegrees / 2.0))).toFloat()
            } else -1f
            var visible = false
            var p = 0
            while (p < points.size) {
                val px = points[p]; val py = points[p + 1]; val pz = points[p + 2]
                p += 3
                if (isCone) {
                    // Vector from light to sample point.
                    val vx = px - l.pos.x
                    val vy = py - l.pos.y
                    val vz = pz - l.pos.z
                    val vlen = sqrt((vx * vx + vy * vy + vz * vz).toDouble()).toFloat()
                    if (vlen > 1e-5f) {
                        val dot = (vx * l.dirX + vy * l.dirY + vz * l.dirZ) / vlen
                        if (dot < coneCos) continue // outside cone — skip this sample
                    }
                }
                if (rayClear(l.pos.x, l.pos.y, l.pos.z, px, py, pz, l.cx, l.cy, l.cz, scX, scY, scZ)) {
                    visible = true
                    break
                }
            }
            if (visible) m = m or (1 shl i)
        }
        return m
    }

    private fun packKey(x: Int, y: Int, z: Int, extra: Int): Long =
        (x.toLong() and 0xFFFF) or
            ((y.toLong() and 0xFFFF) shl 16) or
            ((z.toLong() and 0xFFFF) shl 32) or
            ((extra.toLong() and 0xFF)   shl 48)

    private fun buildEnv(mask: Int): Environment {
        val env = Environment()
        env.set(ColorAttribute(ColorAttribute.AmbientLight, ambient.r, ambient.g, ambient.b, 1f))
        var nPoint = 0
        var nSpot = 0
        for ((i, l) in lights.withIndex()) {
            if ((mask and (1 shl i)) == 0) continue
            val intensity = gpuIntensity(l.def)
            when (l.def.shape) {
                LightShape.CONE -> {
                    // *** Important ***
                    // The libGDX *default* shader (`default.vertex.glsl`) has
                    // NO spot-light loop — only directional + point lights —
                    // so any SpotLight we add to the Environment is silently
                    // ignored on the GPU. (See SpotLight.java header comment:
                    // "the default shader doesn't support spot lights".)
                    //
                    // We therefore render cones as PointLights. The CONE
                    // ANGLE is enforced on the CPU in computeMaskMultiSample:
                    // surfaces outside the cone never receive this light in
                    // their environment mask, so the visible effect is still
                    // a directional cone — just with point-light falloff
                    // inside it instead of spot-style angular falloff.
                    val pl = PointLight().set(l.color, l.pos, intensity)
                    env.add(pl)
                    nPoint++
                    if (LightingDiagnostics.enabled) {
                        val halfAngleDeg = (l.def.coneDegrees / 2f).coerceIn(0f, 180f)
                        println("[LIGHTLOG]   +PointLight(forCone) idx=$i color=(%.2f,%.2f,%.2f) pos=(%.2f,%.2f,%.2f) dir=(%.2f,%.2f,%.2f) intensity=%.2f halfAngle=%.1f° range=%.1f"
                            .format(l.color.r, l.color.g, l.color.b,
                                l.pos.x, l.pos.y, l.pos.z,
                                l.dirX, l.dirY, l.dirZ,
                                intensity, halfAngleDeg, l.def.range))
                    }
                }
                LightShape.SPHERE -> {
                    val pl = PointLight().set(l.color, l.pos, intensity)
                    env.add(pl)
                    nPoint++
                    if (LightingDiagnostics.enabled) {
                        println("[LIGHTLOG]   +PointLight idx=$i color=(%.2f,%.2f,%.2f) pos=(%.2f,%.2f,%.2f) intensity=%.2f range=%.1f"
                            .format(l.color.r, l.color.g, l.color.b,
                                l.pos.x, l.pos.y, l.pos.z,
                                intensity, l.def.range))
                    }
                }
            }
        }
        if (LightingDiagnostics.enabled) {
            println("[LIGHTLOG] buildEnv: mask=0x%x -> %d point + %d spot lights".format(mask, nPoint, nSpot))
        }
        return env
    }

    /** Continuous 3D DDA — same algorithm as in [SurfaceLighting].
     *
     *  The DDA voxelises with `floor`, so we shift the origin and target by
     *  +0.5 on each axis to align voxel boundaries with the game's
     *  cell-centered convention (wall between cell N and cell N+1 sits at
     *  world coord N+0.5, which becomes the integer voxel boundary N+1 after
     *  the shift). This makes DDA voxel index == game cell index, so the
     *  wall/floor/cell-content queries (which use cell indices) line up.
     */
    private fun rayClear(
        oxIn: Float, oyIn: Float, ozIn: Float,
        txIn: Float, tyIn: Float, tzIn: Float,
        scX: Int, scY: Int, scZ: Int,
        tcX: Int, tcY: Int, tcZ: Int
    ): Boolean {
        // Shift into DDA frame so voxel boundaries land on game-wall world coords.
        val ox = oxIn + 0.5f; val oy = oyIn + 0.5f; val oz = ozIn + 0.5f
        val tx = txIn + 0.5f; val ty = tyIn + 0.5f; val tz = tzIn + 0.5f

        // ── Diagnostic: when enabled, warn once per build() if the light's
        //    DDA start voxel differs from its declared source cell. With the
        //    +0.5 shift these should now agree for inventory lights, but
        //    we keep the check to catch future regressions.
        if (LightingDiagnostics.enabled && !leakWarned) {
            val voxX = kotlin.math.floor(ox).toInt()
            val voxY = kotlin.math.floor(oy).toInt()
            val voxZ = kotlin.math.floor(oz).toInt()
            if (voxX != scX || voxY != scY || voxZ != scZ) {
                println("[LIGHTLOG] rayClear: light origin(world)=(% .3f,% .3f,% .3f) ".format(oxIn, oyIn, ozIn) +
                    "stored cell=($scX,$scY,$scZ) vs DDA start vox(shifted)=($voxX,$voxY,$voxZ) " +
                    "→ DDA / cell index disagree even AFTER +0.5 shift; check light placement.")
                leakWarned = true
            }
        }
        val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
        val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (dist < 1e-5f) return true
        val rdx = dx / dist; val rdy = dy / dist; val rdz = dz / dist

        var vx = kotlin.math.floor(ox).toInt()
        var vy = kotlin.math.floor(oy).toInt()
        var vz = kotlin.math.floor(oz).toInt()
        val stepX = if (rdx > 0) 1 else if (rdx < 0) -1 else 0
        val stepY = if (rdy > 0) 1 else if (rdy < 0) -1 else 0
        val stepZ = if (rdz > 0) 1 else if (rdz < 0) -1 else 0

        val INF = Float.POSITIVE_INFINITY
        fun nextBoundary(c: Float, step: Int): Float = when {
            step > 0 -> kotlin.math.floor(c).toFloat() + 1f
            step < 0 -> {
                val f = kotlin.math.floor(c).toFloat()
                if (f == c) f - 1f else f
            }
            else -> 0f
        }
        var tMaxX = if (stepX == 0) INF else (nextBoundary(ox, stepX) - ox) / rdx
        var tMaxY = if (stepY == 0) INF else (nextBoundary(oy, stepY) - oy) / rdy
        var tMaxZ = if (stepZ == 0) INF else (nextBoundary(oz, stepZ) - oz) / rdz
        val tDeltaX = if (stepX == 0) INF else 1f / abs(rdx)
        val tDeltaY = if (stepY == 0) INF else 1f / abs(rdy)
        val tDeltaZ = if (stepZ == 0) INF else 1f / abs(rdz)

        val maxSteps = (abs(dx) + abs(dy) + abs(dz)).toInt() * 4 + 8
        for (step in 0 until maxSteps) {
            if (vx == tcX && vy == tcY && vz == tcZ) return true
            val tNext = minOf(tMaxX, tMaxY, tMaxZ)
            if (tNext > dist) return true

            val prevX = vx; val prevY = vy; val prevZ = vz
            val crossX = tMaxX == tNext
            val crossY = tMaxY == tNext
            val crossZ = tMaxZ == tNext
            if (crossX) { vx += stepX; tMaxX += tDeltaX }
            if (crossY) { vy += stepY; tMaxY += tDeltaY }
            if (crossZ) { vz += stepZ; tMaxZ += tDeltaZ }

            if (crossX || crossY) {
                if (wallBlockedBetween(prevX, prevY, vx, vy, prevZ)) return false
            }
            if (crossZ) {
                if (floorBetween(prevX, prevY, prevZ, vz)) return false
            }
            // Cell content blocks except source/target.
            if ((vx != scX || vy != scY || vz != scZ) &&
                (vx != tcX || vy != tcY || vz != tcZ)) {
                if (cellOpaque(vx, vy, vz)) return false
            }
        }
        return true
    }

    private fun wallBlockedBetween(ax: Int, ay: Int, bx: Int, by: Int, z: Int): Boolean {
        if (ax == bx && ay == by) return false
        val dx = bx - ax; val dy = by - ay
        if (dx != 0 && dy != 0) {
            val viaX = wallCardinal(ax, ay, dx, 0, z) || wallCardinal(ax + dx, ay, 0, dy, z)
            val viaY = wallCardinal(ax, ay, 0, dy, z) || wallCardinal(ax, ay + dy, dx, 0, z)
            return viaX && viaY
        }
        return wallCardinal(ax, ay, dx, dy, z)
    }

    private fun wallCardinal(ax: Int, ay: Int, dx: Int, dy: Int, z: Int): Boolean {
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

    private fun floorBetween(x: Int, y: Int, az: Int, bz: Int): Boolean {
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

    private fun cellOpaque(x: Int, y: Int, z: Int): Boolean {
        val node = world.getNode(x, y, z) ?: return true
        // A stairs tile is a solid sloped block — block horizontal light.
        // (Ladders are thin/rail-like and intentionally NOT treated as opaque.)
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

    companion object {
        /** Search radius (in cells) for world-placed lit items around the actor. */
        private const val WORLD_LIGHT_SEARCH_RADIUS = 16

        /** Default ambient — keep very dark so lights actually drive the picture. */
        private val DEFAULT_AMBIENT = Color(0.02f, 0.02f, 0.02f, 1f)

        /**
         * Builds a [DynamicLighting] for the given frame.
         * - Inventory lights are placed at the actor's world position.
         * - World-placed lit items within a small radius are also included.
         */
        fun build(world: World, actor: Actor, ambient: Color = DEFAULT_AMBIENT): DynamicLighting {
            // Diagnostic dump (no-op unless -Drogue.lightlog=1).
            LightingDiagnostics.logFrame(world, actor)
            val lights = mutableListOf<GpuLight>()
            val acx = round(actor.position.x).toInt().coerceIn(0, world.width - 1)
            val acy = round(actor.position.y).toInt().coerceIn(0, world.height - 1)
            val acz = round(actor.position.z).toInt().coerceIn(0, world.depth - 1)

            // 1. Inventory lights.
            val f = actor.facingDirection
            val fLen = sqrt((f.x * f.x + f.y * f.y).toDouble()).toFloat()
            val fx = if (fLen > 0f) f.x / fLen else 0f
            val fy = if (fLen > 0f) f.y / fLen else 1f
            for (item in actor.inventory) {
                if (!item.isLit()) continue
                val def = item.definition?.light ?: continue
                val gl = GpuLight(
                    def = def,
                    color = parseColor(def.colorHex),
                    pos = Vector3(actor.position.x, actor.position.y, actor.position.z),
                    cx = acx, cy = acy, cz = acz,
                    dirX = fx, dirY = fy, dirZ = 0f
                )
                lights.add(gl)
                if (LightingDiagnostics.enabled) {
                    println("[LIGHTLOG] inv-light item=${item.type} shape=${def.shape} cone=${def.coneDegrees}° " +
                        "range=${def.range} intensity=${def.intensity} colorHex='${def.colorHex}' " +
                        ("-> parsedColor=(%.2f,%.2f,%.2f,%.2f) pos=(%.2f,%.2f,%.2f) cell=($acx,$acy,$acz) " +
                            "dir=(%.2f,%.2f,%.2f) actorFacing=(%.3f,%.3f) fLen=%.3f").format(
                            gl.color.r, gl.color.g, gl.color.b, gl.color.a,
                            gl.pos.x, gl.pos.y, gl.pos.z, gl.dirX, gl.dirY, gl.dirZ,
                            f.x, f.y, fLen))
                }
            }

            // 2. World-placed lit items nearby.
            val r = WORLD_LIGHT_SEARCH_RADIUS
            val minX = (acx - r).coerceAtLeast(0)
            val maxX = (acx + r).coerceAtMost(world.width - 1)
            val minY = (acy - r).coerceAtLeast(0)
            val maxY = (acy + r).coerceAtMost(world.height - 1)
            val minZ = (acz - r).coerceAtLeast(0)
            val maxZ = (acz + r).coerceAtMost(world.depth - 1)
            for (wz in minZ..maxZ) for (wy in minY..maxY) for (wx in minX..maxX) {
                val node = world.getNode(wx, wy, wz) ?: continue
                if (node.items.isEmpty()) continue
                for (item in node.items) {
                    if (!item.isLit()) continue
                    val def = item.definition?.light ?: continue
                    val ifx = item.facingX; val ify = item.facingY
                    val il = sqrt((ifx * ifx + ify * ify).toDouble()).toFloat()
                    val nfx = if (il > 0f) ifx / il else 0f
                    val nfy = if (il > 0f) ify / il else 1f
                    val gl = GpuLight(
                        def = def,
                        color = parseColor(def.colorHex),
                        // Cell-centered world coords: cell (wx,wy,wz) is centered
                        // at world (wx,wy,wz). Place item slightly above the floor
                        // of the cell (floor sits at wz - 0.5).
                        pos = Vector3(wx.toFloat(), wy.toFloat(), wz - 0.1f),
                        cx = wx, cy = wy, cz = wz,
                        dirX = nfx, dirY = nfy, dirZ = 0f
                    )
                    lights.add(gl)
                    if (LightingDiagnostics.enabled) {
                        println("[LIGHTLOG] world-light item=${item.type} shape=${def.shape} cone=${def.coneDegrees}° " +
                            "range=${def.range} intensity=${def.intensity} colorHex='${def.colorHex}' " +
                            ("-> parsedColor=(%.2f,%.2f,%.2f,%.2f) pos=(%.2f,%.2f,%.2f) cell=($wx,$wy,$wz) " +
                                "dir=(%.2f,%.2f,%.2f) itemFacing=(%.3f,%.3f)").format(
                                gl.color.r, gl.color.g, gl.color.b, gl.color.a,
                                gl.pos.x, gl.pos.y, gl.pos.z, gl.dirX, gl.dirY, gl.dirZ,
                                ifx, ify))
                    }
                }
            }

            if (LightingDiagnostics.enabled) {
                val cone = lights.count { it.def.shape == LightShape.CONE }
                val sphere = lights.count { it.def.shape == LightShape.SPHERE }
                println("[LIGHTLOG] DynamicLighting.build complete: ${lights.size} lights ($cone cone / $sphere sphere) actor=(%.2f,%.2f,%.2f) cell=($acx,$acy,$acz)"
                    .format(actor.position.x, actor.position.y, actor.position.z))
            }
            return DynamicLighting(world, lights, ambient)
        }

        private fun parseColor(hex: String): Color {
            return try { Color.valueOf(hex) } catch (_: Exception) { Color.WHITE }
        }
    }
}









