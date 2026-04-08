package com.filestreaming.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/**
 * Odtwarzacz streamingowy — streamuje pliki wideo z serwera HTTP.
 *
 * Cechy:
 * - Streamuje bez pobierania na dysk (tylko bufor w RAM)
 * - Obsługuje przewijanie (Range requests)
 * - Obsługuje playlisty (wiele plików) z zarządzaniem pamięcią
 * - Pełny ekran z automatycznym ukrywaniem kontrolek
 * - Zapętlanie / losowa kolejność
 * - Przejdź do następnego / poprzedniego pliku
 * - Muzyka w tle (drugi ExoPlayer) — wybór pliku audio z urządzenia
 * - Start z 5-sekundowym opóźnieniem po kliknięciu Play
 *
 * Zarządzanie pamięcią:
 * - DefaultLoadControl ogranicza bufor do ~60s w przód, ~10s w tył
 * - Sliding window: max ~55 elementów załadowanych w playerze jednocześnie
 *   (50 w przód + 5 w tył od aktualnej pozycji)
 * - Reszta playlisty trzymana jest jako lekkie tablice URL/nazw
 */
class StreamPlayerActivity : AppCompatActivity() {

    companion object {
        private const val HIDE_CONTROLS_DELAY = 3000L
        private const val PLAY_DELAY_SECONDS = 5

        // ---- Zarządzanie pamięcią (buffer) ----
        private const val MIN_BUFFER_MS = 15_000        // 15s — minimum do buforowania
        private const val MAX_BUFFER_MS = 60_000        // 60s — max bufor w przód (~30–180 MB)
        private const val BUFFER_PLAYBACK_MS = 2_500     // 2.5s — wystarczy żeby zacząć odtwarzanie
        private const val BUFFER_REBUFFER_MS = 5_000     // 5s — po rebufferze
        private const val BACK_BUFFER_MS = 10_000        // 10s — tył bufora, potem zwolnij RAM

        // ---- Sliding window (playlista) ----
        private const val WINDOW_AHEAD = 50              // Ładuj max 50 elementów w przód
        private const val WINDOW_BEHIND = 5              // Trzymaj 5 elementów w tył

        // ---- HTTP (połączenie z serwerem) ----
        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000   // 15s na połączenie
        private const val HTTP_READ_TIMEOUT_MS = 30_000      // 30s na odczyt danych
        private const val ERROR_RETRY_DELAY_MS = 3000L       // 3s przerwy przed ponowieniem
        private const val ERROR_MAX_RETRIES = 5              // max 5 prób automatycznego ponowienia
    }

    // --- Player ---
    private lateinit var player: ExoPlayer
    private var audioPlayer: ExoPlayer? = null

    // --- UI ---
    private lateinit var playerView: PlayerView
    private lateinit var controlsPanel: View
    private lateinit var titleLabel: TextView
    private lateinit var fileCounterLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnFullscreen: MaterialButton
    private lateinit var btnLoop: MaterialButton
    private lateinit var btnRandom: MaterialButton
    private lateinit var btnSelectAudio: MaterialButton

    // --- State ---
    private var playlistUrls: Array<String> = emptyArray()
    private var playlistNames: Array<String> = emptyArray()
    private var windowStart = 0   // Absolute index pierwszego elementu załadowanego w playerze
    private var isLooping = false
    private var isRandomMode = false
    private var isMuted = false
    private var isFullscreen = false
    private var controlsVisible = true
    private var backgroundAudioUri: Uri? = null
    private var errorRetryCount = 0

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val playHandler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 0

