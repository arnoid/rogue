package com.roguelike

import com.roguelike.generation.BiomeEntry
import com.roguelike.generation.BiomeIndex
import com.roguelike.input.InputSystem
import com.roguelike.ui.FileDialog
import com.roguelike.ui.SimpleUI
import java.io.File

/**
 * Lists the biomes declared in `biomes.json` and lets the player pick one
 * before entering the Arena. The selected biome drives which `.wld`
 * submap templates the procedural map generator is allowed to use.
 *
 * The picker is intentionally minimal: one button per biome plus a Back
 * button. The biome list is reloaded each time [render] is first called
 * after [reset] so file edits show up without restarting the game.
 *
 * If the default `biomes.json` is missing or empty the picker shows an
 * inline "Pick biomes.json…" button that opens a [FileDialog]; the
 * chosen file becomes the active index for the rest of the session
 * (and reloads on each [reset]).
 */
class BiomePickerScreen(
    private val ui: SimpleUI,
    private val inputSystem: InputSystem
) {
    private var biomes: List<BiomeEntry> = emptyList()
    private var loaded = false

    /** Active index file path. Null means "use the default location". */
    private var indexPathOverride: String? = null

    /** In-screen file picker used to override [indexPathOverride]. */
    private val fileDialog = FileDialog(ui, inputSystem)

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
        // File dialog has modal precedence — when it's up it captures all
        // input and we render nothing else from the picker.
        if (fileDialog.isOpen) {
            fileDialog.render()
            return Result(selected = null, backPressed = false)
        }

        if (!loaded) {
            biomes = if (indexPathOverride != null) {
                BiomeIndex.loadIndex(indexPathOverride!!)
            } else {
                BiomeIndex.loadIndex()
            }
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

        // Layout constants used by both the populated and empty states.
        val btnW = 360f
        val btnH = 48f
        val btnX = (sw - btnW) / 2f
        val gap = 12f

        // Empty state — offer a file picker so the user can choose a
        // biomes.json from a non-default location.
        if (biomes.isEmpty()) {
            val activePath = indexPathOverride ?: BiomeIndex.DEFAULT_INDEX_PATH
            val msg = "No biomes found in biomes.json"
            val msgW = ui.textWidth(msg, 1.2f)
            ui.drawText(msg, (sw - msgW) / 2f, sh * 0.30f, 1f, 0.5f, 0.5f, 1f, 1.2f)

            val pathLine = "Tried: $activePath"
            val pathW = ui.textWidth(pathLine, 0.9f)
            ui.drawText(pathLine, (sw - pathW) / 2f, sh * 0.34f, 0.6f, 0.6f, 0.7f, 0.9f, 0.9f)

            val pickY = sh * 0.42f
            if (ui.button("Pick biomes.json...", btnX, pickY, btnW, btnH, inputSystem)) {
                openBiomesJsonDialog(activePath)
            }
            val retryY = pickY + btnH + gap
            if (ui.button("Retry default", btnX, retryY, btnW, btnH, inputSystem)) {
                indexPathOverride = null
                loaded = false
            }
        } else {
            // One button per biome
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

        // Empty state: still allow Back below the recovery buttons.
        val backY = sh * 0.82f
        val backClicked = ui.button("Back", btnX, backY, btnW, btnH, inputSystem)
        return Result(selected = null, backPressed = backClicked)
    }

    /**
     * Open the file picker rooted at the directory that *would* have
     * contained the default `biomes.json` (falling back to the working
     * directory). When the user picks a file, store it as the active
     * index override and force a reload on the next [render].
     */
    private fun openBiomesJsonDialog(activePath: String) {
        val activeFile = File(activePath)
        val initialDir = when {
            activeFile.parentFile?.isDirectory == true -> activeFile.parentFile
            File("src/main/resources/world-submaps/biomes").isDirectory ->
                File("src/main/resources/world-submaps/biomes")
            File("src/main/resources/world-submaps").isDirectory ->
                File("src/main/resources/world-submaps")
            else -> File(".")
        }
        fileDialog.open(FileDialog.Mode.OPEN, initialDir, extensions = listOf(".json")) { picked ->
            if (picked != null) {
                indexPathOverride = picked.absolutePath
                loaded = false
                println("[BiomePicker] using biomes index: ${picked.absolutePath}")
            }
        }
    }
}

