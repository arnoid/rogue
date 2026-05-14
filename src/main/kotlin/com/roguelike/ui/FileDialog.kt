package com.roguelike.ui

import com.roguelike.input.InputSystem
import java.io.File

/**
 * A simple in-editor file picker dialog rendered via [SimpleUI].
 *
 * Shows a scrollable list of files/directories with navigation controls.
 * Works in two modes:
 *  - OPEN: pick an existing .wld file
 *  - SAVE: pick a directory and type a filename
 *
 * The dialog is modal: while open it captures all input.
 */
class FileDialog(
    private val ui: SimpleUI,
    private val inputSystem: InputSystem
) {
    enum class Mode { OPEN, SAVE }

    var isOpen = false
        private set

    private var mode = Mode.OPEN
    private var currentDir = File("saved-worlds").canonicalFile
    private var entries = listOf<File>()
    private var scrollOffset = 0
    private var selectedIndex = -1
    private var filenameInput = "world.wld"
    private var filenameCursor = 9  // cursor position in filenameInput
    private var filenameFieldFocused = false
    private var cursorBlinkTime = 0L
    private var onResult: ((File?) -> Unit)? = null

    // Layout constants
    private val dialogW = 500f
    private val dialogH = 400f
    private val rowH = 22f
    private val headerH = 32f
    private val footerH = 50f
    private val padding = 8f
    private val textScale = 1.2f

    /** Open the dialog in the given mode. [callback] receives the chosen file or null on cancel. */
    fun open(mode: Mode, initialDir: File? = null, callback: (File?) -> Unit) {
        this.mode = mode
        this.onResult = callback
        this.isOpen = true
        this.selectedIndex = -1
        this.scrollOffset = 0
        // Drain any stale typed characters accumulated while the dialog was closed
        inputSystem.consumeTypedChars()
        if (mode == Mode.SAVE) {
            filenameInput = "world.wld"
            filenameCursor = filenameInput.length
            filenameFieldFocused = true
            cursorBlinkTime = System.currentTimeMillis()
        } else {
            filenameFieldFocused = false
        }
        currentDir = (initialDir ?: File("saved-worlds")).canonicalFile
        if (!currentDir.isDirectory) currentDir = currentDir.parentFile ?: File(".")
        refreshEntries()
    }

    /** Render the dialog. Returns true while it is still open and consuming input. */
    fun render(): Boolean {
        if (!isOpen) return false

        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val dx = (sw - dialogW) / 2f
        val dy = (sh - dialogH) / 2f

        // Dim background
        ui.drawRect(0f, 0f, sw, sh, 0f, 0f, 0f, 0.5f)

        // Dialog background
        ui.drawRect(dx, dy, dialogW, dialogH, 0.15f, 0.16f, 0.2f, 0.98f)
        // Border
        ui.drawRect(dx, dy, dialogW, 2f, 0.4f, 0.5f, 0.7f)
        ui.drawRect(dx, dy + dialogH - 2f, dialogW, 2f, 0.4f, 0.5f, 0.7f)
        ui.drawRect(dx, dy, 2f, dialogH, 0.4f, 0.5f, 0.7f)
        ui.drawRect(dx + dialogW - 2f, dy, 2f, dialogH, 0.4f, 0.5f, 0.7f)

        // Title
        val title = if (mode == Mode.OPEN) "Open World" else "Save World As"
        ui.drawText(title, dx + padding, dy + 6f, 0.9f, 0.9f, 1f, 1f, 1.4f)

        // Current path
        val pathStr = currentDir.path
        ui.drawText(pathStr, dx + padding, dy + headerH, 0.5f, 0.55f, 0.65f, 0.9f, 1f)

        // File list area
        val listY = dy + headerH + 18f
        val listH = dialogH - headerH - 18f - footerH
        val visibleRows = (listH / rowH).toInt()

        // Handle scroll
        val scroll = inputSystem.getScrollDelta()
        if (scroll != 0f) {
            scrollOffset = (scrollOffset - scroll.toInt()).coerceIn(0, maxOf(0, entries.size - visibleRows))
        }

        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()

        for (i in 0 until visibleRows) {
            val idx = i + scrollOffset
            if (idx >= entries.size) break
            val entry = entries[idx]
            val ry = listY + i * rowH

            val hovered = mx >= dx + padding && mx <= dx + dialogW - padding && my >= ry && my < ry + rowH
            val selected = idx == selectedIndex

            if (selected) {
                ui.drawRect(dx + padding, ry, dialogW - padding * 2, rowH, 0.3f, 0.4f, 0.6f, 0.8f)
            } else if (hovered) {
                ui.drawRect(dx + padding, ry, dialogW - padding * 2, rowH, 0.22f, 0.28f, 0.38f, 0.6f)
            }

            val icon = if (entry.isDirectory) "[DIR] " else "      "
            val name = entry.name
            val r = if (entry.isDirectory) 0.7f else 0.85f
            val g = if (entry.isDirectory) 0.8f else 0.85f
            val b = if (entry.isDirectory) 0.5f else 0.9f
            ui.drawText("$icon$name", dx + padding + 4f, ry + 3f, r, g, b, 1f, textScale)

            if (hovered && inputSystem.isMouseButtonJustPressed(0)) {
                if (entry.isDirectory) {
                    currentDir = entry.canonicalFile
                    scrollOffset = 0
                    selectedIndex = -1
                    refreshEntries()
                } else {
                    selectedIndex = idx
                    filenameInput = entry.name
                    filenameCursor = filenameInput.length
                }
            }
        }

        // Scrollbar indicator
        if (entries.size > visibleRows) {
            val scrollFrac = scrollOffset.toFloat() / (entries.size - visibleRows)
            val barH = listH * visibleRows / entries.size
            val barY = listY + scrollFrac * (listH - barH)
            ui.drawRect(dx + dialogW - padding - 4f, barY, 4f, barH, 0.5f, 0.55f, 0.65f, 0.6f)
        }

        // Footer: filename input (for SAVE) + buttons
        val footerY = dy + dialogH - footerH

        if (mode == Mode.SAVE) {
            ui.drawText("Name:", dx + padding, footerY + 4f, 0.7f, 0.7f, 0.8f, 1f, textScale)

            // Editable filename text field
            val fieldX = dx + 60f
            val fieldY = footerY + 2f
            val fieldW = 220f
            val fieldH = 20f

            // Click to focus
            val fieldHovered = mx >= fieldX && mx < fieldX + fieldW && my >= fieldY && my < fieldY + fieldH
            if (inputSystem.isMouseButtonJustPressed(0)) {
                filenameFieldFocused = fieldHovered
                if (fieldHovered) cursorBlinkTime = System.currentTimeMillis()
            }

            // Background (highlight when focused)
            if (filenameFieldFocused) {
                ui.drawRect(fieldX, fieldY, fieldW, fieldH, 0.12f, 0.14f, 0.22f, 0.95f)
                // Focus border
                ui.drawRect(fieldX, fieldY, fieldW, 1f, 0.5f, 0.6f, 0.9f)
                ui.drawRect(fieldX, fieldY + fieldH - 1f, fieldW, 1f, 0.5f, 0.6f, 0.9f)
                ui.drawRect(fieldX, fieldY, 1f, fieldH, 0.5f, 0.6f, 0.9f)
                ui.drawRect(fieldX + fieldW - 1f, fieldY, 1f, fieldH, 0.5f, 0.6f, 0.9f)
            } else {
                ui.drawRect(fieldX, fieldY, fieldW, fieldH, 0.1f, 0.1f, 0.14f, 0.9f)
            }

            // Handle keyboard input when focused
            if (filenameFieldFocused) {
                // Typed characters
                val typed = inputSystem.consumeTypedChars()
                for (ch in typed) {
                    if (ch.code >= 32 && filenameInput.length < 60) {
                        filenameInput = filenameInput.substring(0, filenameCursor) + ch +
                                filenameInput.substring(filenameCursor)
                        filenameCursor++
                        cursorBlinkTime = System.currentTimeMillis()
                    }
                }

                // Backspace
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) && filenameCursor > 0) {
                    filenameInput = filenameInput.substring(0, filenameCursor - 1) +
                            filenameInput.substring(filenameCursor)
                    filenameCursor--
                    cursorBlinkTime = System.currentTimeMillis()
                }

                // Delete
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) && filenameCursor < filenameInput.length) {
                    filenameInput = filenameInput.substring(0, filenameCursor) +
                            filenameInput.substring(filenameCursor + 1)
                    cursorBlinkTime = System.currentTimeMillis()
                }

                // Arrow keys
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) && filenameCursor > 0) {
                    filenameCursor--
                    cursorBlinkTime = System.currentTimeMillis()
                }
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) && filenameCursor < filenameInput.length) {
                    filenameCursor++
                    cursorBlinkTime = System.currentTimeMillis()
                }

                // Home / End
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME)) {
                    filenameCursor = 0
                    cursorBlinkTime = System.currentTimeMillis()
                }
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END)) {
                    filenameCursor = filenameInput.length
                    cursorBlinkTime = System.currentTimeMillis()
                }

                // Enter confirms
                if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)) {
                    if (filenameInput.isNotBlank()) {
                        val target = File(currentDir, filenameInput)
                        close(target)
                        return true
                    }
                }
            }

            // Draw text
            ui.drawText(filenameInput, fieldX + 4f, fieldY + 4f, 0.9f, 0.9f, 0.95f, 1f, textScale)

            // Draw blinking cursor when focused
            if (filenameFieldFocused) {
                val elapsed = System.currentTimeMillis() - cursorBlinkTime
                if ((elapsed / 500) % 2 == 0L) {
                    val cursorStr = filenameInput.substring(0, filenameCursor)
                    val cursorPixelX = fieldX + 4f + ui.textWidth(cursorStr, textScale)
                    ui.drawRect(cursorPixelX, fieldY + 3f, 1.5f, fieldH - 6f, 0.9f, 0.9f, 1f, 0.9f)
                }
            }
        }

        // Buttons
        val btnW = 80f
        val btnH = 28f
        val btnY = footerY + 18f

        // Cancel button
        val cancelX = dx + dialogW - padding - btnW
        if (ui.button("Cancel", cancelX, btnY, btnW, btnH, inputSystem)) {
            close(null)
        }

        // OK / Open / Save button
        val okLabel = if (mode == Mode.OPEN) "Open" else "Save"
        val okX = cancelX - btnW - 8f
        if (ui.button(okLabel, okX, btnY, btnW, btnH, inputSystem)) {
            if (mode == Mode.OPEN && selectedIndex >= 0 && selectedIndex < entries.size) {
                val f = entries[selectedIndex]
                if (!f.isDirectory) close(f)
            } else if (mode == Mode.SAVE) {
                val target = File(currentDir, filenameInput)
                close(target)
            }
        }

        // Up button (go to parent dir)
        if (currentDir.parentFile != null) {
            if (ui.button("Up", dx + padding, btnY, 50f, btnH, inputSystem)) {
                currentDir = currentDir.parentFile!!.canonicalFile
                scrollOffset = 0
                selectedIndex = -1
                refreshEntries()
            }
        }

        // ESC closes
        if (inputSystem.isKeyJustPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)) {
            close(null)
        }

        return true
    }

    private fun close(result: File?) {
        isOpen = false
        onResult?.invoke(result)
        onResult = null
    }

    private fun refreshEntries() {
        if (!currentDir.exists()) currentDir.mkdirs()
        val files = currentDir.listFiles() ?: emptyArray()
        entries = files
            .filter { it.isDirectory || it.name.endsWith(".wld", ignoreCase = true) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}






