package com.roguelike.utils

object PlatformUtils {
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
            runAppleScript("POSIX path of (choose file of type {\"$extension\"})")
        } else {
            null
        }
    }

    fun chooseFileName(defaultName: String): String? {
        return if (System.getProperty("os.name").contains("Mac", true)) {
            runAppleScript("POSIX path of (choose file name default name \"$defaultName\")")
        } else {
            null
        }
    }
}
