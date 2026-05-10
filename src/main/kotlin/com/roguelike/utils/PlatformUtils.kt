package com.roguelike.utils

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.prefs.Preferences

object PlatformUtils {
    private const val PREFS_KEY_LAST_DIR = "lastFileDialogDirectory"
    private val prefs = Preferences.userNodeForPackage(PlatformUtils::class.java)

    /** Remembers the last directory used by any file dialog, persisted across launches. */
    private var lastDirectory: java.io.File?
        get() {
            val path = prefs.get(PREFS_KEY_LAST_DIR, null) ?: return null
            val dir = java.io.File(path)
            return if (dir.isDirectory) dir else null
        }
        set(value) {
            if (value != null) prefs.put(PREFS_KEY_LAST_DIR, value.absolutePath)
            else prefs.remove(PREFS_KEY_LAST_DIR)
        }

    fun runAppleScript(script: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
            val result = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && result.isNotEmpty()) result else null
        } catch (e: Exception) {
            null
        }
    }

    fun chooseFile(extension: String): String? {
        return if (System.getProperty("os.name").contains("Mac", true)) {
            val dir = lastDirectory?.absolutePath
            val script = if (dir != null)
                "POSIX path of (choose file of type {\"$extension\"} default location POSIX file \"$dir\")"
            else
                "POSIX path of (choose file of type {\"$extension\"})"
            val result = runAppleScript(script)
            if (result != null) lastDirectory = java.io.File(result).parentFile
            result
        } else {
            val chooser = JFileChooser(lastDirectory)
            chooser.fileFilter = FileNameExtensionFilter("$extension files", extension)
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                lastDirectory = chooser.selectedFile.parentFile
                chooser.selectedFile.absolutePath
            } else null
        }
    }

    fun chooseFileName(defaultName: String): String? {
        return if (System.getProperty("os.name").contains("Mac", true)) {
            val dir = lastDirectory?.absolutePath
            val script = if (dir != null)
                "POSIX path of (choose file name default name \"$defaultName\" default location POSIX file \"$dir\")"
            else
                "POSIX path of (choose file name default name \"$defaultName\")"
            val result = runAppleScript(script)
            if (result != null) lastDirectory = java.io.File(result).parentFile
            result
        } else {
            val chooser = JFileChooser(lastDirectory)
            chooser.selectedFile = java.io.File(lastDirectory ?: java.io.File("."), defaultName)
            val result = chooser.showSaveDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                lastDirectory = chooser.selectedFile.parentFile
                chooser.selectedFile.absolutePath
            } else null
        }
    }
}
