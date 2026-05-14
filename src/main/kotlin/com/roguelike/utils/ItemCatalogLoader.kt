package com.roguelike.utils

import com.roguelike.core.model.ItemCatalog
import com.roguelike.core.model.ItemDef
import com.roguelike.core.model.LightDef
import com.roguelike.core.model.LightDirection
import com.roguelike.core.model.LightShape

/**
 * Loads items.json into [ItemCatalog] using standard Java I/O.
 */
object ItemCatalogLoader {

    fun loadFromInternal(path: String = "items/items.json"): List<ItemDef> {
        val text = readInternal(path) ?: return emptyList()
        val defs = parse(text)
        ItemCatalog.load(defs)
        return defs
    }

    fun loadFromString(text: String): List<ItemDef> {
        val defs = parse(text)
        ItemCatalog.load(defs)
        return defs
    }

    private fun readInternal(path: String): String? {
        return ItemCatalogLoader::class.java.classLoader
            ?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
    }

    /**
     * Minimal JSON array-of-objects parser for items.json.
     */
    private fun parse(text: String): List<ItemDef> {
        val out = mutableListOf<ItemDef>()
        // Simple approach: split by top-level objects in the array
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) return out

        // Extract objects between { and }
        var depth = 0
        var objStart = -1
        for (i in trimmed.indices) {
            when (trimmed[i]) {
                '{' -> { if (depth == 1) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 1 && objStart >= 0) {
                        val objStr = trimmed.substring(objStart, i + 1)
                        parseObject(objStr)?.let { out.add(it) }
                        objStart = -1
                    }
                }
                '[' -> if (depth == 0) depth = 1
            }
        }
        return out
    }

    private fun parseObject(json: String): ItemDef? {
        val type = extractString(json, "type") ?: return null
        if (type.isBlank()) return null

        val name = extractString(json, "name") ?: type
        val colorHex = extractString(json, "colorHex") ?: "ffffffff"
        val blocksLight = extractBoolean(json, "blocksLight") ?: true
        val tags = extractStringArray(json, "tags")
        val model = extractString(json, "model")
        val unlitModel = extractString(json, "unlitModel")
        val litModel = extractString(json, "litModel")
        val light = extractObject(json, "light")?.let { parseLightDef(it) }

        return ItemDef(
            type = type, name = name, colorHex = colorHex,
            tags = tags.toMutableSet(), model = model,
            unlitModel = unlitModel, litModel = litModel,
            blocksLight = blocksLight, light = light
        )
    }

    private fun parseLightDef(json: String): LightDef {
        val shape = when (extractString(json, "shape")?.lowercase()) {
            "cone" -> LightShape.CONE
            else -> LightShape.SPHERE
        }
        val direction = when (extractString(json, "direction")?.lowercase()) {
            "owner_facing" -> LightDirection.OWNER_FACING
            else -> LightDirection.OMNIDIRECTIONAL
        }
        return LightDef(
            shape = shape, direction = direction,
            range = extractFloat(json, "range") ?: 5f,
            coneDegrees = extractFloat(json, "coneDegrees") ?: 360f,
            colorHex = extractString(json, "color") ?: "ffffffff",
            intensity = extractFloat(json, "intensity") ?: 1f
        )
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractFloat(json: String, key: String): Float? {
        val pattern = """"$key"\s*:\s*([0-9.]+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val pattern = """"$key"\s*:\s*(true|false)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val pattern = """"$key"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = pattern.find(json) ?: return emptyList()
        val content = match.groupValues[1]
        return """"([^"]*)"""".toRegex().findAll(content).map { it.groupValues[1] }.toList()
    }

    private fun extractObject(json: String, key: String): String? {
        val start = json.indexOf("\"$key\"")
        if (start < 0) return null
        val braceStart = json.indexOf('{', start)
        if (braceStart < 0) return null
        var depth = 0
        for (i in braceStart until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(braceStart, i + 1) }
            }
        }
        return null
    }
}
