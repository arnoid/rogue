package com.roguelike.rendering

import com.badlogic.gdx.graphics.g3d.Model
import com.roguelike.core.math.Vec3

/**
 * Rendering metadata for a tile type.
 * Stored separately from the pure-data tile classes so the core layer stays LibGDX-free.
 */
data class TileRenderData(
    val model: Model,
    val scale: Float,
    val center: Vec3,
    /** Optional alternate model (e.g. open-door model). */
    val altModel: Model? = null
)

/**
 * Registry mapping tile instances to their rendering data.
 * Populated by [com.roguelike.utils.ModelLoader] when tiles are created;
 * consumed by [TileRenderer] when rendering.
 */
class TileRenderRegistry {
    private val dataByTile = mutableMapOf<Any, TileRenderData>()

    /** Register rendering data for a specific tile instance. */
    fun register(tile: Any, data: TileRenderData) {
        dataByTile[tile] = data
    }

    /** Look up rendering data for a tile instance. */
    operator fun get(tile: Any): TileRenderData? = dataByTile[tile]

    fun remove(tile: Any) { dataByTile.remove(tile) }

    fun clear() { dataByTile.clear() }
}

