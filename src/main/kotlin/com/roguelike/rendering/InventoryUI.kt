package com.roguelike.rendering

import com.roguelike.core.model.Actor
import com.roguelike.core.model.Item

/**
 * Inventory UI using Dear ImGui.
 * TODO: Full ImGui implementation (Phase 6, T036)
 */
class InventoryUI(
    var onItemClicked: ((Item) -> Unit)? = null,
    var onItemRightClicked: ((Item) -> Unit)? = null
) {
    fun update(player: Actor) {
        // TODO: ImGui rendering (T036)
    }
}
