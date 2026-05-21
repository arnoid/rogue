package com.roguelike.generation

import com.roguelike.serialization.SimpleJsonParser
import java.io.File

/**
 * Refreshes a biome's `biome.json` to reflect the `.wld` files actually
 * present on disk under its `submaps-entry/` and `submaps/` folders.
 *
 * Semantics — designed to be safe to run repeatedly without losing
 * hand-tuned data:
 *  - Entries whose `.wld` file still exists are kept **verbatim** (their
 *    `baseUnitFootprint`, custom socket `tag`s, hand-edited `playerSpawn`,
 *    etc. are preserved).
 *  - Entries whose `.wld` file no longer exists are dropped.
 *  - `.wld` files on disk that have no entry yet are appended with a
 *    fresh entry derived from the file contents:
 *      * `dimensions` from `width`/`height`/`depth`
 *      * `baseUnitFootprint` defaults to (1,1,1)
 *      * `playerSpawn` from the node tagged `player_spawn` (only for
 *        `submaps-entry/` files; required there).
 *      * `sockets` from every node with non-empty `socketSlots` (direction
 *        inferred from the slot name, `tag` defaults to "default").
 *  - The section a new entry lands in (`submaps-entry` vs `submaps`) is
 *    decided by the parent folder name of the `.wld`.
 *
 * The `metadata` block at the top of the file is preserved as-is.
 */
object BiomeRegenerator {

    /** Per-run summary returned to the caller for UI feedback. */
    data class Report(
        val biomeJsonPath: String,
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val kept: Int = 0,
        val errors: List<String> = emptyList()
    ) {
        fun summaryLine(): String =
            "added=${added.size}, removed=${removed.size}, kept=$kept" +
                (if (errors.isNotEmpty()) ", errors=${errors.size}" else "")
    }

    /**
     * Aggregate report produced by [regenerateIndex]. Holds one [Report]
     * for the index file itself (`biomes.json`) plus one per biome the
     * index points at, alongside roll-up totals for the UI.
     */
    data class IndexReport(
        val indexJsonPath: String,
        val indexReport: Report,
        val biomeReports: List<Report> = emptyList()
    ) {
        val totalAdded: Int get() = indexReport.added.size + biomeReports.sumOf { it.added.size }
        val totalRemoved: Int get() = indexReport.removed.size + biomeReports.sumOf { it.removed.size }
        val totalKept: Int get() = indexReport.kept + biomeReports.sumOf { it.kept }
        val totalErrors: Int get() = indexReport.errors.size + biomeReports.sumOf { it.errors.size }

        fun summaryLine(): String =
            "biomes=${biomeReports.size}, added=$totalAdded, removed=$totalRemoved, kept=$totalKept" +
                (if (totalErrors > 0) ", errors=$totalErrors" else "")
    }

    private const val ENTRY_FOLDER = "submaps-entry"
    private const val POOL_FOLDER = "submaps"
    private const val BIOME_FILE_NAME = "biome.json"

