package com.roguelike.rendering.vulkan

import org.lwjgl.util.shaderc.Shaderc
import java.io.File
import java.security.MessageDigest

/**
 * On-disk SPIR-V cache for the project's GLSL shaders.
 *
 * The cache lives in a per-user directory (`%LOCALAPPDATA%/rogue/shader-cache/<tag>/`
 * on Windows, `$XDG_CACHE_HOME/rogue/shader-cache/<tag>/` or `~/.cache/...`
 * elsewhere) and stores two files per shader:
 *
 *  - `<name>.spv`        — compiled SPIR-V bytecode.
 *  - `<name>.spv.meta`   — single-line manifest containing the SHA-256 of the
 *                          original GLSL source, the target Vulkan API tag,
 *                          the shaderc optimisation level and a schema marker.
 *
 * A shader is considered up to date when its `.spv` exists, the `.meta`
 * exists and parses, and every field in the meta matches the current build.
 *
 * The cache lookup order used by [ShaderCompiler.loadShaderModule] is:
 *
 *  1. Disk cache (this class).
 *  2. Pre-baked `.spv` next to the GLSL on the classpath (for release builds
 *     that ship pre-compiled shaders).
 *  3. Runtime compile via shaderc — which also writes the result back into
 *     the disk cache so the next launch is fast.
 */
object ShaderCache {

    /** Bump when the manifest format changes so old caches are invalidated. */
    private const val SCHEMA = 2

    /** Resource paths (classpath, relative to `src/main/resources`). */
    private val ALL_SHADER_RESOURCES: List<String> = listOf(
        "shaders/ui.vert.glsl",
        "shaders/ui.frag.glsl",
        "shaders/world_lit.vert.glsl",
        "shaders/world_lit.frag.glsl",
        "shaders/world_gpu.vert.glsl",
        "shaders/ambient_pass.vert.glsl",
        "shaders/ambient_pass.frag.glsl",
        "shaders/lit_pass.vert.glsl",
        "shaders/lit_pass.frag.glsl",
        "shaders/shadow_volume.vert.glsl",
        "shaders/shadow_volume.frag.glsl"
    )

    /** All shader resource paths known to the project. */
    val allShaders: List<String> get() = ALL_SHADER_RESOURCES

    /** Resolved root directory for cached SPIR-V, keyed by Vulkan API tag. */
    val cacheDir: File by lazy { resolveCacheDir().also { it.mkdirs() } }

    /** SHA-256 of the GLSL source on the classpath, hex-encoded. */
    fun sourceHash(resourcePath: String): String {
        val bytes = javaClass.classLoader.getResourceAsStream(resourcePath)?.readBytes()
            ?: error("Shader resource not found on classpath: $resourcePath")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Translate a classpath path like `shaders/ui.vert.glsl` to a cache key. */
    private fun spvFile(resourcePath: String): File {
        val name = resourcePath.substringAfterLast('/').removeSuffix(".glsl") + ".spv"
        return File(cacheDir, name)
    }

    private fun metaFile(resourcePath: String) = File(spvFile(resourcePath).absolutePath + ".meta")

    /**
     * Returns true when the cached `.spv` for [resourcePath] is present and
     * matches the current source hash, target Vulkan version, and shaderc
     * optimisation level.
     */
    fun isFresh(resourcePath: String): Boolean {
        val spv = spvFile(resourcePath)
        val meta = metaFile(resourcePath)
        if (!spv.isFile || !meta.isFile) return false
        val parsed = parseMeta(meta.readText()) ?: return false
        return parsed["schema"] == SCHEMA.toString() &&
                parsed["vk"] == VulkanVersion.cacheTag &&
                parsed["opt"] == OPT_TAG &&
                parsed["sha256"] == sourceHash(resourcePath)
    }

    /**
     * Return the shaders from [resources] that are NOT up to date and therefore
     * need to be compiled before the renderer can run.
     */
    fun staleShaders(resources: List<String> = ALL_SHADER_RESOURCES): List<String> =
        resources.filterNot { isFresh(it) }

    /**
     * Compile [resources] (skipping ones that are already fresh) and write the
     * resulting SPIR-V into the cache. Invokes [progress] after each shader
     * with `(completed, total, currentName)`.
     */
    fun compileAll(
        resources: List<String> = staleShaders(),
        progress: (completed: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ) {
        val total = resources.size
        for ((idx, path) in resources.withIndex()) {
            val name = path.substringAfterLast('/')
            progress(idx, total, name)
            compileOne(path)
            progress(idx + 1, total, name)
        }
    }

    /** Read the cached SPIR-V bytes for [resourcePath], or null when absent. */
    fun readCachedSpirv(resourcePath: String): ByteArray? {
        val spv = spvFile(resourcePath)
        return if (spv.isFile) spv.readBytes() else null
    }

    /**
     * Compile a single GLSL shader to SPIR-V using shaderc with the project's
     * optimisation settings, then write the bytes + manifest into the cache.
     */
    private fun compileOne(resourcePath: String) {
        val source = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.bufferedReader()?.readText()
            ?: error("Shader source not found: $resourcePath")

        val kind = when {
            resourcePath.contains(".vert") -> Shaderc.shaderc_vertex_shader
            resourcePath.contains(".frag") -> Shaderc.shaderc_fragment_shader
            else -> error("Unknown shader kind: $resourcePath")
        }

        val spirv = ShaderCompiler.compileGlslToSpirv(source, kind, resourcePath)
        spvFile(resourcePath).writeBytes(spirv)
        metaFile(resourcePath).writeText(buildMeta(resourcePath))
    }

    private fun buildMeta(resourcePath: String): String {
        val sha = sourceHash(resourcePath)
        // Single-line key=value;... format. Simple and robust without a JSON dep.
        return "schema=$SCHEMA;vk=${VulkanVersion.cacheTag};opt=$OPT_TAG;sha256=$sha\n"
    }

    private fun parseMeta(text: String): Map<String, String>? {
        val line = text.trim().lineSequence().firstOrNull() ?: return null
        val map = HashMap<String, String>()
        for (chunk in line.split(';')) {
            val eq = chunk.indexOf('=')
            if (eq < 0) continue
            map[chunk.substring(0, eq).trim()] = chunk.substring(eq + 1).trim()
        }
        return if (map.isEmpty()) null else map
    }

    /** Marker stored in `.meta` so cache flips when we change opt levels. */
    private const val OPT_TAG = "perf"

    private fun resolveCacheDir(): File {
        val tag = VulkanVersion.cacheTag
        // Windows: %LOCALAPPDATA%\rogue\shader-cache\<tag>
        val local = System.getenv("LOCALAPPDATA")
        if (!local.isNullOrBlank()) {
            return File(File(local, "rogue/shader-cache"), tag)
        }
        // XDG / Linux: $XDG_CACHE_HOME or ~/.cache/rogue/shader-cache/<tag>
        val xdg = System.getenv("XDG_CACHE_HOME")
        val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".cache")
        return File(File(base, "rogue/shader-cache"), tag)
    }
}

