package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.roguelike.core.model.Actor

class InventoryUI(private val skin: Skin) : Table() {

    fun update(player: Actor) {
        clearChildren()
        top().right()

        add(Label("Inventory", skin)).pad(10f).row()

        player.inventory.forEach { item ->
            val itemRow = Table()

            // "Icon" — colored square (convert hex string to LibGDX Color at the rendering boundary)
            val icon = Image(skin.getDrawable("white"))
            icon.color = Color.valueOf(item.colorHex)
            itemRow.add(icon).size(16f).padRight(5f)

            itemRow.add(Label(item.name, skin))

            add(itemRow).right().padRight(10f).row()
        }
    }
}
