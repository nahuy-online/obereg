package app.obereg.player

import android.app.AlertDialog
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
 * видео открывается всегда, где доступен вход. Область ролика закреплена сверху —
 * чужая шапка не просто спрятана, а схлопнута в ноль, чтобы не оставалось пустого
 * места; ниже листается лента рекомендаций. Свайп вниз по области ролика (плюс
 * «‹ Свернуть» и системная «Назад») уводят окно в системное «картинка-в-картинке» —
 * видео продолжает играть поверх других окон.
 *
 * Настройки плеера — «⚙ Настройки»: скорость 1x-3x списком, переключение на
 * «Прямой поток» и перезагрузка. Выбранная скорость запоминается между запусками.
 */
class EnginePlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private var speed = 1.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_player)
        val videoId = intent.getStringExtra("videoId") ?: ""
        speed = AppSettings.playerSpeed(this).toDouble()
        webView = findViewById(R.id.engineView)
        statusView = findViewById(R.id.engineStatus)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectTuning(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString().orEmpty()
                if (target.startsWith("obereg://")) {
                    handleInnerCommand(target)
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url.orEmpty()
                if (target.startsWith("obereg://")) {
                    handleInnerCommand(target)
                    return true
                }
                return false
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
                    render("движок-плеер: сеть капризничает — «Настройки» → «Перезагрузить страницу»")
                }
            }
        }
        if (videoId.isEmpty()) {
            render("движок-плеер: не передан идентификатор ролика")
        } else {
            DiagLog.add("движок-плеер: открыт для $videoId (скорость ${speedLabel(speed)})")
            webView.loadUrl("https://www.youtube.com/watch?v=$videoId&hl=ru")
        }
        findViewById<Button>(R.id.btnEngineBack).setOnClickListener { minimizeToPip() }
        findViewById<Button>(R.id.btnEngineSettings).setOnClickListener { showSettings(videoId) }
    }

    /** Команды со страницы: свайп вниз по ролику и т.п. */
    private fun handleInnerCommand(target: String) {
        if (target.startsWith("obereg://minimize")) {
            DiagLog.add("движок-плеер: свайп вниз — сворачиваю в окно")
            minimizeToPip()
        }
    }

    /** Настройки плеера: скорость списком, прямой поток, перезагрузка. */
    private fun showSettings(videoId: String) {
        val rates = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
        val labels = rates.map { "Скорость: " + speedLabel(it) }.toMutableList()
        labels += "Прямой поток"
        labels += "Перезагрузить страницу"
        AlertDialog.Builder(this)
            .setTitle("Настройки плеера")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < rates.size -> applySpeed(rates[which])
                    which == rates.size -> {
                        if (videoId.isEmpty()) {
                            render("движок-плеер: нет ролика для прямого потока")
                        } else {
                            DiagLog.add("движок-плеер: переключаю на прямой поток")
                            startActivity(
                                android.content.Intent(this, PlayerActivity::class.java)
                                    .putExtra("videoId", videoId),
                            )
                        }
                    }
                    else -> {
                        webView.reload()
                        render("движок-плеер: перезагрузка")
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun applySpeed(rate: Double) {
        speed = rate
        AppSettings.setPlayerSpeed(this, rate.toFloat())
        webView.evaluateJavascript("window.__oberegRate = $rate; 'ok'") { _ -> }
        DiagLog.add("движок-плеер: скорость ${speedLabel(rate)}")
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

    /** Тюнинг страницы: схлопнуть чужую шапку, закрепить ролик сверху, свайп, реклама, скорость. */
    private fun injectTuning(view: WebView?) {
        view ?: return
        val script = TUNING.replace("__RATE__", speed.toString())
        view.evaluateJavascript(script) { raw ->
            val answer = PlayerApi.decodeJsString(raw)
            if (answer == "готово") {
                DiagLog.add("движок-плеер: тюнинг установлен")
                render("")
            }
        }
    }

    private fun speedLabel(rate: Double): String = when (rate) {
        1.0 -> "1×"
        1.25 -> "1,25×"
        1.5 -> "1,5×"
        2.0 -> "2×"
        2.5 -> "2,5×"
        3.0 -> "3×"
        else -> "$rate×"
    }

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
                  styl.textContent =
                    'ytm-header-bar, ytm-masthead, ytm-mobile-topbar-renderer, ytm-app-header, ytm-consent-bump-renderer, #masthead-container, #header { display:none !important; height:0 !important; min-height:0 !important; }' +
                    'ytm-app, :root { --ytm-header-height: 0px !important; }' +
                    'html, body { padding-top:0 !important; margin-top:0 !important; background:#0b0d11 !important; }' +
                    'ytm-player, #player-container, .player-container, #movie_player { position: -webkit-sticky !important; position: sticky !important; top: 0 !important; z-index: 990 !important; background:#000 !important; }';
                  (document.head || document.documentElement).appendChild(styl);
                }
                function hideChrome() {
                  ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container'].forEach(function(sel){
                    try {
                      var el = document.querySelector(sel);
                      if (el) { el.style.display = 'none'; el.style.height = '0px'; el.style.minHeight = '0px'; }
                    } catch (e) { }
                  });
                }
                function attachSwipe() {
                  var el = document.querySelector('ytm-player, #player-container, #movie_player');
                  if (!el || el.__oberegSwipe) { return; }
                  el.__oberegSwipe = true;
                  var startY = null;
                  el.addEventListener('touchstart', function(e){ startY = e.touches[0].clientY; }, { passive: true });
                  el.addEventListener('touchend', function(e){
                    if (startY === null) { return; }
                    var dy = e.changedTouches[0].clientY - startY;
                    startY = null;
                    if (dy > 90) { try { location.href = 'obereg://minimize'; } catch (err) { } }
                  }, { passive: true });
                }
                ensureStyle();
                hideChrome();
                attachSwipe();
                setInterval(function(){
                  try {
                    ensureStyle();
                    hideChrome();
                    attachSwipe();
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
