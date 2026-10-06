package app.obereg.player

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Плеер на движке страницы.
 *
 * Играет ролик через доверенный сеанс браузера (тот же, что и экран сервиса):
 * сервис не закрывает этот канал, поэтому видео открывается всегда, где доступен вход.
 * Чужая шапка и логотип прячутся, наши кнопки живут внизу под роликом: скорость до 3×,
 * переключение на «Прямой поток», перезагрузка. «Свернуть» (и системная «Назад») уводит
 * окно в системное «картинка-в-картинке» — видео продолжает играть поверх других окон.
 */
class EnginePlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var speedButton: Button
    private var speedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_player)
        val videoId = intent.getStringExtra("videoId") ?: ""
        webView = findViewById(R.id.engineView)
        statusView = findViewById(R.id.engineStatus)
        speedButton = findViewById(R.id.btnEngineSpeed)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectTuning(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val main = request?.isForMainFrame == true
                val description = error?.description?.toString() ?: "ошибка сети"
                DiagLog.add(
                    "движок-плеер: ошибка загрузки ($description)" + if (main) " [главный документ]" else "",
                )
                if (main) {
                    render("движок-плеер: сеть капризничает — нажмите «Заново»")
                }
            }
        }
        if (videoId.isEmpty()) {
            render("движок-плеер: не передан идентификатор ролика")
        } else {
            DiagLog.add("движок-плеер: открыт для $videoId")
            webView.loadUrl("https://www.youtube.com/watch?v=$videoId&hl=ru")
        }
        findViewById<Button>(R.id.btnEngineBack).setOnClickListener { minimizeToPip() }
        findViewById<Button>(R.id.btnEngineReload).setOnClickListener {
            webView.reload()
            render("движок-плеер: перезагрузка")
        }
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
        speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % 5
            val rate = listOf(1.0, 1.5, 2.0, 2.5, 3.0)[speedIndex]
            webView.evaluateJavascript("window.__oberegRate = $rate; 'ok'") { _ -> }
            val label = speedLabel(rate)
            speedButton.text = "Скорость: $label"
            DiagLog.add("движок-плеер: скорость $label")
        }
    }

    override fun onBackPressed() {
        minimizeToPip()
    }

    /** Свернуть в системное «картинка-в-картинке»: видео играет дальше поверх других окон. */
    private fun minimizeToPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
            if (entered) {
                DiagLog.add("движок-плеер: свернул в окно")
                return
            }
        }
        finish()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DiagLog.add(
            if (isInPictureInPictureMode) "движок-плеер: в окне" else "движок-плеер: развёрнут",
        )
    }

    private fun render(text: String) {
        statusView.text = text
    }

    /** Тюнинг страницы: спрятать чужую шапку, пропуск рекламы, удержание скорости. */
    private fun injectTuning(view: WebView?) {
        view ?: return
        val script = TUNING.replace("__RATE__", "1.0")
        view.evaluateJavascript(script) { raw ->
            val answer = PlayerApi.decodeJsString(raw)
            if (answer == "готово") {
                DiagLog.add("движок-плеер: тюнинг установлен")
                render("")
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
                function ensureStyle() {
                  if (document.getElementById('oberegStyle')) { return; }
                  var styl = document.createElement('style');
                  styl.id = 'oberegStyle';
                  styl.textContent = 'ytm-header-bar, ytm-masthead, ytm-mobile-topbar-renderer, ytm-app-header, ytm-consent-bump-renderer, #masthead-container, #header, .ytp-chrome-top, .ytp-gradient-top { display:none !important; } html, body { background:#0b0d11 !important; }';
                  (document.head || document.documentElement).appendChild(styl);
                }
                function hideChrome() {
                  ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container'].forEach(function(sel){
                    try { var el = document.querySelector(sel); if (el) { el.style.display = 'none'; } } catch (e) { }
                  });
                }
                ensureStyle();
                hideChrome();
                setInterval(function(){
                  try {
                    ensureStyle();
                    hideChrome();
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
