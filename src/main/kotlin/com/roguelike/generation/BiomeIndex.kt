package com.roguelike.generation

import com.roguelike.serialization.SimpleJsonParser
import java.io.File

/**
 * A biome entry from the top-level `biomes.json` index.
 *
 * @param name human-readable biome name (used in the UI picker).
 * @param type biome type identifier (e.g. "default").
 * @param biomeJsonFile resolved File pointing at this biome's own `biome.json`.
 */
data class BiomeEntry(
    val name: String,
    val type: String,
    val biomeJsonFile: File
)

/**
 * A single submap reference inside a biome's `biome.json`.
 *
 * @param name display name (the .wld file's basename without extension).
 * @param worldFile resolved absolute File pointing at the .wld template.
 * @param isStartingSubmap true if the entry came from the `submaps-entry`
 *        section (i.e. it is a valid player-spawn seed); false if it came
 *        from the regular `submaps` pool used by the random generator.
 */
data class BiomeSubmapRef(
    val name: String,
    val worldFile: File,
    val isStartingSubmap: Boolean
)

/**
 * Fully-resolved biome definition loaded from a per-biome `biome.json`.
 *
 * @param entry the original index entry that pointed at this biome.
 * @param startingSubmaps submaps from the `submaps-entry` section — these
 *        are the candidate starting (player-spawn) submaps for this biome.
 * @param submaps submaps from the `submaps` section — these are the pool
 *        the procedural generator draws from when expanding the world.
 */
data class BiomeDefinition(
    val entry: BiomeEntry,
    val startingSubmaps: List<BiomeSubmapRef>,
    val submaps: List<BiomeSubmapRef>
)

/**
 * Reads the top-level `biomes.json` index and per-biome `biome.json` files.
 *
 * File layout under `src/main/resources/world-submaps/`:
 *  - `biomes/biomes.json`        — top-level index: list of biomes.
 *  - `biomes/<biome>/biome.json` — per-biome definition referenced from above.
 *
 * Paths inside each JSON are resolved relative to that JSON file's parent
 * directory, so the index is portable across installs.
 */
object BiomeIndex {

    /**
     * Default location of the biome index file on disk. The launcher uses
     * this when no override is supplied.
     */
    const val DEFAULT_INDEX_PATH = "src/main/resources/world-submaps/biomes/biomes.json"

    /**
     * Parses the top-level `biomes.json` and returns the declared biomes.
     * Each entry's `biomeJsonFile` is already resolved against the index
     * file's directory. Returns an empty list if the index is missing or
     * malformed (with a console diagnostic).
     */
    fun loadIndex(indexPath: String = DEFAULT_INDEX_PATH): List<BiomeEntry> {
        val indexFile = File(indexPath)
        if (!indexFile.exists()) {
            System.err.println("[BiomeIndex] index file not found: ${indexFile.absolutePath}")
            return emptyList()
        }
        val root = SimpleJsonParser.parseAny(indexFile.readText()) as? Map<*, *> ?: run {
            System.err.println("[BiomeIndex] index file is not a JSON object: $indexPath")
            return emptyList()
        }
        val biomes = root["biomes"] as? List<*> ?: run {
            System.err.println("[BiomeIndex] index missing 'biomes' array")
            return emptyList()
        }
        val baseDir = indexFile.parentFile ?: File(".")
        val out = ArrayList<BiomeEntry>()
        for (raw in biomes) {
            val m = raw as? Map<*, *> ?: continue
            val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val type = (m["type"] as? String) ?: name
            val rel = (m["file"] as? String) ?: continue
            val resolved = resolveRelative(baseDir, rel)
            out.add(BiomeEntry(name = name, type = type, biomeJsonFile = resolved))
        }
        return out
    }

    /**
     * Loads a single biome's `biome.json` and returns the resolved submap
     * references. Returns `null` if the file is missing or unreadable.
     */
    fun loadBiome(entry: BiomeEntry): BiomeDefinition? {
        val file = entry.biomeJsonFile
        if (!file.exists()) {
            System.err.println("[BiomeIndex] biome file missing for '${entry.name}': ${file.absolutePath}")
            return null
        }
        val root = SimpleJsonParser.parseAny(file.readText()) as? Map<*, *> ?: run {
            System.err.println("[BiomeIndex] biome file is not a JSON object: ${file.absolutePath}")
            return null
        }
        val baseDir = file.parentFile ?: File(".")
        val starting = readSubmapList(root["submaps-entry"], baseDir, isStarting = true)
        val regular = readSubmapList(root["submaps"], baseDir, isStarting = false)
        println("[BiomeIndex] loaded biome '${entry.name}' (type=${entry.type}): " +
                "${starting.size} starting submap(s), ${regular.size} pool submap(s)")
        return BiomeDefinition(entry, starting, regular)
    }

    private fun readSubmapList(raw: Any?, baseDir: File, isStarting: Boolean): List<BiomeSubmapRef> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<BiomeSubmapRef>()
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val rel = (m["file"] as? String) ?: continue
            val name = (m["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: File(rel).nameWithoutExtension
            val resolved = resolveRelative(baseDir, rel)
            if (!resolved.exists()) {
                System.err.println("[BiomeIndex] referenced .wld missing: ${resolved.absolutePath}")
                continue
            }
            out.add(BiomeSubmapRef(name = name, worldFile = resolved, isStartingSubmap = isStarting))
        }
        return out
    }

    private fun resolveRelative(baseDir: File, relPath: String): File {
        val asFile = File(relPath)
        return if (asFile.isAbsolute) asFile else File(baseDir, relPath).normalize()
    }
}

