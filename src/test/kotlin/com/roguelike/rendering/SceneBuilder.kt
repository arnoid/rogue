package com.roguelike.rendering

import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Immutable scene data ready for rendering through the shadow volume pipeline.
 */
data class TestScene(
    val lights: List<PointLightData>,
    val occluderTriangles: List<List<ShadowVolumeBuilder.Triangle>>,
    val camera: Camera,
) {
    fun dispose() { /* TODO: dispose VulkanMesh resources when implemented */ }
}

/**
 * Sealed descriptions of geometry to add — no GPU calls needed.
 */
private sealed class GeomDesc {
    data class Box(val position: Vector3f, val size: Vector3f, val color: Vector4f, val occluder: Boolean) : GeomDesc()
    data class Sphere(val position: Vector3f, val radius: Float, val color: Vector4f) : GeomDesc()
    data class Plane(val position: Vector3f, val normal: Vector3f, val size: Float, val color: Vector4f) : GeomDesc()
    data class Wall(val position: Vector3f, val width: Float, val height: Float, val color: Vector4f,
                    val thickness: Float, val facing: Vector3f) : GeomDesc()
}

/**
 * DSL-style builder for constructing test scenes programmatically.
 */
class SceneBuilder {
    private val descriptions = mutableListOf<GeomDesc>()
    private val lights = mutableListOf<PointLightData>()
    private var cameraPosition = Vector3f(0f, 5f, 10f)
    private var cameraLookAt = Vector3f(0f, 0f, 0f)
    private var cameraFov = 67f

    fun addBox(position: Vector3f, size: Vector3f, color: Vector4f): SceneBuilder {
        descriptions.add(GeomDesc.Box(Vector3f(position), Vector3f(size), Vector4f(color), occluder = false))
        return this
    }

    fun addSphere(position: Vector3f, radius: Float, color: Vector4f): SceneBuilder {
        descriptions.add(GeomDesc.Sphere(Vector3f(position), radius, Vector4f(color)))
        return this
    }

    fun addPlane(position: Vector3f, normal: Vector3f, size: Float, color: Vector4f): SceneBuilder {
        descriptions.add(GeomDesc.Plane(Vector3f(position), Vector3f(normal), size, Vector4f(color)))
        return this
    }

    fun addWall(
        position: Vector3f, width: Float, height: Float, color: Vector4f,
        thickness: Float = 0.1f, facing: Vector3f = Vector3f(0f, 0f, 1f)
    ): SceneBuilder {
        descriptions.add(GeomDesc.Wall(Vector3f(position), width, height, Vector4f(color), thickness, Vector3f(facing)))
        return this
    }

    fun addOccluderBox(position: Vector3f, size: Vector3f, color: Vector4f): SceneBuilder {
        descriptions.add(GeomDesc.Box(Vector3f(position), Vector3f(size), Vector4f(color), occluder = true))
        return this
    }

    fun addLight(position: Vector3f, color: Vector4f, intensity: Float, radius: Float): SceneBuilder {
        lights.add(PointLightData(Vector3f(position), color, intensity, radius))
        return this
    }

    fun camera(position: Vector3f, lookAt: Vector3f, fov: Float = 67f): SceneBuilder {
        cameraPosition = Vector3f(position)
        cameraLookAt = Vector3f(lookAt)
        cameraFov = fov
        return this
    }