    /**
     * Regenerates a complete biome set, given the top-level `biomes.json`
     * index file:
     *  1. The index is rewritten so its `biomes` array matches the
     *     `<subfolder>/biome.json` files actually on disk under the index
     *     file's parent directory. Existing index entries whose `file`
     *     still resolves are preserved verbatim (so a hand-edited `type`
     *     or any extra fields survive). Entries whose file is missing are
     *     dropped, and any newly-discovered `<dir>/biome.json` not yet
     *     listed is appended with `name`/`type` derived from the folder.
     *  2. Each biome file listed in the (refreshed) index is then handed
     *     to [regenerate] so its `submaps`/`submaps-entry` arrays catch
     *     up with the `.wld` files on disk.
     */
    fun regenerateIndex(biomesJsonFile: File): IndexReport {
        if (!biomesJsonFile.isFile) {
            return IndexReport(
                indexJsonPath = biomesJsonFile.absolutePath,
                indexReport = Report(
                    biomeJsonPath = biomesJsonFile.absolutePath,
                    errors = listOf("biomes.json not found: ${biomesJsonFile.absolutePath}")
                )
            )
        }
        val baseDir = biomesJsonFile.parentFile ?: File(".")
        val root = try {
            SimpleJsonParser.parseAny(biomesJsonFile.readText()) as? Map<*, *>
        } catch (e: Exception) {
            return IndexReport(
                indexJsonPath = biomesJsonFile.absolutePath,
                indexReport = Report(
                    biomeJsonPath = biomesJsonFile.absolutePath,
                    errors = listOf("failed to parse biomes.json: ${e.message}")
                )
            )
        } ?: return IndexReport(
            indexJsonPath = biomesJsonFile.absolutePath,
            indexReport = Report(
                biomeJsonPath = biomesJsonFile.absolutePath,
                errors = listOf("biomes.json root is not a JSON object")
            )
        )

        val metadata = root["metadata"] as? Map<*, *>
        val existing = readIndexEntriesPreservingOrder(root["biomes"], baseDir)

        val errors = mutableListOf<String>()
        val removed = mutableListOf<String>()

        // 1. Keep only entries whose biome.json still exists on disk.
        val kept = existing.filter { (file, _) ->
            val exists = file.isFile
            if (!exists) removed.add(relPath(baseDir, file))
            exists
        }

        val knownPaths = HashSet<String>().apply {
            kept.forEach { add(canon(it.first)) }
        }

        // 2. Scan every immediate subfolder of baseDir for a biome.json
        //    we don't already know about, and append it.
        val added = mutableListOf<String>()
        val newRaw = mutableListOf<Map<String, Any?>>()
        baseDir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { subDir ->
                val candidate = File(subDir, BIOME_FILE_NAME)
                if (!candidate.isFile) return@forEach
                if (canon(candidate) in knownPaths) return@forEach
                val rel = "./${subDir.name}/$BIOME_FILE_NAME"
                val entry = linkedMapOf<String, Any?>(
                    "name" to subDir.name,
                    "type" to subDir.name,
                    "file" to rel
                )
                newRaw.add(entry)
                added.add(relPath(baseDir, candidate))
            }

        val finalBiomes = kept.map { it.second } + newRaw

        // 3. Write the refreshed index back.
        val text = serializeBiomesIndexJson(metadata, finalBiomes)
        biomesJsonFile.writeText(text)

        val indexReport = Report(
            biomeJsonPath = biomesJsonFile.absolutePath,
            added = added,
            removed = removed,
            kept = kept.size,
            errors = errors
        )

        // 4. Regenerate each per-biome file the (refreshed) index now
        //    points at.
        val perBiome = ArrayList<Report>(finalBiomes.size)
        for (entry in finalBiomes) {
            val rel = entry["file"] as? String ?: continue
            val resolved = resolveRelative(baseDir, rel)
            perBiome.add(regenerate(resolved))
        }

        return IndexReport(
            indexJsonPath = biomesJsonFile.absolutePath,
            indexReport = indexReport,
            biomeReports = perBiome
        )
    }

