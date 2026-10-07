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
    private var reloadTried = false

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
        // Мост для страницы: свайп-сворачивание и диагностика вёрстки без переходов по адресам
        // (навигация по своему адресу obereg:// доезжает не на всех движках, мост — надёжен).
        webView.addJavascriptInterface(HostBridge(), "__oberegHost")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                reloadTried = false
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
                    if (!reloadTried) {
                        reloadTried = true
                        render("движок-плеер: сеть капризничает — пробую ещё раз")
                        view?.reload()
                    } else {
                        render("движок-плеер: сеть капризничает — «Настройки» → «Перезагрузить страницу»")
                    }
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
        if (target.startsWith("obereg://layout")) {
            val payload = android.net.Uri.decode(target.substringAfter("?", ""))
            if (payload.isNotBlank()) {
                DiagLog.add("движок-плеер: вёрстка — $payload")
            }
        }
    }

    /** Мост странице: жесты и диагностика без переходов по адресам. */
    inner class HostBridge {
        @android.webkit.JavascriptInterface
        fun minimize() {
            runOnUiThread { minimizeToPip() }
        }

        @android.webkit.JavascriptInterface
        fun layout(report: String) {
            DiagLog.add("движок-плеер: вёрстка — " + report.take(280))
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
                function host() { return window.__oberegHost || null; }
                function ensureStyle() {
                  if (document.getElementById('oberegStyle')) { return; }
                  var styl = document.createElement('style');
                  styl.id = 'oberegStyle';
                  styl.textContent =
                    'ytm-header-bar, ytm-masthead, ytm-mobile-topbar-renderer, ytm-app-header, ytm-consent-bump-renderer, #masthead-container { display:none !important; }' +
                    'ytm-player { position: -webkit-sticky !important; position: sticky !important; top: 0 !important; z-index: 50 !important; background:#000 !important; }';
                  (document.head || document.documentElement).appendChild(styl);
                }
                function hideChrome() {
                  ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container'].forEach(function(sel){
                    try { var el = document.querySelector(sel); if (el) { el.style.display = 'none'; } } catch (e) { }
                  });
                }
                function attachSwipe() {
                  var el = document.querySelector('ytm-player') || document.querySelector('#player-container') || document.querySelector('#movie_player');
                  if (!el || el.__oberegSwipe) { return; }
                  el.__oberegSwipe = true;
                  var startY = null;
                  el.addEventListener('touchstart', function(e){ startY = e.touches[0].clientY; }, { passive: true });
                  el.addEventListener('touchend', function(e){
                    if (startY === null) { return; }
                    var dy = e.changedTouches[0].clientY - startY;
                    startY = null;
                    if (dy > 90) {
                      var h = host();
                      if (h && h.minimize) { try { h.minimize(); return; } catch (err) { } }
                      try { location.href = 'obereg://minimize'; } catch (err) { }
                    }
                  }, { passive: true });
                }
                function reportLayout(tag) {
                  try {
                    var h = host();
                    if (!h || !h.layout) { return; }
                    var parts = [];
                    parts.push('scroll:' + Math.round(window.scrollY || 0));
                    var kids = document.body ? document.body.children : [];
                    for (var i = 0; i < kids.length && i < 10; i++) {
                      var k = kids[i];
                      var r = k.getBoundingClientRect ? k.getBoundingClientRect() : { height: 0, top: 0 };
                      parts.push((k.tagName || '?').toLowerCase() + ':' + Math.round(r.height) + '@' + Math.round(r.top));
                    }
                    var player = document.querySelector('ytm-player') || document.querySelector('#player-container') || document.querySelector('#movie_player');
                    if (player) {
                      var pr = player.getBoundingClientRect();
                      var cs = window.getComputedStyle ? getComputedStyle(player) : null;
                      parts.push('игрок:' + Math.round(pr.height) + '@' + Math.round(pr.top) + (cs ? (':' + cs.position) : ''));
                    }
                    var video = document.querySelector('video');
                    parts.push('видео:' + (video ? Math.round(video.getBoundingClientRect().height) : 0));
                    h.layout(tag + ' ' + parts.join(' '));
                  } catch (e) { }
                }
                ensureStyle();
                hideChrome();
                attachSwipe();
                setTimeout(function(){ reportLayout('в4с'); }, 4000);
                setTimeout(function(){ reportLayout('в10с'); }, 10000);
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
                      if (video.muted) {
                        video.muted = false;
                        try { if (player && player.unMute) { player.unMute(); } } catch (e) { }
                        try { var un = document.querySelector('.ytp-unmute'); if (un) { un.click(); } } catch (e) { }
                      }
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
