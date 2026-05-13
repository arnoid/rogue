package com.roguelike.utils

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemDef
import com.roguelike.core.model.LightDef
import com.roguelike.core.model.LightDirection
import com.roguelike.core.model.LightShape

/**
 * Loads `items.json` (libGDX JSON-flavored) into [ItemCatalog].
 *
 * Implementation note: uses [com.badlogic.gdx.utils.JsonReader] directly so we
 * support flexible/optional fields (model, unlitModel, litModel, light) without
 * having to wire up reflection-friendly POJOs.
 */
object ItemCatalogLoader {

    /**
     * Loads the catalog from a libGDX-internal resource path.
     * Defaults to `items/items.json`.
     */
    fun loadFromInternal(path: String = "items/items.json"): List<ItemDef> {
        val text = readInternal(path) ?: return emptyList()
        val defs = parse(text)
        ItemCatalog.load(defs)
        return defs
    }

    /** Loads the catalog from a raw JSON string. */
    fun loadFromString(text: String): List<ItemDef> {
        val defs = parse(text)
        ItemCatalog.load(defs)
        return defs
    }

    private fun readInternal(path: String): String? {
        return try {
            // Prefer libGDX file handle (works when LWJGL3 working dir is set).
            val handle = com.badlogic.gdx.Gdx.files?.internal(path)
            if (handle != null && handle.exists()) {
                handle.readString()
            } else {
                // Test/headless fallback: load via classpath.
                ItemCatalogLoader::class.java.classLoader
                    ?.getResourceAsStream(path)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
        } catch (_: Throwable) {
            // Last resort: classpath.
            ItemCatalogLoader::class.java.classLoader
                ?.getResourceAsStream(path)
                ?.bufferedReader()
                ?.use { it.readText() }
        }
    }

    private fun parse(text: String): List<ItemDef> {
        val reader = com.badlogic.gdx.utils.JsonReader()
        val root = reader.parse(text) ?: return emptyList()
        val out = mutableListOf<ItemDef>()
        var entry = root.child
        while (entry != null) {
            val type = entry.getString("type", "")
            if (type.isNotBlank()) {
                val name = entry.getString("name", type)
                val colorHex = entry.getString("colorHex", "ffffffff")
                val blocksLight = entry.getBoolean("blocksLight", true)
                val tags = mutableSetOf<String>()
                entry.get("tags")?.let { tagsNode ->
                    var t = tagsNode.child
                    while (t != null) {
                        t.asString()?.let { tags.add(it) }
                        t = t.next
                    }
                }
                val model = entry.getString("model", null)
                val unlitModel = entry.getString("unlitModel", null)
                val litModel = entry.getString("litModel", null)
                val light = entry.get("light")?.let { parseLight(it) }

                out.add(
                    ItemDef(
                        type = type,
                        name = name,
                        colorHex = colorHex,
                        tags = tags,
                        model = model,
                        unlitModel = unlitModel,
                        litModel = litModel,
                        blocksLight = blocksLight,
                        light = light
                    )
                )
            }
            entry = entry.next
        }
        return out
    }

    private fun parseLight(node: com.badlogic.gdx.utils.JsonValue): LightDef {
        val shape = when (node.getString("shape", "sphere").lowercase()) {
            "cone"   -> LightShape.CONE
            else     -> LightShape.SPHERE
        }
        val direction = when (node.getString("direction", "omnidirectional").lowercase()) {
            "owner_facing"    -> LightDirection.OWNER_FACING
            else              -> LightDirection.OMNIDIRECTIONAL
        }
        return LightDef(
            shape = shape,
            direction = direction,
            range = node.getFloat("range", 5f),
            coneDegrees = node.getFloat("coneDegrees", 360f),
            coneFeatherDegrees = node.getFloat("coneFeatherDegrees", 3f),
            colorHex = node.getString("color", "ffffffff"),
            intensity = node.getFloat("intensity", 1f)
        )
    }
}

