package com.roguelike.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader
import com.badlogic.gdx.utils.UBJsonReader

class AssetLoader {
    val models = mutableMapOf<String, Model>()

    private val objLoader = ObjLoader()
    private val g3dLoader = G3dModelLoader(UBJsonReader())

    fun loadModel(name: String, path: String): Model {
        val existing = models[name]
        if (existing != null) return existing

        val file = Gdx.files.internal(path)
        val model =
                if (path.endsWith(".obj")) {
                    objLoader.loadModel(file, ObjLoader.ObjLoaderParameters(true))
                } else {
                    g3dLoader.loadModel(file)
                }
        models[name] = model
        return model
    }

    fun getModel(name: String): Model? = models[name]

    fun dispose() {
        models.values.forEach { it.dispose() }
        models.clear()
    }
}
