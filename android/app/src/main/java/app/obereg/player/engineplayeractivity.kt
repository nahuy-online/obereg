package app.obereg.player

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Плеер на движке страницы.
 *
 * Играет ролик через доверенный сеанс браузера (тот же, что и экран сервиса):
 * видео открывается всегда, где доступен вход. Область ролика закреплена сверху —
 * чужая шапка спрятана, а страница подтягивается по фактической позиции ролика,
 * чтобы не оставалось пустой полосы над плеером; ниже листается лента рекомендаций.
 * Свайп вниз по области ролика (плюс
 * «‹ Свернуть» и системная «Назад») уводят окно в системное «картинка-в-картинке»
 * (видео продолжает играть поверх других окон) и открывают оболочку «Оберег» — так
 * из плеера всегда есть выход к ленте и вкладкам.
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
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, EARLY_HIDE, setOf("*"))
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                reloadTried = false
                injectTuning(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
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
        findViewById<TextView>(R.id.btnEngineBack).setOnClickListener { minimizeToPip() }
        findViewById<TextView>(R.id.btnEngineSettings).setOnClickListener { showSettings(videoId) }
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
            DiagLog.add("движок-плеер: свайп вниз — сворачиваю в окно")
            runOnUiThread { minimizeToPip() }
        }

        @android.webkit.JavascriptInterface
        fun layout(report: String) {
            DiagLog.add("движок-плеер: вёрстка — " + report.take(460))
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

    /** Попытка входа в «картинка-в-картинке»; в меню окошка есть кнопка «Оберег» — открыть ленту. */
    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val openShell = PendingIntent.getActivity(
                this,
                0,
                android.content.Intent(this, FeedActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val openAction = RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_nav_home),
                "Оберег",
                "Открыть ленту",
                openShell,
            )
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(listOf(openAction))
                .build()
            val ok = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
            if (ok) pipActivity = java.lang.ref.WeakReference(this)
            return ok
        }
        return false
    }

    /**
     * Свернуть в окно: видео продолжает играть поверх, а под окошком остаётся оболочка
     * (она всегда лежит ниже плеера в стопке экранов). Если плеер открыт сам по себе —
     * возвращаюсь в оболочку обычным переходом.
     */
    private fun minimizeToPip() {
        if (isTaskRoot) {
            DiagLog.add("движок-плеер: открываю оболочку")
            startActivity(android.content.Intent(this, FeedActivity::class.java))
            finish()
            return
        }
        if (enterPip()) {
            DiagLog.add("движок-плеер: свернул в окно")
            return
        }
        DiagLog.add("движок-плеер: окно недоступно — открываю оболочку")
        startActivity(android.content.Intent(this, FeedActivity::class.java))
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
        if (isInPictureInPictureMode) {
            pipActivity = java.lang.ref.WeakReference(this)
        } else if (pipActivity?.get() === this) {
            pipActivity = null
        }
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

        /** Живое окошко (картинка-в-картинке) — чтобы перед новым роликом закрыть старое. */
        private var pipActivity: java.lang.ref.WeakReference<EnginePlayerActivity>? = null

        /** Закрыть живое окошко со старым роликом: вызывается перед открытием нового. */
        fun closeStalePip() {
            pipActivity?.get()?.let { old -> runCatching { old.finish() } }
            pipActivity = null
        }

        /** Ранний стиль: ставится ещё до отрисовки страницы — чужой шапки и логотипа не видно даже мельком. */
        private val EARLY_HIDE = """
            (function(){
              try {
                function put() {
                  var d = document;
                  var head = d.head || d.documentElement;
                  if (!head) { setTimeout(put, 20); return; }
                  if (d.getElementById('oberegEarly')) { return; }
                  var s = d.createElement('style');
                  s.id = 'oberegEarly';
                  s.textContent = 'ytm-header-bar, ytm-masthead, ytm-mobile-topbar-header, ytm-mobile-topbar-renderer, ytm-app-header, ytm-consent-bump-renderer, #masthead-container { display: none !important; }';
                  head.appendChild(s);
                }
                put();
              } catch (e) { }
            })()
        """.trimIndent()

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
                    'ytm-header-bar, ytm-masthead, ytm-mobile-topbar-header, ytm-mobile-topbar-renderer, ytm-app-header, ytm-consent-bump-renderer, #masthead-container { display:none !important; }' +
                    'ytm-player, #player-container { position: -webkit-sticky !important; position: sticky !important; top: 0 !important; z-index: 50 !important; background:#000 !important; }' +
                    'ytm-app { padding-top: 0 !important; }';
                  (document.head || document.documentElement).appendChild(styl);
                }
                function hideChrome() {
                  ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-header', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container'].forEach(function(sel){
                    try { var el = document.querySelector(sel); if (el) { el.style.display = 'none'; } } catch (e) { }
                  });
                }
                function attachSwipe() {
                  if (!window.__oberegSwipeDoc) {
                    window.__oberegSwipeDoc = true;
                    var startY = null;
                    var startX = null;
                    var overPlayer = false;
                    document.addEventListener('touchstart', function(e){
                      try {
                        var t = e.touches[0];
                        startY = t.clientY;
                        startX = t.clientX;
                        var el = document.querySelector('ytm-player') || document.querySelector('#player-container') || document.querySelector('#movie_player');
                        var r = el ? el.getBoundingClientRect() : null;
                        overPlayer = !!(r && t.clientY >= r.top && t.clientY <= r.bottom && t.clientX >= r.left && t.clientX <= r.right);
                      } catch (err) { startY = null; }
                    }, { passive: true, capture: true });
                    document.addEventListener('touchend', function(e){
                      if (startY === null || !overPlayer) { startY = null; return; }
                      var t = e.changedTouches[0];
                      var dy = t.clientY - startY;
                      var dx = Math.abs(t.clientX - startX);
                      startY = null;
                      if (dy > 90 && dx < dy) {
                        var h = host();
                        if (h && h.minimize) { try { h.minimize(); return; } catch (err) { } }
                        try { location.href = 'obereg://minimize'; } catch (err) { }
                      }
                    }, { passive: true, capture: true });
                  }
                  var els = [
                    document.querySelector('ytm-player'),
                    document.querySelector('#player-container'),
                    document.querySelector('#movie_player'),
                    document.querySelector('video')
                  ];
                  els.forEach(function(el) {
                    if (!el || el.__oberegSwipe) { return; }
                    el.__oberegSwipe = true;
                    var elStartY = null;
                    el.addEventListener('touchstart', function(e){ elStartY = e.touches[0].clientY; }, { passive: true });
                    el.addEventListener('touchend', function(e){
                      if (elStartY === null) { return; }
                      var dy = e.changedTouches[0].clientY - elStartY;
                      elStartY = null;
                      if (dy > 90) {
                        var h = host();
                        if (h && h.minimize) { try { h.minimize(); return; } catch (err) { } }
                        try { location.href = 'obereg://minimize'; } catch (err) { }
                      }
                    }, { passive: true });
                  });
                }
                function pinPlayer() {
                  try {
                    var el = document.querySelector('ytm-player') || document.querySelector('#player-container') || document.querySelector('#movie_player');
                    if (el) { el.style.setProperty('top', '0px', 'important'); }
                    var video = document.querySelector('video');
                    if (!video) { return; }
                    var vt = Math.round(video.getBoundingClientRect().top);
                    if (Math.abs(vt) > 3) {
                      var node = video;
                      var hops = 0;
                      while (node && node !== document.documentElement && hops < 16) {
                        if (node !== video && node.style) {
                          var cs = getComputedStyle(node);
                          var pos = cs.position || '';
                          if ((pos === 'fixed' || pos === 'absolute' || pos === 'sticky') && (parseFloat(cs.top) || 0) > 0.5) {
                            node.style.setProperty('top', '0px', 'important');
                          }
                          if ((parseFloat(cs.paddingTop) || 0) > 0.5) {
                            node.style.setProperty('padding-top', '0px', 'important');
                          }
                          if ((parseFloat(cs.marginTop) || 0) > 0.5) {
                            node.style.setProperty('margin-top', '0px', 'important');
                          }
                        }
                        node = node.parentElement;
                        hops++;
                      }
                      var vt2 = Math.round(video.getBoundingClientRect().top);
                      if (Math.abs(vt2) > 3 && document.body) {
                        var cur = parseFloat(getComputedStyle(document.body).marginTop) || 0;
                        var next = cur - vt2;
                        if (next > -220 && next < 220) {
                          document.body.style.setProperty('margin-top', next + 'px', 'important');
                        }
                      }
                    }
                  } catch (e) { }
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
                    if (video) {
                      var vr = video.getBoundingClientRect();
                      parts.push('видео:' + Math.round(vr.height) + '@' + Math.round(vr.top));
                    } else {
                      parts.push('видео:нет');
                    }
                    var app = document.querySelector('ytm-app');
                    if (app) {
                      var acs = getComputedStyle(app);
                      parts.push('appPad:' + acs.paddingTop + ' appTop:' + Math.round(app.getBoundingClientRect().top));
                    }
                    h.layout(tag + ' ' + parts.join(' '));
                    if (video) {
                      var chain = [];
                      var node = video.parentElement;
                      var hops = 0;
                      while (node && hops < 10) {
                        var ncs = getComputedStyle(node);
                        var nr = node.getBoundingClientRect();
                        chain.push(
                          (node.tagName || '?').toLowerCase() + (node.id ? '#' + node.id : '') +
                          '|' + Math.round(nr.height) + '@' + Math.round(nr.top) +
                          '|' + (ncs.position || '').substring(0, 3) +
                          '|p' + Math.round(parseFloat(ncs.paddingTop) || 0) +
                          'm' + Math.round(parseFloat(ncs.marginTop) || 0)
                        );
                        if (node.tagName === 'BODY') { break; }
                        node = node.parentElement;
                        hops++;
                      }
                      h.layout(tag + ' цепь: ' + chain.join(' > '));
                    }
                  } catch (e) { }
                }
                ensureStyle();
                hideChrome();
                attachSwipe();
                pinPlayer();
                setTimeout(function(){ reportLayout('в4с'); }, 4000);
                setTimeout(function(){ reportLayout('в10с'); }, 10000);
                setInterval(function(){
                  try {
                    ensureStyle();
                    hideChrome();
                    attachSwipe();
                    pinPlayer();
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
