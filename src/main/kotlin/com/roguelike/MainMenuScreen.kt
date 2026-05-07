package com.roguelike

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.roguelike.utils.PlatformUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.badlogic.gdx.scenes.scene2d.Actor as S2DActor

class MainMenuScreen(private val game: Game) : Screen {
    private val stage = Stage(ScreenViewport())
    private val skin = Skin()

    init {
        val font = BitmapFont()
        skin.add("default", font)

        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        skin.add("white", Texture(pixmap))

        val textButtonStyle = TextButton.TextButtonStyle()
        textButtonStyle.font = font
        textButtonStyle.up = skin.newDrawable("white", Color.DARK_GRAY)
        textButtonStyle.over = skin.newDrawable("white", Color.LIGHT_GRAY)
        textButtonStyle.down = skin.newDrawable("white", Color.BLACK)

        val table = Table()
        table.setFillParent(true)

        val arenaBtn = TextButton("Arena", textButtonStyle)
        arenaBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                Thread {
                    val path = PlatformUtils.chooseFile("wld")
                    path?.let { filePath ->
                        Gdx.app.postRunnable {
                            game.screen = RoguelikeGame(game, filePath)
                        }
                    }
                }.start()
            }
        })

        val editorBtn = TextButton("Editor", textButtonStyle)
        editorBtn.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: S2DActor) {
                game.screen = MapEditor(game)
            }
        })

        table.add(arenaBtn).width(200f).height(50f).pad(10f)
        table.row()
        table.add(editorBtn).width(200f).height(50f).pad(10f)

        stage.addActor(table)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
        skin.dispose()
    }
}
