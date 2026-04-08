package com.filestreaming.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Ekran startowy — wybór trybu odtwarzania.
 *
 * Trzy warianty:
 * 1. Odtwarzacz standardowy (sortowanie naturalne: 1, 2, 10)
 * 2. Odtwarzacz z sortowaniem jak w Windows (locale: 1, 10, 2)
 * 3. Wideo bez dźwięku (sortowanie jak w Windows)
 *
 * Wszystkie warianty obsługują muzykę w tle (wybór w ekranie odtwarzacza).
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SORT_MODE = "sort_mode"
        const val EXTRA_MUTED = "muted"

        const val SORT_NATURAL = 0
        const val SORT_LOCALE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        // Przycisk 1: Odtwarzacz standardowy (sortowanie naturalne)
        findViewById<MaterialButton>(R.id.btnStandard).setOnClickListener {
            launchMain(sortMode = SORT_NATURAL, muted = false)
        }

        // Przycisk 2: Odtwarzacz z sortowaniem jak w Windows
        findViewById<MaterialButton>(R.id.btnWindowsSort).setOnClickListener {
            launchMain(sortMode = SORT_LOCALE, muted = false)
        }

        // Przycisk 3: Wideo bez dźwięku (sortowanie jak w Windows)
        findViewById<MaterialButton>(R.id.btnMuted).setOnClickListener {
            launchMain(sortMode = SORT_LOCALE, muted = true)
        }
    }

    private fun launchMain(sortMode: Int, muted: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_SORT_MODE, sortMode)
            putExtra(EXTRA_MUTED, muted)
        }
        startActivity(intent)
    }
}

