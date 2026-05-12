package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.roguelike.core.systems.LightMap3D
import com.roguelike.world.World

class WorldRenderer(
    private val tileRenderer: TileRenderer,
    private val itemRenderer: ItemRenderer? = null,
    private val propRenderer: PropRenderer? = null
) {

    /** Minimum ambient floor brightness so completely-unlit cells aren't pure black. */
    private val unlitAmbient = 0.0f

    private val tmpTint = Color(1f, 1f, 1f, 1f)
    private val tintBuf = FloatArray(3)

    /** Throttle: only emit per-item render log a few times per second per item. */
    private var lastItemLogNs: Long = 0L
    private val itemLogIntervalNs: Long = 1_000_000_000L // 1 second

    /**
     * @param lightMap optional multi-Z per-cell brightness map. If non-null,
     *                 geometry is tinted by `lightMap.tint(x,y,z)`; cells with
     *                 no light render very dark.
     */
    fun render(
        world: World,
        batch: ModelBatch,
        environment: Environment,
        maxZ: Int = world.depth - 1,
        lightMap: LightMap3D? = null
    ) {
        val now = System.nanoTime()
        val shouldLog = (now - lastItemLogNs) > itemLogIntervalNs
        var itemsRendered = 0
        var itemCellsSeen = 0

        for (x in 0 until world.width) {
            for (y in 0 until world.height) {
                for (z in 0..maxZ.coerceAtMost(world.depth - 1)) {
                    val node = world.getNode(x, y, z) ?: continue
                    val tint = tintFor(lightMap, x, y, z)

                    node.tiles.forEach {
                        tileRenderer.render(it, batch, environment, x.toFloat(), y.toFloat(), z.toFloat(), tint = tint)
                    }

                    if (node.items.isNotEmpty()) {
                        itemCellsSeen++
                        if (shouldLog) {
                            println("[WorldRenderer] cell ($x,$y,$z) has ${node.items.size} item(s): ${node.items.joinToString { it.name + "#" + it.id.take(6) }} tint=${tint?.let { "(${it.r},${it.g},${it.b})" }}")
                        }
                    }
                    itemRenderer?.let { renderer ->
                        node.items.forEach { item ->
                            renderer.render(item, batch, environment, x.toFloat(), y.toFloat(), z.toFloat(), tint = tint, debug = shouldLog)
                            itemsRendered++
                        }
                    }
                }
            }
        }

        if (shouldLog && (itemCellsSeen > 0 || itemsRendered > 0)) {
            println("[WorldRenderer] frame summary: itemCellsSeen=$itemCellsSeen itemsRendered=$itemsRendered maxZ=$maxZ depth=${world.depth}")
            lastItemLogNs = now
        }

        // Render props (freely-placed decorations)
        propRenderer?.let { renderer ->
            val maxZClamped = maxZ.coerceAtMost(world.depth - 1)
            for (prop in world.props) {
                if (prop.z <= maxZClamped + 1) {
                    val cx = Math.round(prop.x)
                    val cy = Math.round(prop.y)
                    val cz = Math.round(prop.z)
                    val tint = tintFor(lightMap, cx, cy, cz)
                    renderer.render(prop, batch, environment, tint = tint)
                }
            }
        }
    }

    private fun tintFor(lightMap: LightMap3D?, x: Int, y: Int, z: Int): Color? {
        if (lightMap == null) return null
        lightMap.tint(x, y, z, tintBuf)
        val r = tintBuf[0].coerceAtLeast(unlitAmbient)
        val g = tintBuf[1].coerceAtLeast(unlitAmbient)
        val b = tintBuf[2].coerceAtLeast(unlitAmbient)
        tmpTint.set(r, g, b, 1f)
        return tmpTint
    }
}
