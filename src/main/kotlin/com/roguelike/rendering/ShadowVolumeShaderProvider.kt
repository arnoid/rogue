package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException

/**
 * Loads and manages the three shader programs used by the shadow volume pipeline:
 * - Shadow volume pass (position-only, no-op fragment)
 * - Ambient pass (texture * ambient colour)
 * - Lit pass (per-light diffuse + attenuation)
 */
class ShadowVolumeShaderProvider : Disposable {

    private var shadowVolumeShader: ShaderProgram? = null
    private var ambientShader: ShaderProgram? = null
    private var litPassShader: ShaderProgram? = null

    fun getShadowVolumeShader(): ShaderProgram {
        if (shadowVolumeShader == null) {
            shadowVolumeShader = loadShader("shadow_volume")
        }
        return shadowVolumeShader!!
    }

    fun getAmbientShader(): ShaderProgram {
        if (ambientShader == null) {
            ambientShader = loadShader("ambient_pass")
        }
        return ambientShader!!
    }

    fun getLitPassShader(): ShaderProgram {
        if (litPassShader == null) {
            litPassShader = loadShader("lit_pass")
        }
        return litPassShader!!
    }

    private fun loadShader(name: String): ShaderProgram {
        val vert = Gdx.files.internal("shaders/$name.vert.glsl").readString()
        val frag = Gdx.files.internal("shaders/$name.frag.glsl").readString()
        val program = ShaderProgram(vert, frag)
        if (!program.isCompiled) {
            throw GdxRuntimeException("Shader '$name' compile error:\n${program.log}")
        }
        return program
    }

    override fun dispose() {
        shadowVolumeShader?.dispose()
        ambientShader?.dispose()
        litPassShader?.dispose()
        shadowVolumeShader = null
        ambientShader = null
        litPassShader = null
    }
}

