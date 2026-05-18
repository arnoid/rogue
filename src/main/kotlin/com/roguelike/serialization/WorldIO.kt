package com.roguelike.serialization

import com.roguelike.core.model.*
import com.roguelike.world.BaseTile
import java.io.File

object WorldIO {

    /** Returns true if the given ladder slot is already represented by a directional tag. */
    private fun isLadderSlotCoveredByTag(slot: TileSlot, tags: Set<String>): Boolean = when (slot) {
        TileSlot.WALL_NORTH -> "north_ladder" in tags
        TileSlot.WALL_SOUTH -> "south_ladder" in tags
        TileSlot.WALL_EAST  -> "east_ladder" in tags
        TileSlot.WALL_WEST  -> "west_ladder" in tags
        else -> false
    }

    private fun resourceRoot(): File = File(".").absoluteFile

    private fun toRelativeModelPath(absolutePath: String): String {
        val root = resourceRoot().canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        val normalized = absolutePath.replace('\\', '/')
        return if (normalized.startsWith(root, ignoreCase = true)) {
            normalized.removePrefix(root)
        } else {
            val marker = "/src/main/resources/"
            val idx = normalized.indexOf(marker, ignoreCase = true)
            if (idx >= 0) normalized.substring(idx + marker.length) else absolutePath
        }
    }

    private fun toAbsoluteModelPath(path: String): String {
        if (File(path).isAbsolute) return path
        val resolved = File(resourceRoot(), path)
        return if (resolved.exists()) resolved.canonicalPath else path
    }

    fun loadWorld(
        path: String,
        worldLoader: (Int, Int, Int) -> World,
        tileFactory: (String) -> Tile?
    ): World? {
        return try {
            val file = File(path)
            val text = file.readText()
            // Use minimal JSON parsing (WorldData is a simple structure)
            val data = SimpleJsonParser.parseWorldData(text) ?: return null
            val world = worldLoader(data.width, data.height, data.depth)

            data.nodes.forEach { nodeData ->
                val node = world.getNode(nodeData.x, nodeData.y, nodeData.z) ?: return@forEach
                nodeData.tags.forEach { world.addTag(node, it) }
                nodeData.tiles.forEach { tData ->
                    tileFactory(tData.type)?.let { tile ->
                        if (tile is BaseTile) {
                            tile.rotationX = tData.rotX
                            tile.rotationY = tData.rotY
                            tile.rotationZ = tData.rotZ
                        }
                        node.setTile(tile)
                    }
                }
                nodeData.doorSlots.forEach { slotName ->
                    try { node.tagAsDoor(TileSlot.valueOf(slotName)) } catch (_: IllegalArgumentException) {}
                }
                nodeData.manualDoorSlots.forEach { slotName ->
                    try { node.tagAsManualDoor(TileSlot.valueOf(slotName)) } catch (_: IllegalArgumentException) {}
                }
                nodeData.socketSlots.forEach { slotName ->
                    try { node.tagAsSocket(TileSlot.valueOf(slotName)) } catch (_: IllegalArgumentException) {}
                }
                nodeData.ladderSlots.forEach { slotName ->
                    try { node.tagAsLadder(TileSlot.valueOf(slotName)) } catch (_: IllegalArgumentException) {}
                }
                // Support directional ladder tags (e.g. "north_ladder" -> WALL_NORTH)
                nodeData.tags.forEach { tag ->
                    when (tag) {
                        "north_ladder" -> node.tagAsLadder(TileSlot.WALL_NORTH)
                        "south_ladder" -> node.tagAsLadder(TileSlot.WALL_SOUTH)
                        "east_ladder"  -> node.tagAsLadder(TileSlot.WALL_EAST)
                        "west_ladder"  -> node.tagAsLadder(TileSlot.WALL_WEST)
                    }
                }
                nodeData.items.forEach { itemData ->
                    val item = ItemFactory.create(itemData.type, itemData.id)
                        ?: KeyItem(id = itemData.id, type = itemData.type, colorHex = itemData.color, name = itemData.name)
                    itemData.tags.forEach { item.tags.add(it) }
                    node.items.add(item)
                }
            }

            data.associations.forEach { assocData ->
                val source = world.getNode(assocData.sourceX, assocData.sourceY, assocData.sourceZ)
                val target = world.getNode(assocData.targetX, assocData.targetY, assocData.targetZ)
                if (source != null && target != null) {
                    world.addAssociation(source, target, assocData.type, assocData.data)
                }
            }

            data.props.forEach { propData ->
                world.props.add(Prop(
                    id = propData.id, modelPath = toAbsoluteModelPath(propData.modelPath),
                    name = propData.name, x = propData.x, y = propData.y, z = propData.z,
                    rotationY = propData.rotationY, scale = propData.scale,
                    collisionHalfSizeX = propData.collisionHalfSizeX ?: propData.collisionHalfSize,
                    collisionHalfSizeY = propData.collisionHalfSizeY ?: propData.collisionHalfSize
                ))
            }

            data.lightSources.forEach { lsd ->
                world.lightSources.add(LightSource(
                    id = lsd.id, x = lsd.x, y = lsd.y, z = lsd.z,
                    intensity = lsd.intensity, radius = lsd.radius, colorHex = lsd.colorHex
                ))
            }

            println("[WorldIO] World loaded from $path")
            world
        } catch (e: Exception) {
            System.err.println("[WorldIO] Failed to load world: ${e.message}")
            null
        }
    }

