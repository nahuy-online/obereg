package app.obereg.player

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Плеер на движке страницы.
 *
 * Играет ролик через доверенный сеанс браузера (тот же, что и «Экран YouTube»):
 * сервис не закрывает этот канал, поэтому видео открывается всегда, где доступен
 * вход в сервис. Сверху работает тюнинг: реклама проматывается сама, скорость до 3×.
 */
class EnginePlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private var speedIndex = 0
    private var reloadTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_player)
        val videoId = intent.getStringExtra("videoId") ?: ""
        webView = findViewById(R.id.engineView)
        statusView = findViewById(R.id.engineStatus)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                reloadTried = false
                injectTuning(view)
                render("движок-плеер: страница готова")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                val main = request?.isForMainFrame == true
                val description = error?.description?.toString() ?: "ошибка сети"
                DiagLog.add(
                    "движок-плеер: ошибка загрузки ($description)" + if (main) " [главный документ]" else "",
                )
                if (main && !reloadTried) {
                    reloadTried = true
                    render("движок-плеер: сеть капризничает - пробую ещё раз")
                    view?.reload()
                }
            }
        }
        if (videoId.isEmpty()) {
            render("движок-плеер: не передан идентификатор ролика")
        } else {
            DiagLog.add("движок-плеер: открыт для $videoId")
            render("движок-плеер: загружаю ролик $videoId")
            webView.loadUrl("https://www.youtube.com/watch?v=$videoId&hl=ru")
        }
        findViewById<Button>(R.id.btnEngineBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnEngineDirect).setOnClickListener {
            if (videoId.isEmpty()) {
                render("движок-плеер: нет ролика для прямого потока")
            } else {
                DiagLog.add("движок-плеер: переключаю на прямой поток")
                startActivity(
                    android.content.Intent(this, PlayerActivity::class.java).putExtra("videoId", videoId),
                )
            }
        }
        findViewById<Button>(R.id.btnEngineReload).setOnClickListener {
            webView.reload()
            render("движок-плеер: перезагрузка")
        }
        findViewById<Button>(R.id.btnEngineSpeed).setOnClickListener {
            speedIndex = (speedIndex + 1) % 5
            val rate = listOf(1.0, 1.5, 2.0, 2.5, 3.0)[speedIndex]
            webView.evaluateJavascript("window.__oberegRate = $rate; 'ok'") { _ -> }
            val label = speedLabel(rate)
            findViewById<Button>(R.id.btnEngineSpeed).text = "Скорость: $label"
            DiagLog.add("движок-плеер: скорость $label")
            render("движок-плеер: скорость $label")
        }
    }

    private fun render(text: String) {
        statusView.text = text
    }

    /** Тюнинг страницы: пропуск рекламы, удержание скорости, лёгкая шапка. */
    private fun injectTuning(view: WebView?) {
        view ?: return
        val script = TUNING.replace("__RATE__", "1.0")
        view.evaluateJavascript(script) { raw ->
            val answer = PlayerApi.decodeJsString(raw)
            if (answer == "готово") {
                DiagLog.add("движок-плеер: тюнинг установлен")
            }
        }
    }

    private fun speedLabel(rate: Double): String =
        if (rate == rate.toInt().toDouble()) "${rate.toInt()}×" else "$rate×"

    companion object {
        private val TUNING = """
            (function(){
              try {
                if (window.__oberegTuned) { return 'уже'; }
                window.__oberegTuned = true;
                window.__oberegRate = __RATE__;
                var styl = document.createElement('style');
                styl.textContent = 'ytm-masthead, #masthead-container, .ytp-chrome-top, ytm-header-bar { display:none !important; }';
                document.documentElement.appendChild(styl);
                setInterval(function(){
                  try {
                    var player = document.getElementById('movie_player');
                    var video = document.querySelector('video');
                    if (!video) { return; }
                    var ad = player && player.classList.contains('ad-showing');
                    if (ad) {
                      var skip = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
                      if (skip) { skip.click(); }
                      else if (video.duration && isFinite(video.duration)) { video.currentTime = video.duration; }
                      video.muted = true;
                    } else {
                      if (video.muted) { video.muted = false; }
                      if (Math.abs(video.playbackRate - window.__oberegRate) > 0.01) {
                        video.playbackRate = window.__oberegRate;
                      }
                    }
                  } catch (e) { }
                }, 400);
                return 'готово';
              } catch (e) { return 'сбой'; }
            })()
        """.trimIndent()
    }
}
