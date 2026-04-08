package com.filestreaming.app

/**
 * Singleton przechowujący dane playlisty w pamięci.
 *
 * Rozwiązuje problem TransactionTooLargeException — Android ma limit ~500KB
 * na dane przekazywane przez Intent extras. Przy dużej ilości plików
 * (setki/tysiące) tablice URL-i mogą ten limit przekroczyć.
 *
 * Zamiast tego: MainActivity zapisuje playlistę tutaj,
 * a StreamPlayerActivity ją odczytuje.
 */
object PlaylistHolder {
    var urls: Array<String> = emptyArray()
    var names: Array<String> = emptyArray()
    var startIndex: Int = 0
    var isMuted: Boolean = false

    fun clear() {
        urls = emptyArray()
        names = emptyArray()
        startIndex = 0
        isMuted = false
    }

    fun hasData(): Boolean = urls.isNotEmpty()
}