    /**
     * Build the final TestScene.
     * TODO: When VulkanMesh is integrated, this will create GPU mesh resources.
     */
    fun build(): TestScene {
        val occluderTriangles = mutableListOf<List<ShadowVolumeBuilder.Triangle>>()

        for (desc in descriptions) {
            when (desc) {
                is GeomDesc.Box -> {
                    if (desc.occluder) {
                        val hw = desc.size.x / 2f; val hh = desc.size.y / 2f; val hd = desc.size.z / 2f
                        occluderTriangles.add(createBoxTriangles(desc.position, hw, hh, hd))
                    }
                }
                is GeomDesc.Wall -> {
                    val (sx, sy, sz) = if (isAligned(desc.facing, Vector3f(0f, 0f, 1f)) || isAligned(desc.facing, Vector3f(0f, 0f, -1f))) {
                        Triple(desc.width, desc.height, desc.thickness)
                    } else if (isAligned(desc.facing, Vector3f(1f, 0f, 0f)) || isAligned(desc.facing, Vector3f(-1f, 0f, 0f))) {
                        Triple(desc.thickness, desc.height, desc.width)
                    } else {
                        Triple(desc.width, desc.thickness, desc.height)
                    }
                    val hw = sx / 2f; val hh = sy / 2f; val hd = sz / 2f
                    occluderTriangles.add(createBoxTriangles(desc.position, hw, hh, hd))
                }
                is GeomDesc.Sphere -> { /* Spheres are not occluders */ }
                is GeomDesc.Plane -> { /* Planes are not occluders */ }
            }
        }

        val cam = Camera()
        cam.position.set(cameraPosition)
        cam.direction.set(cameraLookAt).sub(cameraPosition).normalize()
        cam.fov = cameraFov
        cam.near = 0.1f
        cam.far = 300f
        cam.resize(512, 512)
        cam.update()

        return TestScene(
            lights = lights.toList(),
            occluderTriangles = occluderTriangles.toList(),
            camera = cam,
        )
    }

    private fun isAligned(a: Vector3f, b: Vector3f): Boolean {
        return Vector3f(a).sub(b).length() < 0.01f
    }

    companion object {
        fun createBoxTriangles(center: Vector3f, hw: Float, hh: Float, hd: Float): List<ShadowVolumeBuilder.Triangle> {
            val cx = center.x; val cy = center.y; val cz = center.z
            val v = arrayOf(
                Vector3f(cx - hw, cy - hh, cz - hd),
                Vector3f(cx + hw, cy - hh, cz - hd),
                Vector3f(cx + hw, cy + hh, cz - hd),
                Vector3f(cx - hw, cy + hh, cz - hd),
                Vector3f(cx - hw, cy - hh, cz + hd),
                Vector3f(cx + hw, cy - hh, cz + hd),
                Vector3f(cx + hw, cy + hh, cz + hd),
                Vector3f(cx - hw, cy + hh, cz + hd)
            )
            return listOf(
                ShadowVolumeBuilder.Triangle(Vector3f(v[4]), Vector3f(v[5]), Vector3f(v[6])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[4]), Vector3f(v[6]), Vector3f(v[7])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[1]), Vector3f(v[0]), Vector3f(v[3])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[1]), Vector3f(v[3]), Vector3f(v[2])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[5]), Vector3f(v[1]), Vector3f(v[2])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[5]), Vector3f(v[2]), Vector3f(v[6])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[0]), Vector3f(v[4]), Vector3f(v[7])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[0]), Vector3f(v[7]), Vector3f(v[3])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[3]), Vector3f(v[7]), Vector3f(v[6])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[3]), Vector3f(v[6]), Vector3f(v[2])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[0]), Vector3f(v[1]), Vector3f(v[5])),
                ShadowVolumeBuilder.Triangle(Vector3f(v[0]), Vector3f(v[5]), Vector3f(v[4]))
            )
        }

        fun createQuadTriangles(
            topLeft: Vector3f, topRight: Vector3f,
            bottomRight: Vector3f, bottomLeft: Vector3f
        ): List<ShadowVolumeBuilder.Triangle> {
            return listOf(
                ShadowVolumeBuilder.Triangle(Vector3f(bottomLeft), Vector3f(bottomRight), Vector3f(topRight)),
                ShadowVolumeBuilder.Triangle(Vector3f(bottomLeft), Vector3f(topRight), Vector3f(topLeft))
            )
        }
    }
}
