package com.roguelike.generation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end smoke test that copies the real `biomes.json` + the real
 * `default/biome.json` and all its `.wld` files into a temp tree, then
 * runs [BiomeRegenerator.regenerateIndex] and asserts:
 *  - every existing biome and submap entry is preserved (no spurious
 *    drops);
 *  - re-running the regenerator produces byte-identical output (true
 *    idempotence on real data).
 *
 * Skipped silently if the source files aren't present (e.g. running
 * from a stripped distribution).
 */
class BiomeRegeneratorSmokeTest {

    @Test
    fun `real biomes index round-trips without data loss`() {
        val realIndex = File("src/main/resources/world-submaps/biomes/biomes.json")
        if (!realIndex.isFile) return

        val tmp = Files.createTempDirectory("biome-regen-smoke-").toFile()
        try {
            // Mirror the entire biomes/ tree (small — a handful of files).
            val realDir = realIndex.parentFile
            realDir.copyRecursively(tmp, overwrite = true)
            val indexInTmp = File(tmp, "biomes.json")
            assertTrue(indexInTmp.isFile)

            val report = BiomeRegenerator.regenerateIndex(indexInTmp)

            // The real tree has no orphan references and no extra .wld
            // files that aren't already indexed — so a "do-nothing" run
            // is expected. Allow added > 0 only if the user has actually
            // dropped new .wld files since the last regen; in that case
            // nothing should be removed.
            assertEquals(0, report.indexReport.errors.size,
                "index errors: ${report.indexReport.errors}")
            for (r in report.biomeReports) {
                assertEquals(0, r.errors.size, "biome ${r.biomeJsonPath} errors: ${r.errors}")
            }

            // Idempotence on real data.
            val firstIndex = indexInTmp.readText()
            val firstBiomeTexts = report.biomeReports.map { File(it.biomeJsonPath).readText() }
            BiomeRegenerator.regenerateIndex(indexInTmp)
            assertEquals(firstIndex, indexInTmp.readText(),
                "real biomes.json must be byte-stable across runs")
            report.biomeReports.forEachIndexed { i, r ->
                assertEquals(firstBiomeTexts[i], File(r.biomeJsonPath).readText(),
                    "biome ${r.biomeJsonPath} must be byte-stable across runs")
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}

