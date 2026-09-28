package app.obereg.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.URLEncoder
import java.util.concurrent.Executor

/**
 * YouTube внутри приложения.
 *
 * Почему два маршрута: встроенный плеер (`/embed/<id>`) на живом устройстве отвечает
 * ошибкой 153 — сервис отказывается воспроизводить видео в WebView, и это не связано
 * с нашим туннелем (ошибка воспроизводится и в режиме напрямую). Поэтому основной режим —
 * обычный мобильный сайт (`m.youtube.com`), а встраивание осталось как отдельная попытка.
 *
 * Дополнительно:
 * * маршрут прокси выставляется явно: наш HTTP-прокси либо снятие переопределения;
 * * User-Agent обычного браузера вместо WebView-овского;
 * * поиск и переход внутри сайта — чтобы приложение работало как клиент, а не как одно видео;
 * * ошибки загрузки и коды ответов видны на экране, а не молча дают чёрный экран.
 */
class YouTubeActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusView: TextView
    private lateinit var tunnelCheck: CheckBox

    /** Поток, пойманный у встроенного браузера (уже с подтверждением сеанса). */
    private var capturedVideo: String? = null
    private var capturedAudio: String? = null

    /** Сколько раз браузер обращался к медиасерверу — видно, работает ли перехват. */
    private var mediaRequests: Int = 0

    /** Адрес текущей загрузки и признак того, что авто-попытка другим маршрутом уже была. */
    private var currentNavigationUrl: String = ""
    private var retryWithOtherRoute: Boolean = false

    /** Какой маршрут реально применялся последним (true — наш туннель). */
    private var lastRouteTunnel: Boolean = false
    private var routeNote: String = ""
    private var currentVideoId: String = ""
    private var useEmbed: Boolean = false

    private val presets = listOf(
        "aqz-KE-bpKQ" to "Big Buck Bunny (Blender)",
        "jNQXAC9IVRw" to "Me at the zoo",
    )

    /** Обычный браузерный User-Agent: WebView-овский содержит пометку `wv` и хуже принимается сервисом. */
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        webView = findViewById(R.id.youtubeView)
        urlInput = findViewById(R.id.youtubeInput)
        searchInput = findViewById(R.id.searchInput)
        statusView = findViewById(R.id.youtubeStatus)
        tunnelCheck = findViewById(R.id.checkYtTunnel)
        tunnelCheck.setOnCheckedChangeListener { _, _ ->
            routeNote = applyRoute()
            render("маршрут экрана обновлён")
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = browserUserAgent
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            /**
             * Перехват потока, который играет сам браузер.
             *
             * Проверку подлинности клиента проходит движок браузера, и его ссылка —
             * с уже выданным подтверждением. Мы её запоминаем (ничего не подменяем,
             * запрос идёт как обычно), чтобы потом отдать нашему плееру: там есть фон
             * и нет рекламы.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (PlayerApi.looksLikeStream(url)) {
                    mediaRequests++
                }
                when (PlayerApi.streamKind(url)) {
                    "video" -> {
                        capturedVideo = PlayerApi.streamUrlForPlayer(url)
                        render("пойман видеопоток (обращений к медиасерверу: $mediaRequests)")
                    }
                    "audio" -> {
                        capturedAudio = PlayerApi.streamUrlForPlayer(url)
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                render("загружено: ${url ?: "—"}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.description?.toString() ?: "неизвестная ошибка"
                } else {
                    "неизвестная ошибка"
                }
                // Сетевая ошибка на основном документе — пробуем другой маршрут: если напрямую
                // не открылось (канал не пускает), идём через наш туннель; и наоборот.
                // Одна авто-попытка на загрузку, чтобы не крутиться в петле.
                val isMainDocument = request?.isForMainFrame ?: false
                if (isMainDocument && !retryWithOtherRoute && currentNavigationUrl.isNotEmpty()) {
                    retryWithOtherRoute = true
                    val flipped = !lastRouteTunnel
                    routeNote = applyRouteUsing(flipped)
                    render(
                        "страница не загрузилась ($description) — пробую " +
                            if (flipped) "через наш туннель" else "напрямую",
                    )
                    webView.post { webView.loadUrl(currentNavigationUrl) }
                    return
                }
                render("ОШИБКА загрузки: $description\n${request?.url ?: ""}")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                render("ответ сервера: HTTP ${errorResponse?.statusCode ?: 0}\n${request?.url ?: "—"}")
            }
        }

        findViewById<Button>(R.id.btnPlayYoutube).setOnClickListener {
            val id = extractVideoId(urlInput.text.toString().trim())
            if (id == null) {
                render("не удалось распознать ссылку или идентификатор видео")
                return@setOnClickListener
            }
            openVideo(id)
        }
        findViewById<Button>(R.id.btnEmbedMode).setOnClickListener {
            if (currentVideoId.isEmpty()) {
                val id = extractVideoId(urlInput.text.toString().trim())
                if (id == null) {
                    render("сначала выберите видео")
                    return@setOnClickListener
                }
                currentVideoId = id
            }
            useEmbed = true
            openVideo(currentVideoId)
        }
        findViewById<Button>(R.id.btnSearchYoutube).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isEmpty()) {
                render("введите поисковый запрос")
                return@setOnClickListener
            }
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://m.youtube.com/results?search_query=$encoded"
            loadWithFreshRoute(url)
            render("поиск: $query")
        }
        findViewById<Button>(R.id.btnOpenInBrowser).setOnClickListener {
            openOutside("https://www.youtube.com/watch?v=$currentVideoId")
        }
        findViewById<Button>(R.id.btnOpenInApp).setOnClickListener {
            openOutside("vnd.youtube:$currentVideoId")
        }
        findViewById<Button>(R.id.btnCheckAccess).setOnClickListener {
            loadWithFreshRoute("https://www.youtube.com/generate_204")
            render("проверяю доступ к YouTube из WebView…")
        }
        findViewById<Button>(R.id.btnPlayCaptured).setOnClickListener {
            val video = capturedVideo
            if (video == null) {
                render(
                    "потока пока нет: откройте видео на этом экране и дайте ему начать играть, " +
                        "затем нажмите эту кнопку",
                )
                return@setOnClickListener
            }
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayerActivity.EXTRA_STREAM_VIDEO, video)
            capturedAudio?.let { intent.putExtra(PlayerActivity.EXTRA_STREAM_AUDIO, it) }
            startActivity(intent)
            render("передал пойманный поток в свой плеер")
        }
        findViewById<Button>(R.id.btnBackToApp).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPresetYt1).setOnClickListener {
            urlInput.setText(presets[0].first)
            useEmbed = false
            openVideo(presets[0].first)
        }
        findViewById<Button>(R.id.btnPresetYt2).setOnClickListener {
            urlInput.setText(presets[1].first)
            useEmbed = false
            openVideo(presets[1].first)
        }

        val incoming = intent?.getStringExtra(EXTRA_VIDEO_ID)
        val startId = if (!incoming.isNullOrEmpty()) incoming else presets[0].first
        urlInput.setText(startId)
        routeNote = applyRoute()
        openVideo(startId)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        routeNote = applyRoute()
        render("маршрут обновлён")
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Явно выставить маршрут WebView.
     *
     * Если туннель поднят — ставим наш HTTP CONNECT-прокси, иначе **снимаем** переопределение:
     * без этого WebView остался бы с адресом мёртвого прокси и все запросы падали бы
     * с `ERR_PROXY_CONNECTION_FAILED`.
     */
    private fun applyRoute(): String = applyRouteUsing(tunnelCheck.isChecked)

    /** Применить маршрут по явному выбору: наш туннель или напрямую. */
    private fun applyRouteUsing(wantTunnel: Boolean): String {
        lastRouteTunnel = wantTunnel
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "маршрут: напрямую (прокси туннеля требует Android 9+)"
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return "маршрут: напрямую (WebView не поддерживает настройку прокси)"
        }
        val executor = Executor { command -> command.run() }
        val controller = ProxyController.getInstance()
        // Маршрут для этого экрана — выбор пользователя: без галочки браузер идёт напрямую
        // (через системный VPN, если он поднят). Наш туннель для браузера труднее: медиа-потоки
        // живут долго, и именно на них возникают обрывы, если включить его принудительно.
        val address = if (wantTunnel) CoreHolder.httpProxyAddr() else null
        return try {
            if (address != null) {
                val config = ProxyConfig.Builder().addProxyRule(address).build()
                controller.setProxyOverride(config, executor) { /* готово */ }
                "маршрут: через наш туннель ($address)"
            } else {
                controller.clearProxyOverride(executor) { /* готово */ }
                if (wantTunnel) {
                    "маршрут: напрямую (туннель не поднят — на главном экране есть кнопка запуска)"
                } else {
                    "маршрут: напрямую (системный VPN, если включён)"
                }
            }
        } catch (error: Throwable) {
            "не удалось выставить маршрут: ${error.message} — идём напрямую"
        }
    }

    private fun openVideo(videoId: String) {
        currentVideoId = videoId
        val url = if (useEmbed) {
            "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
        } else {
            "https://m.youtube.com/watch?v=$videoId"
        }
        loadWithFreshRoute(url)
    }

    /** Загрузить адрес с обновлённым маршрутом и одной авто-попыткой другого маршрута при ошибке. */
    private fun loadWithFreshRoute(url: String) {
        currentNavigationUrl = url
        retryWithOtherRoute = false
        routeNote = applyRoute()
        render("открываю: $url")
        webView.loadUrl(url)
    }

    /** Запасной путь: отдать ссылку системе — браузеру или приложению YouTube. */
    private fun openOutside(uri: String) {
        if (currentVideoId.isEmpty()) {
            render("сначала выберите видео")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            render("передал ссылку системе: $uri")
        } catch (error: Throwable) {
            render("не удалось открыть снаружи: ${error.message}")
        }
    }

    private fun render(message: String) {
        val status = runCatching { CoreHolder.status() }.getOrNull()
        statusView.text = buildString {
            appendLine(message)
            if (routeNote.isNotEmpty()) appendLine(routeNote)
            if (status != null) {
                appendLine("ядро: ${status.state} / ${status.mode} / соединений: ${status.sessionsServed}")
            }
            appendLine("перехват: обращений к медиасерверу $mediaRequests" +
                if (capturedVideo == null) "" else ", поток пойман")
            // Подсказка: исходы извлечения видны на экране плеера, а не здесь — это разные экраны.
            append("свой плеер: экран «Открыть плеер» → кнопка «YouTube: свой плеер»")
        }
    }

    /** Достать идентификатор видео из ссылки YouTube или из «голого» идентификатора. */
    private fun extractVideoId(raw: String): String? {
        if (raw.isEmpty()) return null
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""^([A-Za-z0-9_-]{11})$"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(raw)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    companion object {
        const val EXTRA_VIDEO_ID = "obereg.youtube.videoId"
    }
}
