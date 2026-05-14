package com.roguelike.ui

import java.io.File

/**
 * Manages an MRU (most-recently-used) list of world file paths.
 * Persisted to a simple text file so it survives restarts.
 */
class RecentFiles(private val maxEntries: Int = 10) {

    private val storePath = "saved-worlds/.recent"
    private val entries = mutableListOf<String>()

    init {
        load()
    }

    /** Return an immutable snapshot of the recent list (newest first). */
    fun list(): List<String> = entries.toList()

    /** Record that [path] was just opened or saved. Moves it to the top. */
    fun touch(path: String) {
        val canonical = File(path).canonicalPath
        entries.remove(canonical)
        entries.add(0, canonical)
        if (entries.size > maxEntries) {
            entries.subList(maxEntries, entries.size).clear()
        }
        save()
    }

    private fun load() {
        try {
            val f = File(storePath)
            if (f.exists()) {
                entries.clear()
                f.readLines()
                    .filter { it.isNotBlank() }
                    .take(maxEntries)
                    .forEach { entries.add(it) }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun save() {
        try {
            File(storePath).parentFile?.mkdirs()
            File(storePath).writeText(entries.joinToString("\n"))
        } catch (_: Exception) { /* ignore */ }
    }
}

