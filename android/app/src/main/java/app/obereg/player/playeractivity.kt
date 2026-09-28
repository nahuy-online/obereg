package app.obereg.player

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Минимальный плеер: воспроизведение видеофайла, при включённой галочке —
 * через локальный SOCKS5-прокси ядра (то есть через встроенный туннель).
 *
 * Что уже есть: воспроизведение ссылки на файл, маршрутизация через туннель,
 * своё воспроизведение потоков видеосервиса (извлечение делает ядро + оболочка),
 * перебор нескольких источников потока, если очередной не воспроизводится,
 * панель состояния с исходом и счётчиком обслуженных соединений.
 *
 * Что относится к следующему этапу продукта: каталог и поиск внутри приложения,
 * скачивание и офлайн-просмотр.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var urlInput: EditText
    private lateinit var tunnelCheck: CheckBox
    private lateinit var statusView: TextView
    private var currentTitle: String = ""
    private var lastPlayerEvent: String = "событий ещё не было"

    /** Последнее значимое сообщение (исход извлечения, ошибка) — не затирается событиями плеера. */
    private var statusMessage: String = "готово к работе"

    /** Запасные источники потока: если основной не воспроизводится, берём следующий. */
    private val variants = ArrayDeque<ExtractResult>()

    /** Что делать при ошибке воспроизведения (перебор источников). */
    private var onPlaybackFailure: (() -> Boolean)? = null

    private val presets = listOf(
        "asset:///sample.mp4",
        "https://download.samplelib.com/mp4/sample-5s.mp4",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        urlInput = findViewById(R.id.urlInput)
        tunnelCheck = findViewById(R.id.checkTunnel)
        statusView = findViewById(R.id.playerStatus)

        urlInput.setText(presets.first())

        findViewById<Button>(R.id.btnPlay).setOnClickListener { play(urlInput.text.toString().trim()) }
        findViewById<Button>(R.id.btnYouTubePlayer).setOnClickListener { playYouTube() }
        findViewById<Button>(R.id.btnStopPlayback).setOnClickListener { stopPlayback() }
        findViewById<Button>(R.id.btnPreset1).setOnClickListener {
            urlInput.setText(presets[0])
            play(presets[0])
        }
        findViewById<Button>(R.id.btnPreset2).setOnClickListener {
            urlInput.setText(presets[1])
            play(presets[1])
        }
        tunnelCheck.setOnCheckedChangeListener { _, checked ->
            renderStatus(if (checked) "маршрут: туннель (применяется при следующем запуске)" else "маршрут: напрямую")
        }

        renderStatus("готово к воспроизведению")

        // Запуск из каталога: ссылка и название приходят во вложениях.
        val incomingUrl = intent?.getStringExtra(EXTRA_URL)
        if (!incomingUrl.isNullOrEmpty()) {
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: ""
            urlInput.setText(incomingUrl)
            play(incomingUrl)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Плеер создаётся один раз вместе с обработчиком событий.
     *
     * Раньше обработчик добавлялся только в одном из путей запуска, из-за чего ошибка
     * при воспроизведении раздельных дорожек могла остаться незамеченной.
     */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(this).build()
        player = created
        playerView.player = created
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message?.take(140)
                lastPlayerEvent = "ОШИБКА ${error.errorCodeName}: ${error.message}"
                val advanced = onPlaybackFailure?.invoke() ?: false
                if (!advanced) {
                    renderStatus(
                        "воспроизведение не удалось: ${error.errorCodeName}" +
                            if (cause.isNullOrBlank()) "" else " — $cause",
                    )
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                lastPlayerEvent = when (state) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "буферизация"
                    Player.STATE_READY -> "готово/играет"
                    Player.STATE_ENDED -> "завершено"
                    else -> "состояние $state"
                }
                // Важно: состояние плеера не должно затирать исход извлечения.
                renderPanel()
            }
        })
        return created
    }

    private fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onFailure: (() -> Boolean)? = null,
    ) {
        hideKeyboard()
        if (url.isEmpty()) {
            renderStatus("укажите ссылку на видеофайл")
            return
        }
        val local = url.startsWith("asset:///")
        if (!local && YouTubeExtractor.videoIdFrom(url) != null) {
            // Ссылка на страницу видеосервиса — это не файл: плеер такой адрес не разберёт.
            renderStatus(
                "это ссылка на страницу видеосервиса, а не на файл — нажмите " +
                    "«YouTube: свой плеер (извлечение потока)»",
            )
            return
        }
        val tunnel = tunnelCheck.isChecked && !local
        val proxyAddress = if (tunnel) CoreHolder.status().listenAddr else null

        if (tunnel && proxyAddress == null) {
            renderStatus("туннель не поднят — на экране приёмки нажмите «Запустить туннель»; сейчас играю напрямую")
        }

        val exo = ensurePlayer()
        exo.stop()
        exo.clearMediaItems()
        onPlaybackFailure = onFailure

        val mediaSource = buildMediaSource(url, proxyAddress, headers)
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true

        val route = when {
            local -> "локальный файл внутри приложения"
            proxyAddress != null -> "через туннель ($proxyAddress)"
            else -> "напрямую"
        }
        val titleLine = if (currentTitle.isEmpty()) "" else "$currentTitle\n"
        renderStatus("${titleLine}воспроизведение: $route\nисточник: $url")
    }

    /**
     * Своё воспроизведение потоков видеосервиса.
     *
     * Извлечение делает оболочка вместе с ядром; если клиент отдал несколько вариантов
     * потока, плеер начнёт с лучшего и при ошибке сам перейдёт к запасным.
     */
    private fun playYouTube() {
        hideKeyboard()
        val raw = urlInput.text.toString().trim()
        val videoId = YouTubeExtractor.videoIdFrom(raw)
        if (videoId == null) {
            renderStatus("это не похоже на ссылку или идентификатор YouTube")
            return
        }
        val proxy = CoreHolder.status().listenAddr
        renderStatus("извлекаю поток для видео $videoId…")
        Thread {
            val outcome = YouTubeExtractor(
                proxyAddress = proxy,
                cookieProvider = { webViewCookies() },
            ).resolveDetailed(videoId)
            runOnUiThread {
                val first = outcome.usable.firstOrNull()
                if (first == null) {
                    renderStatus("извлечение не удалось: ${outcome.notes.joinToString(" | ")}")
                    return@runOnUiThread
                }
                currentTitle = "YouTube $videoId"
                variants.clear()
                variants.addAll(outcome.usable.drop(1))
                onVariant(first)
            }
        }.start()
    }

    /** Отдать плееру один вариант воспроизведения. */
    private fun onVariant(variant: ExtractResult) {
        when (variant) {
            is ExtractResult.Success -> {
                play(
                    variant.format.url,
                    PlayerApi.mediaHeaders(variant.clientUserAgent),
                    onFailure = { fallBackToNextVariant() },
                )
                val quality = variant.format.qualityLabel ?: variant.format.itag.toString()
                renderStatus(
                    "свой плеер: поток $quality, всего потоков в ответе — ${variant.total}" +
                        (if (variants.isEmpty()) "" else ", запасных источников: ${variants.size}"),
                )
            }
            is ExtractResult.SeparateStreams -> {
                playSeparate(
                    variant.video.url,
                    variant.audio.url,
                    PlayerApi.mediaHeaders(variant.clientUserAgent),
                    onFailure = { fallBackToNextVariant() },
                )
                val quality = variant.video.qualityLabel ?: variant.video.itag.toString()
                renderStatus(
                    "свой плеер: видео и звук раздельно — играю вместе (видео $quality)" +
                        (if (variants.isEmpty()) "" else ", запасных источников: ${variants.size}"),
                )
            }
            else -> Unit
        }
    }

    /** Если поток не пошёл — попробовать следующий источник из собранных вариантов. */
    private fun fallBackToNextVariant(): Boolean {
        val next = variants.removeFirstOrNull() ?: return false
        renderStatus("этот источник не подошёл — пробую следующий (осталось: ${variants.size + 1})")
        onVariant(next)
        return true
    }

    /** Воспроизведение двух раздельных потоков (видео + звук) одновременно. */
    private fun playSeparate(
        videoUrl: String,
        audioUrl: String,
        headers: Map<String, String> = emptyMap(),
        onFailure: (() -> Boolean)? = null,
    ) {
        val proxyAddress = if (tunnelCheck.isChecked) CoreHolder.status().listenAddr else null
        val exo = ensurePlayer()
        exo.stop()
        exo.clearMediaItems()
        onPlaybackFailure = onFailure

        val factory = DefaultDataSource.Factory(this, httpDataSource(proxyAddress, headers))
        val videoSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(videoUrl))
        val audioSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(audioUrl))
        exo.setMediaSource(MergingMediaSource(videoSource, audioSource))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        onPlaybackFailure = null
        variants.clear()
        renderStatus("остановлено")
    }

    private fun buildMediaSource(
        url: String,
        proxyAddress: String?,
        headers: Map<String, String> = emptyMap(),
    ) =
        ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this, httpDataSource(proxyAddress, headers)))
            .createMediaSource(MediaItem.fromUri(url))

    /** Сетевой слой плеера: при наличии адреса прокси HTTP-трафик идёт в SOCKS5 ядра. */
    private fun httpDataSource(
        proxyAddress: String?,
        headers: Map<String, String> = emptyMap(),
    ): OkHttpDataSource.Factory {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
        if (proxyAddress != null) {
            val parts = proxyAddress.split(":")
            if (parts.size == 2) {
                clientBuilder.proxy(
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())),
                )
            }
        }
        return OkHttpDataSource.Factory(clientBuilder.build())
            .setDefaultRequestProperties(headers)
    }

    /**
     * Cookie домена видеосервиса из встроенного браузера.
     *
     * Сессия экрана YouTube уже проходит проверки сервиса, поэтому её cookie
     * заметно повышают шансы, что и запрос извлечения не попадёт под проверку «не бот».
     */
    private fun webViewCookies(): String? = runCatching {
        android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com/")
    }.getOrNull()

    /** Убрать клавиатуру, чтобы она не закрывала строку статуса. */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun renderStatus(message: String) {
        statusMessage = message
        renderPanel()
    }

    /**
     * Собрать панель статуса заново, не теряя последний значимый исход.
     *
     * Раньше события плеера вызывали renderStatus и затирали исход извлечения
     * (он пропадал через долю секунды после старта воспроизведения).
     */
    private fun renderPanel() {
        val status = runCatching { CoreHolder.status() }.getOrNull()
        val core = buildString {
            appendLine("исход: $statusMessage")
            appendLine("плеер: $lastPlayerEvent")
            if (status != null) {
                appendLine("ядро: ${status.state} / ${status.mode} / прокси ${status.listenAddr ?: "нет"}")
                append("обслужено соединений: ${status.sessionsServed}, ↓${status.bytesDown} Б")
            }
        }
        statusView.text = core
    }

    companion object {
        const val EXTRA_URL = "obereg.url"
        const val EXTRA_TITLE = "obereg.title"
    }
}
