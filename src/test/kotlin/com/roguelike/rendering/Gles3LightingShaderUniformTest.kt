package com.roguelike.rendering

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class Gles3LightingShaderUniformTest {

    private val resourcesDir: File by lazy {
        // Walk up from the test working directory to find src/main/resources/shaders/
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(6)
            .map { File(it, "src/main/resources/shaders") }
            .first { it.exists() }
    }

    private fun vertSrc(): String = File(resourcesDir, "gles3_lighting.vert.glsl").readText()
    private fun fragSrc(): String = File(resourcesDir, "gles3_lighting.frag.glsl").readText()

    @Test
    fun `vertex shader declares geometry uniforms`() {
        val src = vertSrc()
        listOf("u_projViewTrans", "u_worldTrans", "u_normalMatrix", "u_shadowMapProjViewTrans").forEach {
            assertTrue(it in src, "vertex shader missing uniform: $it")
        }
    }

    @Test
    fun `vertex shader declares expected outputs`() {
        val src = vertSrc()
        listOf("v_texCoord", "v_worldPos", "v_worldNormal", "v_shadowCoord").forEach {
            assertTrue(it in src, "vertex shader missing output: $it")
        }
    }

    @Test
    fun `fragment shader declares ambient and diffuse uniforms`() {
        val src = fragSrc()
        listOf("u_ambientColor", "u_diffuseTexture", "u_diffuseColor").forEach {
            assertTrue(it in src, "fragment shader missing uniform: $it")
        }
    }

    @Test
    fun `fragment shader declares directional light uniforms`() {
        val src = fragSrc()
        listOf("u_hasDirLight", "u_dirLightDir", "u_dirLightColor", "u_shadowMap").forEach {
            assertTrue(it in src, "fragment shader missing uniform: $it")
        }
    }

    @Test
    fun `fragment shader declares point light uniforms`() {
        val src = fragSrc()
        listOf(
            "u_pointLightCount",
            "u_pointLightPos",
            "u_pointLightColor",
            "u_pointLightIntensity"
        ).forEach {
            assertTrue(it in src, "fragment shader missing uniform: $it")
        }
    }

    @Test
    fun `fragment shader declares point shadow cubemap uniforms`() {
        val src = fragSrc()
        listOf(
            "u_hasPointShadow",
            "u_pointShadowCube",
            "u_pointShadowFarPlane"
        ).forEach {
            assertTrue(it in src, "fragment shader missing cubemap uniform: $it")
        }
    }

    @Test
    fun `fragment shader file does not contain version directive`() {
        // #version is injected by ShaderProgram.prependFragmentCode in Main.kt;
        // a second #version in the file body causes a compile error.
        assertTrue(!fragSrc().contains("#version"),
            "fragment shader file must not contain #version (it is prepended by Main.kt)")
    }

    @Test
    fun `vertex shader file does not contain version directive`() {
        // #version is injected by ShaderProgram.prependVertexCode in Main.kt;
        // a second #version in the file body causes a compile error.
        assertTrue(!vertSrc().contains("#version"),
            "vertex shader file must not contain #version (it is prepended by Main.kt)")
    }
}
