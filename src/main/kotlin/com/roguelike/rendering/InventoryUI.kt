package com.roguelike.rendering

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.roguelike.core.model.Actor
import com.roguelike.core.model.Item
import com.roguelike.core.model.isLightSource
import com.roguelike.core.model.isLit
import com.roguelike.core.model.toggleLit

/**
 * Displays the player's inventory on-screen.
 *  - Left-click toggles `light_source_lit` on light-source items (or invokes
 *    [onItemClicked] if set).
 *  - Right-click invokes [onItemRightClicked] (used by the game to drop the
 *    item onto the world facing the actor's current direction).
 *
 * The UI is rebuilt only when the inventory or any item's lit state changes,
 * so click listeners are stable and not duplicated every frame.
 */
class InventoryUI(
    private val skin: Skin,
    var onItemClicked: ((Item) -> Unit)? = null,
    var onItemRightClicked: ((Item) -> Unit)? = null
) : Table() {

    private var lastSignature: String? = null

    init {
        top().right()
        // Don't swallow clicks in empty areas of the fill-parent table —
        // only the actual rows are interactive.
        touchable = Touchable.childrenOnly
    }

    fun update(player: Actor) {
        val signature = signatureOf(player)
        if (signature == lastSignature) return
        lastSignature = signature
        rebuild(player)
    }

    private fun signatureOf(player: Actor): String =
        "n=${player.inventory.size}:" + player.inventory.joinToString("|") { "${it.id}:${it.isLit()}" }

    private fun rebuild(player: Actor) {
        clearChildren()
        top().right()

        add(Label("Inventory", skin)).pad(10f).row()

        player.inventory.forEach { item ->
            val itemRow = Table()
            itemRow.touchable = Touchable.enabled

            val icon = Image(skin.getDrawable("white"))
            icon.color = if (item.isLightSource() && item.isLit()) {
                Color.YELLOW
            } else {
                try { Color.valueOf(item.colorHex) } catch (_: Exception) { Color.WHITE }
            }
            itemRow.add(icon).size(16f).padRight(5f)

            val label = if (item.isLightSource()) {
                val suffix = if (item.isLit()) " (lit)" else " (unlit)"
                "${item.name}$suffix"
            } else item.name
            itemRow.add(Label(label, skin))

            // Left-click handler (default ClickListener listens to LEFT only).
            itemRow.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    val handler = onItemClicked
                    if (handler != null) {
                        handler(item)
                    } else if (item.isLightSource()) {
                        item.toggleLit()
                    }
                    lastSignature = null
                }
            })

            // Right-click handler — intercept touchDown directly so we react
            // immediately without ClickListener's same-button-up requirement
            // (which sometimes fails for non-LEFT buttons inside Stage actors).
            itemRow.addListener(object : InputListener() {
                override fun touchDown(
                    event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int
                ): Boolean {
                    if (button == Input.Buttons.RIGHT) {
                        onItemRightClicked?.invoke(item)
                        lastSignature = null
                        return true // consume so nothing else handles it
                    }
                    return false
                }
            })

            add(itemRow).right().padRight(10f).row()
        }
    }
}
