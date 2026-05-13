package com.roguelike.rendering

import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider

/**
 * [ShaderProvider] that returns a single shared [Gles3LightingShader] for all renderables.
 * The shader is created lazily on the first call to [getShader].
 */
class Gles3ShaderProvider : ShaderProvider {

    private var shader: Gles3LightingShader? = null

    override fun getShader(renderable: Renderable): Gles3LightingShader = cachedShader()

    /** Return (and lazily create) the cached shader without requiring a Renderable. */
    fun cachedShader(): Gles3LightingShader =
        shader ?: Gles3LightingShader().also { it.init(); shader = it }

    override fun dispose() {
        shader?.dispose()
        shader = null
    }
}