    fun saveWorld(path: String, world: World) {
        try {
            val data = WorldData(
                width = world.width, height = world.height, depth = world.depth,
                nodes = ArrayList(), associations = ArrayList()
            )

            for (x in 0 until world.width) {
                for (y in 0 until world.height) {
                    for (z in 0 until world.depth) {
                        val node = world.getNode(x, y, z) ?: continue
                        if (node.tags.isEmpty() && node.tiles.isEmpty() && node.items.isEmpty()
                            && node.doorSlots.isEmpty() && node.manualDoorSlots.isEmpty()
                            && node.socketSlots.isEmpty() && node.ladderSlots.isEmpty()) continue

                        data.nodes.add(NodeData(
                            x = x, y = y, z = z,
                            tags = ArrayList(node.tags.toList()),
                            tiles = ArrayList(node.tiles.map { tile ->
                                if (tile is BaseTile) TileData(tile.type, tile.slot.name, tile.rotationX, tile.rotationY, tile.rotationZ)
                                else TileData(tile.type, tile.slot.name)
                            }),
                            items = ArrayList(node.items.map { item ->
                                ItemData(item.id, item.type, item.colorHex, item.name, ArrayList(item.tags.toList()))
                            }),
                            doorSlots = ArrayList(node.doorSlots.map { it.name }),
                            manualDoorSlots = ArrayList(node.manualDoorSlots.map { it.name }),
                            socketSlots = ArrayList(node.socketSlots.map { it.name }),
                            ladderSlots = ArrayList(node.ladderSlots
                                .filter { slot -> !isLadderSlotCoveredByTag(slot, node.tags) }
                                .map { it.name })
                        ))
                    }
                }
            }

            world.associations.forEach { assoc ->
                data.associations.add(AssociationData(
                    assoc.source.x, assoc.source.y, assoc.source.z,
                    assoc.target.x, assoc.target.y, assoc.target.z,
                    assoc.type, assoc.data
                ))
            }

            world.props.forEach { prop ->
                data.props.add(PropData(
                    id = prop.id, modelPath = toRelativeModelPath(prop.modelPath),
                    name = prop.name, x = prop.x, y = prop.y, z = prop.z,
                    rotationY = prop.rotationY, scale = prop.scale,
                    collisionHalfSize = prop.collisionHalfSize,
                    collisionHalfSizeX = prop.collisionHalfSizeX,
                    collisionHalfSizeY = prop.collisionHalfSizeY
                ))
            }

            world.lightSources.forEach { ls ->
                data.lightSources.add(LightSourceData(
                    id = ls.id, x = ls.x, y = ls.y, z = ls.z,
                    intensity = ls.intensity, radius = ls.radius, colorHex = ls.colorHex
                ))
            }

            // TODO: Replace with proper JSON serializer (T046)
            File(path).writeText(SimpleJsonParser.toJson(data))
            println("[WorldIO] World saved to $path")
        } catch (e: Exception) {
            System.err.println("[WorldIO] Failed to save world: ${e.message}")
        }
    }
}

/**
 * Minimal JSON parser/serializer for WorldData.
 */
internal object SimpleJsonParser {

    // ====================== SERIALIZATION ======================

