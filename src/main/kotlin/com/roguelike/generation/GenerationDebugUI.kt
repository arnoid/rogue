package com.roguelike.generation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener

/**
 * Debug UI overlay for the map generation step-through debugger.
 *
 * Shows two buttons when a candidate submap is found:
 * - "I do agree!" (soft pink) → confirms placement
 * - "I do not agree!" (boring gray) → rejects candidate
 */
class GenerationDebugUI(private val stage: Stage, private val skin: Skin) : DebugUICallback {

    private val debugTable = Table()
    private var confirmButton: TextButton? = null
    private var rejectButton: TextButton? = null

    init {
        debugTable.setFillParent(true)
        debugTable.bottom().padBottom(50f)
        debugTable.isVisible = false
        stage.addActor(debugTable)

        setupButtons()
    }

    private fun setupButtons() {
        // Soft pink style for "I do agree!"
        val pinkStyle = TextButton.TextButtonStyle()
        pinkStyle.font = skin.getFont("default")
        val pinkPixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pinkPixmap.setColor(Color(1f, 0.7f, 0.8f, 1f)) // soft pink
        pinkPixmap.fill()
        val pinkTex = Texture(pinkPixmap)
        pinkPixmap.dispose()
        skin.add("pink_bg", pinkTex)
        pinkStyle.up = skin.newDrawable("pink_bg")
        pinkStyle.over = skin.newDrawable("pink_bg", Color(1f, 0.8f, 0.9f, 1f))
        pinkStyle.down = skin.newDrawable("pink_bg", Color(0.9f, 0.5f, 0.6f, 1f))

        // Boring gray style for "I do not agree!"
        val grayStyle = TextButton.TextButtonStyle()
        grayStyle.font = skin.getFont("default")
        val grayPixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        grayPixmap.setColor(Color.GRAY)
        grayPixmap.fill()
        val grayTex = Texture(grayPixmap)
        grayPixmap.dispose()
        skin.add("gray_bg", grayTex)
        grayStyle.up = skin.newDrawable("gray_bg")
        grayStyle.over = skin.newDrawable("gray_bg", Color.LIGHT_GRAY)
        grayStyle.down = skin.newDrawable("gray_bg", Color.DARK_GRAY)

        confirmButton = TextButton("I do agree!", pinkStyle)
        rejectButton = TextButton("I do not agree!", grayStyle)
    }

    override fun showCandidate(candidate: DebugCandidate, onConfirm: () -> Unit, onReject: () -> Unit) {
        debugTable.clear()

        confirmButton?.clearListeners()
        confirmButton?.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                onConfirm()
                hideDebugUI()
            }
        })

        rejectButton?.clearListeners()
        rejectButton?.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                onReject()
                hideDebugUI()
            }
        })

        debugTable.add(confirmButton).width(200f).height(50f).pad(10f)
        debugTable.add(rejectButton).width(200f).height(50f).pad(10f)
        debugTable.isVisible = true
    }

    override fun hideDebugUI() {
        debugTable.isVisible = false
    }
}

