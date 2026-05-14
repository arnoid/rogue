package com.roguelike

import com.roguelike.input.InputSystem
import com.roguelike.ui.SimpleUI

/**
 * Main menu with Arena and World Editor buttons.
 */
class MainMenuScreen(private val ui: SimpleUI, private val inputSystem: InputSystem) {

    /**
     * Render the main menu. Returns the action selected, or null.
     */
    fun render(): MenuAction? {
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

        // Quit button
        val quitY = editorY + btnH + gap * 2
        ui.drawRect(btnX - 14f, quitY + 12f, 8f, btnH - 24f, 0.9f, 0.3f, 0.3f) // red marker
        val quitClicked = ui.button("Quit", btnX, quitY, btnW, btnH, inputSystem)

        // Footer
        val footer = "ESC to return from game/editor"
        val footerScale = 1f
        val footerW = ui.textWidth(footer, footerScale)
        ui.drawText(footer, (sw - footerW) / 2f, sh * 0.88f, 0.35f, 0.4f, 0.5f, 0.8f, footerScale)

        return when {
            arenaClicked -> MenuAction.ARENA
            editorClicked -> MenuAction.EDITOR
            quitClicked -> MenuAction.QUIT
            else -> null
        }
    }
}

enum class MenuAction {
    ARENA, EDITOR, QUIT
}
