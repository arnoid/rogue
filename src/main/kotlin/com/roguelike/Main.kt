package com.roguelike

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.glutils.ShaderProgram

fun main() {
    // GL 3.2 core profile forbids GLSL 1.10 attribute/varying syntax; remap to in/out.
    ShaderProgram.prependVertexCode = "#version 150\n#define attribute in\n#define varying out\n"
    ShaderProgram.prependFragmentCode = "#version 150\n#define varying in\nout vec4 fragColor;\n#define gl_FragColor fragColor\n#define texture2D texture\n#define textureCube texture\n"

    val config = Lwjgl3ApplicationConfiguration()
    config.setTitle("Roguelike 3D Launcher")
    config.setWindowedMode(1024, 768)
    config.useVsync(true)
    config.setForegroundFPS(60)
    config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2)
    Lwjgl3Application(RoguelikeLauncher(), config)
}
