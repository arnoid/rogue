package com.roguelike

import com.roguelike.generation.BiomeEntry
import com.roguelike.generation.BiomeIndex
import com.roguelike.input.InputSystem
import com.roguelike.ui.SimpleUI

/**
 * Lists the biomes declared in `biomes.json` and lets the player pick one
 * before entering the Arena. The selected biome drives which `.wld`
 * submap templates the procedural map generator is allowed to use.
 *
 * The picker is intentionally minimal: one button per biome plus a Back
 * button. The biome list is reloaded each time [render] is first called
 * after [reset] so file edits show up without restarting the game.
 */
class BiomePickerScreen(
    private val ui: SimpleUI,
    private val inputSystem: InputSystem
) {
    private var biomes: List<BiomeEntry> = emptyList()
    private var loaded = false

    /**
     * Outcome of a single [render] pass.
     *  - [selected]  : non-null when the user clicked a biome button this frame.
     *  - [backPressed] : true if the user clicked Back / hit ESC equivalent.
     *  Both null/false means the picker should stay open another frame.
     */
    data class Result(val selected: BiomeEntry?, val backPressed: Boolean)

    /** Force a fresh load of `biomes.json` on the next render. */
    fun reset() {
        loaded = false
        biomes = emptyList()
    }

    fun render(): Result {
        if (!loaded) {
            biomes = BiomeIndex.loadIndex()
            loaded = true
        }

        val sw = ui.screenWidth
        val sh = ui.screenHeight

        // Title
        val title = "SELECT BIOME"
        val titleScale = 2.2f
        val titleW = ui.textWidth(title, titleScale)
        ui.drawText(title, (sw - titleW) / 2f, sh * 0.10f, 0.8f, 0.85f, 1f, 1f, titleScale)

        val sub = "Pick a biome to seed the random map generator"
        val subScale = 1.0f
        val subW = ui.textWidth(sub, subScale)
        ui.drawText(sub, (sw - subW) / 2f, sh * 0.18f, 0.5f, 0.55f, 0.7f, 1f, subScale)

        // Empty state
        if (biomes.isEmpty()) {
            val msg = "No biomes found in biomes.json"
            val msgW = ui.textWidth(msg, 1.2f)
            ui.drawText(msg, (sw - msgW) / 2f, sh * 0.35f, 1f, 0.5f, 0.5f, 1f, 1.2f)
        }

        // One button per biome
        val btnW = 360f
        val btnH = 48f
        val btnX = (sw - btnW) / 2f
        val gap = 12f
        val startY = sh * 0.28f
        var chosen: BiomeEntry? = null
        biomes.forEachIndexed { i, entry ->
            val y = startY + i * (btnH + gap)
            // Type chip to the left of the button label
            ui.drawRect(btnX - 14f, y + 12f, 8f, btnH - 24f, 0.4f, 0.8f, 0.5f)
            val label = "${entry.name}  [${entry.type}]"
            if (ui.button(label, btnX, y, btnW, btnH, inputSystem)) {
                chosen = entry
            }
        }

        // Back button (always last)
        val backY = sh * 0.82f
        val backClicked = ui.button("Back", btnX, backY, btnW, btnH, inputSystem)

        return Result(selected = chosen, backPressed = backClicked)
    }
}

