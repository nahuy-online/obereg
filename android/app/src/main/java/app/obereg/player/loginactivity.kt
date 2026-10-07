package app.obereg.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Вход в аккаунт.
 *
 * Открывает страницу входа сервиса в своём окне; после входа cookie остаются в приложении
 * и подхватываются лентами, подписками и плеером. Прогресс проверяем сами: как только
 * cookie входа появились — «Вход выполнен» и окно само закрывается.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val loginChecker = object : Runnable {
        override fun run() {
            if (finished) return
            if (PlayerApi.hasLoginCookies(youtubeCookies())) {
                finished = true
                DiagLog.add("вход: выполнен")
                statusView.text = "Вход выполнен — можно возвращаться"
                handler.postDelayed({ finish() }, 1_200)
                return
            }
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        webView = findViewById(R.id.loginView)
        statusView = findViewById(R.id.loginStatus)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        // Вход Google использует несколько домены — третьи cookie обязательны.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        applyRoute()
        statusView.text = "Войдите в аккаунт — приложение запомнит вход"
        DiagLog.add("вход: окно открыто")
        webView.loadUrl("https://m.youtube.com/signin?hl=ru")
        findViewById<TextView>(R.id.btnLoginDone).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnLoginBack).setOnClickListener { finish() }
        handler.postDelayed(loginChecker, 3_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loginChecker)
        webView.destroy()
        super.onDestroy()
    }

    private fun youtubeCookies(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://www.youtube.com/")
    }.getOrNull()

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
}
