package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener

/**
 * A Scene2D actor that renders a minimalist wireframe cube orientation indicator.
 *
 * The cube is centered with an isometric-style view that follows the main camera orientation.
 * From the bottom-front-left origin vertex:
 *   - X-axis edge (right)    → crimson red
 *   - Y-axis edge (left/back) → lime green
 *   - Z-axis edge (up)       → cyan blue
 * All other edges are neutral medium grey.
 */
class OrientationGizmo(
    private val mainCamera: Camera,
    private val modelBatch: ModelBatch,
    private val shapeRenderer: ShapeRenderer,
    private val onReset: () -> Unit
) : Actor() {

    private val gizmoCamera = PerspectiveCamera(45f, 100f, 100f).apply {
        near = 0.1f
        far = 20f
    }

    private val cubeModel: Model
    private val cubeInstance: ModelInstance
    private val labelBatch = SpriteBatch()
    private val labelFont = BitmapFont().apply { color = Color.WHITE }

    // Cube half-size
    private val s = 0.6f

    // Colors
    private val grey    = Color(0.45f, 0.45f, 0.45f, 1f)
    private val red     = Color(0.85f, 0.15f, 0.15f, 1f)  // crimson
    private val green   = Color(0.35f, 0.85f, 0.15f, 1f)  // lime
    private val cyan    = Color(0.15f, 0.85f, 0.95f, 1f)  // cyan-blue
    private val bgColor = Color(0.12f, 0.12f, 0.12f, 1f)  // deep charcoal

    init {
        val mb = ModelBuilder()
        mb.begin()
        val attr = (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorUnpacked).toLong()

        // Origin vertex: bottom-front-left = (-s, -s, -s)
        // From origin:
        //   X-axis → (+s, -s, -s)  right
        //   Y-axis → (-s, +s, -s)  back/left
        //   Z-axis → (-s, -s, +s)  up

        // ── Colored origin edges ────────────────────────────────────────
        mb.part("x-axis", GL20.GL_LINES, attr, Material()).apply {
            setColor(red)
            line(-s, -s, -s, s, -s, -s)  // X: right
        }
        mb.part("y-axis", GL20.GL_LINES, attr, Material()).apply {
            setColor(green)
            line(-s, -s, -s, -s, s, -s)  // Y: back
        }
        mb.part("z-axis", GL20.GL_LINES, attr, Material()).apply {
            setColor(cyan)
            line(-s, -s, -s, -s, -s, s)  // Z: up
        }

        // ── Grey edges (remaining 9 edges) ──────────────────────────────
        mb.part("grey-edges", GL20.GL_LINES, attr, Material()).apply {
            setColor(grey)
            // Bottom face (z = -s) — 2 remaining edges
            line( s, -s, -s,  s,  s, -s)
            line(-s,  s, -s,  s,  s, -s)
            // Top face (z = +s) — 4 edges
            line(-s, -s,  s,  s, -s,  s)
            line( s, -s,  s,  s,  s,  s)
            line( s,  s,  s, -s,  s,  s)
            line(-s,  s,  s, -s, -s,  s)
            // Vertical pillars — 3 remaining (origin pillar is cyan)
            line( s, -s, -s,  s, -s,  s)
            line( s,  s, -s,  s,  s,  s)
            line(-s,  s, -s, -s,  s,  s)
        }

        cubeModel = mb.end()
        cubeInstance = ModelInstance(cubeModel)

        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                onReset()
            }
        })
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        // Save scissor state
        val scissorEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
        val scissorBox = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)
        Gdx.gl20.glGetIntegerv(GL20.GL_SCISSOR_BOX, scissorBox)

        batch.end()

        val size = width

        // Update gizmo camera to match main camera orientation
        gizmoCamera.direction.set(mainCamera.direction)
        gizmoCamera.up.set(mainCamera.up)
        gizmoCamera.position.set(mainCamera.direction).scl(-3.5f)
        gizmoCamera.update()

        val screenPos = localToStageCoordinates(com.badlogic.gdx.math.Vector2(0f, 0f))

        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)

        // Set viewport for the gizmo
        com.badlogic.gdx.graphics.glutils.HdpiUtils.glViewport(
            screenPos.x.toInt(), screenPos.y.toInt(), size.toInt(), size.toInt()
        )

        // Draw deep charcoal background
        val uiMatrix = Matrix4().setToOrtho2D(0f, 0f, size, size)
        shapeRenderer.projectionMatrix = uiMatrix
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = bgColor
        shapeRenderer.rect(0f, 0f, size, size)
        shapeRenderer.end()

        // Clear depth so the cube renders cleanly
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)

        // Render wireframe cube
        Gdx.gl.glLineWidth(2f)
        modelBatch.begin(gizmoCamera)
        modelBatch.render(cubeInstance)
        modelBatch.end()
        Gdx.gl.glLineWidth(1f)

        // Draw axis labels projected to 2D
        val projMatrix = Matrix4().setToOrtho2D(0f, 0f, size, size)
        labelBatch.projectionMatrix = projMatrix
        labelBatch.begin()

        val proj = Vector3()
        data class AxisLabel(val label: String, val color: Color, val pos: Vector3)
        val labels = listOf(
            AxisLabel("X", red,   Vector3( s + 0.15f, -s, -s)),
            AxisLabel("Y", green, Vector3(-s, s + 0.15f, -s)),
            AxisLabel("Z", cyan,  Vector3(-s, -s, s + 0.15f))
        )
        for (axis in labels) {
            proj.set(axis.pos)
            gizmoCamera.project(proj, 0f, 0f, size, size)
            if (proj.z in 0f..1f) {
                labelFont.color = axis.color
                labelFont.draw(labelBatch, axis.label, proj.x - 4f, proj.y + 12f)
            }
        }
        labelBatch.end()

        // Restore viewport and scissor state
        com.badlogic.gdx.graphics.glutils.HdpiUtils.glViewport(
            0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight
        )
        if (scissorEnabled) {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        } else {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        }

        batch.begin()
    }

    fun dispose() {
        cubeModel.dispose()
        labelFont.dispose()
        labelBatch.dispose()
    }
}
