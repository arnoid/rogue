package com.roguelike.editor

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.roguelike.core.model.World

/**
 * Bottom status bar for the map editor.
 * Dimensions change by 3 to satisfy World's divisible-by-3 requirement.
 */
class EditorStatusBar(
    private val getWorld: () -> World,
    private val onResize: (nx: Int, ny: Int, nz: Int) -> Unit
) {
    lateinit var xLabel: Label
    lateinit var yLabel: Label
    lateinit var zLabel: Label
    lateinit var layerLabel: Label

    var maxRenderZ: Int = 0

    fun build(): VisTable {
        val bottomBar = VisTable()
        bottomBar.background = VisUI.getSkin().getDrawable("window-bg")

        val world = getWorld()

        bottomBar.add(VisLabel("X:")).padLeft(10f)
        xLabel = VisLabel(world.width.toString())
        yLabel = VisLabel(world.height.toString())
        zLabel = VisLabel(world.depth.toString())

        fun mkBtn(label: String, action: () -> Unit) = VisTextButton(label).also {
            it.addListener(object : ChangeListener() {
                override fun changed(e: ChangeEvent, a: com.badlogic.gdx.scenes.scene2d.Actor) { action() }
            })
        }

        bottomBar.add(mkBtn("-") { onResize(getWorld().width - 3, getWorld().height, getWorld().depth) }).width(28f)
        bottomBar.add(xLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { onResize(getWorld().width + 3, getWorld().height, getWorld().depth) }).width(28f)

        bottomBar.add(VisLabel("  Y:")).padLeft(10f)
        bottomBar.add(mkBtn("-") { onResize(getWorld().width, getWorld().height - 3, getWorld().depth) }).width(28f)
        bottomBar.add(yLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { onResize(getWorld().width, getWorld().height + 3, getWorld().depth) }).width(28f)

        bottomBar.add(VisLabel("  Z:")).padLeft(10f)
        bottomBar.add(mkBtn("-") { onResize(getWorld().width, getWorld().height, getWorld().depth - 3) }).width(28f)
        bottomBar.add(zLabel).width(28f).center()
        bottomBar.add(mkBtn("+") { onResize(getWorld().width, getWorld().height, getWorld().depth + 3) }).width(28f)

        bottomBar.add(VisLabel("  Layer:")).padLeft(20f)
        layerLabel = VisLabel(maxRenderZ.toString())
        bottomBar.add(mkBtn("-") {
            maxRenderZ = (maxRenderZ - 1).coerceAtLeast(0)
            layerLabel.setText(maxRenderZ.toString())
        }).width(28f)
        bottomBar.add(layerLabel).width(28f).center()
        bottomBar.add(mkBtn("+") {
            maxRenderZ = (maxRenderZ + 1).coerceAtMost(getWorld().depth - 1)
            layerLabel.setText(maxRenderZ.toString())
        }).width(28f)

        return bottomBar
    }

    fun refresh(world: World) {
        maxRenderZ = world.depth - 1
        xLabel.setText(world.width.toString())
        yLabel.setText(world.height.toString())
        zLabel.setText(world.depth.toString())
        layerLabel.setText(maxRenderZ.toString())
    }
}
