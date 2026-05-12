package com.roguelike.core.model

import java.util.UUID

/**
 * Pure data interfaces for items.
 *
 * Color is stored as a hex string (e.g. "0000ffff") to stay LibGDX-free.
 * The rendering layer converts it with `Color.valueOf()`.
 *
 * Items also carry a mutable [tags] set so per-instance state (such as
 * [ItemTags.LIGHT_SOURCE_LIT]) can be toggled at runtime.
 *
 * Items resolve their static definition (display name, models, light
 * behavior, blocksLight) through [ItemCatalog] via [type] — see [definition].
 */
interface Item {
    val id: String
    val type: String
    /** RGBA hex string as produced by LibGDX Color.toString() / parsed by Color.valueOf(). */
    val colorHex: String
    val name: String
    /** Mutable per-instance tags (e.g. lit state). */
    val tags: MutableSet<String>
    /**
     * Facing direction of the item when placed in the world (unit vector in
     * the XY plane). For inventory items this is typically ignored — the owning
     * actor's facing is used instead. Default is +Y (north).
     */
    var facingX: Float
    var facingY: Float

    /** Catalog definition for this item type (or null if not registered). */
    val definition: ItemDef? get() = ItemCatalog[type]
}

// ──────────────────────────────────────────────────────────────────────────
// Tag helpers — operate on any [Item] regardless of its concrete class.
// ──────────────────────────────────────────────────────────────────────────

/** True if this item is tagged as a light source (instance or catalog). */
fun Item.isLightSource(): Boolean =
    ItemTags.LIGHT_SOURCE in tags ||
        (definition?.tags?.contains(ItemTags.LIGHT_SOURCE) == true)

/** True if this light-source item is currently lit. */
fun Item.isLit(): Boolean =
    isLightSource() && ItemTags.LIGHT_SOURCE_LIT in tags

/** Sets the lit state. No-op for non-light-source items. */
fun Item.setLit(lit: Boolean) {
    if (!isLightSource()) return
    if (lit) tags.add(ItemTags.LIGHT_SOURCE_LIT)
    else tags.remove(ItemTags.LIGHT_SOURCE_LIT)
}

/** Toggles the lit state. Returns the new state (false if not a light source). */
fun Item.toggleLit(): Boolean {
    if (!isLightSource()) return false
    val newState = !isLit()
    setLit(newState)
    return newState
}

// ──────────────────────────────────────────────────────────────────────────
// Concrete item types
// ──────────────────────────────────────────────────────────────────────────

data class KeyItem(
    override val id: String       = UUID.randomUUID().toString(),
    override val type: String     = "Key",
    override val colorHex: String = "ffffffff",
    override val name: String     = "Key",
    override val tags: MutableSet<String> = mutableSetOf(),
    override var facingX: Float = 0f,
    override var facingY: Float = 1f
) : Item

/** Candle — emits a forward cone in the owner's facing direction when lit. */
data class CandleItem(
    override val id: String       = UUID.randomUUID().toString(),
    override val type: String     = "Candle",
    override val colorHex: String = "ffd27aff",
    override val name: String     = "Candle",
    override val tags: MutableSet<String> = mutableSetOf(ItemTags.LIGHT_SOURCE),
    override var facingX: Float = 0f,
    override var facingY: Float = 1f
) : Item

/** Torch — emits an omnidirectional spherical light around the owner when lit. */
data class TorchItem(
    override val id: String       = UUID.randomUUID().toString(),
    override val type: String     = "Torch",
    override val colorHex: String = "ff9f45ff",
    override val name: String     = "Torch",
    override val tags: MutableSet<String> = mutableSetOf(ItemTags.LIGHT_SOURCE),
    override var facingX: Float = 0f,
    override var facingY: Float = 1f
) : Item

// ──────────────────────────────────────────────────────────────────────────
// Factory
// ──────────────────────────────────────────────────────────────────────────

/** Creates an item instance from a catalog type string. Returns null if unknown. */
object ItemFactory {
    fun create(type: String, id: String = UUID.randomUUID().toString()): Item? {
        val def = ItemCatalog[type]
        val tags = def?.tags?.toMutableSet() ?: mutableSetOf()
        val name = def?.name ?: type
        val color = def?.colorHex ?: "ffffffff"
        return when (type) {
            "Key"    -> KeyItem(id, type, color, name, tags)
            "Candle" -> CandleItem(id, type, color, name, tags)
            "Torch"  -> TorchItem(id, type, color, name, tags)
            else     -> if (def != null) KeyItem(id, type, color, name, tags) else null
        }
    }
}

