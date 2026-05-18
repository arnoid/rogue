package com.roguelike.rendering

import com.roguelike.ui.SimpleUI
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Debug visualisation renderer.
 *
 * Draws wireframe shapes (cubes, lines, spheres, etc.) through the UI overlay
 * system.  Intended for editor gizmos and in-game debug mode where models,
 * collision boxes, and other invisible objects need a visible representation.
 *
 * All drawing is done via [SimpleUI.drawQuad] so lines render as properly
 * rotated thin quads with no axis-aligned staircase artifacts.
 *
 * Usage:
 * ```
 * val debug = DebugRenderer(ui)
 * debug.drawWireframeCube(x, y, z, size, camera, r, g, b, a)
 * debug.drawLine(x1, y1, x2, y2, r, g, b, a)
 * ```
 */
class DebugRenderer(private val ui: SimpleUI) {

    // ----- Screen-space line drawing (proper rotated quad) -----

    /**
     * Draw a line between two screen-space pixel coordinates as a thin
     * rotated quadrilateral.  This avoids the staircase artifacts that occur
     * when approximating diagonal lines with axis-aligned rectangles.
     */
    fun drawLine(
        x1: Float, y1: Float, x2: Float, y2: Float,
        r: Float, g: Float, b: Float, a: Float,
        thickness: Float = 1.5f
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.5f) return

        // Unit perpendicular to the line direction, scaled by half-thickness
        val nx = (-dy / len) * thickness * 0.5f
        val ny = (dx / len) * thickness * 0.5f

