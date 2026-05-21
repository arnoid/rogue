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

            // Cull hidden interior faces typical of voxel OBJ exports.
            // MagicaVoxel-style exporters emit every face of every solid
            // voxel, so interior shared faces appear twice — once for each
            // adjacent voxel — with opposing normals and coincident
            // centroids. Detecting and dropping those pairs typically
            // removes 50-80% of triangles for free.
            val rawIndices = indexList.toShortArray()
            val culledIndices = cullInteriorFaces(vertices, rawIndices)

            val data = MeshData(vertices, colors, culledIndices, center, size, scale)
            models[name] = data
            val before = rawIndices.size / 3
            val after = culledIndices.size / 3
            val pct = if (before > 0) (100.0 * (before - after) / before).toInt() else 0
            println("[AssetLoader] Loaded '$name': ${vertCount} verts, hasColors=${colors != null}, tris=$before→$after (-${pct}% interior)")
            return data
        } finally {
            aiReleaseImport(scene)
        }
    }

    /**
     * Drop interior voxel faces: any triangle whose centroid coincides
     * with another triangle's centroid AND whose face normal points the
     * opposite way. Quantised hashing keeps this O(N) on average — we
     * snap centroids to a small grid (1/1024 of the model's bbox span)
     * so floating-point jitter from the OBJ exporter doesn't break the
     * pairing. Both triangles in a back-to-back pair are removed.
     */
    private fun cullInteriorFaces(vertices: FloatArray, indices: ShortArray): ShortArray {
        val triCount = indices.size / 3
        if (triCount < 2) return indices

        // Compute centroids + normals for every triangle.
        data class Tri(val cx: Float, val cy: Float, val cz: Float,
                       val nx: Float, val ny: Float, val nz: Float)
        val tris = ArrayList<Tri>(triCount)
        // Track model bbox for centroid quantisation.
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until triCount) {
            val i0 = indices[i * 3].toInt() and 0xFFFF
            val i1 = indices[i * 3 + 1].toInt() and 0xFFFF
            val i2 = indices[i * 3 + 2].toInt() and 0xFFFF
            val ax = vertices[i0 * 6];     val ay = vertices[i0 * 6 + 1]; val az = vertices[i0 * 6 + 2]
            val bx = vertices[i1 * 6];     val by = vertices[i1 * 6 + 1]; val bz = vertices[i1 * 6 + 2]
            val cxv = vertices[i2 * 6];    val cyv = vertices[i2 * 6 + 1]; val czv = vertices[i2 * 6 + 2]
            val ccx = (ax + bx + cxv) / 3f
            val ccy = (ay + by + cyv) / 3f
            val ccz = (az + bz + czv) / 3f
            // Face normal via cross product (right-hand rule). Don't
            // trust per-vertex normals — they're smoothed and would
            // pair adjacent non-coplanar faces.
            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cxv - ax; val e2y = cyv - ay; val e2z = czv - az
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 0f) { nx /= nl; ny /= nl; nz /= nl }
            tris.add(Tri(ccx, ccy, ccz, nx, ny, nz))
            if (ccx < minX) minX = ccx; if (ccy < minY) minY = ccy; if (ccz < minZ) minZ = ccz
            if (ccx > maxX) maxX = ccx; if (ccy > maxY) maxY = ccy; if (ccz > maxZ) maxZ = ccz
        }

        // Quantise centroids to 1/1024 of the bbox span on each axis so
        // exporter rounding (typically 1e-4) doesn't break the match.
        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)
        val spanZ = (maxZ - minZ).coerceAtLeast(1e-6f)
        val invQX = 1024f / spanX
        val invQY = 1024f / spanY
        val invQZ = 1024f / spanZ
        fun keyOf(x: Float, y: Float, z: Float): Long {
            val qx = ((x - minX) * invQX).toInt().toLong() and 0xFFFFL
            val qy = ((y - minY) * invQY).toInt().toLong() and 0xFFFFL
            val qz = ((z - minZ) * invQZ).toInt().toLong() and 0xFFFFL
            return (qx shl 32) or (qy shl 16) or qz
        }

        // For each centroid key, remember the first triangle index that
        // produced it. When a later triangle shares the same key AND has
        // an opposing normal (dot < -0.9), mark BOTH as culled.
        val bucket = HashMap<Long, IntArray>(triCount * 2)
        val culled = BooleanArray(triCount)
        for (i in 0 until triCount) {
            val t = tris[i]
            val key = keyOf(t.cx, t.cy, t.cz)
            val existing = bucket[key]
            var matched = false
            if (existing != null) {
                for (j in existing.indices) {
                    val other = existing[j]
                    if (culled[other]) continue
                    val o = tris[other]
                    val dot = t.nx * o.nx + t.ny * o.ny + t.nz * o.nz
                    if (dot < -0.9f) {
                        culled[i] = true
                        culled[other] = true
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                if (existing == null) {
                    bucket[key] = intArrayOf(i)
                } else {
                    // Append; rare enough that a fresh small array is fine.
                    val grown = IntArray(existing.size + 1)
                    System.arraycopy(existing, 0, grown, 0, existing.size)
                    grown[existing.size] = i
                    bucket[key] = grown
                }
            }
        }

        var kept = 0
        for (i in 0 until triCount) if (!culled[i]) kept++
        if (kept == triCount) return indices
        val out = ShortArray(kept * 3)
        var w = 0
        for (i in 0 until triCount) {
            if (culled[i]) continue
            out[w]     = indices[i * 3]
            out[w + 1] = indices[i * 3 + 1]
            out[w + 2] = indices[i * 3 + 2]
            w += 3
        }
        return out
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