    /**
     * Rewrites [biomeJsonFile] in place. Returns a [Report] describing
     * what changed. On a read/parse failure the file is **not** touched
     * and the report contains the error.
     */
    fun regenerate(biomeJsonFile: File): Report {
        if (!biomeJsonFile.isFile) {
            return Report(
                biomeJsonPath = biomeJsonFile.absolutePath,
                errors = listOf("biome.json not found: ${biomeJsonFile.absolutePath}")
            )
        }
        val baseDir = biomeJsonFile.parentFile ?: File(".")
        val root = try {
            SimpleJsonParser.parseAny(biomeJsonFile.readText()) as? Map<*, *>
        } catch (e: Exception) {
            return Report(
                biomeJsonPath = biomeJsonFile.absolutePath,
                errors = listOf("failed to parse biome.json: ${e.message}")
            )
        } ?: return Report(
            biomeJsonPath = biomeJsonFile.absolutePath,
            errors = listOf("biome.json root is not a JSON object")
        )

        val metadata = root["metadata"] as? Map<*, *>
        val existingEntry = readEntriesPreservingOrder(root["submaps-entry"], baseDir)
        val existingPool = readEntriesPreservingOrder(root["submaps"], baseDir)

        val errors = mutableListOf<String>()

        // 1. Drop entries whose .wld no longer exists.
        val removed = mutableListOf<String>()
        val keptEntry = existingEntry.filter { (file, _) ->
            val exists = file.isFile
            if (!exists) removed.add(relPath(baseDir, file))
            exists
        }
        val keptPool = existingPool.filter { (file, _) ->
            val exists = file.isFile
            if (!exists) removed.add(relPath(baseDir, file))
            exists
        }

        // 2. Build a quick lookup of which canonical paths we already know.
        val knownPaths = HashSet<String>().apply {
            keptEntry.forEach { add(canon(it.first)) }
            keptPool.forEach { add(canon(it.first)) }
        }

        // 3. Scan the two subfolders for .wld files not yet indexed.
        val added = mutableListOf<String>()
        val newEntryRaw = mutableListOf<Map<String, Any?>>()
        val newPoolRaw = mutableListOf<Map<String, Any?>>()

        val entryDir = File(baseDir, ENTRY_FOLDER)
        if (entryDir.isDirectory) {
            scanFolder(entryDir).sortedBy { it.name }.forEach { wld ->
                if (canon(wld) in knownPaths) return@forEach
                val built = buildEntry(wld, isStarting = true, errors = errors)
                if (built != null) {
                    newEntryRaw.add(built)
                    added.add(relPath(baseDir, wld))
                }
            }
        }
        val poolDir = File(baseDir, POOL_FOLDER)
        if (poolDir.isDirectory) {
            scanFolder(poolDir).sortedBy { it.name }.forEach { wld ->
                if (canon(wld) in knownPaths) return@forEach
                val built = buildEntry(wld, isStarting = false, errors = errors)
                if (built != null) {
                    newPoolRaw.add(built)
                    added.add(relPath(baseDir, wld))
                }
            }
        }

        // 4. Compose the rewritten document. Preserve every kept entry's
        //    original raw JSON to avoid lossy re-serialization of fields
        //    we don't know about.
        val finalEntry = keptEntry.map { it.second } + newEntryRaw
        val finalPool = keptPool.map { it.second } + newPoolRaw

        val text = serializeBiomeJson(metadata, finalEntry, finalPool)
        biomeJsonFile.writeText(text)

        return Report(
            biomeJsonPath = biomeJsonFile.absolutePath,
            added = added,
            removed = removed,
            kept = keptEntry.size + keptPool.size,
            errors = errors
        )
    }