        // Four corners of the thin quad
        ui.drawQuad(
            x1 + nx, y1 + ny,   // corner 0
            x2 + nx, y2 + ny,   // corner 1
            x2 - nx, y2 - ny,   // corner 2
            x1 - nx, y1 - ny,   // corner 3
            r, g, b, a
        )
    }

    /**
     * Draw a lit line between two 3D world-space points, projected through [camera].
     * The line is a thin quad that participates in per-pixel GPU lighting/shadow
     * raytracing via [SimpleUI.drawLitQuad].
     *
     * @param wx1, wy1, wz1  World-space start point
     * @param wx2, wy2, wz2  World-space end point
     * @param camera  Camera for projection
     * @param r, g, b, a  Base color
     * @param nx, ny, nz  Normal for lighting (face normal of the edge's parent face)
     * @param thickness  Screen-space line thickness in pixels
     */
    fun drawLitLine(
        wx1: Float, wy1: Float, wz1: Float,
        wx2: Float, wy2: Float, wz2: Float,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        nx: Float, ny: Float, nz: Float,
        thickness: Float = 2.5f
    ) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight
        val p1 = camera.project(Vector3f(wx1, wy1, wz1), sw, sh)
        val p2 = camera.project(Vector3f(wx2, wy2, wz2), sw, sh)

        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.5f) return

        // Perpendicular for thickness
        val px = (-dy / len) * thickness * 0.5f
        val py = (dx / len) * thickness * 0.5f

        ui.drawLitQuad(
            p1.x + px, p1.y + py,  p2.x + px, p2.y + py,
            p2.x - px, p2.y - py,  p1.x - px, p1.y - py,
            wx1, wy1, wz1,  wx2, wy2, wz2,
            wx2, wy2, wz2,  wx1, wy1, wz1,
            nx, ny, nz,
            r, g, b, a
        )
    }

    /**
     * Draw a wireframe cube in world space with per-pixel GPU lighting/shadow raytracing.
     * Each of the 12 edges is drawn as a lit line.
     */
    fun drawLitWireframeCube(
        x: Float, y: Float, z: Float,
        size: Float = 1f,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        thickness: Float = 2.5f
    ) {
        val s = size

        // 8 corners (world space)
        val cx = floatArrayOf(x, x+s, x+s, x,   x, x+s, x+s, x)
        val cy = floatArrayOf(y, y,   y+s, y+s, y, y,   y+s, y+s)
        val cz = floatArrayOf(z, z,   z,   z,   z+s, z+s, z+s, z+s)

        // 12 edges as vertex index pairs + approximate normal for each edge
        // Bottom face edges (z-facing normal averaged)
        val edges = arrayOf(
            // bottom face (normal 0,0,-1)
            intArrayOf(0,1), intArrayOf(1,2), intArrayOf(2,3), intArrayOf(3,0),
            // top face (normal 0,0,1)
            intArrayOf(4,5), intArrayOf(5,6), intArrayOf(6,7), intArrayOf(7,4),
            // vertical pillars — use average of adjacent face normals
            intArrayOf(0,4), intArrayOf(1,5), intArrayOf(2,6), intArrayOf(3,7)
        )

        // Normal per edge — use direction from edge midpoint to camera
        val camPos = camera.position

        for (edge in edges) {
            val i = edge[0]; val j = edge[1]
            // Edge midpoint for normal — use direction from edge center to camera
            val mx = (cx[i] + cx[j]) * 0.5f
            val my = (cy[i] + cy[j]) * 0.5f
            val mz = (cz[i] + cz[j]) * 0.5f
            val dnx = camPos.x - mx
            val dny = camPos.y - my
            val dnz = camPos.z - mz
            val nl = sqrt(dnx * dnx + dny * dny + dnz * dnz)
            val enx = if (nl > 0.001f) dnx / nl else 0f
            val eny = if (nl > 0.001f) dny / nl else 0f
            val enz = if (nl > 0.001f) dnz / nl else 1f

            drawLitLine(
                cx[i], cy[i], cz[i],
                cx[j], cy[j], cz[j],
                camera, r, g, b, a,
                enx, eny, enz, thickness
            )
        }
    }

    // ----- 3D wireframe cube -----

    /**
     * Draw a wireframe cube in world space projected through [camera].
     *
     * @param x      Origin X of the cube (world units)
     * @param y      Origin Y of the cube (world units)
     * @param z      Origin Z of the cube (world units)
     * @param size   Side length of the cube (default 1)
     * @param camera Camera used for projection
     * @param r, g, b, a  Line colour and opacity
     * @param thickness   Screen-space line thickness in pixels
     */
    fun drawWireframeCube(
        x: Float, y: Float, z: Float,
        size: Float = 1f,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        thickness: Float = 1.5f
    ) {
        drawWireframeBox(x, y, z, size, size, size, camera, r, g, b, a, thickness)
    }

    /**
     * Draw the 12-edge wireframe of an axis-aligned box at (x,y,z) with the
     * given per-axis dimensions. Same projection / styling as
     * [drawWireframeCube]. Used for multi-cell selection previews where a
     * single uniform-edge cube isn't enough.
     */
    fun drawWireframeBox(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        thickness: Float = 1.5f
    ) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        val corners = arrayOf(
            Vector3f(x,    y,    z),     Vector3f(x+sx, y,    z),
            Vector3f(x+sx, y+sy, z),     Vector3f(x,    y+sy, z),
            Vector3f(x,    y,    z+sz),  Vector3f(x+sx, y,    z+sz),
            Vector3f(x+sx, y+sy, z+sz),  Vector3f(x,    y+sy, z+sz)
        )

        val projected = Array(8) { i -> camera.project(corners[i], sw, sh) }

        val edges = intArrayOf(
            0,1, 1,2, 2,3, 3,0,
            4,5, 5,6, 6,7, 7,4,
            0,4, 1,5, 2,6, 3,7
        )

        var i = 0
        while (i < edges.size) {
            val pa = projected[edges[i]]
            val pb = projected[edges[i + 1]]
            drawLine(pa.x, pa.y, pb.x, pb.y, r, g, b, a, thickness)
            i += 2
        }
    }

    // ----- 3D wireframe sphere -----

    /**
     * Draw a wireframe sphere in world space projected through [camera].
     * Approximated as circles in the 3 principal planes.
     */
    fun drawWireframeSphere(
        cx: Float, cy: Float, cz: Float,
        radius: Float,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        segments: Int = 16,
        thickness: Float = 1.5f
    ) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        // Draw 3 circles: XY, XZ, YZ planes
        drawCircle3D(cx, cy, cz, radius, 0, camera, sw, sh, r, g, b, a, segments, thickness) // XY
        drawCircle3D(cx, cy, cz, radius, 1, camera, sw, sh, r, g, b, a, segments, thickness) // XZ
        drawCircle3D(cx, cy, cz, radius, 2, camera, sw, sh, r, g, b, a, segments, thickness) // YZ
    }

    /**
     * Draw a filled semitransparent sphere as a screen-space circle
     * (billboard facing the camera).
     */
    fun drawFilledSphere(
        cx: Float, cy: Float, cz: Float,
        radius: Float,
        camera: Camera,
        r: Float, g: Float, b: Float, a: Float,
        segments: Int = 12
    ) {
        val sw = ui.screenWidth
        val sh = ui.screenHeight

        // Project centre
        val centre = camera.project(Vector3f(cx, cy, cz), sw, sh)
        // Project a point offset by radius to figure out screen-space size
        val edge = camera.project(Vector3f(cx + radius, cy, cz), sw, sh)
        val screenRadius = sqrt((edge.x - centre.x) * (edge.x - centre.x) + (edge.y - centre.y) * (edge.y - centre.y))
        if (screenRadius < 1f) return

        // Draw as triangle fan using quads
        val step = (2.0 * Math.PI / segments).toFloat()
        for (i in 0 until segments) {
            val a0 = i * step
            val a1 = (i + 1) * step
            val x0 = centre.x + cos(a0) * screenRadius
            val y0 = centre.y + sin(a0) * screenRadius
            val x1 = centre.x + cos(a1) * screenRadius
            val y1 = centre.y + sin(a1) * screenRadius
            ui.drawQuad(
                centre.x, centre.y, x0, y0, x1, y1, centre.x, centre.y,
                r, g, b, a
            )
        }
    }

    private fun drawCircle3D(
        cx: Float, cy: Float, cz: Float, radius: Float,
        plane: Int, // 0=XY, 1=XZ, 2=YZ
        camera: Camera, sw: Float, sh: Float,
        r: Float, g: Float, b: Float, a: Float,
        segments: Int, thickness: Float
    ) {
        val step = (2.0 * Math.PI / segments).toFloat()
        var prevX = 0f; var prevY = 0f
        for (i in 0..segments) {
            val angle = i * step
            val ca = cos(angle) * radius
            val sa = sin(angle) * radius
            val wx: Float; val wy: Float; val wz: Float
            when (plane) {
                0 -> { wx = cx + ca; wy = cy + sa; wz = cz }
                1 -> { wx = cx + ca; wy = cy; wz = cz + sa }
                else -> { wx = cx; wy = cy + ca; wz = cz + sa }
            }
            val p = camera.project(Vector3f(wx, wy, wz), sw, sh)
            if (i > 0) {
                drawLine(prevX, prevY, p.x, p.y, r, g, b, a, thickness)
            }
            prevX = p.x
            prevY = p.y
        }
    }
}
