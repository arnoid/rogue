package com.roguelike.generation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenerationDebugUITest {

    @Test
    fun `confirm button label is exactly I do agree!`() {
        assertEquals("I do agree!", GenerationDebugUI.CONFIRM_LABEL)
    }

    @Test
    fun `reject button label is exactly I do not agree!`() {
        assertEquals("I do not agree!", GenerationDebugUI.REJECT_LABEL)
    }

    @Test
    fun `confirm button color is soft pink`() {
        assertEquals(1f, GenerationDebugUI.CONFIRM_COLOR_R)
        assertEquals(0.7f, GenerationDebugUI.CONFIRM_COLOR_G)
        assertEquals(0.8f, GenerationDebugUI.CONFIRM_COLOR_B)
    }

    @Test
    fun `reject button color is neutral gray`() {
        assertEquals(0.5f, GenerationDebugUI.REJECT_COLOR_R)
        assertEquals(0.5f, GenerationDebugUI.REJECT_COLOR_G)
        assertEquals(0.5f, GenerationDebugUI.REJECT_COLOR_B)
    }
}
