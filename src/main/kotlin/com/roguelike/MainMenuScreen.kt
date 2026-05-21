package com.roguelike

import com.roguelike.generation.BiomeIndex
import com.roguelike.generation.BiomeRegenerator
import com.roguelike.input.InputSystem
import com.roguelike.ui.FileDialog
import com.roguelike.ui.SimpleUI
import java.io.File

/**
 * Main menu with Arena and World Editor buttons.
 */
class MainMenuScreen(private val ui: SimpleUI, private val inputSystem: InputSystem) {

    /**
     * In-screen file picker, opened by "Regen biomes" so the user can
     * choose which biome.json to rescan. The dialog is modal while open
     * and captures all input.
     */
    private val fileDialog = FileDialog(ui, inputSystem)

    /**
     * Last [BiomeRegenerator.IndexReport], shown as a transient status
     * banner at the bottom of the menu for [STATUS_LINGER_FRAMES] frames
     * so the user can see the result of their click. Reset to null after
     * the banner expires.
     */
    private var lastReport: BiomeRegenerator.IndexReport? = null
    private var statusFramesLeft: Int = 0

    /**
     * Render the main menu. Returns the action selected, or null.
     */
    fun render(): MenuAction? {
        // File dialog has modal precedence — when it's up, capture all
        // input and skip menu interactions for this frame.
        if (fileDialog.isOpen) {
            fileDialog.render()
            return null
        }

        val sw = ui.screenWidth
        val sh = ui.screenHeight

        // Title
        val title = "ROGUELIKE 3D"
        val titleScale = 2.5f
        val titleW = ui.textWidth(title, titleScale)
        ui.drawText(title, (sw - titleW) / 2f, sh * 0.10f, 0.8f, 0.85f, 1f, 1f, titleScale)

        // Subtitle
        val sub = "Vulkan Engine"
        val subScale = 1.2f
        val subW = ui.textWidth(sub, subScale)
        ui.drawText(sub, (sw - subW) / 2f, sh * 0.18f, 0.5f, 0.55f, 0.7f, 1f, subScale)

        // Divider
        ui.drawRect(sw * 0.25f, sh * 0.24f, sw * 0.5f, 2f, 0.4f, 0.45f, 0.6f)

        // Buttons
        val btnW = 300f
        val btnH = 50f
        val btnX = (sw - btnW) / 2f
        val gap = 16f
        val startY = sh * 0.32f

        // Arena button — with colored indicator on the left
        ui.drawRect(btnX - 14f, startY + 12f, 8f, btnH - 24f, 0.3f, 0.9f, 0.4f) // green marker
        val arenaClicked = ui.button("Arena", btnX, startY, btnW, btnH, inputSystem)

        // World Editor button — with colored indicator on the left
        val editorY = startY + btnH + gap
        ui.drawRect(btnX - 14f, editorY + 12f, 8f, btnH - 24f, 0.4f, 0.6f, 0.95f) // blue marker
        val editorClicked = ui.button("World Editor", btnX, editorY, btnW, btnH, inputSystem)

        // Regen biomes button — opens a file dialog, then rescans the
        // chosen biome.json's submap folders and rewrites it in place.
        val regenY = editorY + btnH + gap
        ui.drawRect(btnX - 14f, regenY + 12f, 8f, btnH - 24f, 0.85f, 0.7f, 0.3f) // amber marker
        val regenClicked = ui.button("Regen biomes", btnX, regenY, btnW, btnH, inputSystem)
        if (regenClicked) openRegenDialog()

        // Quit button
        val quitY = regenY + btnH + gap * 2
        ui.drawRect(btnX - 14f, quitY + 12f, 8f, btnH - 24f, 0.9f, 0.3f, 0.3f) // red marker
        val quitClicked = ui.button("Quit", btnX, quitY, btnW, btnH, inputSystem)

        // Footer
        val footer = "ESC to return from game/editor"
        val footerScale = 1f
        val footerW = ui.textWidth(footer, footerScale)
        ui.drawText(footer, (sw - footerW) / 2f, sh * 0.88f, 0.35f, 0.4f, 0.5f, 0.8f, footerScale)

        // Transient regen status banner (shown briefly after a regen).
        renderStatusBanner(sw, sh)

        return when {
            arenaClicked -> MenuAction.ARENA
            editorClicked -> MenuAction.EDITOR
            quitClicked -> MenuAction.QUIT
            else -> null
        }
    }

