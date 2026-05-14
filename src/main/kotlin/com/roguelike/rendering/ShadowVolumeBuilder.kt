package com.roguelike.rendering

import org.joml.Vector3f

/**
 * CPU-side shadow volume geometry builder.
 *
 * Given a list of triangles and a light position, constructs a closed shadow
 * volume mesh by:
 * 1. Classifying faces as front-facing or back-facing relative to the light
 * 2. Finding silhouette edges (edges between front and back faces)
 * 3. Extruding silhouette edges away from the light
 * 4. Adding front cap (original front-facing tris) and back cap (extruded back-facing tris)
 */
class ShadowVolumeBuilder {

    data class Triangle(val v0: Vector3f, val v1: Vector3f, val v2: Vector3f) {
        fun normal(): Vector3f {
            val edge1 = Vector3f(v1).sub(v0)
            val edge2 = Vector3f(v2).sub(v0)
            return edge1.cross(edge2).normalize()
        }

        fun centre(): Vector3f = Vector3f(v0).add(v1).add(v2).mul(1f / 3f)
    }

    /** An edge key that is order-independent for adjacency lookup. */
    private data class EdgeKey(val a: Long, val b: Long) {
        companion object {
            fun from(v0: Vector3f, v1: Vector3f): EdgeKey {
                val a = hashVertex(v0)
                val b = hashVertex(v1)
                return if (a <= b) EdgeKey(a, b) else EdgeKey(b, a)
            }

            private fun hashVertex(v: Vector3f): Long {
                // Quantise to avoid floating-point mismatch
                val x = (v.x * 10000).toLong()
                val y = (v.y * 10000).toLong()
                val z = (v.z * 10000).toLong()
                return x * 1000000007L + y * 1000003L + z
            }
        }
    }

    /**
     * Classify triangles as front-facing (toward light) or back-facing (away from light).
     * @return Pair of (frontFacing, backFacing) triangle lists.
     */
    fun classifyFaces(triangles: List<Triangle>, lightPos: Vector3f): Pair<List<Triangle>, List<Triangle>> {
        val front = mutableListOf<Triangle>()
        val back = mutableListOf<Triangle>()

        for (tri in triangles) {
            val normal = tri.normal()
            val toLight = Vector3f(lightPos).sub(tri.centre())
            if (normal.dot(toLight) > 0f) {
                front.add(tri)
            } else {
                back.add(tri)
            }
        }
        return Pair(front, back)
    }

    /**
     * Find silhouette edges — edges shared by exactly one front-facing
     * and one back-facing triangle.
     */
    fun findSilhouetteEdges(triangles: List<Triangle>, lightPos: Vector3f): List<SilhouetteEdge> {
        val (front, back) = classifyFaces(triangles, lightPos)
        val frontEdges = mutableSetOf<EdgeKey>()
        val backEdges = mutableSetOf<EdgeKey>()

        // Collect edges from front-facing triangles
        for (tri in front) {
            frontEdges.add(EdgeKey.from(tri.v0, tri.v1))
            frontEdges.add(EdgeKey.from(tri.v1, tri.v2))
            frontEdges.add(EdgeKey.from(tri.v2, tri.v0))
        }

        // Collect edges from back-facing triangles
        for (tri in back) {
            backEdges.add(EdgeKey.from(tri.v0, tri.v1))
            backEdges.add(EdgeKey.from(tri.v1, tri.v2))
            backEdges.add(EdgeKey.from(tri.v2, tri.v0))
        }

        // Silhouette = edges that appear in BOTH front and back sets
        val silhouetteKeys = frontEdges.intersect(backEdges)

        // Map edge keys back to actual vertices using the triangles
        val edgeVertexMap = mutableMapOf<EdgeKey, Pair<Vector3f, Vector3f>>()
        for (tri in triangles) {
            val edges = listOf(
                Pair(tri.v0, tri.v1),
                Pair(tri.v1, tri.v2),
                Pair(tri.v2, tri.v0)
            )
            for ((a, b) in edges) {
                val key = EdgeKey.from(a, b)
                if (key in silhouetteKeys && key !in edgeVertexMap) {
                    edgeVertexMap[key] = Pair(Vector3f(a), Vector3f(b))
                }
            }
        }

        return edgeVertexMap.values.map { (a, b) -> SilhouetteEdge(a, b) }
    }