    fun toJson(data: WorldData): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"width\": ${data.width},\n")
        sb.append("  \"height\": ${data.height},\n")
        sb.append("  \"depth\": ${data.depth},\n")

        // nodes
        sb.append("  \"nodes\": [\n")
        data.nodes.forEachIndexed { i, n ->
            sb.append("    {")
            sb.append("\"x\":${n.x},\"y\":${n.y},\"z\":${n.z}")
            if (n.tags.isNotEmpty()) sb.append(",\"tags\":${strList(n.tags)}")
            if (n.tiles.isNotEmpty()) {
                sb.append(",\"tiles\":[")
                n.tiles.forEachIndexed { ti, t ->
                    sb.append("{\"type\":${esc(t.type)},\"slot\":${esc(t.slot)}")
                    if (t.rotX != 0f) sb.append(",\"rotX\":${t.rotX}")
                    if (t.rotY != 0f) sb.append(",\"rotY\":${t.rotY}")
                    if (t.rotZ != 0f) sb.append(",\"rotZ\":${t.rotZ}")
                    sb.append("}")
                    if (ti < n.tiles.size - 1) sb.append(",")
                }
                sb.append("]")
            }
            if (n.items.isNotEmpty()) {
                sb.append(",\"items\":[")
                n.items.forEachIndexed { ii, it ->
                    sb.append("{\"id\":${esc(it.id)},\"type\":${esc(it.type)},\"color\":${esc(it.color)},\"name\":${esc(it.name)}")
                    if (it.tags.isNotEmpty()) sb.append(",\"tags\":${strList(it.tags)}")
                    sb.append("}")
                    if (ii < n.items.size - 1) sb.append(",")
                }
                sb.append("]")
            }
            if (n.doorSlots.isNotEmpty()) sb.append(",\"doorSlots\":${strList(n.doorSlots)}")
            if (n.manualDoorSlots.isNotEmpty()) sb.append(",\"manualDoorSlots\":${strList(n.manualDoorSlots)}")
            if (n.socketSlots.isNotEmpty()) sb.append(",\"socketSlots\":${strList(n.socketSlots)}")
            if (n.ladderSlots.isNotEmpty()) sb.append(",\"ladderSlots\":${strList(n.ladderSlots)}")
            sb.append("}")
            if (i < data.nodes.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        // associations
        sb.append("  \"associations\": [\n")
        data.associations.forEachIndexed { i, a ->
            sb.append("    {\"sourceX\":${a.sourceX},\"sourceY\":${a.sourceY},\"sourceZ\":${a.sourceZ}")
            sb.append(",\"targetX\":${a.targetX},\"targetY\":${a.targetY},\"targetZ\":${a.targetZ}")
            sb.append(",\"type\":${esc(a.type)}")
            if (a.data != null) sb.append(",\"data\":${esc(a.data)}")
            sb.append("}")
            if (i < data.associations.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        // props
        sb.append("  \"props\": [\n")
        data.props.forEachIndexed { i, p ->
            sb.append("    {\"id\":${esc(p.id)},\"modelPath\":${esc(p.modelPath)},\"name\":${esc(p.name)}")
            sb.append(",\"x\":${p.x},\"y\":${p.y},\"z\":${p.z}")
            sb.append(",\"rotationY\":${p.rotationY},\"scale\":${p.scale}")
            sb.append(",\"collisionHalfSize\":${p.collisionHalfSize}")
            if (p.collisionHalfSizeX != null) sb.append(",\"collisionHalfSizeX\":${p.collisionHalfSizeX}")
            if (p.collisionHalfSizeY != null) sb.append(",\"collisionHalfSizeY\":${p.collisionHalfSizeY}")
            sb.append("}")
            if (i < data.props.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        // lightSources
        sb.append("  \"lightSources\": [\n")
        data.lightSources.forEachIndexed { i, ls ->
            sb.append("    {\"id\":${esc(ls.id)},\"x\":${ls.x},\"y\":${ls.y},\"z\":${ls.z}")
            sb.append(",\"intensity\":${ls.intensity},\"radius\":${ls.radius},\"colorHex\":${esc(ls.colorHex)}}")
            if (i < data.lightSources.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")

        sb.append("}")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun strList(list: List<String>): String =
        list.joinToString(",", "[", "]") { esc(it) }

    // ====================== PARSING ======================

    fun parseWorldData(text: String): WorldData? {
        return try {
            val root = JsonTokenizer(text).parseValue() as? Map<*, *> ?: return null
            val width = (root["width"] as? Number)?.toInt() ?: return null
            val height = (root["height"] as? Number)?.toInt() ?: return null
            val depth = (root["depth"] as? Number)?.toInt() ?: return null

            val nodes = ArrayList<NodeData>()
            (root["nodes"] as? List<*>)?.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                nodes.add(parseNode(m))
            }

            val associations = ArrayList<AssociationData>()
            (root["associations"] as? List<*>)?.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                associations.add(AssociationData(
                    sourceX = num(m, "sourceX"), sourceY = num(m, "sourceY"), sourceZ = num(m, "sourceZ"),
                    targetX = num(m, "targetX"), targetY = num(m, "targetY"), targetZ = num(m, "targetZ"),
                    type = str(m, "type"), data = m["data"] as? String
                ))
            }

            val props = ArrayList<PropData>()
            (root["props"] as? List<*>)?.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                props.add(PropData(
                    id = str(m, "id"), modelPath = str(m, "modelPath"), name = str(m, "name"),
                    x = fl(m, "x"), y = fl(m, "y"), z = fl(m, "z"),
                    rotationY = fl(m, "rotationY"), scale = fl(m, "scale", 1f),
                    collisionHalfSize = fl(m, "collisionHalfSize", 0.25f),
                    collisionHalfSizeX = (m["collisionHalfSizeX"] as? Number)?.toFloat(),
                    collisionHalfSizeY = (m["collisionHalfSizeY"] as? Number)?.toFloat()
                ))
            }

            val lightSources = ArrayList<LightSourceData>()
            (root["lightSources"] as? List<*>)?.forEach { raw ->
                val m = raw as? Map<*, *> ?: return@forEach
                lightSources.add(LightSourceData(
                    id = str(m, "id"), x = fl(m, "x"), y = fl(m, "y"), z = fl(m, "z"),
                    intensity = fl(m, "intensity", 5f), radius = fl(m, "radius", 5f), colorHex = str(m, "colorHex", "ffcc88")
                ))
            }

            WorldData(width, height, depth, nodes, associations, props, lightSources)
        } catch (e: Exception) {
            System.err.println("[SimpleJsonParser] Parse error: ${e.message}")
            null
        }
    }

    private fun parseNode(m: Map<*, *>): NodeData {
        val tiles = ArrayList<TileData>()
        (m["tiles"] as? List<*>)?.forEach { raw ->
            val t = raw as? Map<*, *> ?: return@forEach
            tiles.add(TileData(
                type = str(t, "type"), slot = str(t, "slot"),
                rotX = fl(t, "rotX"), rotY = fl(t, "rotY"), rotZ = fl(t, "rotZ")
            ))
        }
        val items = ArrayList<ItemData>()
        (m["items"] as? List<*>)?.forEach { raw ->
            val it = raw as? Map<*, *> ?: return@forEach
            items.add(ItemData(
                id = str(it, "id"), type = str(it, "type"),
                color = str(it, "color", "FFFFFFFF"), name = str(it, "name"),
                tags = strArr(it, "tags")
            ))
        }
        return NodeData(
            x = num(m, "x"), y = num(m, "y"), z = num(m, "z"),
            tags = strArr(m, "tags"), tiles = tiles, items = items,
            doorSlots = strArr(m, "doorSlots"), manualDoorSlots = strArr(m, "manualDoorSlots"),
            socketSlots = strArr(m, "socketSlots"), ladderSlots = strArr(m, "ladderSlots")
        )
    }

    private fun num(m: Map<*, *>, key: String): Int = (m[key] as? Number)?.toInt() ?: 0
    private fun fl(m: Map<*, *>, key: String, def: Float = 0f): Float = (m[key] as? Number)?.toFloat() ?: def
    private fun str(m: Map<*, *>, key: String, def: String = ""): String = m[key] as? String ?: def
    private fun strArr(m: Map<*, *>, key: String): ArrayList<String> {
        val list = ArrayList<String>()
        (m[key] as? List<*>)?.forEach { if (it is String) list.add(it) }
        return list
    }

    // ====================== TOKENIZER ======================

    /**
     * Minimal recursive-descent JSON parser.
     * Returns Map, List, String, Number (Double), Boolean, or null.
     */
    private class JsonTokenizer(private val src: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // skip '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (pos < src.length) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                break
            }
            skipWs()
            if (pos < src.length && src[pos] == '}') pos++
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // skip '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (pos < src.length) {
                list.add(parseValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                break
            }
            skipWs()
            if (pos < src.length && src[pos] == ']') pos++
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length && src[pos] != '"') {
                if (src[pos] == '\\') {
                    pos++
                    if (pos < src.length) {
                        when (src[pos]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            else -> { sb.append('\\'); sb.append(src[pos]) }
                        }
                        pos++
                    }
                } else {
                    sb.append(src[pos++])
                }
            }
            if (pos < src.length) pos++ // skip closing '"'
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') { isFloat = true; pos++; while (pos < src.length && src[pos].isDigit()) pos++ }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) { isFloat = true; pos++; if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++; while (pos < src.length && src[pos].isDigit()) pos++ }
            val s = src.substring(start, pos)
            return if (isFloat) s.toDouble() else s.toLong()
        }

        private fun parseBoolean(): Boolean {
            return if (src.startsWith("true", pos)) { pos += 4; true }
            else { pos += 5; false }
        }

        private fun parseNull(): Any? { pos += 4; return null }

        private fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
        private fun expect(c: Char) { if (pos < src.length && src[pos] == c) pos++ }
    }
}
