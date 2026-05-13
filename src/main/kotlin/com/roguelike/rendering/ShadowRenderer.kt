package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider
import com.badlogic.gdx.graphics.glutils.FrameBufferCubemap
import com.badlogic.gdx.math.Vector3
import com.roguelike.core.model.Actor
import com.roguelike.core.model.isLit
import com.roguelike.core.model.lighting.DirectionalLightData
import com.roguelike.core.model.lighting.GpuLightEnvironment
import com.roguelike.core.model.lighting.PointLightData

/**
 * GPU shadow rendering pipeline using custom GLSL 1.50 shaders.
 *
 * Render order per frame:
 *   1. For each active point light: render scene depth into a [FrameBufferCubemap].
 *   2. If a directional light is configured: render scene depth into [DirectionalShadowLight]'s FBO.
 *   3. Main pass: bind all shadow textures, set lighting uniforms, render scene via [Gles3LightingShader].
 *
 * Usage:
 * ```
 * shadowRenderer.render(camera, gpuLightEnv) { batch, env ->
 *     worldRenderer.render(world, batch, env, maxZ)
 * }
 * ```
 */
class ShadowRenderer {

    private val shaderProvider = Gles3ShaderProvider()
    private val mainBatch  = ModelBatch(shaderProvider)
    private val shadowBatch = ModelBatch(DepthShaderProvider())

    private var shadowLight: DirectionalShadowLight? = null
    private var sceneCentre = Vector3.Zero.cpy()

    private val cubemapPool = arrayOfNulls<FrameBufferCubemap>(MAX_POINT_LIGHTS)
    val cubemapFarPlane = FloatArray(MAX_POINT_LIGHTS) { 50f }
    private val cubemapCamera = PerspectiveCamera(90f, CUBEMAP_SIZE.toFloat(), CUBEMAP_SIZE.toFloat())

    /**
     * Configure (or replace) the directional shadow light.
     * Call before the first [render]; safe to call every frame to update direction/colour.
     */
    fun initDirectionalLight(data: DirectionalLightData) {
        shadowLight?.dispose()
        val light = DirectionalShadowLight(2048, 2048, 60f, 60f, 1f, 300f)
        light.set(
            data.r * data.intensity, data.g * data.intensity, data.b * data.intensity,
            data.directionX, data.directionY, data.directionZ
        )
        shadowLight = light
    }

    /** Move the directional shadow frustum centre (e.g., to follow the player). */
    fun setSceneCentre(x: Float, y: Float, z: Float) {
        sceneCentre.set(x, y, z)
    }

    /**
     * Execute the full render pipeline for one frame.
     *
     * @param camera The scene camera for the main pass.
     * @param gpuLightEnv Per-frame light data.
     * @param renderScene Callback invoked for each pass (depth and main). The provided
     *   [ModelBatch] is already begun; do NOT call begin/end inside the lambda.
     */
    fun render(
        camera: Camera,
        gpuLightEnv: GpuLightEnvironment,
        renderScene: (batch: ModelBatch, env: Environment) -> Unit
    ) {
        // Pass 1: omnidirectional depth for each active point light.
        for (i in gpuLightEnv.pointLights.indices) {
            renderCubemapDepthPass(i, gpuLightEnv.pointLights[i], renderScene)
        }

        // Pass 2: directional depth pass.
        val sl = shadowLight
        if (sl != null) {
            sl.begin(sceneCentre, sl.direction)
            shadowBatch.begin(sl.camera)
            renderScene(shadowBatch, Environment())
            shadowBatch.end()
            sl.end()
        }

        // Pass 3: main lit pass — configure shader and render scene.
        val litShader = shaderProvider.cachedShader()
        litShader.setLightEnvironment(gpuLightEnv)
        litShader.setShadowMap(sl?.frameBuffer?.colorBufferTexture)

        // Wire cubemaps (active lights) and clear inactive slots.
        for (i in 0 until MAX_POINT_LIGHTS) {
            litShader.setPointShadowCubemap(i, cubemapPool[i], cubemapFarPlane[i])
        }

        mainBatch.begin(camera)
        renderScene(mainBatch, Environment())
        mainBatch.end()
    }

    /**
     * Render scene depth into a [FrameBufferCubemap] for point light at [index].
     * The scene is rendered 6 times — once per cube face.
     */
    fun renderCubemapDepthPass(
        index: Int,
        lightData: PointLightData,
        renderScene: (batch: ModelBatch, env: Environment) -> Unit
    ) {
        if (index < 0 || index >= MAX_POINT_LIGHTS) return

        val fbo = cubemapPool[index] ?: FrameBufferCubemap(
            Pixmap.Format.RGBA8888, CUBEMAP_SIZE, CUBEMAP_SIZE, true
        ).also { cubemapPool[index] = it }

        val farPlane = lightData.intensity.coerceAtLeast(1f)
        cubemapFarPlane[index] = farPlane

        val lightPos = Vector3(lightData.x, lightData.y, lightData.z)
        fbo.begin()
        while (fbo.nextSide()) {
            Gdx.gl.glClearColor(1f, 1f, 1f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
            cubemapCamera.position.set(lightPos)
            fbo.side.getDirection(cubemapCamera.direction)
            fbo.side.getUp(cubemapCamera.up)
            cubemapCamera.near = 0.1f
            cubemapCamera.far = farPlane
            cubemapCamera.update()
            shadowBatch.begin(cubemapCamera)
            renderScene(shadowBatch, Environment())
            shadowBatch.end()
        }
        fbo.end()
    }

    fun dispose() {
        mainBatch.dispose()
        shadowBatch.dispose()
        shaderProvider.dispose()
        shadowLight?.dispose()
        shadowLight = null
        cubemapPool.forEach { it?.dispose() }
        cubemapPool.fill(null)
    }

    companion object {
        const val CUBEMAP_SIZE = 512
        const val MAX_POINT_LIGHTS = 8

        /**
         * Build a [GpuLightEnvironment] from lit inventory items carried by [actor].
         * Each lit item contributes a [PointLightData] at the actor's current world position.
         */
        fun fromActor(
            actor: Actor,
            ambientR: Float = 0.2f,
            ambientG: Float = 0.2f,
            ambientB: Float = 0.2f
        ): GpuLightEnvironment {
            val pointLights = mutableListOf<PointLightData>()
            for (item in actor.inventory) {
                if (!item.isLit()) continue
                val def = item.definition?.light ?: continue
                val hex = def.colorHex
                val r: Float; val g: Float; val b: Float
                if (hex.length >= 6) {
                    val v = hex.trimStart('#')
                    r = v.substring(0, 2).toInt(16) / 255f
                    g = v.substring(2, 4).toInt(16) / 255f
                    b = v.substring(4, 6).toInt(16) / 255f
                } else {
                    r = 1f; g = 1f; b = 1f
                }
                pointLights += PointLightData(
                    x = actor.position.x,
                    y = actor.position.y,
                    z = actor.position.z + 0.5f,
                    r = r, g = g, b = b,
                    intensity = def.intensity * def.range
                )
            }
            return GpuLightEnvironment.build(
                directionalLight = null,
                pointLights = pointLights,
                ambientR = ambientR,
                ambientG = ambientG,
                ambientB = ambientB
            )
        }
    }
}
