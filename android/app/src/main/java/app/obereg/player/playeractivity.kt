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

    /** Последняя отданная плееру ссылка и её заголовки — нужны для пробы при отказе. */
    private var currentStreamUrl: String? = null
    private var currentStreamHeaders: Map<String, String> = emptyMap()

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
        findViewById<Button>(R.id.btnCheckLogin).setOnClickListener {
            renderStatus("проверка входа: ${PlayerApi.loginStateLabel(webViewCookies())}")
        }
        findViewById<Button>(R.id.btnYouTubeScreen).setOnClickListener {
            // Запасной путь: экран видеосервиса внутри приложения — там проверка сервиса проходит сама.
            val raw = urlInput.text.toString().trim()
            val id = YouTubeExtractor.videoIdFrom(raw)
            val intent = android.content.Intent(this, YouTubeActivity::class.java)
            if (id != null) intent.putExtra(YouTubeActivity.EXTRA_VIDEO_ID, id)
            startActivity(intent)
            renderStatus(
                if (id == null) {
                    "открыл экран YouTube; вставьте ссылку в поле и нажмите «Открыть»"
                } else {
                    "открыл экран YouTube с видео $id"
                },
            )
        }
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

        // Поток, пойманный у встроенного браузера: его проверку прошёл движок браузера,
        // поэтому ссылка обычно воспроизводится, а рекламы у нас нет.
        val streamVideo = intent?.getStringExtra(EXTRA_STREAM_VIDEO)
        if (!streamVideo.isNullOrEmpty()) {
            val headers = PlayerApi.mediaHeaders(PlayerApi.BROWSER_USER_AGENT, webViewCookies())
            val streamAudio = intent?.getStringExtra(EXTRA_STREAM_AUDIO)
            currentTitle = "поток со страницы"
            if (!streamAudio.isNullOrEmpty() && streamAudio != streamVideo) {
                playSeparate(streamVideo, streamAudio, headers)
                renderStatus("свой плеер: играю пойманный поток — видео и звук вместе")
            } else {
                play(streamVideo, headers)
                renderStatus("свой плеер: играю пойманный поток")
            }
            return
        }

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
                DiagLog.add("воспроизведение: ${error.errorCodeName}${cause?.let { " ($it)" } ?: ""}")
                val advanced = onPlaybackFailure?.invoke() ?: false
                if (!advanced) {
                    renderStatus(
                        "воспроизведение не удалось: ${error.errorCodeName}" +
                            if (cause.isNullOrBlank()) "" else " — $cause",
                    )
                    // Если сервис отказал по коду или отдал непонятный контейнер —
                    // спрашиваем у него самого причину и что именно он присылает.
                    if (error.errorCodeName == "ERROR_CODE_IO_BAD_HTTP_STATUS" ||
                        error.errorCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
                    ) {
                        probeCurrentStream()
                    }
                } else {
                    renderPanel()
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
        val proxyAddress = if (local) null else currentProxy()

        if (tunnel && proxyAddress == null) {
            renderStatus("туннель не поднят — на экране приёмки нажмите «Запустить туннель»; сейчас играю напрямую")
        }

        val exo = ensurePlayer()
        exo.stop()
        exo.clearMediaItems()
        onPlaybackFailure = onFailure
        currentStreamUrl = if (local) null else url
        currentStreamHeaders = headers

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
        val proxy = currentProxy()
        renderStatus("извлекаю поток для видео $videoId… (маршрут: ${routeLabel(proxy)})")
        Thread {
            // Любая непредвиденная ошибка должна стать понятной строкой, а не вылетом приложения.
            val outcome = runCatching {
                YouTubeExtractor(
                    proxyAddress = proxy,
                    cookieProvider = { webViewCookies() },
                ).resolveDetailed(videoId)
            }.getOrElse { error ->
                runOnUiThread {
                    renderStatus(
                        "извлечение прервано ошибкой: ${error.javaClass.simpleName}" +
                            (error.message?.let { ": " + it.take(120) } ?: ""),
                    )
                }
                null
            } ?: return@Thread
            runOnUiThread {
                val first = outcome.usable.firstOrNull()
                DiagLog.add(
                    "извлечение: пригодных ${outcome.usable.size}; заметки: ${outcome.notes.joinToString(" | ").take(300)}",
                )
                outcome.usable.forEachIndexed { index, variant ->
                    val url = when (variant) {
                        is ExtractResult.Success -> variant.format.url
                        is ExtractResult.SeparateStreams -> "${variant.video.url.take(120)} | ${variant.audio.url.take(120)}"
                        else -> "?"
                    }
                    DiagLog.add("вариант ${index + 1}: $url")
                }
                if (first == null) {
                    val cookies = webViewCookies()
                    val hint = if (!PlayerApi.hasLoginCookies(cookies)) {
                        " — войдите в видеосервис на экране YouTube и повторите: проверка «не бот» обычно снимается входом"
                    } else {
                        ""
                    }
                    renderStatus(
                        "извлечение не удалось: ${outcome.notes.joinToString(" | ")}$hint" +
                            " — рабочий путь: кнопка «Экран YouTube»",
                    )
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
                    PlayerApi.mediaHeaders(variant.clientUserAgent, webViewCookies()),
                    onFailure = { fallBackToNextVariant() },
                )
                DiagLog.add("играю вариант: ${variant.format.url.take(180)}")
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
                    PlayerApi.mediaHeaders(variant.clientUserAgent, webViewCookies()),
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
        val proxyAddress = currentProxy()
        val exo = ensurePlayer()
        exo.stop()
        exo.clearMediaItems()
        onPlaybackFailure = onFailure
        currentStreamUrl = videoUrl
        currentStreamHeaders = headers

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

    /**
     * Проба ссылки на поток при отказе: спрашиваем у сервиса причину его же словами.
     *
     * Номер кода мало о чём говорит («403» — это и требование токена, и регион, и подпись),
     * а в теле ответа сервис обычно пишет причину открытым текстом.
     */
    private fun probeCurrentStream() {
        val url = currentStreamUrl ?: return
        val headers = currentStreamHeaders
        val proxyAddress = currentProxy()
        Thread {
            val summary = try {
                val builder = OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .readTimeout(java.time.Duration.ofSeconds(20))
                if (proxyAddress != null) {
                    val parts = proxyAddress.split(":")
                    if (parts.size == 2) {
                        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())))
                    }
                }
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1023")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                builder.build().newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    val stream = response.body?.byteStream()
                    val head = ByteArray(16)
                    val read = stream?.read(head) ?: -1
                    val headBytes = if (read > 0) head.copyOf(read) else ByteArray(0)
                    val looksTextual = contentType?.let {
                        it.startsWith("text") || it.contains("json") || it.contains("html")
                    } ?: false
                    val text = if (looksTextual) String(headBytes, Charsets.UTF_8) else ""
                    PlayerApi.responseFingerprint(response.code, contentType, headBytes) +
                        if (text.isNotBlank()) " · текст: ${text.take(80)}" else ""
                }
            } catch (error: Exception) {
                "проба не удалась: ${error.message?.take(120)}"
            }
            val report = exitAddressReport(proxyAddress)
            runOnUiThread {
                DiagLog.add("проба ссылки: $summary")
                renderStatus("проверка ссылки (${routeLabel(proxyAddress)}): $summary; $report")
            }
        }.start()
    }

    /**
     * Единый маршрут для всего, что связано с видеосервисом.
     *
     * Важно: ссылка на поток выдаётся под конкретный адрес выхода. Если извлечение идёт
     * через туннель, а файл запрашивается напрямую (или наоборот), сервис отвечает отказом —
     * обычно пустым «403». Поэтому маршрут берётся из одного места для всех запросов.
     */
    private fun currentProxy(): String? =
        if (tunnelCheck.isChecked) CoreHolder.status().listenAddr else null

    /** Подпись текущего маршрута для отчёта. */
    private fun routeLabel(proxyAddress: String?): String =
        if (proxyAddress == null) "напрямую" else "через туннель ($proxyAddress)"

    /**
     * Проверка адреса выхода: два запроса — если адреса разные, ссылка на поток обречена.
     *
     * Ссылка выдаётся под тот адрес, с которого пошёл запрос извлечения. Если туннель
     * на каждом соединении выходит с разного адреса, видео не отдадут никогда — и это
     * видно сразу по двум числам.
     */
    private fun exitAddressReport(proxyAddress: String?): String {
        fun once(): String? = try {
            val builder = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(10))
            if (proxyAddress != null) {
                val parts = proxyAddress.split(":")
                if (parts.size == 2) {
                    builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())))
                }
            }
            val request = okhttp3.Request.Builder().url("https://api.ipify.org").build()
            builder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.trim()
            }
        } catch (_: Exception) {
            null
        }

        val first = once()
        val second = once()
        return when {
            first == null || second == null -> "адрес выхода определить не удалось"
            first == second -> "адрес выхода $first (стабилен)"
            else -> "ВНИМАНИЕ: адрес выхода меняется ($first и $second) — ссылка выдаётся под один, а запрашивается с другого"
        }
    }

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

        /** Поток, пойманный у встроенного браузера. */
        const val EXTRA_STREAM_VIDEO = "obereg.stream.video"
        const val EXTRA_STREAM_AUDIO = "obereg.stream.audio"
    }
}
