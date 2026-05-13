package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.RenderContext
import com.badlogic.gdx.graphics.glutils.FrameBufferCubemap
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix3
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.GdxRuntimeException
import com.roguelike.core.model.lighting.GpuLightEnvironment

/**
 * Custom GLSL 1.50 shader for per-pixel mesh-aware lighting and shadow mapping.
 *
 * Supports:
 * - Blinn-Phong diffuse per fragment
 * - Up to 8 point lights with distance attenuation
 * - One directional shadow map (sampler2D)
 * - Up to 8 omnidirectional point-light shadow cubemaps
 * - Configurable ambient term
 *
 * Texture unit allocation:
 *   Unit 0  — diffuse texture
 *   Unit 1  — directional shadow map
 *   Units 2–9 — point-light shadow cubemaps (indices 0–7)
 */
class Gles3LightingShader : Shader {

    lateinit var program: ShaderProgram
        private set

    private lateinit var whiteTexture: Texture

    private val tmpM4 = Matrix4()
    private val tmpM3 = Matrix3()

    // Pending per-frame light data, set before begin() via setLightEnvironment().
    private var pendingEnv: GpuLightEnvironment? = null
    private var pendingShadowMap: Texture? = null
    private val pendingCubemaps = arrayOfNulls<FrameBufferCubemap>(MAX_POINT_LIGHTS)
    private val pendingFarPlanes = FloatArray(MAX_POINT_LIGHTS) { 50f }

    override fun init() {
        val vert = Gdx.files.internal("shaders/gles3_lighting.vert.glsl").readString()
        val frag = Gdx.files.internal("shaders/gles3_lighting.frag.glsl").readString()
        program = ShaderProgram(vert, frag)
        if (!program.isCompiled) {
            throw GdxRuntimeException("Gles3LightingShader compile error:\n${program.log}")
        }
        val pix = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pix.setColor(1f, 1f, 1f, 1f)
        pix.fill()
        whiteTexture = Texture(pix)
        pix.dispose()
    }

    /** Store light data to apply during the next [begin] call. */
    fun setLightEnvironment(env: GpuLightEnvironment) {
        pendingEnv = env
    }

    /** Store directional shadow map texture to bind at unit 1. Null = no directional shadows. */
    fun setShadowMap(shadowMap: Texture?) {
        pendingShadowMap = shadowMap
    }

    /**
     * Store a point-light shadow cubemap for slot [index] (0–7).
     * Pass null fbo to mark the slot as inactive.
     */
    fun setPointShadowCubemap(index: Int, fbo: FrameBufferCubemap?, farPlane: Float) {
        if (index < 0 || index >= MAX_POINT_LIGHTS) return
        pendingCubemaps[index] = fbo
        pendingFarPlanes[index] = farPlane
    }

    override fun begin(camera: Camera, context: RenderContext) {
        program.bind()

        // Camera transform.
        program.setUniformMatrix("u_projViewTrans", camera.combined)

        // Apply stored light environment.
        val env = pendingEnv
        if (env != null) {
            program.setUniformf("u_ambientColor", env.ambientR, env.ambientG, env.ambientB)

            val dir = env.directionalLight
            if (dir != null) {
                program.setUniformi("u_hasDirLight", 1)
                program.setUniformf("u_dirLightDir", dir.directionX, dir.directionY, dir.directionZ)
                program.setUniformf("u_dirLightColor",
                    dir.r * dir.intensity, dir.g * dir.intensity, dir.b * dir.intensity)
            } else {
                program.setUniformi("u_hasDirLight", 0)
            }

            val count = env.pointLights.size.coerceAtMost(MAX_POINT_LIGHTS)
            program.setUniformi("u_pointLightCount", count)
            for (i in 0 until count) {
                val p = env.pointLights[i]
                program.setUniformf("u_pointLightPos[$i]", p.x, p.y, p.z)
                program.setUniformf("u_pointLightColor[$i]", p.r, p.g, p.b)
                program.setUniformf("u_pointLightIntensity[$i]", p.intensity)
            }
        } else {
            program.setUniformf("u_ambientColor", 0.2f, 0.2f, 0.2f)
            program.setUniformi("u_hasDirLight", 0)
            program.setUniformi("u_pointLightCount", 0)
        }

        // Directional shadow map → texture unit 1.
        val sm = pendingShadowMap
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        if (sm != null) {
            sm.bind(1)
            program.setUniformi("u_shadowMap", 1)
        }

        // Point light shadow cubemaps → texture units 2–9.
        for (i in 0 until MAX_POINT_LIGHTS) {
            val fbo = pendingCubemaps[i]
            val unit = 2 + i
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit)
            if (fbo != null) {
                fbo.colorBufferTexture.bind(unit)
                program.setUniformi("u_pointShadowCube[$i]", unit)
                program.setUniformf("u_pointShadowFarPlane[$i]", pendingFarPlanes[i])
                program.setUniformi("u_hasPointShadow[$i]", 1)
            } else {
                program.setUniformi("u_hasPointShadow[$i]", 0)
            }
        }

        // Restore to unit 0 for per-renderable diffuse binding.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
    }

    override fun render(renderable: Renderable) {
        if (renderable.worldTransform.det3x3() == 0f) return

        // World transform.
        program.setUniformMatrix("u_worldTrans", renderable.worldTransform)

        // Normal matrix = upper-left 3x3 of inverse-transpose of worldTrans.
        tmpM4.set(renderable.worldTransform).inv().tra()
        tmpM3.set(tmpM4)
        program.setUniformMatrix("u_normalMatrix", tmpM3)

        // Diffuse texture → unit 0. Tiles use ColorAttribute; items use TextureAttribute.
        val texAttr = renderable.material?.get(TextureAttribute.Diffuse) as? TextureAttribute
        val colorAttr = renderable.material?.get(ColorAttribute.Diffuse) as? ColorAttribute
        if (texAttr != null) {
            texAttr.textureDescription.texture.bind(0)
            program.setUniformf("u_diffuseColor", 1f, 1f, 1f, 1f)
        } else {
            whiteTexture.bind(0)
            val c = colorAttr?.color
            if (c != null) program.setUniformf("u_diffuseColor", c.r, c.g, c.b, c.a)
            else          program.setUniformf("u_diffuseColor", 1f, 1f, 1f, 1f)
        }
        program.setUniformi("u_diffuseTexture", 0)

        // autoBind=true rebinds mesh attributes to this program's locations,
        // avoiding stale VAO state left by the depth pass.
        renderable.meshPart.render(program, true)
    }

    override fun end() {
        // ModelBatch manages RenderContext state.
    }

    override fun canRender(renderable: Renderable): Boolean = true

    override fun compareTo(other: Shader): Int = 0

    override fun dispose() {
        if (::program.isInitialized) program.dispose()
        if (::whiteTexture.isInitialized) whiteTexture.dispose()
    }

    companion object {
        const val MAX_POINT_LIGHTS = 8
    }
}
