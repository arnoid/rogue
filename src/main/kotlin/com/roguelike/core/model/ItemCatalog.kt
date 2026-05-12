package com.roguelike.core.model

/**
 * Item catalog — the single source of truth for item type definitions
 * (display name, models, light behavior, blocking). Loaded from
 * `items/items.json` at startup via [ItemCatalogLoader].
 *
 * Pure data — no LibGDX dependency.
 */
object ItemCatalog {
    private val entries = mutableMapOf<String, ItemDef>()

    /** Replace all catalog entries (used by loader). */
    fun load(defs: List<ItemDef>) {
        entries.clear()
        defs.forEach { entries[it.type] = it }
    }

    fun all(): Collection<ItemDef> = entries.values

    operator fun get(type: String): ItemDef? = entries[type]

    fun has(type: String): Boolean = entries.containsKey(type)

    /** Reset the catalog to empty (mainly for tests). */
    fun clear() { entries.clear() }
}

/** Shape of a light emitted by a lit light-source item. */
enum class LightShape { CONE, SPHERE }

/** Where the light direction comes from. */
enum class LightDirection { OWNER_FACING, OMNIDIRECTIONAL }

/** Light parameters attached to a [ItemDef]. */
data class LightDef(
    val shape: LightShape,
    val direction: LightDirection,
    /** Effective radius in world units (cells). */
    val range: Float,
    /** Full cone angle in degrees (only used for [LightShape.CONE]). */
    val coneDegrees: Float = 360f,
    /** RGBA hex color (libGDX-style: rrggbbaa). */
    val colorHex: String = "ffffffff",
    /** Multiplier applied on top of distance falloff (0..1+). */
    val intensity: Float = 1f
)

/**
 * Catalog definition for an item type. Items hold a reference to their [ItemDef]
 * via [ItemDef.type] (looked up through [ItemCatalog]).
 */
data class ItemDef(
    val type: String,
    val name: String,
    /** UI / world-fallback color (rgba hex). */
    val colorHex: String = "ffffffff",
    /** Tags inherent to this type (e.g. [ItemTags.LIGHT_SOURCE]). */
    val tags: Set<String> = emptySet(),
    /** Optional single-state model path. */
    val model: String? = null,
    /** Optional unlit model path for light-source items. */
    val unlitModel: String? = null,
    /** Optional lit model path for light-source items. */
    val litModel: String? = null,
    /** Whether the item model blocks light. */
    val blocksLight: Boolean = true,
    /** Light emitted when this item is lit (null = no light, even if lit). */
    val light: LightDef? = null
) {
    /** Returns the model path appropriate for the current lit state. */
    fun modelFor(lit: Boolean): String? = when {
        lit && litModel != null -> litModel
        !lit && unlitModel != null -> unlitModel
        model != null -> model
        else -> litModel ?: unlitModel
    }
}

/** Well-known item tag strings. */
object ItemTags {
    /** This item is a light source (lit or unlit). */
    const val LIGHT_SOURCE = "light_source"
    /** This light-source item is currently lit. */
    const val LIGHT_SOURCE_LIT = "light_source_lit"
}

