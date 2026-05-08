package com.roguelike.rendering

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.roguelike.core.model.Item
import com.roguelike.core.model.KeyItem
import com.roguelike.utils.AssetLoader

class ItemRenderer(val assetLoader: AssetLoader) {
    private val keyModel: Model by lazy { assetLoader.loadModel("item_key", "objects/key.obj") }
    private var keyScale: Float = 0f
    private val keyCenter = Vector3()
    
    private val instanceCache = mutableMapOf<Item, ModelInstance>()

    private fun ensureKeyData() {
        if (keyScale == 0f) {
            val box = BoundingBox()
            keyModel.calculateBoundingBox(box)
            keyScale = 0.5f / box.width
            box.getCenter(keyCenter)
        }
    }

    fun render(item: Item, batch: ModelBatch, environment: Environment, x: Float, y: Float, z: Float) {
        when (item) {
            is KeyItem -> {
                ensureKeyData()
                val instance = instanceCache.getOrPut(item) { ModelInstance(keyModel) }
                
                instance.transform.setToTranslation(x, y, z)
                instance.transform.scale(keyScale, keyScale, keyScale)
                instance.transform.rotate(Vector3.X, -90f)
                instance.transform.translate(-keyCenter.x, -keyCenter.y, -keyCenter.z)
                
                if (instance.materials.size > 0) {
                    instance.materials.get(0).set(ColorAttribute.createDiffuse(Color.valueOf(item.colorHex)))
                }
                batch.render(instance, environment)
            }
        }
    }
}
