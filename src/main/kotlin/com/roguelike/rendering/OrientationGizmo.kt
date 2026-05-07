package com.roguelike.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener

/**
 * A Scene2D actor that renders a 3D orientation gizmo.
 */
class OrientationGizmo(
    private val mainCamera: Camera,
    private val modelBatch: ModelBatch,
    private val shapeRenderer: ShapeRenderer,
    private val onReset: () -> Unit
) : Actor() {

    private val axesCamera = PerspectiveCamera(67f, 100f, 100f).apply {
        near = 0.1f
        far = 10f
    }
    
    private val axesModel: Model
    private val axesInstance: ModelInstance
    private val uiMatrix = Matrix4()

    init {
        val modelBuilder = ModelBuilder()
        modelBuilder.begin()
        val axisLen = 1f
        val attr = (VertexAttributes.Usage.Position or VertexAttributes.Usage.ColorUnpacked).toLong()
        
        modelBuilder.part("x", GL20.GL_LINES, attr, Material()).apply {
            setColor(Color.RED)
            line(0f, 0f, 0f, axisLen, 0f, 0f)
        }
        modelBuilder.part("y", GL20.GL_LINES, attr, Material()).apply {
            setColor(Color.GREEN)
            line(0f, 0f, 0f, 0f, axisLen, 0f)
        }
        modelBuilder.part("z", GL20.GL_LINES, attr, Material()).apply {
            setColor(Color.BLUE)
            line(0f, 0f, 0f, 0f, 0f, axisLen)
        }
        axesModel = modelBuilder.end()
        axesInstance = ModelInstance(axesModel)

        addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                onReset()
            }
        })
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        // Save scissor state — the ScrollPane may have set a scissor that we must not disturb
        val scissorEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
        val scissorBox = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)  // must be direct for glGetIntegerv
        Gdx.gl20.glGetIntegerv(GL20.GL_SCISSOR_BOX, scissorBox)

        batch.end() // End Scene2D batch to do custom GL/3D rendering

        val size = width
        
        // Update axes camera to match main camera orientation
        axesCamera.direction.set(mainCamera.direction)
        axesCamera.up.set(mainCamera.up)
        axesCamera.position.set(mainCamera.direction).scl(-2f)
        axesCamera.update()

        // Get absolute screen position of the actor
        val screenPos = localToStageCoordinates(com.badlogic.gdx.math.Vector2(0f, 0f))
        
        // Disable scissor so the gizmo renders cleanly in its own viewport
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)

        // Set viewport for the gizmo
        com.badlogic.gdx.graphics.glutils.HdpiUtils.glViewport(screenPos.x.toInt(), screenPos.y.toInt(), size.toInt(), size.toInt())
        
        // Draw background
        uiMatrix.setToOrtho2D(0f, 0f, size, size)
        shapeRenderer.projectionMatrix = uiMatrix
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = Color(1f, 1f, 1f, 0.5f)
        shapeRenderer.rect(0f, 0f, size, size)
        shapeRenderer.end()

        modelBatch.begin(axesCamera)
        modelBatch.render(axesInstance)
        modelBatch.end()

        // Restore viewport and scissor state
        com.badlogic.gdx.graphics.glutils.HdpiUtils.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        if (scissorEnabled) {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        } else {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        }

        batch.begin() // Restart Scene2D batch
    }

    fun dispose() {
        axesModel.dispose()
    }
}
