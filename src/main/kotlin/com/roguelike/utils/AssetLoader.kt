package com.roguelike.utils

import org.joml.Vector3f
import org.lwjgl.assimp.*
import org.lwjgl.assimp.Assimp.*

/**
 * Mesh data loaded from a model file.
 */
data class MeshData(
    val vertices: FloatArray,
    val indices: ShortArray,
    val center: Vector3f,
    val boundingBoxSize: Vector3f,
    val scale: Float
)

/**
 * Loads model assets using LWJGL Assimp bindings.
 * Replaces libGDX's ObjLoader and G3dModelLoader.
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

            // Build vertex data: position + normal (6 floats per vertex)
            val vertices = FloatArray(vertCount * 6)
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
            }

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

            val data = MeshData(vertices, indexList.toShortArray(), center, size, scale)
            models[name] = data
            return data
        } finally {
            aiReleaseImport(scene)
        }
    }

    fun getModel(name: String): MeshData? = models[name]

    fun dispose() {
        models.clear()
    }
}
