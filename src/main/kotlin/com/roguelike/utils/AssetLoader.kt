package com.roguelike.utils

import org.joml.Vector3f
import org.lwjgl.assimp.*
import org.lwjgl.assimp.Assimp.*
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

/**
 * Mesh data loaded from a model file.
 *
 * @param vertices  6 floats per vertex: px, py, pz, nx, ny, nz
 * @param colors    3 floats per vertex: r, g, b  (sampled from palette texture; null → no texture)
 * @param indices   triangle index list
 */
data class MeshData(
    val vertices: FloatArray,
    val colors: FloatArray?,
    val indices: ShortArray,
    val center: Vector3f,
    val boundingBoxSize: Vector3f,
    val scale: Float
)

/**
 * Loads model assets using LWJGL Assimp bindings.
 * Replaces libGDX's ObjLoader and G3dModelLoader.
 *
 * When a model references a palette texture (via its material's `map_Kd`), the
 * loader reads the PNG, samples each vertex's UV to resolve a colour, and stores
 * per-vertex RGB in [MeshData.colors].
 */
class AssetLoader {
    val models = mutableMapOf<String, MeshData>()

    fun loadModel(name: String, path: String): MeshData {
        models[name]?.let { return it }

        val resolvedPath = if (java.io.File(path).isAbsolute) path
        else {
            // Try classpath resource
            val url = javaClass.classLoader.getResource(path)
            if (url != null) {
                var p = url.toURI().path
                // On Windows, URI path starts with /C:/ — strip leading slash for Assimp
                if (p.length >= 3 && p[0] == '/' && p[2] == ':') {
                    p = p.substring(1)
                }
                p
            } else path
        }

        val scene = aiImportFile(resolvedPath,
            aiProcess_Triangulate or aiProcess_GenNormals or aiProcess_FlipUVs
        ) ?: throw RuntimeException("Failed to load model: $path (${aiGetErrorString()})")

        try {
            val mesh = AIMesh.create(scene.mMeshes()!!.get(0))
            val vertCount = mesh.mNumVertices()
            val positions = mesh.mVertices()
            val normals = mesh.mNormals()

            // --- Load palette texture from the material (if present) ---
            val palettePixels = loadPaletteTexture(scene, mesh, resolvedPath)

            // --- Read UV coordinates (texture channel 0) ---
            val texCoords = mesh.mTextureCoords(0) // AIVector3D.Buffer or null

            // Build vertex data: position + normal (6 floats per vertex)
            val vertices = FloatArray(vertCount * 6)
            val colors: FloatArray? = if (palettePixels != null) FloatArray(vertCount * 3) else null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

            for (i in 0 until vertCount) {
                val pos = positions.get(i)
                val norm = normals?.get(i)
                vertices[i * 6 + 0] = pos.x()
                vertices[i * 6 + 1] = pos.y()
                vertices[i * 6 + 2] = pos.z()
                vertices[i * 6 + 3] = norm?.x() ?: 0f
                vertices[i * 6 + 4] = norm?.y() ?: 0f
                vertices[i * 6 + 5] = norm?.z() ?: 0f
                minX = minOf(minX, pos.x()); minY = minOf(minY, pos.y()); minZ = minOf(minZ, pos.z())
                maxX = maxOf(maxX, pos.x()); maxY = maxOf(maxY, pos.y()); maxZ = maxOf(maxZ, pos.z())

                // Sample palette texture at vertex UV
                if (colors != null && palettePixels != null && texCoords != null) {
                    val uv = texCoords.get(i)
                    val rgb = sampleTexture(palettePixels, uv.x(), uv.y())
                    colors[i * 3 + 0] = rgb[0]
                    colors[i * 3 + 1] = rgb[1]
                    colors[i * 3 + 2] = rgb[2]
                }
            }

            // Free palette image if loaded
            palettePixels?.let { stbi_image_free(it.pixels) }

            // Build index data
            val faceCount = mesh.mNumFaces()
            val faces = mesh.mFaces()
            val indexList = mutableListOf<Short>()
            for (i in 0 until faceCount) {
                val face = faces.get(i)
                val indices = face.mIndices()
                for (j in 0 until face.mNumIndices()) {
                    indexList.add(indices.get(j).toShort())
                }
            }

            val center = Vector3f((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
            val size = Vector3f(maxX - minX, maxY - minY, maxZ - minZ)
            val maxDim = maxOf(size.x, maxOf(size.y, size.z))
            val scale = if (maxDim > 0f) 1.0f / maxDim else 1.0f

            val data = MeshData(vertices, colors, indexList.toShortArray(), center, size, scale)
            models[name] = data
            println("[AssetLoader] Loaded '$name': ${vertCount} verts, hasColors=${colors != null}")
            return data
        } finally {
            aiReleaseImport(scene)
        }
    }

    // ---- Palette texture helpers ----

    /**
     * Holds a loaded RGBA image in CPU memory.
     */
    private data class ImageData(val pixels: ByteBuffer, val width: Int, val height: Int, val channels: Int)

    /**
     * Attempt to load the diffuse texture (`map_Kd`) from the first material of the mesh.
     * Returns [ImageData] if successful, null otherwise.
     */
    private fun loadPaletteTexture(scene: AIScene, mesh: AIMesh, modelPath: String): ImageData? {
        val materialsPtr = scene.mMaterials() ?: return null
        val matIndex = mesh.mMaterialIndex()
        if (matIndex < 0 || matIndex >= scene.mNumMaterials()) return null

        val material = AIMaterial.create(materialsPtr.get(matIndex))

        // Query diffuse texture path
        MemoryStack.stackPush().use { stack ->
            val aiPath = AIString.calloc(stack)
            val result = aiGetMaterialTexture(
                material, aiTextureType_DIFFUSE, 0, aiPath,
                null as IntArray?, null, null, null, null, null
            )
            if (result != aiReturn_SUCCESS) return null

            val texFileName = aiPath.dataString()
            if (texFileName.isBlank()) return null

            // Resolve texture path relative to model file
            val modelDir = java.io.File(modelPath).parentFile
            val texFile = if (modelDir != null) java.io.File(modelDir, texFileName) else java.io.File(texFileName)

            if (!texFile.exists()) {
                println("[AssetLoader] Palette texture not found: ${texFile.absolutePath}")
                return null
            }

            // Load via STB
            val wBuf = stack.mallocInt(1)
            val hBuf = stack.mallocInt(1)
            val cBuf = stack.mallocInt(1)
            stbi_set_flip_vertically_on_load(false)
            val pixels = stbi_load(texFile.absolutePath, wBuf, hBuf, cBuf, 4)
            if (pixels == null) {
                println("[AssetLoader] Failed to load palette texture: ${stbi_failure_reason()}")
                return null
            }

            val w = wBuf.get(0)
            val h = hBuf.get(0)
            println("[AssetLoader] Loaded palette texture: ${texFile.name} (${w}x${h})")
            return ImageData(pixels, w, h, 4)
        }
    }

    /**
     * Sample an RGBA image at the given UV (0-1 range, origin bottom-left after flip)
     * and return normalised RGB [0..1].
     */
    private fun sampleTexture(img: ImageData, u: Float, v: Float): FloatArray {
        val px = (u * img.width).toInt().coerceIn(0, img.width - 1)
        val py = (v * img.height).toInt().coerceIn(0, img.height - 1)
        val offset = (py * img.width + px) * 4 // RGBA
        val r = (img.pixels.get(offset).toInt() and 0xFF) / 255f
        val g = (img.pixels.get(offset + 1).toInt() and 0xFF) / 255f
        val b = (img.pixels.get(offset + 2).toInt() and 0xFF) / 255f
        return floatArrayOf(r, g, b)
    }

    fun getModel(name: String): MeshData? = models[name]

    fun dispose() {
        models.clear()
    }
}
