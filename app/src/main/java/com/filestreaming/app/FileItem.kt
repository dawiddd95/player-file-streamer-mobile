package com.filestreaming.app

/**
 * Model danych dla pliku/katalogu na serwerze.
 */
data class FileItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0
) {
    /**
     * Rozmiar pliku w czytelnej formie.
     */
    fun formattedSize(): String {
        if (isDirectory) return ""
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
            else -> "${"%.2f".format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Rozszerzenie pliku (lowercase).
     */
    fun extension(): String {
        return name.substringAfterLast('.', "").lowercase()
    }

    /**
     * Czy plik jest multimedialny?
     */
    fun isMedia(): Boolean {
        val mediaExtensions = setOf(
            "mp3", "mp4", "avi", "mkv", "flv", "wmv",
            "mov", "m4v", "flac", "wav", "ogg", "aac",
            "wma", "m4a", "webm", "3gp", "ts", "m2ts",
            "m3u8"
        )
        return extension() in mediaExtensions
    }

    /**
     * Czy plik jest wideo?
     */
    fun isVideo(): Boolean {
        val videoExtensions = setOf(
            "mp4", "avi", "mkv", "flv", "wmv",
            "mov", "m4v", "webm", "3gp", "ts", "m2ts",
            "m3u8"
        )
        return extension() in videoExtensions
    }
}