    /**
     * Extrude silhouette edges away from the light to create the shadow volume sides.
     * Each edge becomes a quad (2 triangles).
     */
    fun extrudeSilhouette(
        edges: List<SilhouetteEdge>,
        lightPos: Vector3f,
        extrudeDistance: Float = 1000f
    ): ShadowVolumeMesh {
        if (edges.isEmpty()) return ShadowVolumeMesh(FloatArray(0), ShortArray(0), 0, 0)

        val verts = mutableListOf<Float>()
        val inds = mutableListOf<Short>()
        var vertIdx: Short = 0

        for (edge in edges) {
            // Extrude v0 and v1 away from light
            val dir0 = Vector3f(edge.v0).sub(lightPos).normalize().mul(extrudeDistance)
            val dir1 = Vector3f(edge.v1).sub(lightPos).normalize().mul(extrudeDistance)
            val v0ext = Vector3f(edge.v0).add(dir0)
            val v1ext = Vector3f(edge.v1).add(dir1)

            // Add 4 vertices: v0, v1, v1_ext, v0_ext
            fun addVert(v: Vector3f) { verts.add(v.x); verts.add(v.y); verts.add(v.z) }
            addVert(edge.v0)   // vertIdx + 0
            addVert(edge.v1)   // vertIdx + 1
            addVert(v1ext)     // vertIdx + 2
            addVert(v0ext)     // vertIdx + 3

            // Two triangles for the quad
            inds.add(vertIdx); inds.add((vertIdx + 1).toShort()); inds.add((vertIdx + 2).toShort())
            inds.add(vertIdx); inds.add((vertIdx + 2).toShort()); inds.add((vertIdx + 3).toShort())

            vertIdx = (vertIdx + 4).toShort()
        }

        return ShadowVolumeMesh(
            verts.toFloatArray(),
            inds.toShortArray(),
            vertIdx.toInt(),
            inds.size
        )
    }

    /**
     * Build a complete closed shadow volume for the given triangles and light.
     *
     * The volume consists of:
     * - Front cap: original front-facing triangles
     * - Sides: extruded silhouette edges
     * - Back cap: back-facing triangles with vertices extruded to distance
     *
     * @return A closed [ShadowVolumeMesh] suitable for stencil rendering.
     */
    fun buildShadowVolume(
        triangles: List<Triangle>,
        lightPos: Vector3f,
        extrudeDistance: Float = 1000f
    ): ShadowVolumeMesh {
        val (front, back) = classifyFaces(triangles, lightPos)
        val edges = findSilhouetteEdges(triangles, lightPos)

        if (edges.isEmpty()) return ShadowVolumeMesh(FloatArray(0), ShortArray(0), 0, 0)

        val allVerts = mutableListOf<Float>()
        val allInds = mutableListOf<Short>()
        var vertIdx: Short = 0

        fun addVert(v: Vector3f) { allVerts.add(v.x); allVerts.add(v.y); allVerts.add(v.z) }

        // 1. Front cap: original front-facing triangles
        for (tri in front) {
            addVert(tri.v0); addVert(tri.v1); addVert(tri.v2)
            allInds.add(vertIdx); allInds.add((vertIdx + 1).toShort()); allInds.add((vertIdx + 2).toShort())
            vertIdx = (vertIdx + 3).toShort()
        }

        // 2. Sides: extruded silhouette quads
        for (edge in edges) {
            val dir0 = Vector3f(edge.v0).sub(lightPos).normalize().mul(extrudeDistance)
            val dir1 = Vector3f(edge.v1).sub(lightPos).normalize().mul(extrudeDistance)
            val v0ext = Vector3f(edge.v0).add(dir0)
            val v1ext = Vector3f(edge.v1).add(dir1)

            addVert(edge.v0); addVert(edge.v1); addVert(v1ext); addVert(v0ext)
            allInds.add(vertIdx); allInds.add((vertIdx + 1).toShort()); allInds.add((vertIdx + 2).toShort())
            allInds.add(vertIdx); allInds.add((vertIdx + 2).toShort()); allInds.add((vertIdx + 3).toShort())
            vertIdx = (vertIdx + 4).toShort()
        }

        // 3. Back cap: back-facing triangles extruded to distance
        for (tri in back) {
            val dir0 = Vector3f(tri.v0).sub(lightPos).normalize().mul(extrudeDistance)
            val dir1 = Vector3f(tri.v1).sub(lightPos).normalize().mul(extrudeDistance)
            val dir2 = Vector3f(tri.v2).sub(lightPos).normalize().mul(extrudeDistance)
            val v0ext = Vector3f(tri.v0).add(dir0)
            val v1ext = Vector3f(tri.v1).add(dir1)
            val v2ext = Vector3f(tri.v2).add(dir2)
            // Reverse winding for back cap (faces inward)
            addVert(v2ext); addVert(v1ext); addVert(v0ext)
            allInds.add(vertIdx); allInds.add((vertIdx + 1).toShort()); allInds.add((vertIdx + 2).toShort())
            vertIdx = (vertIdx + 3).toShort()
        }

        return ShadowVolumeMesh(
            allVerts.toFloatArray(),
            allInds.toShortArray(),
            vertIdx.toInt(),
            allInds.size
        )
    }
}
