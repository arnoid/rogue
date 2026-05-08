package com.roguelike.utils

import java.io.File
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object PlatformUtils {
    private val prefs = Preferences.userNodeForPackage(PlatformUtils::class.java)
    private const val LAST_DIR_KEY = "lastFileDialogDir"

    private fun getLastDir(): File? {
        val path = prefs.get(LAST_DIR_KEY, null) ?: return null
        val dir = File(path)
        return if (dir.isDirectory) dir else null
    }

    private fun saveLastDir(file: File) {
        val dir = if (file.isDirectory) file else file.parentFile
        if (dir != null) prefs.put(LAST_DIR_KEY, dir.absolutePath)
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
            val result = runAppleScript("POSIX path of (choose file of type {\"$extension\"})")
            if (result != null) saveLastDir(File(result))
            result
        } else {
            val chooser = JFileChooser(getLastDir())
            chooser.fileFilter = FileNameExtensionFilter("$extension files", extension)
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                saveLastDir(chooser.selectedFile)
                chooser.selectedFile.absolutePath
            } else null
        }
    }

    fun chooseFileName(defaultName: String): String? {
        return if (System.getProperty("os.name").contains("Mac", true)) {
            val result = runAppleScript("POSIX path of (choose file name default name \"$defaultName\")")
            if (result != null) saveLastDir(File(result))
            result
        } else {
            val chooser = JFileChooser(getLastDir())
            chooser.selectedFile = File(getLastDir() ?: File("."), defaultName)
            val result = chooser.showSaveDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                saveLastDir(chooser.selectedFile)
                chooser.selectedFile.absolutePath
            } else null
        }
    }
}
