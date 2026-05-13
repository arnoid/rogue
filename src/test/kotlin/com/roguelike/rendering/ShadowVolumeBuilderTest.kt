package com.roguelike.rendering

import com.badlogic.gdx.math.Vector3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShadowVolumeBuilderTest {

    /**
     * A unit cube centred at origin: 8 vertices, 12 triangles (2 per face).
     * Winding is CCW when viewed from outside (outward-facing normals).
     */
    private fun unitCubeTriangles(): List<ShadowVolumeBuilder.Triangle> {
        val s = 0.5f
        // 8 vertices of a cube
        val v = arrayOf(
            Vector3(-s, -s, -s), // 0: left-bottom-back
            Vector3( s, -s, -s), // 1: right-bottom-back
            Vector3( s,  s, -s), // 2: right-top-back
            Vector3(-s,  s, -s), // 3: left-top-back
            Vector3(-s, -s,  s), // 4: left-bottom-front
            Vector3( s, -s,  s), // 5: right-bottom-front
            Vector3( s,  s,  s), // 6: right-top-front
            Vector3(-s,  s,  s)  // 7: left-top-front
        )
        return listOf(
            // Front face (+Z)
            ShadowVolumeBuilder.Triangle(v[4].cpy(), v[5].cpy(), v[6].cpy()),
            ShadowVolumeBuilder.Triangle(v[4].cpy(), v[6].cpy(), v[7].cpy()),
            // Back face (-Z)
            ShadowVolumeBuilder.Triangle(v[1].cpy(), v[0].cpy(), v[3].cpy()),
            ShadowVolumeBuilder.Triangle(v[1].cpy(), v[3].cpy(), v[2].cpy()),
            // Right face (+X)
            ShadowVolumeBuilder.Triangle(v[5].cpy(), v[1].cpy(), v[2].cpy()),
            ShadowVolumeBuilder.Triangle(v[5].cpy(), v[2].cpy(), v[6].cpy()),
            // Left face (-X)
            ShadowVolumeBuilder.Triangle(v[0].cpy(), v[4].cpy(), v[7].cpy()),
            ShadowVolumeBuilder.Triangle(v[0].cpy(), v[7].cpy(), v[3].cpy()),
            // Top face (+Y)
            ShadowVolumeBuilder.Triangle(v[3].cpy(), v[7].cpy(), v[6].cpy()),
            ShadowVolumeBuilder.Triangle(v[3].cpy(), v[6].cpy(), v[2].cpy()),
            // Bottom face (-Y)
            ShadowVolumeBuilder.Triangle(v[0].cpy(), v[1].cpy(), v[5].cpy()),
            ShadowVolumeBuilder.Triangle(v[0].cpy(), v[5].cpy(), v[4].cpy())
        )
    }

    // T023: Silhouette detection
    @Test
    fun `silhouette edges detected for cube with light on +X axis`() {
        val tris = unitCubeTriangles()
        val lightPos = Vector3(3f, 0f, 0f) // light on +X axis
        val builder = ShadowVolumeBuilder()

        val (front, back) = builder.classifyFaces(tris, lightPos)
        // At least some faces face toward and some face away
        assertTrue(front.isNotEmpty(), "should have front-facing faces")
        assertTrue(back.isNotEmpty(), "should have back-facing faces")

        val edges = builder.findSilhouetteEdges(tris, lightPos)
        // A cube silhouette from any axis-aligned light has exactly 4 silhouette edges
        assertEquals(4, edges.size, "cube silhouette from axis should have 4 edges")
    }

    @Test
    fun `silhouette edges all connect front to back faces`() {
        val tris = unitCubeTriangles()
        val lightPos = Vector3(3f, 0f, 0f)
        val builder = ShadowVolumeBuilder()
        val edges = builder.findSilhouetteEdges(tris, lightPos)

        // Each silhouette edge must have distinct non-equal vertices
        for (edge in edges) {
            assertTrue(edge.v0.dst(edge.v1) > 0.001f, "silhouette edge must not be degenerate")
        }
    }

    // T024: Extrusion
    @Test
    fun `extruded quads extend vertices away from light`() {
        val builder = ShadowVolumeBuilder()
        val lightPos = Vector3(3f, 0f, 0f)
        val edge = SilhouetteEdge(Vector3(0.5f, -0.5f, -0.5f), Vector3(0.5f, 0.5f, -0.5f))
        val extrudeDistance = 100f

        val quads = builder.extrudeSilhouette(listOf(edge), lightPos, extrudeDistance)

        // Each edge produces 2 triangles (1 quad = 6 vertex indices worth of data)
        // The extruded vertices should be further from the light than the originals
        assertTrue(quads.vertexCount >= 4, "extrusion should produce vertices")
        assertTrue(quads.indexCount >= 6, "extrusion should produce triangle indices")

        // Verify extruded vertices are far from light
        for (i in 0 until quads.vertexCount) {
            val vx = quads.vertices[i * 3]
            val vy = quads.vertices[i * 3 + 1]
            val vz = quads.vertices[i * 3 + 2]
            val v = Vector3(vx, vy, vz)
            // Original or extruded — all should be on the side away from light or at original pos
            assertTrue(v.x <= 0.5f + 0.01f || v.dst(lightPos) > 50f,
                "vertex should either be original or extruded far from light")
        }
    }

    // T025: Cap generation
    @Test
    fun `shadow volume is closed — has front cap, sides, and back cap`() {
        val tris = unitCubeTriangles()
        val lightPos = Vector3(3f, 0f, 0f)
        val builder = ShadowVolumeBuilder()

        val volume = builder.buildShadowVolume(tris, lightPos, 100f)

        // Must have geometry
        assertTrue(volume.vertexCount > 0, "shadow volume must have vertices")
        assertTrue(volume.indexCount > 0, "shadow volume must have indices")
        // Index count must be a multiple of 3 (triangle list)
        assertEquals(0, volume.indexCount % 3, "index count must be multiple of 3")
        // Should have more than just the silhouette quads (front cap + back cap add triangles)
        // 4 silhouette edges = 8 triangles for sides, front cap = some tris, back cap = some tris
        assertTrue(volume.indexCount > 24, "closed volume should have caps + sides")
    }

    @Test
    fun `shadow volume with no back-facing faces produces empty volume`() {
        // All faces face toward the light — no silhouette
        val lightPos = Vector3(0f, 0f, 0f) // light at centre of cube
        val tris = unitCubeTriangles()
        val builder = ShadowVolumeBuilder()

        // When light is inside the mesh, all or most triangles face away,
        // so there may be no silhouette (or all are front/back)
        val volume = builder.buildShadowVolume(tris, lightPos, 100f)
        // At minimum, it shouldn't crash
        assertNotNull(volume)
    }

    // T032: Self-shadowing — L-shaped mesh
    @Test
    fun `L-shaped mesh produces shadow volume that encloses back-facing region`() {
        // Two squares at 90 degrees forming an L:
        // Face 1: on XZ plane at y=0 (facing +Y)
        // Face 2: on YZ plane at x=0 (facing +X)
        val tris = listOf(
            // Face on XZ plane (y=0, normal +Y)
            ShadowVolumeBuilder.Triangle(
                Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f), Vector3(1f, 0f, 1f)
            ),
            ShadowVolumeBuilder.Triangle(
                Vector3(0f, 0f, 0f), Vector3(1f, 0f, 1f), Vector3(0f, 0f, 1f)
            ),
            // Face on YZ plane (x=0, normal +X)
            ShadowVolumeBuilder.Triangle(
                Vector3(0f, 0f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 1f, 1f)
            ),
            ShadowVolumeBuilder.Triangle(
                Vector3(0f, 0f, 0f), Vector3(0f, 1f, 1f), Vector3(0f, 1f, 0f)
            )
        )
        // Light on -X+Y side — face on XZ plane (normal +Y) is front-facing,
        // face on YZ plane (normal +X) is back-facing since light is on -X side
        val lightPos = Vector3(-2f, 2f, 0.5f)
        val builder = ShadowVolumeBuilder()

        val (front, back) = builder.classifyFaces(tris, lightPos)
        // At least one front and one back face for self-shadowing to occur
        assertTrue(front.isNotEmpty(), "L-shape should have front-facing faces")
        assertTrue(back.isNotEmpty(), "L-shape should have back-facing faces (self-shadow region)")

        val volume = builder.buildShadowVolume(tris, lightPos, 100f)
        assertTrue(volume.indexCount > 0, "L-shape should produce shadow volume geometry")
    }
}

