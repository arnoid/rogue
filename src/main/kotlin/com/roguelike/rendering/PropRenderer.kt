package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.model.Prop
import com.roguelike.utils.AssetLoader

/**
 * Renders freely-placed props (decorations/furniture) in the world.
 * Loads models on demand and caches ModelInstances per prop ID.
 */
class PropRenderer(private val assetLoader: AssetLoader) {

    private val instanceCache = mutableMapOf<String, ModelInstance>()
    private val modelCenters = mutableMapOf<String, Vector3>()

    private fun getOrCreateInstance(prop: Prop): ModelInstance? {
        instanceCache[prop.id]?.let { return it }
        return try {
            val model = assetLoader.loadModel("prop_${prop.modelPath}", prop.modelPath)
            val box = BoundingBox()
            model.calculateBoundingBox(box)
            val center = Vector3()
            box.getCenter(center)
            modelCenters[prop.id] = center
            val inst = ModelInstance(model)
            instanceCache[prop.id] = inst
            inst
        } catch (e: Exception) {
            println("[PropRenderer] Failed to load model: ${prop.modelPath} - ${e.message}")
            null
        }
    }

    fun render(prop: Prop, batch: ModelBatch, environment: Environment, selected: Boolean = false) {
        val instance = getOrCreateInstance(prop) ?: return
        val scale = prop.scale
        val center = modelCenters[prop.id] ?: Vector3.Zero

        instance.transform.setToTranslation(prop.x, prop.y, prop.z)
        instance.transform.scale(scale, scale, scale)
        instance.transform.rotate(Vector3.X, -90f)
        if (prop.rotationY != 0f) instance.transform.rotate(Vector3.Y, prop.rotationY)
        instance.transform.rotate(Vector3.Z, 180f)
        instance.transform.translate(-center.x, -center.y, -center.z)

        if (selected && instance.materials.size > 0) {
            instance.materials.get(0).set(ColorAttribute.createDiffuse(Color.CYAN))
        }

        batch.render(instance, environment)

        // Reset color
        if (selected && instance.materials.size > 0) {
            instance.materials.get(0).set(ColorAttribute.createDiffuse(Color.WHITE))
        }
    }

    fun removeFromCache(propId: String) {
        instanceCache.remove(propId)
        modelCenters.remove(propId)
    }
}

