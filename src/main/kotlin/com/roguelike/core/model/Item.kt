package com.roguelike.core.model

import java.util.UUID

/**
 * Pure data interfaces for items.
 * Color is stored as a hex string (e.g. "0000ffff") to stay LibGDX-free.
 * The rendering layer converts it with Color.valueOf().
 */
interface Item {
    val id: String
    val type: String
    /** RGBA hex string as produced by LibGDX Color.toString() / parsed by Color.valueOf(). */
    val colorHex: String
    val name: String
}

data class KeyItem(
    override val id: String       = UUID.randomUUID().toString(),
    override val type: String     = "Key",
    override val colorHex: String = "ffffffff",
    override val name: String     = "Key"
) : Item
