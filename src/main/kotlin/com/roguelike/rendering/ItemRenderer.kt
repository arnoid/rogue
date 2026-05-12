package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.model.Item
import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.isLightSource
import com.roguelike.core.model.isLit
import com.roguelike.utils.AssetLoader

/**
 * Renders items using their [com.roguelike.core.model.ItemDef] catalog entry
 * (resolves unlit vs. lit model based on the item's `light_source_lit` tag).
 *
 * Models are cached by path; instances cached per (item.id, modelPath) so
 * toggling lit state cleanly switches to the appropriate cached instance.
 */
class ItemRenderer(val assetLoader: AssetLoader) {

    private data class ModelData(val model: Model, val scale: Float, val center: Vector3)

    private val modelCache = mutableMapOf<String, ModelData>()
    private val instanceCache = mutableMapOf<Pair<String, String>, ModelInstance>()

    private fun loadModelData(path: String): ModelData? {
        modelCache[path]?.let { return it }
        return try {
            println("[ItemRenderer] loading model '$path'")
            val model = assetLoader.loadModel("item_$path", path)
            val box = BoundingBox()
            model.calculateBoundingBox(box)
            val maxDim = maxOf(box.width, maxOf(box.height, box.depth))
            // Items occupy ~0.7 of a cell so they are clearly visible on the floor.
            val scale = if (maxDim > 0f) 0.7f / maxDim else 1f
            val center = Vector3()
            box.getCenter(center)
            val data = ModelData(model, scale, center)
            modelCache[path] = data
            println("[ItemRenderer] loaded  '$path' bbox=(${box.width},${box.height},${box.depth}) scale=$scale center=(${center.x},${center.y},${center.z}) materials=${model.materials.size}")
            data
        } catch (e: Exception) {
            println("[ItemRenderer] FAILED to load model: '$path' - ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Render a single item at the given world-cell position.
     *
     * The item is positioned at the bottom of the cell (just above the floor
     * surface, which sits at `z - 0.5`), so dropped items sit on the floor
     * rather than floating mid-cell or hiding inside the player sphere.
     *
     * @param tint optional RGB color (alpha ignored). When non-null the item
     *             is rendered with `diffuse = baseColor * tint`. Pass a dark
     *             tint to render the item dim/unlit.
     * @param debug emit a diagnostic log line for this call.
     */
    fun render(
        item: Item,
        batch: ModelBatch,
        environment: Environment,
        x: Float, y: Float, z: Float,
        tint: Color? = null,
        debug: Boolean = false
    ) {
        val def = ItemCatalog[item.type]
        if (def == null) {
            if (debug) println("[ItemRenderer] SKIP item ${item.name}#${item.id.take(6)} — no catalog entry for type='${item.type}'")
            return
        }
        val lit = item.isLightSource() && item.isLit()
        val path = def.modelFor(lit)
        if (path == null) {
            if (debug) println("[ItemRenderer] SKIP item ${item.name}#${item.id.take(6)} — catalog has no model path (lit=$lit)")
            return
        }
        val data = loadModelData(path)
        if (data == null) {
            if (debug) println("[ItemRenderer] SKIP item ${item.name}#${item.id.take(6)} — model load failed for '$path'")
            return
        }
        val instance = instanceCache.getOrPut(item.id to path) {
            if (debug) println("[ItemRenderer] new ModelInstance for ${item.name}#${item.id.take(6)} path='$path'")
            ModelInstance(data.model)
        }

        // Sit the item just above the floor of its cell.
        val drawZ = z - 0.4f
        instance.transform.setToTranslation(x, y, drawZ)
        instance.transform.scale(data.scale, data.scale, data.scale)
        instance.transform.rotate(Vector3.X, -90f)
        // Rotate around vertical axis so the model faces the item's stored direction.
        // facing is in XY plane; default is +Y (north). atan2 gives angle CCW from +X;
        // we want 0° to mean "north" (+Y), so use atan2(facingX, facingY) and negate
        // to convert to libGDX's Y-rotation convention (CCW around Y when viewed from +Y).
        val yawDeg = Math.toDegrees(kotlin.math.atan2(item.facingX.toDouble(), item.facingY.toDouble())).toFloat()
        if (yawDeg != 0f) instance.transform.rotate(Vector3.Z, yawDeg)
        instance.transform.translate(-data.center.x, -data.center.y, -data.center.z)

        if (instance.materials.size > 0) {
            val baseColor = try { Color.valueOf(item.colorHex) } catch (_: Exception) { Color.WHITE }
            val finalColor = if (tint != null) {
                Color(baseColor.r * tint.r, baseColor.g * tint.g, baseColor.b * tint.b, 1f)
            } else baseColor
            instance.materials.get(0).set(ColorAttribute.createDiffuse(finalColor))
            // Lit light-source items self-emit so they remain visible in darkness.
            if (lit) {
                instance.materials.get(0).set(ColorAttribute.createEmissive(baseColor))
            } else {
                instance.materials.get(0).remove(ColorAttribute.Emissive)
            }
            if (debug) println("[ItemRenderer] render ${item.name}#${item.id.take(6)} pos=($x,$y,$drawZ) path='$path' lit=$lit diffuse=(${finalColor.r},${finalColor.g},${finalColor.b}) emissive=${if (lit) "on" else "off"}")
        } else {
            if (debug) println("[ItemRenderer] render ${item.name}#${item.id.take(6)} pos=($x,$y,$drawZ) path='$path' lit=$lit (NO MATERIALS — model loaded without material; tint not applied)")
        }
        batch.render(instance, environment)
    }
}
