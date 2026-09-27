package app.obereg.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Просмотр видео YouTube внутри приложения.
 *
 * Используется официальный встроенный плеер (`/embed/<id>`).
 *
 * Важные детали, которые выяснились на живом устройстве:
 * * прокси WebView — состояние **процессное**: если один раз включить наш прокси и затем
 *   остановить туннель, WebView продолжит ходить на мёртвый адрес и выдаст
 *   `net::ERR_PROXY_CONNECTION_FAILED`. Поэтому маршрут всегда выставляется явно:
 *   либо наш прокси, либо снятие переопределения;
 * * стандартный User-Agent WebView содержит пометку `wv`, из-за чего встроенный плеер
 *   отказывается воспроизводить видео (ошибка 153). Подставляем обычный браузерный UA;
 * * если воспроизведение всё же недоступно, у пользователя есть честные запасные пути:
 *   открыть в системном браузере или в приложении YouTube.
 */
class YouTubeActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusView: TextView
    private var routeNote: String = ""
    private var currentVideoId: String = ""

    private val presets = listOf(
        "aqz-KE-bpKQ" to "Big Buck Bunny (Blender)",
        "jNQXAC9IVRw" to "Me at the zoo",
        "dQw4w9WgXcQ" to "Rick Astley — Never Gonna Give You Up",
    )

    /** Обычный браузерный User-Agent: плеер YouTube не любит пометку `wv` из WebView. */
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)

        webView = findViewById(R.id.youtubeView)
        urlInput = findViewById(R.id.youtubeInput)
        statusView = findViewById(R.id.youtubeStatus)

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
            override fun onPageFinished(view: WebView?, url: String?) {
                render("страница загружена: ${url ?: "—"}")
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
                render("ОШИБКА загрузки: $description\n${request?.url ?: ""}")
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
        findViewById<Button>(R.id.btnBackToApp).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnOpenInBrowser).setOnClickListener {
            openOutside("https://www.youtube.com/watch?v=$currentVideoId")
        }
        findViewById<Button>(R.id.btnOpenInApp).setOnClickListener {
            openOutside("vnd.youtube:$currentVideoId")
        }

        findViewById<Button>(R.id.btnPresetYt1).setOnClickListener {
            urlInput.setText(presets[0].first)
            openVideo(presets[0].first)
        }
        findViewById<Button>(R.id.btnPresetYt2).setOnClickListener {
            urlInput.setText(presets[1].first)
            openVideo(presets[1].first)
        }
        findViewById<Button>(R.id.btnPresetYt3).setOnClickListener {
            urlInput.setText(presets[2].first)
            openVideo(presets[2].first)
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
        // Туннель мог быть остановлен, пока экран был в фоне: маршрут выставляем заново.
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
     * Если туннель поднят — ставим наш HTTP CONNECT-прокси. Иначе **снимаем**
     * переопределение: иначе WebView остался бы с адресом мёртвого прокси
     * и все запросы падали бы с `ERR_PROXY_CONNECTION_FAILED`.
     */
    private fun applyRoute(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "маршрут: напрямую (прокси туннеля требует Android 9+)"
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return "маршрут: напрямую (WebView не поддерживает настройку прокси)"
        }
        val executor = Executor { command -> command.run() }
        val controller = ProxyController.getInstance()
        val address = CoreHolder.httpProxyAddr()
        return try {
            if (address != null) {
                val config = ProxyConfig.Builder().addProxyRule(address).build()
                controller.setProxyOverride(config, executor) { /* готово */ }
                "маршрут: через туннель ($address)"
            } else {
                controller.clearProxyOverride(executor) { /* готово */ }
                "маршрут: напрямую (прокси туннеля снят)"
            }
        } catch (error: Throwable) {
            "не удалось выставить маршрут: ${error.message} — идём напрямую"
        }
    }

    private fun openVideo(videoId: String) {
        currentVideoId = videoId
        render("открываю видео $videoId")
        webView.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0")
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
                append("ядро: ${status.state} / ${status.mode} / обслужил соединений: ${status.sessionsServed}")
            }
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
