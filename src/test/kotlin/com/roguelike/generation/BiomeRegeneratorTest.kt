package com.roguelike.generation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [BiomeRegenerator]. Each test builds a small on-disk biome
 * layout (`<tmp>/biome.json` + `submaps/` + `submaps-entry/` folders)
 * inside a per-test temp directory, runs `regenerate`, and asserts on
 * both the returned [BiomeRegenerator.Report] and the rewritten file.
 *
 * All assertions on file contents use either the re-parsed JSON (so we
 * don't pin exact whitespace) or simple substring checks.
 */
class BiomeRegeneratorTest {

    private lateinit var tmpDir: File

    @BeforeEach
    fun setUp() {
        tmpDir = Files.createTempDirectory("biome-regen-").toFile()
        File(tmpDir, "submaps").mkdirs()
        File(tmpDir, "submaps-entry").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `adds entries for newly-created wld files`() {
        // Start with an empty biome.json that just has metadata.
        writeBiomeJson(submapsEntry = "[]", submaps = "[]")
        // Add one new pool .wld and one new entry .wld on disk.
        writeWld(File(tmpDir, "submaps/crossing-3x3x3.wld"), 3, 3, 3, withSpawn = false, sockets = mapOf(
            Triple(0, 1, 1) to "WALL_WEST",
            Triple(2, 1, 1) to "WALL_EAST"
        ))
        writeWld(File(tmpDir, "submaps-entry/player-start-3x3x3.wld"), 3, 3, 3, withSpawn = true, sockets = mapOf(
            Triple(0, 1, 1) to "WALL_WEST"
        ))

        val report = BiomeRegenerator.regenerate(biomeJsonFile())

        assertEquals(2, report.added.size, "should add both new files: ${report.added}")
        assertEquals(0, report.removed.size)
        assertEquals(0, report.kept)
        assertTrue(report.errors.isEmpty(), "unexpected errors: ${report.errors}")

        // Re-read the rewritten biome.json and verify shape.
        val rewritten = parse(biomeJsonFile())
        val pool = rewritten["submaps"] as List<Map<String, Any?>>
        val entry = rewritten["submaps-entry"] as List<Map<String, Any?>>
        assertEquals(1, pool.size)
        assertEquals(1, entry.size)
        assertEquals("./submaps/crossing-3x3x3.wld", pool[0]["file"])
        assertEquals("crossing-3x3x3", pool[0]["name"])
        val dims = pool[0]["dimensions"] as Map<String, Any?>
        assertEquals(3L, (dims["width"] as Number).toLong())

        // Sockets inferred from the .wld socketSlots.
        val sockets = pool[0]["sockets"] as List<Map<String, Any?>>
        assertEquals(2, sockets.size)
        assertEquals("WEST", sockets.first { it["slot"] == "WALL_WEST" }["direction"])
        assertEquals("EAST", sockets.first { it["slot"] == "WALL_EAST" }["direction"])

        // PlayerSpawn carried over from the player_spawn-tagged node.
        val spawn = entry[0]["playerSpawn"] as Map<String, Any?>
        assertEquals(1L, (spawn["x"] as Number).toLong())
        assertEquals(1L, (spawn["y"] as Number).toLong())
    }

    @Test
    fun `removes entries whose wld file no longer exists`() {
        // Biome references a .wld that does not exist on disk.
        writeBiomeJson(
            submaps = """
                [
                  {
                    "file": "./submaps/ghost.wld",
                    "name": "ghost",
                    "dimensions": { "width": 3, "height": 3, "depth": 3 },
                    "baseUnitFootprint": { "x": 1, "y": 1, "z": 1 },
                    "sockets": []
                  }
                ]
            """.trimIndent()
        )

        val report = BiomeRegenerator.regenerate(biomeJsonFile())

        assertEquals(0, report.added.size)
        assertEquals(1, report.removed.size, "should drop the missing entry")
        assertEquals(0, report.kept)

        val rewritten = parse(biomeJsonFile())
        assertTrue((rewritten["submaps"] as List<*>).isEmpty())
    }

    @Test
    fun `preserves hand-tuned fields on existing entries`() {
        // Existing entry has custom baseUnitFootprint and an EXTRA field
        // ('biomeFlavor') that the regenerator doesn't know about — both
        // must round-trip unchanged.
        writeWld(File(tmpDir, "submaps/big-12x12.wld"), 12, 12, 3, withSpawn = false, sockets = emptyMap())
        writeBiomeJson(
            submaps = """
                [
                  {
                    "file": "./submaps/big-12x12.wld",
                    "name": "big-12x12",
                    "dimensions": { "width": 12, "height": 12, "depth": 3 },
                    "baseUnitFootprint": { "x": 4, "y": 4, "z": 1 },
                    "biomeFlavor": "spooky",
                    "sockets": []
                  }
                ]
            """.trimIndent()
        )

        val report = BiomeRegenerator.regenerate(biomeJsonFile())

        assertEquals(0, report.added.size)
        assertEquals(0, report.removed.size)
        assertEquals(1, report.kept)

        val rewritten = parse(biomeJsonFile())
        val pool = rewritten["submaps"] as List<Map<String, Any?>>
        assertEquals(1, pool.size)
        val footprint = pool[0]["baseUnitFootprint"] as Map<String, Any?>
        assertEquals(4L, (footprint["x"] as Number).toLong(), "baseUnitFootprint must be preserved")
        assertEquals("spooky", pool[0]["biomeFlavor"], "unknown fields must round-trip")
    }

    @Test
    fun `regenerate is idempotent across runs`() {
        writeWld(File(tmpDir, "submaps/crossing.wld"), 3, 3, 3, withSpawn = false, sockets = mapOf(
            Triple(0, 1, 1) to "WALL_WEST"
        ))
        writeBiomeJson(submapsEntry = "[]", submaps = "[]")

        // First run — picks up the new file.
        val first = BiomeRegenerator.regenerate(biomeJsonFile())
        assertEquals(1, first.added.size)

        // Snapshot text and re-run; should report no changes.
        val firstText = biomeJsonFile().readText()
        val second = BiomeRegenerator.regenerate(biomeJsonFile())
        assertEquals(0, second.added.size, "second run should add nothing")
        assertEquals(0, second.removed.size, "second run should remove nothing")
        assertEquals(1, second.kept)

        // The file text must be byte-identical on the second write
        // (otherwise we'd churn the .json on every restart).
        assertEquals(firstText, biomeJsonFile().readText(),
            "regenerate must produce identical output when nothing changed")
    }

    @Test
    fun `mixed add and remove in one run`() {
        // Two existing entries: one whose file is present, one whose
        // file is gone. Plus a new file on disk not in the index.
        writeWld(File(tmpDir, "submaps/keep.wld"), 3, 3, 3, withSpawn = false, sockets = emptyMap())
        writeWld(File(tmpDir, "submaps/added.wld"), 3, 3, 3, withSpawn = false, sockets = emptyMap())
        writeBiomeJson(
            submaps = """
                [
                  {
                    "file": "./submaps/keep.wld",
                    "name": "keep",
                    "dimensions": { "width": 3, "height": 3, "depth": 3 },
                    "baseUnitFootprint": { "x": 1, "y": 1, "z": 1 },
                    "sockets": []
                  },
                  {
                    "file": "./submaps/gone.wld",
                    "name": "gone",
                    "dimensions": { "width": 3, "height": 3, "depth": 3 },
                    "baseUnitFootprint": { "x": 1, "y": 1, "z": 1 },
                    "sockets": []
                  }
                ]
            """.trimIndent()
        )

        val report = BiomeRegenerator.regenerate(biomeJsonFile())

        assertEquals(1, report.kept, "the 'keep' entry must survive")
        assertEquals(1, report.added.size, "the 'added' file must be appended")
        assertEquals(1, report.removed.size, "the 'gone' entry must be dropped")
        assertTrue(report.removed.any { it.contains("gone") }, "removed=${report.removed}")
        assertTrue(report.added.any { it.contains("added") }, "added=${report.added}")
    }

    @Test
    fun `returns error when biome json is missing`() {
        val report = BiomeRegenerator.regenerate(File(tmpDir, "does-not-exist.json"))
        assertTrue(report.errors.isNotEmpty(), "missing file must surface as an error")
        assertEquals(0, report.added.size)
        assertEquals(0, report.removed.size)
    }

    // ── regenerateIndex tests ─────────────────────────────────────

    @Test
    fun `regenerateIndex adds new biome folders, removes missing ones, regenerates each biome`() {
        // Index points at two biomes: 'default' (still exists) and
        // 'ghost' (its biome.json file is gone). A third biome 'extra'
        // exists on disk under extra/biome.json but is not in the index
        // yet — must be appended.
        val biomesDir = File(tmpDir, "biomes")
        biomesDir.mkdirs()

        // default biome — empty, with a new .wld on disk to pick up
        val defaultDir = File(biomesDir, "default").apply { mkdirs() }
        File(defaultDir, "submaps").mkdirs()
        File(defaultDir, "submaps-entry").mkdirs()
        writeMinimalBiomeJson(File(defaultDir, "biome.json"))
        writeWld(File(defaultDir, "submaps/corner.wld"), 3, 3, 3, withSpawn = false, sockets = emptyMap())

        // extra biome — exists on disk but not yet in the index
        val extraDir = File(biomesDir, "extra").apply { mkdirs() }
        File(extraDir, "submaps").mkdirs()
        File(extraDir, "submaps-entry").mkdirs()
        writeMinimalBiomeJson(File(extraDir, "biome.json"))

        // Write the index referencing 'default' and a missing 'ghost'.
        val indexFile = File(biomesDir, "biomes.json")
        indexFile.writeText(
            """
            {
              "metadata": { "description": "test", "version": 1 },
              "biomes": [
                { "name": "default", "type": "default", "file": "./default/biome.json" },
                { "name": "ghost",   "type": "ghost",   "file": "./ghost/biome.json" }
              ]
            }
            """.trimIndent()
        )

        val report = BiomeRegenerator.regenerateIndex(indexFile)

        // Index-level changes
        assertEquals(1, report.indexReport.added.size, "extra/biome.json must be appended: ${report.indexReport.added}")
        assertEquals(1, report.indexReport.removed.size, "ghost must be dropped: ${report.indexReport.removed}")
        assertEquals(1, report.indexReport.kept, "default must survive")

        // Per-biome reports — one per surviving + new biome entry
        assertEquals(2, report.biomeReports.size, "should run regenerate for both default and extra")
        val defaultReport = report.biomeReports.first { it.biomeJsonPath.contains("default") }
        assertEquals(1, defaultReport.added.size, "default should pick up the new corner.wld: ${defaultReport.added}")

        // Re-read the rewritten index and verify shape
        val rewritten = parse(indexFile)
        val biomes = rewritten["biomes"] as List<Map<String, Any?>>
        val names = biomes.map { it["name"] as String }.toSet()
        assertTrue("default" in names, "default kept: $names")
        assertTrue("extra"   in names, "extra appended: $names")
        assertTrue("ghost" !in names, "ghost dropped: $names")
    }

    @Test
    fun `regenerateIndex preserves hand-edited type and extra fields on kept entries`() {
        val biomesDir = File(tmpDir, "biomes")
        biomesDir.mkdirs()
        val defaultDir = File(biomesDir, "default").apply { mkdirs() }
        File(defaultDir, "submaps").mkdirs()
        File(defaultDir, "submaps-entry").mkdirs()
        writeMinimalBiomeJson(File(defaultDir, "biome.json"))

        val indexFile = File(biomesDir, "biomes.json")
        indexFile.writeText(
            """
            {
              "metadata": { "description": "test", "version": 1 },
              "biomes": [
                { "name": "default", "type": "custom-type", "file": "./default/biome.json", "weight": 7 }
              ]
            }
            """.trimIndent()
        )

        BiomeRegenerator.regenerateIndex(indexFile)

        val rewritten = parse(indexFile)
        val biomes = rewritten["biomes"] as List<Map<String, Any?>>
        assertEquals(1, biomes.size)
        assertEquals("custom-type", biomes[0]["type"], "hand-edited type must round-trip")
        assertEquals(7L, (biomes[0]["weight"] as Number).toLong(), "extra fields must round-trip")
    }

    @Test
    fun `regenerateIndex is idempotent`() {
        val biomesDir = File(tmpDir, "biomes")
        biomesDir.mkdirs()
        val defaultDir = File(biomesDir, "default").apply { mkdirs() }
        File(defaultDir, "submaps").mkdirs()
        File(defaultDir, "submaps-entry").mkdirs()
        writeMinimalBiomeJson(File(defaultDir, "biome.json"))

        val indexFile = File(biomesDir, "biomes.json")
        indexFile.writeText(
            """
            {
              "metadata": { "description": "test", "version": 1 },
              "biomes": []
            }
            """.trimIndent()
        )

        BiomeRegenerator.regenerateIndex(indexFile)
        val first = indexFile.readText()
        BiomeRegenerator.regenerateIndex(indexFile)
        val second = indexFile.readText()
        assertEquals(first, second, "regenerateIndex must produce byte-identical output on the second run")
    }

    @Test
    fun `regenerateIndex returns error when biomes json is missing`() {
        val report = BiomeRegenerator.regenerateIndex(File(tmpDir, "no-such.json"))
        assertTrue(report.indexReport.errors.isNotEmpty(), "missing index must surface as an error")
        assertEquals(0, report.biomeReports.size)
    }

    private fun writeMinimalBiomeJson(file: File) {
        file.writeText(
            """
            {
              "metadata": { "biome": "${file.parentFile.name}", "version": 1 },
              "submaps-entry": [],
              "submaps": []
            }
            """.trimIndent()
        )
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun biomeJsonFile() = File(tmpDir, "biome.json")

    private fun writeBiomeJson(
        metadata: String = """{ "biome": "test", "version": 1 }""",
        submapsEntry: String = "[]",
        submaps: String = "[]"
    ) {
        biomeJsonFile().writeText(
            """
            {
              "metadata": $metadata,
              "submaps-entry": $submapsEntry,
              "submaps": $submaps
            }
            """.trimIndent()
        )
    }

    /**
     * Write a minimal `.wld` file that [BiomeRegenerator] can parse.
     * Only includes the fields the regenerator actually reads (size,
     * `player_spawn` tag if requested, and a node per socket with its
     * `socketSlots` list populated).
     */
    private fun writeWld(
        file: File,
        w: Int, h: Int, d: Int,
        withSpawn: Boolean,
        sockets: Map<Triple<Int, Int, Int>, String>
    ) {
        val nodes = StringBuilder()
        if (withSpawn) {
            nodes.append("""{"x":1,"y":1,"z":1,"tags":["player_spawn"],"tiles":[]}""")
            if (sockets.isNotEmpty()) nodes.append(",")
        }
        sockets.entries.forEachIndexed { i, (pos, slot) ->
            val (x, y, z) = pos
            nodes.append("""{"x":$x,"y":$y,"z":$z,"tiles":[],"socketSlots":["$slot"]}""")
            if (i < sockets.size - 1) nodes.append(",")
        }
        file.writeText(
            """
            {
              "width": $w,
              "height": $h,
              "depth": $d,
              "nodes": [$nodes],
              "associations": [],
              "props": [],
              "lightSources": []
            }
            """.trimIndent()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(file: File): Map<String, Any?> =
        com.roguelike.serialization.SimpleJsonParser.parseAny(file.readText()) as Map<String, Any?>
}


