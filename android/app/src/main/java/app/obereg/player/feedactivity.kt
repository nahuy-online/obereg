package app.obereg.player

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Лента: рекомендации и подписки, собранные нашим экраном.
 *
 * Данные берутся со страницы раздела — там их отдаёт сам сервис, и тот же движок браузера
 * проходит проверки. Разбор делает ядро, и в готовый список вставляется наша рекомендация
 * на позицию из настроек. Тап открывает ролик на экране сервиса; когда появится свой плеер
 * (после этапа аттестации), тот же тап пойдёт в него.
 */
class FeedActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var items: List<FeedItem> = emptyList()
    private var mode: String = MODE_RECOMMENDED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed)

        listView = findViewById(R.id.feedList)
        statusView = findViewById(R.id.feedStatus)

        findViewById<Button>(R.id.btnFeedRecommended).setOnClickListener { load(MODE_RECOMMENDED) }
        findViewById<Button>(R.id.btnFeedSubscriptions).setOnClickListener { load(MODE_SUBSCRIPTIONS) }
        findViewById<Button>(R.id.btnFeedBack).setOnClickListener { finish() }
        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let(::openItem)
        }

        load(MODE_RECOMMENDED)
    }

    private fun load(newMode: String) {
        mode = newMode
        val url = if (mode == MODE_SUBSCRIPTIONS) {
            "https://m.youtube.com/feed/subscriptions"
        } else {
            "https://m.youtube.com/"
        }
        statusView.text = "загружаю: $url"
        Thread {
            var html = fetch(url)
            var data = html?.let(PlayerApi::extractInitialData)
            if (data == null) {
                // Второй заход: обычная (не мобильная) страница — иногда отдаёт данные,
                // когда мобильная вернула страницу согласия или пустую оболочку.
                val altUrl = if (mode == MODE_SUBSCRIPTIONS) {
                    "https://www.youtube.com/feed/subscriptions"
                } else {
                    "https://www.youtube.com/?hl=ru"
                }
                DiagLog.add("лента: второй заход $altUrl")
                val alt = fetch(altUrl)
                val altData = alt?.let(PlayerApi::extractInitialData)
                if (altData != null) {
                    html = alt
                    data = altData
                }
            }
            runOnUiThread {
                if (html == null) {
                    statusView.text = "не удалось получить страницу — проверьте маршрут (Профиль → туннель)"
                    return@runOnUiThread
                }
                if (data == null) {
                    val digest = PlayerApi.pageDigest(html)
                    DiagLog.add("лента: нет данных — $digest")
                    statusView.text = "нет данных ленты — $digest"
                    return@runOnUiThread
                }
                val coreJson = CoreHolder.analyzeFeed(
                    data,
                    AppSettings.promoVideoId(this),
                    AppSettings.promoTitle(this),
                    AppSettings.promoPosition(this),
                )
                items = FeedCodec.mapItems(coreJson)
                listView.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    items.map(FeedCodec::label),
                )
                DiagLog.add("лента ($mode): роликов ${items.size}")
                statusView.text = if (items.isEmpty()) {
                    "лента пуста — вероятно, нужен вход: откройте экран сервиса и войдите"
                } else {
                    "роликов: ${items.size}" +
                        if (items.any { it.injected }) ", наша рекомендация на месте" else ""
                }
            }
        }.start()
    }

    /** Запрос страницы: тем же маршрутом, что и экран сервиса, с теми же cookie. */
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
            val cookies = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com/")
            }.getOrNull()
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
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
        val intent = Intent(this, YouTubeActivity::class.java)
        intent.putExtra(YouTubeActivity.EXTRA_VIDEO_ID, item.videoId)
        startActivity(intent)
    }

    companion object {
        const val MODE_RECOMMENDED = "recommended"
        const val MODE_SUBSCRIPTIONS = "subscriptions"
    }
}
