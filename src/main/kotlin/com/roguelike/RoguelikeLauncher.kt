package com.roguelike

import com.badlogic.gdx.Game

class RoguelikeLauncher : Game() {
    override fun create() {
        setScreen(MainMenuScreen(this))
    }
}
