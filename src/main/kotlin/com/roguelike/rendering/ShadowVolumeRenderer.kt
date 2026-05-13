package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable

/**
 * Orchestrates the multi-pass stencil shadow volume rendering pipeline.
 *
 * Render sequence per frame:
 * 1. Ambient pass: render full scene with ambient-only lighting (writes colour + depth)
 * 2. For each active light:
 *    a. Clear stencil
 *    b. Stencil pass: render shadow volumes with depth-fail to mark shadowed areas
 *    c. Lit pass: render scene where stencil==0 with additive blending
 */
class ShadowVolumeRenderer(
    private val shaderProvider: ShadowVolumeShaderProvider,
    private val ambientR: Float = 0.08f,
    private val ambientG: Float = 0.08f,
    private val ambientB: Float = 0.10f
) : Disposable {

    private val builder = ShadowVolumeBuilder()
    private val identity = Matrix4()

    // Reusable mesh for shadow volume geometry — resized as needed
    private var svMesh: Mesh? = null
    private var svMeshMaxVerts = 0
    private var svMeshMaxInds = 0

    /**
     * Render one frame with shadow volumes.
     *
     * @param camera       Scene camera.
     * @param lights       Active point lights this frame.
     * @param occluders    List of triangle lists representing occluder geometry.
     * @param renderScene  Callback that renders the full scene into the given [ModelBatch].
     *                     The batch is already begun; the callback must NOT call begin/end.
     */
    fun render(
        camera: Camera,
        lights: List<PointLightData>,
        occluders: List<List<ShadowVolumeBuilder.Triangle>>,
        renderScene: (batch: ModelBatch, env: Environment) -> Unit
    ) {
        val gl = Gdx.gl

        // ── Pass 1: Ambient ──────────────────────────────────────────────
        // Clear stencil along with colour+depth
        gl.glClearColor(0f, 0f, 0f, 1f)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT or GL20.GL_STENCIL_BUFFER_BIT)

        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glDepthMask(true)

        val ambientShader = shaderProvider.getAmbientShader()
        ambientShader.bind()
        ambientShader.setUniformMatrix("u_projViewTrans", camera.combined)
        ambientShader.setUniformf("u_ambientColor", ambientR, ambientG, ambientB)

        // Render scene geometry with ambient shader
        // For now we pass through using ModelBatch for the main scene
        val ambientBatch = ModelBatch()
        ambientBatch.begin(camera)
        renderScene(ambientBatch, Environment())
        ambientBatch.end()
        ambientBatch.dispose()

        // ── Pass 2: Per-light stencil + lit ──────────────────────────────
        for (light in lights) {
            // 2a. Clear stencil to 0
            gl.glClear(GL20.GL_STENCIL_BUFFER_BIT)

            // 2b. Stencil pass: render shadow volumes with depth-fail
            gl.glEnable(GL20.GL_STENCIL_TEST)
            gl.glDepthMask(false)          // Don't write to depth
            gl.glColorMask(false, false, false, false) // Don't write to colour

            val svShader = shaderProvider.getShadowVolumeShader()
            svShader.bind()
            svShader.setUniformMatrix("u_projViewTrans", camera.combined)
            svShader.setUniformMatrix("u_worldTrans", identity)

            // Build shadow volumes for all occluders
            for (occluderTris in occluders) {
                val volume = builder.buildShadowVolume(occluderTris, light.position, light.radius * 10f)
                if (volume.indexCount == 0) continue

                val mesh = getOrCreateMesh(volume)

                // Front-face pass: cull back, increment on depth FAIL
                gl.glEnable(GL20.GL_CULL_FACE)
                gl.glCullFace(GL20.GL_BACK)
                gl.glStencilFunc(GL20.GL_ALWAYS, 0, 0xFF.toInt())
                gl.glStencilOp(GL20.GL_KEEP, GL20.GL_INCR_WRAP, GL20.GL_KEEP)
                mesh.render(svShader, GL20.GL_TRIANGLES, 0, volume.indexCount)

                // Back-face pass: cull front, decrement on depth FAIL
                gl.glCullFace(GL20.GL_FRONT)
                gl.glStencilOp(GL20.GL_KEEP, GL20.GL_DECR_WRAP, GL20.GL_KEEP)
                mesh.render(svShader, GL20.GL_TRIANGLES, 0, volume.indexCount)
            }

            // 2c. Lit pass: render scene where stencil == 0
            gl.glColorMask(true, true, true, true)
            gl.glDepthMask(false)          // Keep depth from ambient pass
            gl.glCullFace(GL20.GL_BACK)

            // Stencil test: pass only where stencil == 0 (not in shadow)
            gl.glStencilFunc(GL20.GL_EQUAL, 0, 0xFF.toInt())
            gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP)

            // Additive blending so multiple lights accumulate
            gl.glEnable(GL20.GL_BLEND)
            gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE)

            val litShader = shaderProvider.getLitPassShader()
            litShader.bind()
            litShader.setUniformMatrix("u_projViewTrans", camera.combined)
            litShader.setUniformf("u_LightPos", light.position.x, light.position.y, light.position.z)
            litShader.setUniformf("u_LightColor", light.color.r, light.color.g, light.color.b)
            litShader.setUniformf("u_LightIntensity", light.intensity)
            litShader.setUniformf("u_LightRadius", light.radius)

            val litBatch = ModelBatch()
            litBatch.begin(camera)
            renderScene(litBatch, Environment())
            litBatch.end()
            litBatch.dispose()

            gl.glDisable(GL20.GL_BLEND)
            gl.glDisable(GL20.GL_STENCIL_TEST)
        }

        // Restore state
        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_STENCIL_TEST)
        gl.glDisable(GL20.GL_BLEND)
    }

    private fun getOrCreateMesh(volume: ShadowVolumeMesh): Mesh {
        if (svMesh == null || volume.vertexCount > svMeshMaxVerts || volume.indexCount > svMeshMaxInds) {
            svMesh?.dispose()
            svMeshMaxVerts = (volume.vertexCount * 1.5f).toInt().coerceAtLeast(1024)
            svMeshMaxInds = (volume.indexCount * 1.5f).toInt().coerceAtLeast(2048)
            svMesh = Mesh(
                false, svMeshMaxVerts, svMeshMaxInds,
                VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position")
            )
        }
        svMesh!!.setVertices(volume.vertices, 0, volume.vertexCount * 3)
        svMesh!!.setIndices(volume.indices, 0, volume.indexCount)
        return svMesh!!
    }

    override fun dispose() {
        svMesh?.dispose()
        svMesh = null
    }
}