    /**
     * Returns the list of entries from a `submaps-entry` or `submaps`
     * array as `(resolvedWldFile, originalRawMap)` pairs, preserving the
     * input order.
     */
    private fun readEntriesPreservingOrder(
        raw: Any?,
        baseDir: File
    ): List<Pair<File, Map<String, Any?>>> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<Pair<File, Map<String, Any?>>>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val rel = (m["file"] as? String) ?: continue
            val resolved = resolveRelative(baseDir, rel)
            @Suppress("UNCHECKED_CAST")
            out.add(resolved to (m as Map<String, Any?>))
        }
        return out
    }

    /**
     * Same idea as [readEntriesPreservingOrder] but for the top-level
     * `biomes.json` index: each entry's `file` resolves to a per-biome
     * `biome.json`, not a `.wld`.
     */
    private fun readIndexEntriesPreservingOrder(
        raw: Any?,
        baseDir: File
    ): List<Pair<File, Map<String, Any?>>> = readEntriesPreservingOrder(raw, baseDir)

    private fun scanFolder(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wld", ignoreCase = true) }
            ?.toList()
            ?: emptyList()

    /**
     * Build a fresh biome.json entry for [wld] by inspecting its contents.
     * Returns null and pushes an error string into [errors] if the file
     * can't be parsed.
     */
    private fun buildEntry(
        wld: File,
        isStarting: Boolean,
        errors: MutableList<String>
    ): Map<String, Any?>? {
        val data = try {
            SimpleJsonParser.parseWorldData(wld.readText())
        } catch (e: Exception) {
            errors.add("failed to parse ${wld.name}: ${e.message}")
            return null
        }
        if (data == null) {
            errors.add("failed to parse ${wld.name}")
            return null
        }

        // file path is always relative to the biome.json directory (which
        // is two levels up from the .wld's parent: biome.json sits at
        // <baseDir>/biome.json and .wld at <baseDir>/<section>/<file>).
        val section = wld.parentFile?.name ?: ""
        val relFile = "./$section/${wld.name}"

        val map = LinkedHashMap<String, Any?>()
        map["file"] = relFile
        map["name"] = wld.nameWithoutExtension
        map["dimensions"] = linkedMapOf("width" to data.width, "height" to data.height, "depth" to data.depth)
        map["baseUnitFootprint"] = linkedMapOf("x" to 1, "y" to 1, "z" to 1)

        if (isStarting) {
            val spawn = data.nodes.firstOrNull { "player_spawn" in it.tags }
            if (spawn == null) {
                errors.add("${wld.name}: no node tagged 'player_spawn' (required for submaps-entry)")
                // We still emit the entry but without playerSpawn; the
                // generator will fall back to a default and the user can
                // edit the .wld to add the tag.
            } else {
                map["playerSpawn"] = linkedMapOf("x" to spawn.x, "y" to spawn.y, "z" to spawn.z)
            }
        }

        val sockets = ArrayList<Map<String, Any?>>()
        for (node in data.nodes) {
            for (slotName in node.socketSlots) {
                val direction = directionForSlot(slotName) ?: continue
                sockets.add(linkedMapOf(
                    "x" to node.x,
                    "y" to node.y,
                    "z" to node.z,
                    "slot" to slotName,
                    "direction" to direction,
                    "tag" to "default"
                ))
            }
        }
        map["sockets"] = sockets

        return map
    }

    private fun directionForSlot(slotName: String): String? = when (slotName) {
        "WALL_NORTH" -> "NORTH"
        "WALL_SOUTH" -> "SOUTH"
        "WALL_EAST"  -> "EAST"
        "WALL_WEST"  -> "WEST"
        else -> null
    }

    private fun resolveRelative(baseDir: File, relPath: String): File {
        val asFile = File(relPath)
        return if (asFile.isAbsolute) asFile else File(baseDir, relPath).normalize()
    }

    private fun canon(f: File): String = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

    private fun relPath(baseDir: File, f: File): String {
        val base = canon(baseDir)
        val fc = canon(f)
        return if (fc.startsWith(base)) fc.substring(base.length).trimStart('/', '\\') else fc
    }

    // ────────────────────────────────────────────────────────────────
    // Hand-written serializer matching the existing biome.json layout.
    // We can't use a generic Map→JSON writer because the file format has
    // a specific shape and the existing files are checked into source
    // control; minimizing diff churn matters for code review.
    // ────────────────────────────────────────────────────────────────

    private fun serializeBiomeJson(
        metadata: Map<*, *>?,
        entryEntries: List<Map<String, Any?>>,
        poolEntries: List<Map<String, Any?>>
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        // metadata first (preserved as-is)
        if (metadata != null) {
            sb.append("  \"metadata\": ")
            writeValue(sb, metadata, indent = "  ")
            sb.append(",\n")
        }
        // submaps-entry
        sb.append("  \"submaps-entry\": ")
        writeEntryArray(sb, entryEntries)
        sb.append(",\n")
        // submaps
        sb.append("  \"submaps\": ")
        writeEntryArray(sb, poolEntries)
        sb.append("\n}\n")
        return sb.toString()
    }
    private fun writeEntryArray(sb: StringBuilder, entries: List<Map<String, Any?>>) {
        if (entries.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        entries.forEachIndexed { i, entry ->
            sb.append("    ")
            writeEntry(sb, entry)
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]")
    }

    /**
     * Serializer for the top-level `biomes.json` index. Mirrors the
     * existing file shape: `{ metadata, biomes: [ {name,type,file,...} ] }`.
     * Index entries are written compactly (one object per line) because
     * they typically only carry 3 fields.
     */
    private fun serializeBiomesIndexJson(
        metadata: Map<*, *>?,
        biomes: List<Map<String, Any?>>
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        if (metadata != null) {
            sb.append("  \"metadata\": ")
            writeValue(sb, metadata, indent = "  ")
            sb.append(",\n")
        }
        sb.append("  \"biomes\": ")
        if (biomes.isEmpty()) {
            sb.append("[]")
        } else {
            sb.append("[\n")
            biomes.forEachIndexed { i, entry ->
                sb.append("    ")
                writeValue(sb, entry, indent = "    ", compactObject = true)
                if (i < biomes.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ]")
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    /** Format a single submap entry on multiple lines for readability. */
    private fun writeEntry(sb: StringBuilder, entry: Map<String, Any?>) {
        sb.append("{\n")
        val keys = entry.keys.toList()
        keys.forEachIndexed { i, key ->
            sb.append("      ")
            sb.append(jsonString(key))
            sb.append(": ")
            val v = entry[key]
            if (key == "sockets" && v is List<*>) {
                writeSocketArray(sb, v)
            } else {
                writeValue(sb, v, indent = "      ")
            }
            if (i < keys.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("    }")
    }

    /** Sockets are written one per line for diff-friendliness. */
    private fun writeSocketArray(sb: StringBuilder, sockets: List<*>) {
        if (sockets.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        sockets.forEachIndexed { i, s ->
            sb.append("        ")
            writeValue(sb, s, indent = "        ", compactObject = true)
            if (i < sockets.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("      ]")
    }

    /**
     * Generic JSON value writer for Map / List / String / Number / Boolean
     * / null. [compactObject] keeps {a:1,b:2} on a single line; otherwise
     * objects span multiple lines with the given [indent] prefix.
     */
    private fun writeValue(
        sb: StringBuilder,
        v: Any?,
        indent: String,
        compactObject: Boolean = false
    ) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Number -> sb.append(formatNumber(v))
            is String -> sb.append(jsonString(v))
            is Map<*, *> -> {
                if (compactObject) {
                    sb.append("{ ")
                    val keys = v.keys.toList()
                    keys.forEachIndexed { i, k ->
                        sb.append(jsonString(k.toString()))
                        sb.append(": ")
                        writeValue(sb, v[k], indent, compactObject = true)
                        if (i < keys.size - 1) sb.append(", ")
                    }
                    sb.append(" }")
                } else {
                    sb.append("{\n")
                    val keys = v.keys.toList()
                    val childIndent = "$indent  "
                    keys.forEachIndexed { i, k ->
                        sb.append(childIndent)
                        sb.append(jsonString(k.toString()))
                        sb.append(": ")
                        writeValue(sb, v[k], childIndent, compactObject)
                        if (i < keys.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append(indent).append("}")
                }
            }
            is List<*> -> {
                if (v.isEmpty()) {
                    sb.append("[]")
                } else {
                    sb.append("[")
                    v.forEachIndexed { i, item ->
                        writeValue(sb, item, indent, compactObject = true)
                        if (i < v.size - 1) sb.append(", ")
                    }
                    sb.append("]")
                }
            }
            else -> sb.append(jsonString(v.toString()))
        }
    }

    /**
     * Render a number without trailing `.0` for integer values — the
     * existing biome.json files use bare integers for coordinates, and
     * the SimpleJsonParser returns every number as Double, so we'd
     * otherwise rewrite `"x": 1` as `"x": 1.0`.
     */
    private fun formatNumber(n: Number): String {
        val d = n.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}





