package com.roguelike.serialization

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemFactory
import com.roguelike.core.model.Player
import com.roguelike.core.model.TileSlot
import com.roguelike.core.model.Tile
import com.roguelike.core.model.World
import com.roguelike.core.model.setLit
import com.roguelike.core.systems.ModelOcclusionProvider
import com.roguelike.core.systems.SurfaceLighting
import com.roguelike.utils.ItemCatalogLoader
import com.roguelike.world.DoorNorthTile
import com.roguelike.world.DoorSouthTile
import com.roguelike.world.DoorEastTile
import com.roguelike.world.DoorWestTile
import com.roguelike.world.FloorTile
import com.roguelike.world.LadderTile
import com.roguelike.world.StairsTile
import com.roguelike.world.WallEastTile
import com.roguelike.world.WallNorthTile
import com.roguelike.world.WallSouthTile
import com.roguelike.world.WallWestTile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.math.abs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldLightingIntegrationTest {

    private lateinit var loadedWorld: World

    @BeforeAll
    fun setup() {
        ItemCatalog.clear()
        val defs = ItemCatalogLoader.loadFromInternal("items/items.json")
        check(defs.isNotEmpty())

        // Find project root by scanning up from current working directory
        val worldFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(6)
            .map { File(it, "saved-worlds/world.wld") }
            .firstOrNull { it.exists() }

        loadedWorld = if (worldFile != null) {
            WorldIO.loadWorld(
                worldFile.canonicalPath,
                { w, h, d -> World(w, h, d) },
                { type -> makeTile(type) }
            ) ?: World(9, 9, 3)
        } else {
            World(9, 9, 3)
        }
    }

    private fun makeTile(type: String): Tile? = when (type) {
        FloorTile.TYPE -> FloorTile()
        WallNorthTile.TYPE -> WallNorthTile()
        WallSouthTile.TYPE -> WallSouthTile()
        WallEastTile.TYPE -> WallEastTile()
        WallWestTile.TYPE -> WallWestTile()
        DoorNorthTile.TYPE -> DoorNorthTile()
        DoorSouthTile.TYPE -> DoorSouthTile()
        DoorEastTile.TYPE -> DoorEastTile()
        DoorWestTile.TYPE -> DoorWestTile()
        StairsTile.TYPE -> StairsTile()
        LadderTile.TYPE -> LadderTile()
        else -> null
    }

    /**
     * Headless occluder: thin AABB slab for each wall face, so rays crossing
     * a wall boundary are blocked without needing LibGDX BoundingBox.
     * Each box is [minX, minY, minZ, maxX, maxY, maxZ].
     */
    class FlatOccluder(world: World) : ModelOcclusionProvider {
        private val boxes: List<FloatArray> = buildSlabs(world)

        companion object {
            private const val HALF = 0.5f
            private const val THIN = 0.05f

            private fun buildSlabs(world: World): List<FloatArray> {
                val slabs = mutableListOf<FloatArray>()
                for (z in 0 until world.depth) {
                    for (y in 0 until world.height) {
                        for (x in 0 until world.width) {
                            val node = world.getNode(x, y, z) ?: continue
                            val xf = x.toFloat(); val yf = y.toFloat(); val zf = z.toFloat()
                            // Use tile.isBlocking() directly so open doors are excluded without
                            // requiring tagAsDoor() to have been called on the node.
                            val northTile = node.getTile(TileSlot.WALL_NORTH)
                            if (northTile != null && northTile.isBlocking())
                                slabs.add(floatArrayOf(xf - HALF, yf + HALF - THIN, zf - HALF, xf + HALF, yf + HALF + THIN, zf + HALF))
                            val southTile = node.getTile(TileSlot.WALL_SOUTH)
                            if (southTile != null && southTile.isBlocking())
                                slabs.add(floatArrayOf(xf - HALF, yf - HALF - THIN, zf - HALF, xf + HALF, yf - HALF + THIN, zf + HALF))
                            val eastTile = node.getTile(TileSlot.WALL_EAST)
                            if (eastTile != null && eastTile.isBlocking())
                                slabs.add(floatArrayOf(xf + HALF - THIN, yf - HALF, zf - HALF, xf + HALF + THIN, yf + HALF, zf + HALF))
                            val westTile = node.getTile(TileSlot.WALL_WEST)
                            if (westTile != null && westTile.isBlocking())
                                slabs.add(floatArrayOf(xf - HALF - THIN, yf - HALF, zf - HALF, xf - HALF + THIN, yf + HALF, zf + HALF))
                        }
                    }
                }
                return slabs
            }
        }

        override fun isOccluded(ox: Float, oy: Float, oz: Float, tx: Float, ty: Float, tz: Float): Boolean {
            val dx = tx - ox; val dy = ty - oy; val dz = tz - oz
            for (box in boxes) {
                if (segmentHitsBox(ox, oy, oz, dx, dy, dz, box)) return true
            }
            return false
        }

        private fun segmentHitsBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, box: FloatArray): Boolean {
            var tMin = 0f; var tMax = 1f
            fun slabAxis(o: Float, d: Float, bMin: Float, bMax: Float): Boolean {
                if (abs(d) < 1e-7f) return o > bMin && o < bMax
                val t1 = (bMin - o) / d; val t2 = (bMax - o) / d
                tMin = maxOf(tMin, minOf(t1, t2)); tMax = minOf(tMax, maxOf(t1, t2))
                return tMin < tMax
            }
            return slabAxis(ox, dx, box[0], box[3]) && slabAxis(oy, dy, box[1], box[4]) && slabAxis(oz, dz, box[2], box[5])
        }
    }

    private fun bright(buf: FloatArray) = maxOf(buf[0], maxOf(buf[1], buf[2]))

    private fun playerWithTorch(world: World, x: Int, y: Int, z: Int): Player {
        val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        return Player().also {
            it.position.set(x.toFloat(), y.toFloat(), z.toFloat())
            it.inventory.add(torch)
        }
    }

    @Test
    fun noLightLeakThroughWallWithFlatOccluder() {
        // Hand-crafted world with a north wall between y=4 and y=5
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(WallNorthTile())
        world.getNode(4, 5, 1)!!.setTile(FloorTile())
        world.getNode(4, 6, 1)!!.setTile(FloorTile())

        val player = playerWithTorch(world, 4, 4, 1)
        val occluder = FlatOccluder(world)
        val sl = SurfaceLighting.build(world, player, occluder = occluder)
        val buf = FloatArray(3)

        // Far side of the wall must be dark
        sl.floor(4, 6, 1, buf)
        assertTrue(bright(buf) < 0.05f, "floor 2 cells past north wall must be dark, was ${bright(buf)}")

        // Same side as light must be lit
        sl.floor(4, 3, 1, buf)
        assertTrue(bright(buf) > 0.05f, "floor on same side as torch must be lit, was ${bright(buf)}")
    }

    @Test
    fun loadedWorldHasNonZeroDimensions() {
        assertTrue(loadedWorld.width > 0 && loadedWorld.height > 0 && loadedWorld.depth > 0,
            "Loaded world must have positive dimensions")
    }

    @Test
    fun flatOccluderOnLoadedWorldDoesNotCrash() {
        // Smoke test: building FlatOccluder and running SurfaceLighting on the loaded world must not throw
        val occluder = FlatOccluder(loadedWorld)
        val player = playerWithTorch(loadedWorld, loadedWorld.width / 2, loadedWorld.height / 2, 1)
        val sl = SurfaceLighting.build(loadedWorld, player, occluder = occluder)
        val buf = FloatArray(3)
        // Just sample a few cells — any result is acceptable as long as it doesn't crash
        sl.floor(loadedWorld.width / 2, loadedWorld.height / 2, 1, buf)
    }

    // T013 door-state test (added for US2)
    @Test
    fun closedDoorBlocksLight_openDoorAllowsLight() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())
        world.getNode(4, 5, 1)!!.setTile(FloorTile())
        world.getNode(4, 6, 1)!!.setTile(FloorTile())

        // Door at north edge of cell (4, 4, 1)
        val door = DoorNorthTile(isOpen = false)
        world.getNode(4, 4, 1)!!.setTile(door)

        val player = playerWithTorch(world, 4, 3, 1)

        // Closed door: FlatOccluder includes the door slab → far side dark
        val closedOccluder = FlatOccluder(world)
        val slClosed = SurfaceLighting.build(world, player, occluder = closedOccluder)
        val buf = FloatArray(3)
        slClosed.floor(4, 6, 1, buf)
        assertTrue(bright(buf) < 0.05f, "floor beyond closed door must be dark, was ${bright(buf)}")

        // Open door: rebuild world without blocking door tile
        door.isOpen = true
        // Rebuild world node — DoorNorthTile with isOpen=true has isBlocking()=false
        // FlatOccluder rebuilds from current world state
        val openOccluder = FlatOccluder(world)
        val slOpen = SurfaceLighting.build(world, player, occluder = openOccluder)
        slOpen.floor(4, 6, 1, buf)
        assertTrue(bright(buf) > 0.05f, "floor beyond open door must be lit, was ${bright(buf)}")
    }

    // T016: 8-light scenario — combined brightness at center > single-source brightness
    @Test
    fun eightLightSourcesBlendAdditively() {
        val world = World(9, 9, 3)
        world.getNode(4, 4, 1)!!.setTile(FloorTile())

        // Build 8 lit items at different positions around center (4, 4, 1)
        val positions = listOf(
            2 to 2, 6 to 2, 2 to 6, 6 to 6,
            4 to 2, 4 to 6, 2 to 4, 6 to 4
        )
        for ((lx, ly) in positions) {
            val node = world.getNode(lx, ly, 1) ?: continue
            node.setTile(FloorTile())
            val torch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
            node.items.add(torch)
        }

        // Single light for comparison (use the first position only)
        val singleWorld = World(9, 9, 3)
        singleWorld.getNode(4, 4, 1)!!.setTile(FloorTile())
        val (sx, sy) = positions[0]
        val singleNode = singleWorld.getNode(sx, sy, 1) ?: error("node missing")
        singleNode.setTile(FloorTile())
        val singleTorch = ItemFactory.create("Torch")!!.also { it.setLit(true) }
        singleNode.items.add(singleTorch)

        val dummyPlayer = Player().also { it.position.set(4f, 4f, 1f) }
        val occluderMulti = FlatOccluder(world)
        val occluderSingle = FlatOccluder(singleWorld)

        val slMulti = SurfaceLighting.build(world, dummyPlayer, occluder = occluderMulti)
        val slSingle = SurfaceLighting.build(singleWorld, dummyPlayer, occluder = occluderSingle)

        val multiResult = FloatArray(3).also { slMulti.floor(4, 4, 1, it) }
        val singleResult = FloatArray(3).also { slSingle.floor(4, 4, 1, it) }

        val multiBright = bright(multiResult)
        val singleBright = bright(singleResult)

        assertTrue(multiBright > singleBright,
            "8 lights should produce brighter surface than 1 light: multi=$multiBright single=$singleBright")
        assertTrue(multiBright > 0.05f, "center floor should be lit by 8 sources, was $multiBright")
    }
}