    /**
     * Open the file dialog rooted at the default biomes directory (or
     * the working directory if that doesn't exist). The user is expected
     * to pick the top-level `biomes.json` index file; we then refresh
     * the index *and* every biome it points at via
     * [BiomeRegenerator.regenerateIndex]. The aggregated report is
     * stashed in [lastReport] for the banner.
     */
    private fun openRegenDialog() {
        val initialDir = when {
            File(BiomeIndex.DEFAULT_INDEX_PATH).parentFile?.isDirectory == true ->
                File(BiomeIndex.DEFAULT_INDEX_PATH).parentFile
            File("src/main/resources/world-submaps/biomes").isDirectory ->
                File("src/main/resources/world-submaps/biomes")
            else -> File(".")
        }
        fileDialog.open(FileDialog.Mode.OPEN, initialDir, extensions = listOf(".json")) { picked ->
            if (picked == null) return@open
            val report = BiomeRegenerator.regenerateIndex(picked)
            lastReport = report
            statusFramesLeft = STATUS_LINGER_FRAMES
            println("[MainMenu] Regen biomes: ${report.indexJsonPath} -> ${report.summaryLine()}")
            // Per-biome breakdown for the log.
            for (r in report.biomeReports) {
                println("[MainMenu]   biome ${r.biomeJsonPath}: ${r.summaryLine()}")
                if (r.added.isNotEmpty())   println("[MainMenu]     added:   ${r.added.joinToString()}")
                if (r.removed.isNotEmpty()) println("[MainMenu]     removed: ${r.removed.joinToString()}")
                r.errors.forEach { println("[MainMenu]     ERROR:   $it") }
            }
            if (report.indexReport.added.isNotEmpty())
                println("[MainMenu]   index added:   ${report.indexReport.added.joinToString()}")
            if (report.indexReport.removed.isNotEmpty())
                println("[MainMenu]   index removed: ${report.indexReport.removed.joinToString()}")
            report.indexReport.errors.forEach { println("[MainMenu]   index ERROR:   $it") }
        }
    }

    /**
     * Draws the regen result strip near the bottom of the menu while
     * [statusFramesLeft] > 0. Decremented each frame regardless of
     * whether we drew, so it eventually disappears even if the user
     * spawns another modal.
     */
    private fun renderStatusBanner(sw: Float, sh: Float) {
        val report = lastReport ?: return
        if (statusFramesLeft <= 0) {
            lastReport = null
            return
        }
        statusFramesLeft--

        val errored = report.totalErrors > 0
        val r = if (errored) 0.95f else 0.4f
        val g = if (errored) 0.4f else 0.85f
        val b = if (errored) 0.4f else 0.5f
        val msg = if (errored) {
            "Regen finished with ${report.totalErrors} error(s) — ${report.summaryLine()}"
        } else {
            "Regen done — ${report.summaryLine()}"
        }
        val msgScale = 0.95f
        val msgW = ui.textWidth(msg, msgScale)
        val y = sh * 0.94f
        // Background pill behind the text for legibility.
        ui.drawRect((sw - msgW) / 2f - 8f, y - 4f, msgW + 16f, 22f, 0.1f, 0.12f, 0.15f)
        ui.drawText(msg, (sw - msgW) / 2f, y, r, g, b, 1f, msgScale)
    }

    companion object {
        /**
         * Frames the regen status banner stays visible. ~3 seconds at
         * 60 fps. The banner doesn't need to be pixel-perfectly timed —
         * the user will dismiss the menu (or trigger another regen)
         * long before it really matters.
         */
        private const val STATUS_LINGER_FRAMES = 180
    }
}

enum class MenuAction {
    ARENA, EDITOR, QUIT
}