    // --- Activity Result Launcher for audio file picker ---
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onAudioPicked(it) }
        }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        initViews()
        initPlayer()
        initGestureDetector()

        // Załaduj playlistę z PlaylistHolder (BEZ auto-play)
        loadFromPlaylistHolder()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        audioPlayer?.pause()
        playHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        playHandler.removeCallbacksAndMessages(null)
        player.release()
        audioPlayer?.release()
        PlaylistHolder.clear()
    }

    // =========================================================================
    // Init
    // =========================================================================

    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        controlsPanel = findViewById(R.id.controlsPanel)
        titleLabel = findViewById(R.id.titleLabel)
        fileCounterLabel = findViewById(R.id.fileCounterLabel)
        statusLabel = findViewById(R.id.statusLabel)
        audioLabel = findViewById(R.id.audioLabel)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnLoop = findViewById(R.id.btnLoop)
        btnRandom = findViewById(R.id.btnRandom)
        btnSelectAudio = findViewById(R.id.btnSelectAudio)

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { playPrevious() }
        btnNext.setOnClickListener { playNext() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnLoop.setOnClickListener { toggleLoop() }
        btnRandom.setOnClickListener { toggleRandom() }
        btnSelectAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        // ── HTTP: timeouty i keep-alive dla stabilnego połączenia ──
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        // ── Ograniczenie buforowania — oszczędność RAM ──
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_PLAYBACK_MS,
                BUFFER_REBUFFER_MS
            )
            .setBackBuffer(BACK_BUFFER_MS, false)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        statusLabel.text = getString(R.string.stream_ended)
                        btnPlayPause.text = getString(R.string.play)
                        showControls()
                    }
                    Player.STATE_READY -> {
                        errorRetryCount = 0
                        if (player.isPlaying) {
                            statusLabel.text = if (isMuted) getString(R.string.streaming_muted)
                                                else getString(R.string.streaming)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        statusLabel.text = getString(R.string.buffering)
                    }
                    Player.STATE_IDLE -> {
                        statusLabel.text = getString(R.string.ready)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.text = getString(R.string.pause)
                    statusLabel.text = if (isMuted) getString(R.string.streaming_muted)
                                        else getString(R.string.streaming)
                    scheduleHideControls()
                } else {
                    if (player.playbackState != Player.STATE_ENDED) {
                        btnPlayPause.text = getString(R.string.resume)
                        statusLabel.text = getString(R.string.paused)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                managePlaylistWindow()
                updateFileCounter()
                updateTitle()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (errorRetryCount < ERROR_MAX_RETRIES) {
                    errorRetryCount++
                    statusLabel.text = "Błąd połączenia — ponowienie $errorRetryCount/$ERROR_MAX_RETRIES…"
                    playHandler.postDelayed({
                        val pos = player.currentPosition
                        player.prepare()
                        player.seekTo(player.currentMediaItemIndex, pos)
                        player.play()
                    }, ERROR_RETRY_DELAY_MS)
                } else {
                    statusLabel.text = getString(R.string.stream_error_format, error.message ?: "?")
                    showControls()
                    Toast.makeText(
                        this@StreamPlayerActivity,
                        getString(R.string.playback_error),
                        Toast.LENGTH_LONG
                    ).show()
                    errorRetryCount = 0
                }
            }
        })
    }

    private fun initGestureDetector() {
        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controlsVisible) hideControls() else showControlsTemporarily()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleFullscreen()
                    return true
                }
            })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // =========================================================================
    // Load media (BEZ auto-play) + sliding window
    // =========================================================================

    private fun loadFromPlaylistHolder() {
        if (!PlaylistHolder.hasData()) {
            Toast.makeText(this, getString(R.string.no_url), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Odczytaj dane z singletona
        playlistUrls = PlaylistHolder.urls
        playlistNames = PlaylistHolder.names
        isMuted = PlaylistHolder.isMuted
        val startIndex = PlaylistHolder.startIndex

        // Wycisz wideo jeśli tryb bez dźwięku
        if (isMuted) {
            player.volume = 0f
        }

        // Załaduj okno playlisty wokół startIndex (BEZ auto-play)
        loadPlaylistWindow(startIndex, 0)

        player.repeatMode = Player.REPEAT_MODE_OFF
        // NIE wywołujemy player.play() — czekamy na kliknięcie Play

        updateFileCounter()
        updateTitle()
        statusLabel.text = getString(R.string.ready)
        btnPlayPause.text = getString(R.string.play)

        // Pełny ekran od razu, ale kontrolki widoczne
        enterFullscreen()
        showControls()
        hideHandler.removeCallbacks(hideRunnable)
    }

    // =========================================================================
    // Sliding window — zarządzanie pamięcią playlisty
    // =========================================================================

    /**
     * Ładuje okno elementów do playera wokół [centerIndex].
     * Ładuje max WINDOW_BEHIND elementów za i WINDOW_AHEAD przed aktualną pozycją.
     */
    private fun loadPlaylistWindow(centerIndex: Int, seekPosition: Long) {
        val total = playlistUrls.size
        windowStart = (centerIndex - WINDOW_BEHIND).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_AHEAD).coerceAtMost(total)

        val mediaItems = (windowStart until windowEnd).map {
            MediaItem.fromUri(playlistUrls[it])
        }

        val playerIndex = centerIndex - windowStart
        player.setMediaItems(mediaItems, playerIndex, seekPosition)
        player.prepare()
    }

    /**
     * Dynamicznie dodaje/usuwa elementy w playerze żeby utrzymać okno
     * wokół aktualnie odtwarzanego pliku. Wywoływane przy przejściu na nowy plik.
     */
    private fun managePlaylistWindow() {
        val total = playlistUrls.size
        // Przy małych playlistach nie ma co zarządzać
        if (total <= WINDOW_AHEAD + WINDOW_BEHIND) return

        val playerIdx = player.currentMediaItemIndex
        val absIdx = windowStart + playerIdx

        // 1. Doładuj elementy w przód jeśli potrzeba
        val loadedEnd = windowStart + player.mediaItemCount
        val desiredEnd = (absIdx + WINDOW_AHEAD).coerceAtMost(total)
        for (i in loadedEnd until desiredEnd) {
            player.addMediaItem(MediaItem.fromUri(playlistUrls[i]))
        }

        // 2. Usuń nadmiarowe elementy z tyłu (oszczędność pamięci)
        //    Przy włączonym zapętlaniu NIE usuwamy — bo player musi wrócić na początek
        if (!isLooping) {
            val excess = playerIdx - WINDOW_BEHIND
            if (excess > 0) {
                player.removeMediaItems(0, excess)
                windowStart += excess
            }
        }
    }

    /**
     * Zwraca bezwzględny indeks aktualnego pliku w pełnej playliście.
     */
    private fun getAbsoluteIndex(): Int {
        return windowStart + player.currentMediaItemIndex
    }

    // =========================================================================
    // Background Audio — wybór muzyki z urządzenia
    // =========================================================================

    private fun onAudioPicked(uri: Uri) {
        // Zachowaj uprawnienia do odczytu
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}

        backgroundAudioUri = uri

        // Stwórz / odśwież odtwarzacz audio w tle
        audioPlayer?.release()
        val ap = ExoPlayer.Builder(this).build()
        ap.volume = 0.5f  // 50% głośności
        ap.repeatMode = Player.REPEAT_MODE_ONE  // Zapętlaj muzykę

        val mediaItem = MediaItem.fromUri(uri)
        ap.setMediaItem(mediaItem)
        ap.prepare()

        audioPlayer = ap

        // Pobierz nazwę pliku
        val filename = getFilenameFromUri(uri)
        audioLabel.text = getString(R.string.audio_format, filename)
        statusLabel.text = "Muzyka w tle: $filename"

        // Jeśli wideo jest odtwarzane, uruchom audio natychmiast
        if (player.isPlaying) {
            ap.play()
        }
    }

    private fun getFilenameFromUri(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "audio"
    }

    // =========================================================================
    // Playback controls (z 5-sekundowym opóźnieniem)
    // =========================================================================

    private fun togglePlayPause() {
        if (player.isPlaying) {
            // Pauza
            player.pause()
            audioPlayer?.pause()
            playHandler.removeCallbacksAndMessages(null)
            btnPlayPause.text = getString(R.string.resume)
            statusLabel.text = getString(R.string.paused)
            showControls()
            hideHandler.removeCallbacks(hideRunnable)
        } else {
            // Jeśli zakończono — przewiń na początek
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0, 0)
            }

            // Ukryj kontrolki od razu
            controlsPanel.visibility = View.GONE
            controlsVisible = false

            // Odliczanie 5 sekund
            countdownSeconds = PLAY_DELAY_SECONDS
            playHandler.removeCallbacksAndMessages(null)
            startCountdown()
        }
    }

    private fun startCountdown() {
        if (countdownSeconds > 0) {
            statusLabel.text = getString(R.string.start_countdown, countdownSeconds)
            countdownSeconds--
            playHandler.postDelayed({ startCountdown() }, 1000)
        } else {
            // Czas minął — uruchom odtwarzanie
            player.play()
            audioPlayer?.let {
                if (backgroundAudioUri != null) it.play()
            }
            btnPlayPause.text = getString(R.string.pause)
            statusLabel.text = if (isMuted) getString(R.string.streaming_muted)
                                else getString(R.string.streaming)
        }
    }

    private fun playNext() {
        val absIdx = getAbsoluteIndex()

        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (absIdx < playlistUrls.size - 1) {
            // Jesteśmy na krawędzi okna — doładuj i spróbuj ponownie
            managePlaylistWindow()
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
        } else if (isLooping && playlistUrls.size > 1) {
            // Koniec playlisty z zapętleniem — wróć na początek
            val wasPlaying = player.isPlaying
            loadPlaylistWindow(0, 0)
            if (wasPlaying) player.play()
        } else {
            Toast.makeText(this, getString(R.string.last_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPrevious() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            val absIdx = getAbsoluteIndex()
            if (absIdx > 0) {
                // Okno nie sięga dalej w tył — przeładuj z nowym centrum
                val wasPlaying = player.isPlaying
                loadPlaylistWindow(absIdx - 1, 0)
                if (wasPlaying) player.play()
            } else {
                // Już na samym początku
                player.seekTo(0)
            }
        }
    }

    // =========================================================================
    // Loop & Random
    // =========================================================================

    private fun toggleLoop() {
        isLooping = !isLooping
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        btnLoop.text = getString(if (isLooping) R.string.loop_on else R.string.loop_off)

        // Gdy włączamy pętlę, a okno nie zaczyna się od 0 — musimy dodać
        // brakujące elementy na początku, bo player musi móc wrócić na start
        if (isLooping && windowStart > 0) {
            val missingItems = (0 until windowStart).map {
                MediaItem.fromUri(playlistUrls[it])
            }
            player.addMediaItems(0, missingItems)
            windowStart = 0
        }
    }

    private fun toggleRandom() {
        isRandomMode = !isRandomMode
        player.shuffleModeEnabled = isRandomMode
        btnRandom.text = getString(if (isRandomMode) R.string.random_on else R.string.random_off)
    }

    // =========================================================================
    // Fullscreen
    // =========================================================================

    private fun enterFullscreen() {
        isFullscreen = true
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        btnFullscreen.text = getString(R.string.window_mode)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            btnFullscreen.text = getString(R.string.window_mode)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.text = getString(R.string.fullscreen)
        }
        showControlsTemporarily()
    }

    // =========================================================================
    // Controls visibility
    // =========================================================================

    private fun showControls() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun showControlsTemporarily() {
        controlsPanel.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsPanel.visibility = View.GONE
        controlsVisible = false
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, HIDE_CONTROLS_DELAY)
    }

    // =========================================================================
    // UI Updates (używa bezwzględnego indeksu, nie indeksu w oknie)
    // =========================================================================

    private fun updateFileCounter() {
        val total = playlistUrls.size
        val current = if (total > 0) getAbsoluteIndex() + 1 else 0
        fileCounterLabel.text = getString(R.string.file_counter_format, current, total)
    }

    private fun updateTitle() {
        val absIdx = getAbsoluteIndex()
        if (absIdx in playlistNames.indices) {
            titleLabel.text = playlistNames[absIdx]
        }
    }

    // =========================================================================
    // Back
    // =========================================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Anuluj odliczanie jeśli aktywne
        playHandler.removeCallbacksAndMessages(null)

        if (isFullscreen) {
            toggleFullscreen()
        } else {
            audioPlayer?.stop()
            super.onBackPressed()
        }
    }
}
