package com.roguelike

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration

fun main() {
    val config = Lwjgl3ApplicationConfiguration()
    config.setTitle("Roguelike 3D Launcher")
    config.setWindowedMode(1024, 768)
    config.useVsync(true)
    config.setForegroundFPS(60)
    Lwjgl3Application(RoguelikeLauncher(), config)
}
