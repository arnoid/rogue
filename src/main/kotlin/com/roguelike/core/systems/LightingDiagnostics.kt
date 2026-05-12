package com.roguelike.core.systems

import com.roguelike.core.model.Actor
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.World
import com.roguelike.core.model.isLit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Opt-in lighting diagnostics.
 *
 * Enable by launching the JVM with `-Drogue.lightlog=1` (or any non-empty value).
 *
 * The diagnostics are designed to surface the most common cause of "light
 * bleeding through walls" bugs:
 *
 *   The game's coordinate convention is **cell-centered**: cell N is centered
 *   at world coord N and occupies [N-0.5 .. N+0.5). Movement, walls, doors
 *   and rounding (`round(position)`) all use this convention.
 *
 *   The DDA ray-march in [DynamicLighting.rayClear] / [SurfaceLighting.rayClear]
 *   voxelises with `floor(coord)`. That implicitly treats cell N as
 *   [N .. N+1), i.e. it's offset by **+0.5 cells** relative to game cells.
 *
 * When the player stands at e.g. (4.4, 4.0) the game considers them inside
 * cell (4,4), but the DDA's starting voxel is `floor(4.4)=4, floor(4.0)=4`
 * which corresponds to the world box [4..5)×[4..5) — straddling game cells
 * (4,4) (right half) and (5,4) (left half). Walls at the cell-(4)/cell-(5)
 * border live at world x=4.5 and the DDA, which only checks integer
 * boundaries (x=5, x=6, ...), never sees them — so light leaks straight
 * through.
 *
 * The logger below prints a per-frame summary when:
 *  - the player has at least one lit light source, AND
 *  - the player position is NOT exactly on a cell center (|pos - round(pos)| > 0.001),
 *    which is precisely when wall-collision sliding happens.
 *
 * Logs include both the game's cell index (`round`) and the DDA voxel index
 * (`floor`) so you can immediately see when they disagree.
 */
object LightingDiagnostics {

    /**
     * Enabled when the system property `rogue.lightlog` is set to anything
     * non-empty at JVM start. Can ALSO be toggled at runtime via
     * [setEnabled] (e.g. bound to a key press) so the user doesn't have to
     * restart the game to capture diagnostics.
     */
    @JvmStatic
    @Volatile
    var enabled: Boolean = System.getProperty("rogue.lightlog")?.isNotEmpty() == true
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        println("[LIGHTLOG] diagnostics ${if (value) "ENABLED" else "disabled"}")
    }

    fun toggle() = setEnabled(!enabled)

    private const val TAG = "LIGHTLOG"
    private var lastDumpNanos = 0L
    private const val MIN_INTERVAL_NS = 250_000_000L // 4 Hz max

    fun logFrame(world: World, actor: Actor) {
        if (!enabled) return
        // Throttle so we don't spam the console.
        val now = System.nanoTime()
        if (now - lastDumpNanos < MIN_INTERVAL_NS) return

        val hasLit = actor.inventory.any { it.isLit() && it.definition?.light != null }
        if (!hasLit) return

        val px = actor.position.x; val py = actor.position.y; val pz = actor.position.z
        val cellX = round(px).toInt(); val cellY = round(py).toInt(); val cellZ = round(pz).toInt()
        val voxX = floor(px).toInt();  val voxY = floor(py).toInt();  val voxZ = floor(pz).toInt()
        val offX = px - cellX; val offY = py - cellY

        val touchingWall = abs(offX) > 0.001f || abs(offY) > 0.001f
        val mismatch = (cellX != voxX) || (cellY != voxY) || (cellZ != voxZ)
        // We now ALWAYS dump if a lit item is present, so issues like
        // "candle is lit but no light renders" are visible regardless of
        // whether the player is touching a wall.
        @Suppress("UNUSED_VARIABLE") val _t = touchingWall

        lastDumpNanos = now

        println("[$TAG] ── frame dump ──────────────────────────────────────────────")
        println("[$TAG] player pos=(% .4f, % .4f, % .4f)".format(px, py, pz))
        val f = actor.facingDirection
        val fLen = kotlin.math.sqrt((f.x * f.x + f.y * f.y).toDouble()).toFloat()
        println("[$TAG] player facingDirection=(% .4f, % .4f) len=%.4f".format(f.x, f.y, fLen) +
            (if (fLen < 1e-4f) "  <<< ZERO LENGTH — spotlights will fall back to (0,1)" else ""))
        println("[$TAG]   game cell  (round) = ($cellX, $cellY, $cellZ)")
        // NOTE: It is normal and expected that floor(pos) != round(pos) for any
        // sub-cell position; the DDA in rayClear applies a +0.5 shift internally
        // so the two views are reconciled. We display the raw floor() value for
        // reference only.
        println("[$TAG]   raw floor(pos)     = ($voxX, $voxY, $voxZ)" +
            if (mismatch) "  (info: differs from round; DDA applies +0.5 shift, this is fine)" else "")
        println("[$TAG]   sub-cell offset    = (% .4f, % .4f)".format(offX, offY))

        // Walls of the current game cell — these are the ones the player is
        // sliding against. If the DDA mismatches, light may pass through them.
        val node = world.getNode(cellX, cellY, cellZ)
        if (node != null) {
            val w = listOfNotNull(
                if (node.isWallBlocking(TileSlot.WALL_NORTH)) "N" else null,
                if (node.isWallBlocking(TileSlot.WALL_SOUTH)) "S" else null,
                if (node.isWallBlocking(TileSlot.WALL_EAST))  "E" else null,
                if (node.isWallBlocking(TileSlot.WALL_WEST))  "W" else null
            )
            println("[$TAG]   walls on player cell ($cellX,$cellY,$cellZ): " +
                if (w.isEmpty()) "(none)" else w.joinToString(","))
        } else {
            println("[$TAG]   player cell ($cellX,$cellY,$cellZ) has NO node")
        }

        // Lights this frame.
        var idx = 0
        for (item in actor.inventory) {
            if (!item.isLit()) continue
            val def = item.definition?.light ?: continue
            // Inventory lights always originate at the actor's continuous position.
            val lcellX = cellX; val lcellY = cellY; val lcellZ = cellZ
            val lvoxX = voxX; val lvoxY = voxY; val lvoxZ = voxZ
            println("[$TAG]   inv-light[$idx] type=${item.type} shape=${def.shape} " +
                "range=${def.range} intensity=${def.intensity} cone=${def.coneDegrees}")
            println("[$TAG]     origin world  = (% .4f, % .4f, % .4f)".format(px, py, pz))
            println("[$TAG]     stored cell   = ($lcellX, $lcellY, $lcellZ)  (used for LOS)")
            println("[$TAG]     raw floor()   = ($lvoxX, $lvoxY, $lvoxZ)" +
                if (lcellX != lvoxX || lcellY != lvoxY || lcellZ != lvoxZ)
                    "  (info: DDA applies +0.5 shift, this is expected)"
                else "")
            idx++
        }
        println("[$TAG] ────────────────────────────────────────────────────────────")
    }
}

