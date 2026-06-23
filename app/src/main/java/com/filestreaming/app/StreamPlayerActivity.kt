package com.filestreaming.app

import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/**
 * Odtwarzacz streamingowy VR — streamuje pliki wideo z serwera HTTP.
 *
 * Wersja VR: okno/panel na goglach jest maksymalnie duże (cała szerokość
 * i wysokość pola widzenia). Wideo skaluje się z zachowaniem proporcji
 * (resize_mode="fit") — jak na ekranie kinowym w VR.
 *
 * Cechy:
 * - Streamuje bez pobierania na dysk (tylko bufor w RAM)
 * - Obsługuje przewijanie (Range requests)
 * - Obsługuje playlisty (wiele plików) z zarządzaniem pamięcią
 * - Pełny immersive mode (bez pasków systemowych)
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
        // Wartości dostrojone pod Wi-Fi (sieć radiowa = jitter, nie kabel).
        // Poprzednie MIN_BUFFER_MS=15s / BUFFER_PLAYBACK_MS=2.5s startowały
        // odtwarzanie z bardzo małym zapasem — wystarczał chwilowy spadek
        // przepustowości (retransmisja Wi-Fi, GC, obciążenie serwera), żeby
        // bufor się wyczerpał szybciej niż zdążył dociągnąć dane, co dawało
        // losowe ścinanie w trakcie odtwarzania mimo szybkiego łącza.
        private const val MIN_BUFFER_MS = 30_000        // 30s — minimum do buforowania
        private const val MAX_BUFFER_MS = 90_000        // 90s — max bufor w przód
        private const val BUFFER_PLAYBACK_MS = 5_000     // 5s zapasu przed startem odtwarzania
        private const val BUFFER_REBUFFER_MS = 8_000     // 8s zapasu po rebufferze, zanim ruszy dalej
        private const val BACK_BUFFER_MS = 10_000        // 10s — tył bufora, potem zwolnij RAM

        // ---- Sliding window (playlista) ----
        private const val WINDOW_AHEAD = 50              // Ładuj max 50 elementów w przód
        private const val WINDOW_BEHIND = 5              // Trzymaj 5 elementów w tył
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

        // VR: nadpisz density w kontekście Activity (uzupełnia VrApp)
        overrideActivityDensity()

        // VR: tryb kina — immersive, ekran zawsze włączony, maksymalny panel
        setupCinemaMode()

        setContentView(R.layout.activity_player)

        initViews()
        initPlayer()
        initGestureDetector()

        // Załaduj playlistę z PlaylistHolder (BEZ auto-play)
        loadFromPlaylistHolder()
    }

    override fun onResume() {
        super.onResume()
        // VR: przywróć immersive mode (gogle mogą go zresetować)
        enterFullscreen()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // VR: po zmianie konfiguracji (np. obrót) — ponownie nadpisz density
        overrideActivityDensity()
    }

    // =========================================================================
    // VR Cinema Mode
    // =========================================================================

    /**
     * Nadpisuje density w kontekście Activity.
     * Uzupełnia globalny override z VrApp — Activity ma własny Resources
     * który trzeba osobno skonfigurować.
     */
    private fun overrideActivityDensity() {
        val targetDpi = VrApp.VR_DENSITY_DPI
        val density = targetDpi / 160f

        resources.displayMetrics.apply {
            densityDpi = targetDpi
            this.density = density
            scaledDensity = density
        }
        resources.configuration.densityDpi = targetDpi
    }

    /**
     * Konfiguruje pełny tryb kina VR:
     * - Ekran zawsze włączony (nie gaśnie w goglach)
     * - Ukryj WSZYSTKO (status bar, navigation bar)
     * - Rysuj pod wycięciami ekranu (notch/cutout)
     * - Żądaj maksymalnego rozmiaru okna
     */
    @Suppress("DEPRECATION")
    private fun setupCinemaMode() {
        // Ekran zawsze włączony — nie gaśnie w VR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Pełny immersive mode — schowaj WSZYSTKO (status bar, navigation bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Display cutout — rysuj pod wycięciami ekranu (notch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // VR: programowe żądanie maksymalnego rozmiaru okna/panelu
        requestMaxWindowSize()
    }

    /**
     * Programowo żąda maksymalnego rozmiaru okna.
     * Na goglach Meta Quest to ustawia panel 2D na największy możliwy rozmiar.
     */
    @Suppress("DEPRECATION")
    private fun requestMaxWindowSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30)
            window.setDecorFitsSystemWindows(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            // Rozciągnij okno na CAŁY dostępny ekran, nawet poza system bars
            window.setAttributes(window.attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            })
        } else {
            // Starsze API — użyj system UI flags
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    // =========================================================================
    // Init
    // =========================================================================

    @OptIn(UnstableApi::class)
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

        // VR Cinema: wideo dopasowuje się do rozmiaru okna, zachowując proporcje
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

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
        // ── Ograniczenie buforowania — oszczędność RAM ──
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_PLAYBACK_MS,
                BUFFER_REBUFFER_MS
            )
            .setBackBuffer(BACK_BUFFER_MS, false)  // Zwolnij dane po 10s za kursorem
            // KRYTYCZNE dla wideo VR (duża rozdzielczość = duży bitrate):
            // Domyślny DefaultLoadControl ma też limit bufora WG ROZMIARU (bajtów),
            // nie tylko wg czasu. Przy wysokim bitrate plik VR mógł trafiać w ten
            // limit rozmiaru i przestawać buforować na długo PRZED osiągnięciem
            // MAX_BUFFER_MS w czasie — co dawało wyczerpanie bufora i zacięcie,
            // mimo że łącze (140 Mb/s) miało zapas przepustowości.
            // prioritizeTimeOverSizeThresholds=true każe LoadControl trzymać się
            // wyłącznie progów czasowych zdefiniowanych wyżej.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
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
                // ── Zarządzanie sliding window ──
                managePlaylistWindow()
                updateFileCounter()
                updateTitle()
            }

            override fun onPlayerError(error: PlaybackException) {
                statusLabel.text = getString(R.string.stream_error_format, error.message ?: "?")
                showControls()
                Toast.makeText(
                    this@StreamPlayerActivity,
                    getString(R.string.playback_error),
                    Toast.LENGTH_LONG
                ).show()
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
    // Fullscreen — VR enhanced
    // =========================================================================

    /**
     * Wchodzi w pełny immersive mode.
     * Na goglach VR: ukrywa WSZYSTKIE paski systemowe i rozciąga okno.
     */
    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        isFullscreen = true

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // VR: dodatkowe flagi immersive (dla starszych API i gogli)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

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
