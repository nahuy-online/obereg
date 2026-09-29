package app.obereg.player

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Лента: рекомендации и подписки.
 *
 * Данные берём **из браузерного движка** (невидимый WebView): там согласие и вход уже
 * пройдены — именно поэтому экран сервиса показывает те же ленты. Если движок данные
 * не отдал (страница ещё грузится, разметка другая), включается резервный путь —
 * отдельный запрос страницы с собранными cookie.
 *
 * Разбор делает ядро, и в готовый список вставляется наша рекомендация из настроек.
 */
class FeedActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private var items: List<FeedItem> = emptyList()
    private var mode: String = MODE_RECOMMENDED
    private var pageHandled = false

    /** Одна повторная попытка спросить данные у движка: страница иногда достраивается позже. */
    private var engineRetried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed)

        listView = findViewById(R.id.feedList)
        statusView = findViewById(R.id.feedStatus)
        webView = findViewById(R.id.feedWebView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tryEngineData()
            }
        }

        findViewById<Button>(R.id.btnFeedRecommended).setOnClickListener { load(MODE_RECOMMENDED) }
        findViewById<Button>(R.id.btnFeedSubscriptions).setOnClickListener { load(MODE_SUBSCRIPTIONS) }
        findViewById<Button>(R.id.btnFeedBack).setOnClickListener { finish() }
        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let(::openItem)
        }

        load(MODE_RECOMMENDED)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun load(newMode: String) {
        mode = newMode
        val url = feedUrl(mode, mobile = true)
        pageHandled = false
        engineRetried = false
        statusView.text = "загружаю в браузерном движке: $url"
        applyRoute()
        webView.stopLoading()
        webView.loadUrl(url)
        // Страховка: если движок данные не отдал — пробуем отдельным запросом.
        webView.postDelayed({
            if (!pageHandled) {
                DiagLog.add("лента: движок молчит, пробую отдельным запросом")
                fallbackFetch()
            }
        }, 15_000)
    }

    private fun feedUrl(targetMode: String, mobile: Boolean): String = when {
        targetMode == MODE_SUBSCRIPTIONS && mobile -> "https://m.youtube.com/feed/subscriptions"
        targetMode == MODE_SUBSCRIPTIONS -> "https://www.youtube.com/feed/subscriptions"
        mobile -> "https://m.youtube.com/"
        else -> "https://www.youtube.com/?hl=ru"
    }

    /** Спросить у движка данные страницы. */
    private fun tryEngineData() {
        if (pageHandled) return
        val js = "(function(){try{return JSON.stringify(window.ytInitialData||null);}" +
            "catch(e){return 'null';}})()"
        webView.evaluateJavascript(js) { raw ->
            val json = PlayerApi.decodeJsString(raw)
            if (json == null || json == "null" || json.length < 1000) {
                DiagLog.add("лента: движок не отдал данные (${json?.length ?: 0} символов)")
                if (!engineRetried) {
                    engineRetried = true
                    webView.postDelayed({ tryEngineData() }, 2_500)
                }
                return@evaluateJavascript
            }
            pageHandled = true
            DiagLog.add("лента: данные от движка (${json.length} символов)")
            handleFeedJson(json)
        }
    }

    /** Резервный путь: отдельный запрос страницы с собранными cookie и согласием. */
    private fun fallbackFetch() {
        Thread {
            var html = fetch(feedUrl(mode, mobile = true))
            var data = html?.let(PlayerApi::extractInitialData)
            if (data == null) {
                val alt = fetch(feedUrl(mode, mobile = false))
                val altData = alt?.let(PlayerApi::extractInitialData)
                if (altData != null) {
                    html = alt
                    data = altData
                }
            }
            runOnUiThread {
                if (pageHandled) return@runOnUiThread
                if (data == null) {
                    val digest = html?.let(PlayerApi::pageDigest) ?: "страница не получена"
                    DiagLog.add("лента: нет данных — $digest")
                    statusView.text = "нет данных ленты — $digest"
                    return@runOnUiThread
                }
                handleFeedJson(data)
            }
        }.start()
    }

    /** Общая часть: разбор ядром и показ списка. */
    private fun handleFeedJson(json: String) {
        val coreJson = CoreHolder.analyzeFeed(
            json,
            AppSettings.promoVideoId(this),
            AppSettings.promoTitle(this),
            AppSettings.promoPosition(this),
        )
        items = FeedCodec.mapItems(coreJson)
        listView.adapter = FeedAdapter(items)
        DiagLog.add("лента ($mode): роликов ${items.size}")
        statusView.text = if (items.isEmpty()) {
            FeedCodec.emptyMessage(mode)
        } else {
            "роликов: ${items.size}" +
                if (items.any { it.injected }) ", наша рекомендация на месте" else ""
        }
    }

    /** Маршрут движка: наш туннель, если так выбрано в настройках. */
    private fun applyRoute() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val executor = Executor { command -> command.run() }
        val controller = ProxyController.getInstance()
        val address = if (AppSettings.screenViaTunnel(this)) CoreHolder.httpProxyAddr() else null
        runCatching {
            if (address != null) {
                val config = ProxyConfig.Builder().addProxyRule(address).build()
                controller.setProxyOverride(config, executor) { }
            } else {
                controller.clearProxyOverride(executor) { }
            }
        }
    }

    /** Запрос страницы (резервный путь). */
    private fun fetch(url: String): String? {
        return try {
            val builder = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .readTimeout(java.time.Duration.ofSeconds(25))
            if (AppSettings.screenViaTunnel(this)) {
                val address = CoreHolder.status().listenAddr
                val parts = address?.split(":")
                if (parts != null && parts.size == 2) {
                    builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())))
                }
            }
            val siteCookies = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com/")
            }.getOrNull()
            val sharedCookies = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://www.google.com/")
            }.getOrNull()
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Cookie", PlayerApi.buildCookieHeader(siteCookies, sharedCookies))
                .build()
            builder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DiagLog.add("лента: HTTP ${response.code} на $url")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (error: Exception) {
            DiagLog.add("лента: ошибка сети: ${error.message?.take(120)}")
            null
        }
    }

    private fun openItem(item: FeedItem) {
        DiagLog.add("лента: открываю ${item.videoId}")
        val intent = android.content.Intent(this, YouTubeActivity::class.java)
        intent.putExtra(YouTubeActivity.EXTRA_VIDEO_ID, item.videoId)
        startActivity(intent)
    }

    companion object {
        const val MODE_RECOMMENDED = "recommended"
        const val MODE_SUBSCRIPTIONS = "subscriptions"
    }
}
