package com.roguelike.ui

import com.roguelike.input.InputSystem

/**
 * A horizontal menu bar rendered at the top of the screen via [SimpleUI].
 *
 * Supports top-level menu items that expand into dropdown lists on click.
 * Each dropdown item can be a label (clickable), a divider, or disabled text.
 *
 * Usage:
 * ```
 * val action = menuBar.render(inputSystem)
 * if (action != null) handleAction(action)
 * ```
 */
class MenuBar(private val ui: SimpleUI) {

    // Layout
    val barHeight = 24f
    private val itemPadX = 12f
    private val dropW = 260f
    private val dropRowH = 22f
    private val textScale = 1.1f
    private val dividerH = 8f

    data class MenuItem(val label: String, val action: String? = null, val isDivider: Boolean = false)
    data class Menu(val label: String, val items: List<MenuItem>)

    private val menus = mutableListOf<Menu>()
    /** Index of the currently open top-level menu, or -1 if none. */
    private var openMenuIndex = -1

    fun addMenu(label: String, items: List<MenuItem>) {
        menus.add(Menu(label, items))
    }

    /** Update the items of an existing menu by label (e.g. to refresh recent files). */
    fun updateMenu(label: String, items: List<MenuItem>) {
        val idx = menus.indexOfFirst { it.label == label }
        if (idx >= 0) menus[idx] = Menu(label, items)
    }

    /**
     * Render the menu bar.
     * @return the action string of a clicked menu item, or null.
     */
    fun render(inputSystem: InputSystem): String? {
        val sw = ui.screenWidth
        val mx = inputSystem.getMouseX()
        val my = inputSystem.getMouseY()
        val clicked = inputSystem.isMouseButtonJustPressed(0)

        // --- Draw bar background ---
        ui.drawRect(0f, 0f, sw, barHeight, 0.14f, 0.15f, 0.19f, 0.95f)
        // Bottom border
        ui.drawRect(0f, barHeight - 1f, sw, 1f, 0.3f, 0.35f, 0.45f, 0.8f)

        var result: String? = null
        var curX = 4f

        for ((menuIdx, menu) in menus.withIndex()) {
            val labelW = ui.textWidth(menu.label, textScale) + itemPadX * 2
            val hovered = mx >= curX && mx < curX + labelW && my >= 0f && my < barHeight
            val isOpen = openMenuIndex == menuIdx

            // Highlight
            if (isOpen || hovered) {
                ui.drawRect(curX, 0f, labelW, barHeight, 0.25f, 0.3f, 0.42f, 0.9f)
            }

            ui.drawText(menu.label, curX + itemPadX, 4f, 0.88f, 0.88f, 0.95f, 1f, textScale)

            // Toggle open on click
            if (hovered && clicked) {
                openMenuIndex = if (isOpen) -1 else menuIdx
            }
            // Hover-switch: if one menu is open and we hover another, switch
            if (openMenuIndex >= 0 && hovered && openMenuIndex != menuIdx) {
                openMenuIndex = menuIdx
            }

            // --- Draw dropdown ---
            if (isOpen) {
                result = renderDropdown(menu, curX, barHeight, inputSystem, mx, my, clicked)
                if (result != null) {
                    openMenuIndex = -1 // close on selection
                }
            }

            curX += labelW
        }

        // Click outside any menu/dropdown → close
        if (clicked && openMenuIndex >= 0) {
            // Check if click was NOT on the bar or the open dropdown
            if (my > barHeight) {
                // Check if click is inside the dropdown area – allow renderDropdown to handle it
                val dropdownConsumed = result != null || isInsideOpenDropdown(mx, my)
                if (!dropdownConsumed) {
                    openMenuIndex = -1
                }
            }
        }

        return result
    }

    /** Whether a click at (mx, my) falls inside the currently open dropdown. */
    private fun isInsideOpenDropdown(mx: Float, my: Float): Boolean {
        if (openMenuIndex < 0 || openMenuIndex >= menus.size) return false
        val menu = menus[openMenuIndex]
        var curX = 4f
        for (i in 0 until openMenuIndex) {
            curX += ui.textWidth(menus[i].label, textScale) + itemPadX * 2
        }
        val dropH = menu.items.sumOf { if (it.isDivider) dividerH.toDouble() else dropRowH.toDouble() }.toFloat() + 4f
        return mx >= curX && mx < curX + dropW && my >= barHeight && my < barHeight + dropH
    }

    private fun renderDropdown(
        menu: Menu, dropX: Float, dropY: Float,
        @Suppress("UNUSED_PARAMETER") inputSystem: InputSystem,
        mx: Float, my: Float, clicked: Boolean
    ): String? {
        var result: String? = null
        // Compute dropdown height
        var totalH = 4f
        for (item in menu.items) {
            totalH += if (item.isDivider) dividerH else dropRowH
        }

        // Background
        ui.drawRect(dropX, dropY, dropW, totalH, 0.13f, 0.14f, 0.18f, 0.97f)
        // Border
        ui.drawRect(dropX, dropY, dropW, 1f, 0.35f, 0.4f, 0.55f)
        ui.drawRect(dropX, dropY + totalH - 1f, dropW, 1f, 0.35f, 0.4f, 0.55f)
        ui.drawRect(dropX, dropY, 1f, totalH, 0.35f, 0.4f, 0.55f)
        ui.drawRect(dropX + dropW - 1f, dropY, 1f, totalH, 0.35f, 0.4f, 0.55f)

        var iy = dropY + 2f
        for (item in menu.items) {
            if (item.isDivider) {
                ui.drawRect(dropX + 8f, iy + dividerH / 2f - 0.5f, dropW - 16f, 1f, 0.3f, 0.35f, 0.45f, 0.6f)
                iy += dividerH
                continue
            }

            val rowHovered = mx >= dropX && mx < dropX + dropW && my >= iy && my < iy + dropRowH
            if (rowHovered) {
                ui.drawRect(dropX + 2f, iy, dropW - 4f, dropRowH, 0.28f, 0.35f, 0.55f, 0.85f)
            }

            ui.drawText(item.label, dropX + 12f, iy + 3f, 0.85f, 0.85f, 0.92f, 1f, textScale)

            if (rowHovered && clicked && item.action != null) {
                result = item.action
            }

            iy += dropRowH
        }

        return result
    }

    /** Whether the mouse is currently over the menu bar (for input-blocking). */
    fun isMouseOverBar(inputSystem: InputSystem): Boolean {
        val my = inputSystem.getMouseY()
        return my < barHeight || openMenuIndex >= 0
    }
}


