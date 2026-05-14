package com.roguelike.input

import org.lwjgl.glfw.GLFW.*

/**
 * GLFW callback-based input system that provides a polling API.
 * Replaces Gdx.input.* throughout the codebase.
 */
class InputSystem {
    private val keyState = BooleanArray(GLFW_KEY_LAST + 1)
    private val keyJustPressed = BooleanArray(GLFW_KEY_LAST + 1)
    private val mouseButtonState = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)
    private val mouseButtonJustPressed = BooleanArray(GLFW_MOUSE_BUTTON_LAST + 1)

    private var mouseX: Float = 0f
    private var mouseY: Float = 0f
    private var scrollDelta: Float = 0f
    private var scrollAccumulator: Float = 0f

    // Character input buffer (for text fields)
    private val charBuffer = mutableListOf<Char>()

    fun isKeyPressed(key: Int): Boolean = key in keyState.indices && keyState[key]
    fun isKeyJustPressed(key: Int): Boolean = key in keyJustPressed.indices && keyJustPressed[key]
    fun isMouseButtonPressed(button: Int): Boolean = button in mouseButtonState.indices && mouseButtonState[button]
    fun isMouseButtonJustPressed(button: Int): Boolean = button in mouseButtonJustPressed.indices && mouseButtonJustPressed[button]
    fun getMouseX(): Float = mouseX
    fun getMouseY(): Float = mouseY
    fun getScrollDelta(): Float = scrollDelta

    /** Return and clear all characters typed this frame. */
    fun consumeTypedChars(): List<Char> {
        if (charBuffer.isEmpty()) return emptyList()
        val result = charBuffer.toList()
        charBuffer.clear()
        return result
    }

    /**
     * Install GLFW callbacks on the given window.
     */
    fun install(window: Long) {
        glfwSetKeyCallback(window) { _, key, _, action, _ ->
            if (key in keyState.indices) {
                when (action) {
                    GLFW_PRESS -> {
                        keyState[key] = true
                        keyJustPressed[key] = true
                    }
                    GLFW_RELEASE -> {
                        keyState[key] = false
                    }
                }
            }
        }

        glfwSetMouseButtonCallback(window) { _, button, action, _ ->
            if (button in mouseButtonState.indices) {
                when (action) {
                    GLFW_PRESS -> {
                        mouseButtonState[button] = true
                        mouseButtonJustPressed[button] = true
                    }
                    GLFW_RELEASE -> {
                        mouseButtonState[button] = false
                    }
                }
            }
        }

        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            mouseX = xpos.toFloat()
            mouseY = ypos.toFloat()
        }

        glfwSetScrollCallback(window) { _, _, yoffset ->
            scrollAccumulator += yoffset.toFloat()
        }

        glfwSetCharCallback(window) { _, codepoint ->
            charBuffer.add(codepoint.toChar())
        }
    }

    /**
     * Call at end of frame to clear just-pressed state and snapshot scroll.
     */
    fun endFrame() {
        keyJustPressed.fill(false)
        mouseButtonJustPressed.fill(false)
        scrollDelta = scrollAccumulator
        scrollAccumulator = 0f
    }
}

