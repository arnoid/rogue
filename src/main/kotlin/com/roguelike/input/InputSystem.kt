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
    private var lastMouseX: Float = 0f
    private var lastMouseY: Float = 0f
    private var mouseDeltaXAccum: Float = 0f
    private var mouseDeltaYAccum: Float = 0f
    private var mouseDeltaX: Float = 0f
    private var mouseDeltaY: Float = 0f
    private var firstMouseSample: Boolean = true
    private var scrollDelta: Float = 0f
    private var scrollAccumulator: Float = 0f
    private var window: Long = 0L
    private var cursorCaptured: Boolean = false

    // Character input buffer (for text fields)
    private val charBuffer = mutableListOf<Char>()

    fun isKeyPressed(key: Int): Boolean = key in keyState.indices && keyState[key]
    fun isKeyJustPressed(key: Int): Boolean = key in keyJustPressed.indices && keyJustPressed[key]
    fun isMouseButtonPressed(button: Int): Boolean = button in mouseButtonState.indices && mouseButtonState[button]
    fun isMouseButtonJustPressed(button: Int): Boolean = button in mouseButtonJustPressed.indices && mouseButtonJustPressed[button]
    fun getMouseX(): Float = mouseX
    fun getMouseY(): Float = mouseY
    /** Horizontal mouse motion since the previous frame, in pixels. */
    fun getMouseDeltaX(): Float = mouseDeltaX
    /** Vertical mouse motion since the previous frame, in pixels. */
    fun getMouseDeltaY(): Float = mouseDeltaY
    fun getScrollDelta(): Float = scrollDelta

    /**
     * Capture the cursor: hides the OS pointer and locks it to the window
     * centre, with raw motion delivered through [getMouseDeltaX] /
     * [getMouseDeltaY]. Required for first-person mouse-look so the
     * cursor can never leave the window or stop generating motion at the
     * screen edge.
     *
     * No-op when called repeatedly with the same state.
     */
    fun setCursorCaptured(captured: Boolean) {
        if (window == 0L || captured == cursorCaptured) return
        cursorCaptured = captured
        if (captured) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
            if (glfwRawMouseMotionSupported()) {
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
            }
            // Suppress the next sample's delta so the cursor-jump that
            // happens when GLFW recentres doesn't translate into a giant
            // yaw/pitch kick on the first captured frame.
            firstMouseSample = true
        } else {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
        }
    }

    fun isCursorCaptured(): Boolean = cursorCaptured

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
        this.window = window
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
            val x = xpos.toFloat()
            val y = ypos.toFloat()
            if (firstMouseSample) {
                // First sample (or first sample after capture) — only
                // record the absolute position so we don't emit a giant
                // delta on the cursor's "jump" to centre.
                firstMouseSample = false
            } else {
                mouseDeltaXAccum += x - lastMouseX
                mouseDeltaYAccum += y - lastMouseY
            }
            lastMouseX = x
            lastMouseY = y
            mouseX = x
            mouseY = y
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
        mouseDeltaX = mouseDeltaXAccum
        mouseDeltaY = mouseDeltaYAccum
        mouseDeltaXAccum = 0f
        mouseDeltaYAccum = 0f
    }
}

