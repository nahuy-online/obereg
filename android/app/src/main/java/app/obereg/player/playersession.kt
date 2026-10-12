package app.obereg.player

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference

/**
 * Сеанс ролика: один WebView живёт вне экранов и переносится между ними.
 *
 * Благодаря этому воспроизведение не прерывается: ролик можно развернуть на весь экран,
 * свернуть в мини-плеер внизу оболочки или уйти в другое приложение — страница не
 * перезагружается, а просто меняет хозяина, и видео продолжает играть.
 *
 * Настройка страницы (скрытие чужой шапки, привязка ролика к верху, свайп-сворачивание,
 * пропуск рекламы, скорость) живёт здесь же, чтобы одинаково работать на обоих экранах.
 */
object PlayerSession {

    /** Кто сейчас показывает ролик: экран плеера просит свернуть в мини-плеер по свайпу. */
    interface Host {
        fun onMinimizeRequested()
    }

    var host: Host? = null
    var lastStatus: ((String) -> Unit)? = null

    var videoId: String = ""
        private set
    var title: String = ""
        private set
    var speed: Double = 1.0
        private set

    private var web: WebView? = null
    private var reloadTried = false
    private var wantPlay = true

    private var pipActivity: WeakReference<Activity>? = null

    /** Контекст приложения: чтобы поднять/остановить службу медиа-уведомления. */
    private var appContext: Context? = null

    /** Последние данные «пульса» страницы — в журнал пишем только перемены. */
    private var lastPlaying: Boolean? = null
    private var lastResumeTryAt = 0L
    private var lastNote = ""
    private var lastNoteAt = 0L

    /** Оболочка обновляет мини-плеер, когда ролик закрыт из уведомления или уведомление убрано. */
    var onReleased: (() -> Unit)? = null

    /** Защита от дребезга жеста экрана (одна свёртка на полторы секунды). */
    private var lastViewMinimizeAt = 0L

    /** Когда в последний раз просили развернуть плеер: после этого мгновенные «свайпы» гасим. */
    var lastExpandAtMs = 0L

    /** Последняя позиция ролика из «пульса» — для пересборки плеера с места. */
    private var lastPositionMs = 0L

    /** Последний контейнер, куда ролик был пристыкован (для пересборки). */
    private var lastContainer: WeakReference<FrameLayout>? = null

    fun markExpand() {
        lastExpandAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Ролик загружен и им можно пользоваться. */
    val active: Boolean get() = web != null && videoId.isNotEmpty()

    /** Ролик сейчас в системном окне «картинка-в-картинке». */
    val pipActive: Boolean get() = pipActivity?.get()?.isInPictureInPictureMode == true

    fun markPip(activity: Activity) { pipActivity = WeakReference(activity) }

    fun clearPip(activity: Activity) {
        if (pipActivity?.get() === activity) pipActivity = null
    }

    /** Закрыть живое окно со старым роликом: вызывается перед открытием нового. */
    fun closeStalePip() {
        pipActivity?.get()?.let { old -> runCatching { old.finish() } }
        pipActivity = null
    }

    /** Запомнить, что ролик сейчас играет: после переезда между экранами вернём игру. */
    fun captureWantPlay() {
        web?.evaluateJavascript(JS_CAPTURE) { raw ->
            wantPlay = PlayerApi.decodeJsString(raw) != "0"
            pushWantToJs()
        }
    }

    fun syncWantPlay() {
        captureWantPlay()
    }

    /**
     * Несколько попыток продолжить воспроизведение после переезда: страница сервиса
     * иногда сама ставит паузу, когда окно меняет размер — подстраховываемся с запасом.
     */
    fun scheduleResumeAttempts() {
        val handler = Handler(Looper.getMainLooper())
        listOf(300L, 800L, 1600L, 2800L, 4500L).forEach { delay ->
            handler.postDelayed({
                if (!wantPlay) { return@postDelayed }
                videoPaused { paused -> if (paused) { resumeIfPaused() } }
            }, delay)
        }
    }

    /** Создать (один раз) WebView сеанса. */
    fun ensure(context: Context): WebView {
        web?.let { return it }
        val view = SessionWebView(context.applicationContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        view.setBackgroundColor(0xFF000000.toInt())
        view.addJavascriptInterface(Bridge(), "__oberegHost")
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(view, TUNING_EARLY, setOf("*"))
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                reloadTried = false
                injectTuning(v)
            }

            override fun onPageCommitVisible(v: WebView?, url: String?) {
                injectTuning(v)
            }

            override fun shouldOverrideUrlLoading(
                v: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString().orEmpty()
                if (target.startsWith("obereg://")) {
                    innerCommand(target)
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                val target = url.orEmpty()
                if (target.startsWith("obereg://")) {
                    innerCommand(target)
                    return true
                }
                return false
            }

            override fun onReceivedError(
                v: WebView?,
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
                        post("движок-плеер: сеть капризничает — пробую ещё раз")
                        v?.reload()
                    } else {
                        post("движок-плеер: сеть капризничает — «Перезагрузить» в меню скорости")
                    }
                }
            }
        }
        speed = AppSettings.playerSpeed(context).toDouble()
        appContext = context.applicationContext
        web = view
        return view
    }

    /**
     * Держать страницу «живой»: классический приём — WebView не должен считать себя
     * спящим, когда экран уходит в окно, в мини-плеер или приложение сворачивается.
     * Так воспроизведение продолжается, а кнопки play/pause снова работают.
     */
    fun keepAlive() {
        val view = web ?: return
        runCatching { view.onResume() }
        runCatching { view.resumeTimers() }
        runCatching { view.invalidate() }
    }

    /** Открыть ролик: если это тот же ролик — просто продолжаем, если новый — грузим. */
    fun open(context: Context, id: String, videoTitle: String) {
        val view = ensure(context)
        title = videoTitle
        if (id.isBlank()) {
            post("движок-плеер: не передан идентификатор ролика")
            return
        }
        if (videoId != id || view.url.isNullOrEmpty()) {
            videoId = id
            wantPlay = true
            lastPlaying = null
            DiagLog.add("движок-плеер: открыт для $id (скорость ${speedLabel(speed)})")
            val url = if (id.startsWith("http")) id else "https://www.youtube.com/watch?v=$id&hl=ru"
            view.loadUrl(url)
            PlaybackService.ensure(context)
            PlaybackService.update(
                playing = false,
                positionMs = 0,
                durationMs = 0,
                title = videoTitle.ifBlank { id },
                channel = "",
                videoId = PlaybackCodec.sanitizeVideoId(id),
                loop = false,
            )
        }
    }

    /** Показать ролик в указанном контейнере (экран плеера или мини-плеер оболочки). */
    fun attachTo(container: FrameLayout) {
        lastContainer = WeakReference(container)
        val view = web ?: return
        if (view.parent === container) {
            keepAlive()
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.removeAllViews()
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        keepAlive()
        wakeAfterAttach()
    }

    /**
     * После переезда WebView будим страницу: подтверждаем «хочу играть» и, если сервис
     * успел встать на паузу, сразу продолжаем — без долгой серии попыток.
     */
    private fun wakeAfterAttach() {
        pushWantToJs()
        runCatching { web?.invalidate() }
        // Мигание видимости на уровне вида (не страницы): заставляет заново собрать слой окна.
        runCatching {
            web?.visibility = View.INVISIBLE
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { web?.visibility = View.VISIBLE } }, 120)
        }
        Handler(Looper.getMainLooper()).postDelayed({ web?.evaluateJavascript(JS_WAKE) { _ -> } }, 150)
        Handler(Looper.getMainLooper()).postDelayed({ web?.evaluateJavascript(JS_WAKE) { _ -> } }, 1200)
        Handler(Looper.getMainLooper()).postDelayed({ web?.evaluateJavascript(JS_WAKE) { _ -> } }, 2600)
    }

    /** Сказать странице, что мы хотим играть: страж паузы пропускает только своё. */
    fun pushWantToJs() {
        val flag = if (wantPlay) "true" else "false"
        web?.evaluateJavascript(
            "(function(){ try { window.__oberegWantPlay = $flag; if ($flag) { window.__oberegAllowPause = false; } return 'ok'; } catch (e) { return 'сбой'; } })()",
        ) { _ -> }
    }

    /** Убрать ролик с экрана, не выключая воспроизведение. */
    fun detach() {
        keepAlive()
        (web?.parent as? ViewGroup)?.removeView(web)
    }

    /**
     * Свёртка жестом экрана: свайп ловим на уровне самого WebView — не зависим от того,
     * как страница обрабатывает касания. Вызывается из SessionWebView.
     */
    fun requestMinimizeFromView() {
        val h = host ?: return
        if (pipActive) { return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExpandAtMs < 1400) { return }
        if (now - lastViewMinimizeAt < 1500) { return }
        lastViewMinimizeAt = now
        DiagLog.add("движок-плеер: свайп (жест экрана) — сворачиваю")
        h.onMinimizeRequested()
    }

    fun togglePlayPause() {
        web?.evaluateJavascript(JS_TOGGLE) { raw ->
            when (PlayerApi.decodeJsString(raw)) {
                "1" -> wantPlay = true
                "2" -> wantPlay = false
            }
            pushWantToJs()
            pushStateQuick()
        }
    }

    /** Дать уведомлению свежее состояние сразу, не дожидаясь очередного «пульса». */
    fun pushStateQuick() {
        web?.evaluateJavascript(JS_SNAPSHOT) { raw ->
            onMediaPulse(PlayerApi.decodeJsString(raw).orEmpty())
        }
    }

    /** Кнопка play/pause из системного окна: переключить и обновить значок в окне. */
    fun togglePlayPauseFromPip() {
        togglePlayPause()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ updatePipParams() }, 600)
        handler.postDelayed({ updatePipParams() }, 1700)
    }

    /** Обновить значок play/pause в системном окне по текущему состоянию ролика. */
    fun updatePipParams() {
        val activity = pipActivity?.get() as? EnginePlayerActivity ?: return
        web?.evaluateJavascript(JS_PAUSED) { raw ->
            val playing = PlayerApi.decodeJsString(raw) != "1"
            Handler(Looper.getMainLooper()).post { activity.applyPipParams(playing) }
        }
    }

    /** Продолжить, если видео встало на паузу (после сворачивания в окно/мини-плеер). */
    fun resumeIfPaused() {
        web?.evaluateJavascript(JS_RESUME) { _ -> }
    }

    fun videoPaused(callback: (Boolean) -> Unit) {
        web?.evaluateJavascript(JS_PAUSED) { raw ->
            callback(PlayerApi.decodeJsString(raw) == "1")
        }
    }

    /** Режим системного окна: в окошке показываем только ролик — на весь кадр. */
    fun applyPipLayout(on: Boolean) {
        web?.evaluateJavascript(APPLY_PIP_JS.replace("__ON__", if (on) "true" else "false")) { _ -> }
    }

    fun applySpeed(context: Context, rate: Double) {
        speed = rate
        AppSettings.setPlayerSpeed(context, rate.toFloat())
        web?.evaluateJavascript(
            "window.__oberegRate = $rate; (function(){var v=document.querySelector('video'); if(v){v.playbackRate=$rate;}})(); 'ok'",
        ) { _ -> }
    }

    fun reload() { web?.reload() }

    /** Ручное «оживление» видео (кнопка в меню плеера): пересобрать слой, сдвинуть время. */
    fun reviveVideo() {
        DiagLog.add("движок-плеер: оживляю видео вручную")
        web?.evaluateJavascript(JS_REVIVE) { _ -> }
    }

    /** Пересобрать плеер с текущего места: свежий WebView — свежий видеослой. */
    fun rebuildFromPosition() {
        val id = videoId
        val ctx = appContext
        if (id.isBlank() || id.startsWith("http") || ctx == null) {
            DiagLog.add("движок-плеер: пересборка недоступна")
            return
        }
        val sec = (lastPositionMs / 1000).coerceAtLeast(0L)
        DiagLog.add("движок-плеер: пересобираю плеер с места ${sec}с")
        val container = lastContainer?.get()
        detach()
        runCatching { web?.destroy() }
        web = null
        videoId = ""
        wantPlay = true
        lastPlaying = null
        val view = ensure(ctx)
        videoId = id
        view.loadUrl("https://www.youtube.com/watch?v=$id&hl=ru&t=${sec}s")
        PlaybackService.ensure(ctx)
        if (container != null) {
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            keepAlive()
            wakeAfterAttach()
        }
    }

    /** Закрыть ролик совсем: страница уничтожается, мини-плеер прячется, уведомление исчезает. */
    fun release() {
        detach()
        runCatching { web?.destroy() }
        web = null
        videoId = ""
        title = ""
        wantPlay = true
        lastPlaying = null
        appContext?.let { PlaybackService.stop(it) }
        val callback = onReleased
        Handler(Looper.getMainLooper()).post { callback?.invoke() }
    }

    // ==== Управление из медиа-уведомления и с кнопок гарнитуры ====

    /** Играть (из уведомления или с кнопки гарнитуры). */
    fun mediaPlay() {
        wantPlay = true
        pushWantToJs()
        web?.evaluateJavascript(JS_RESUME) { _ -> }
        pushStateQuick()
    }

    /** Пауза по нашей кнопке (уведомление или гарнитура). */
    fun mediaPause() {
        wantPlay = false
        pushWantToJs()
        web?.evaluateJavascript(JS_PAUSE) { _ -> }
        pushStateQuick()
    }

    /** Следующий ролик. */
    fun mediaNext() {
        DiagLog.add("движок-плеер: дальше (из уведомления)")
        wantPlay = true
        web?.evaluateJavascript(JS_NEXT) { raw ->
            if (PlayerApi.decodeJsString(raw) == "нет") {
                DiagLog.add("движок-плеер: у сервиса нет кнопки «дальше»")
            }
        }
        handlerResumeSoon()
    }

    /** Предыдущий ролик (или в начало, если сервис не умеет). */
    fun mediaPrevious() {
        DiagLog.add("движок-плеер: назад (из уведомления)")
        wantPlay = true
        web?.evaluateJavascript(JS_PREV) { _ -> }
        handlerResumeSoon()
    }

    /** Переключить повтор текущего ролика. */
    fun mediaRepeat() {
        web?.evaluateJavascript(JS_REPEAT) { raw ->
            val answer = PlayerApi.decodeJsString(raw)
            DiagLog.add(
                "движок-плеер: повтор " + when (answer) {
                    "on" -> "включён"
                    "off" -> "выключен"
                    else -> "не переключился"
                },
            )
            pushStateQuick()
        }
    }

    /** Перемотка из уведомления (миллисекунды). */
    fun mediaSeek(positionMs: Long) {
        val seconds = PlaybackCodec.clampSeekMs(positionMs, 0) / 1000.0
        web?.evaluateJavascript(JS_SEEK.replace("__S__", seconds.toString())) { _ -> }
    }

    /** Закрыть ролик из уведомления: страница освобождается, уведомление исчезает. */
    fun mediaClose() {
        DiagLog.add("движок-плеер: закрыто из уведомления")
        release()
    }

    private fun handlerResumeSoon() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!wantPlay) { return@postDelayed }
            videoPaused { paused -> if (paused) { resumeIfPaused() } }
        }, 700)
    }

    /** «Пульс» со страницы: состояние ролика для медиа-уведомления (раз в секунду). */
    private fun onMediaPulse(json: String) {
        val pulse = PlaybackCodec.parsePulse(json) ?: return
        lastPositionMs = pulse.positionMs
        if (pulse.title.isNotBlank()) {
            title = pulse.title
        }
        if (lastPlaying == null) {
            DiagLog.add("движок-плеер: состояние ролика на связи")
        } else if (lastPlaying != pulse.playing) {
            DiagLog.add(if (pulse.playing) "движок-плеер: игра" else "движок-плеер: пауза")
        }
        lastPlaying = pulse.playing
        PlaybackService.update(
            playing = pulse.playing,
            positionMs = pulse.positionMs,
            durationMs = pulse.durationMs,
            title = title,
            channel = pulse.channel,
            videoId = pulse.videoId.ifBlank { PlaybackCodec.sanitizeVideoId(videoId) },
            loop = pulse.loop,
        )
        // Страховка: ролик встал на паузу не по нашей воле — вернём игру (но не после досмотра).
        val now = android.os.SystemClock.elapsedRealtime()
        if (!pulse.playing && wantPlay && !PlaybackCodec.nearEnd(pulse) && now - lastResumeTryAt > 2500) {
            lastResumeTryAt = now
            resumeIfPaused()
        }
    }

    private fun post(text: String) {
        DiagLog.add(text)
        lastStatus?.invoke(text)
    }

    private fun innerCommand(target: String) {
        if (target.startsWith("obereg://minimize")) {
            DiagLog.add("движок-плеер: свайп вниз — сворачиваю в мини-плеер")
            host?.onMinimizeRequested()
        }
        if (target.startsWith("obereg://layout")) {
            val payload = android.net.Uri.decode(target.substringAfter("?", ""))
            if (payload.isNotBlank()) {
                DiagLog.add("движок-плеер: вёрстка — $payload")
            }
        }
    }

    /** Мост странице: жесты, состояние ролика и диагностика без переходов по адресам. */
    private class Bridge {

        @android.webkit.JavascriptInterface
        fun minimize() {
            DiagLog.add("движок-плеер: свайп вниз — сворачиваю в мини-плеер")
            Handler(Looper.getMainLooper()).post { host?.onMinimizeRequested() }
        }

        @android.webkit.JavascriptInterface
        fun layout(report: String) {
            DiagLog.add("движок-плеер: вёрстка — " + report.take(460))
        }

        /** Сообщение со страницы (страж паузы, редкие события) — с защитой от повторов. */
        @android.webkit.JavascriptInterface
        fun note(text: String) {
            Handler(Looper.getMainLooper()).post {
                val trimmed = text.take(120)
                val now = android.os.SystemClock.elapsedRealtime()
                if (trimmed != lastNote || now - lastNoteAt > 6000) {
                    lastNote = trimmed
                    lastNoteAt = now
                    DiagLog.add("движок-плеер: $trimmed")
                }
            }
        }

        /** «Пульс» состояния: играет ли ролик, позиция, длительность, название, канал. */
        @android.webkit.JavascriptInterface
        fun media(json: String) {
            val trimmed = json.take(2000)
            Handler(Looper.getMainLooper()).post { onMediaPulse(trimmed) }
        }

        /** Пользователь сам поставил паузу (жест на странице) — уважаем и не возобновляем. */
        @android.webkit.JavascriptInterface
        fun pausedByUser() {
            Handler(Looper.getMainLooper()).post {
                wantPlay = false
                DiagLog.add("движок-плеер: пауза по кнопке сервиса — уважаю")
            }
        }
    }

    private fun injectTuning(view: WebView?) {
        view ?: return
        val safeId = PlaybackCodec.sanitizeVideoId(videoId)
        if (safeId.isNotBlank()) {
            view.evaluateJavascript("try{ window.__oberegVideoId = '$safeId'; }catch(e){} 'ok'") { _ -> }
        }
        pushWantToJs()
        val script = TUNING.replace("__RATE__", speed.toString())
        view.evaluateJavascript(script) { raw ->
            val answer = PlayerApi.decodeJsString(raw)
            if (answer == "готово") {
                DiagLog.add("движок-плеер: тюнинг установлен")
                lastStatus?.invoke("")
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

    /** WebView, который всегда считает своё окно видимым и сам ловит свайп-сворачивание. */
    private class SessionWebView(context: Context) : WebView(context) {

        private var swipeStartRawY = -1f
        private var swipeStartRawX = 0f
        private var swipeStartAt = 0L
        private var swipeMoves = 0

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(true)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            runCatching {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // Геометрия — по сырым координатам ЭКРАНА: анимации и переезды окна
                        // не должны превращать тап в «свайп» (из-за этого была петля
                        // «развернул → мгновенно свернулось»).
                        swipeStartRawY = event.rawY
                        swipeStartRawX = event.rawX
                        swipeStartAt = android.os.SystemClock.elapsedRealtime()
                        swipeMoves = 0
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        swipeMoves++
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (swipeStartRawY >= 0f) {
                            val dy = event.rawY - swipeStartRawY
                            val dx = Math.abs(event.rawX - swipeStartRawX)
                            val dt = android.os.SystemClock.elapsedRealtime() - swipeStartAt
                            val threshold = 80f * resources.displayMetrics.density
                            val screenH = resources.displayMetrics.heightPixels
                            val fromTop = swipeStartRawY < screenH * 0.55f
                            val realDrag = swipeMoves >= 3 && dt >= 90 && dt <= 1400
                            if (realDrag && dy > threshold && dx < dy && fromTop) {
                                requestMinimizeFromView()
                            }
                        }
                        swipeStartRawY = -1f
                    }
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** Играет ли ролик: сначала спрашиваем сам плеер сервиса, при сомнении — элемент видео. */
    private val JS_PAUSED = """
        (function(){
          try {
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (v && typeof v.paused === 'boolean') { return v.paused ? '1' : '0'; }
            var p = document.getElementById('movie_player');
            try {
              if (p && typeof p.getPlayerState === 'function') {
                var s = p.getPlayerState();
                if (s === 1 || s === 3) { return '0'; }
                if (s === 2 || s === 5 || s === -1) { return '1'; }
              }
            } catch (e) { }
            return '1';
          } catch (e) { return '1'; }
        })()
    """.trimIndent()

    /** Продолжить воспроизведение: через API плеера сервиса и сам элемент видео. */
    private val JS_RESUME = """
        (function(){
          try {
            window.__oberegWantPlay = true;
            var p = document.getElementById('movie_player');
            var played = false;
            try { if (p && typeof p.playVideo === 'function') { p.playVideo(); played = true; } } catch (e) { }
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (v && v.paused) {
              try { var pr = v.play(); if (pr && pr.catch) { pr.catch(function(){}); } } catch (e) { }
            }
            return played ? 'api' : 'ok';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Переключить play/pause: через API плеера сервиса, с запасным путём по элементу. */
    private val JS_TOGGLE = """
        (function(){
          try {
            var p = document.getElementById('movie_player');
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            var playing = null;
            if (v && typeof v.paused === 'boolean') { playing = !v.paused; }
            if (playing === null) {
              try {
                if (p && typeof p.getPlayerState === 'function') {
                  var s = p.getPlayerState();
                  playing = (s === 1 || s === 3);
                }
              } catch (e) { }
            }
            if (playing === null) { playing = false; }
            if (playing) {
              window.__oberegWantPlay = false;
              window.__oberegAllowPause = true;
              try { if (p && typeof p.pauseVideo === 'function') { p.pauseVideo(); } } catch (e) { }
              try { if (v && !v.paused) { v.pause(); } } catch (e) { }
              setTimeout(function(){ window.__oberegAllowPause = false; }, 900);
              return '2';
            }
            window.__oberegWantPlay = true;
            try { if (p && typeof p.playVideo === 'function') { p.playVideo(); } } catch (e) { }
            try { if (v && v.paused) { var pr = v.play(); if (pr && pr.catch) { pr.catch(function(){}); } } } catch (e) { }
            return '1';
          } catch (e) { return '0'; }
        })()
    """.trimIndent()

    /** Явная пауза по нашей кнопке: уважаем своё решение, чужим паузам страж не мешает. */
    private val JS_PAUSE = """
        (function(){
          try {
            window.__oberegWantPlay = false;
            window.__oberegAllowPause = true;
            var p = document.getElementById('movie_player');
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            try { if (p && typeof p.pauseVideo === 'function') { p.pauseVideo(); } } catch (e) { }
            try { if (v && !v.paused) { v.pause(); } } catch (e) { }
            setTimeout(function(){ window.__oberegAllowPause = false; }, 900);
            return '2';
          } catch (e) { return '0'; }
        })()
    """.trimIndent()

    /** Наше «хочу играть» на странице — переносим состояние между экранами. */
    private val JS_CAPTURE = """
        (function(){
          try {
            if (typeof window.__oberegWantPlay === 'boolean') { return window.__oberegWantPlay ? '1' : '0'; }
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            return (v && v.paused) ? '0' : '1';
          } catch (e) { return '1'; }
        })()
    """.trimIndent()

    /** Снимок состояния ролика — отсюда медиа-уведомление берёт данные. */
    private val JS_SNAPSHOT = """
        (function(){ try { return (typeof window.__oberegSnapshot === 'function') ? window.__oberegSnapshot() : '{}'; } catch (e) { return '{}'; } })()
    """.trimIndent()

    /** Следующий ролик. */
    private val JS_NEXT = """
        (function(){
          try {
            var p = document.getElementById('movie_player');
            if (p && typeof p.nextVideo === 'function') { p.nextVideo(); return 'api'; }
            var btn = document.querySelector('.ytp-next-button, button[aria-label*="Следующее"], button[aria-label*="Next"]');
            if (btn) { btn.click(); return 'кнопка'; }
            return 'нет';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Предыдущий ролик (запасной путь — в начало). */
    private val JS_PREV = """
        (function(){
          try {
            var p = document.getElementById('movie_player');
            if (p && typeof p.previousVideo === 'function') { p.previousVideo(); return 'api'; }
            if (p && typeof p.seekTo === 'function') { p.seekTo(0, true); return 'в начало'; }
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (v) { v.currentTime = 0; return 'элемент'; }
            return 'нет';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Повтор текущего ролика: включить/выключить. */
    private val JS_REPEAT = """
        (function(){
          try {
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            var p = document.getElementById('movie_player');
            var next = !(v && v.loop);
            try { if (v) { v.loop = next; } } catch (e) { }
            try { if (p && typeof p.setLoopVideo === 'function') { p.setLoopVideo(next); } } catch (e) { }
            return next ? 'on' : 'off';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Перемотка: __S__ — секунды. */
    private val JS_SEEK = """
        (function(){
          try {
            var s = __S__;
            if (typeof s !== 'number' || !isFinite(s) || s < 0) { return 'сбой'; }
            var p = document.getElementById('movie_player');
            if (p && typeof p.seekTo === 'function') { p.seekTo(s, true); return 'api'; }
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (v) { v.currentTime = s; return 'элемент'; }
            return 'нет';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Ручное «оживление» видео из меню плеера (для чёрного экрана). */
    private val JS_REVIVE = """
        (function(){ try { return (window.__oberegRevive ? window.__oberegRevive() : 'нет'); } catch (e) { return 'сбой'; } })()
    """.trimIndent()

    /** Разбудить страницу после переезда: продолжить, если нужно, и починить картинку/вёрстку. */
    private val JS_WAKE = """
        (function(){
          try {
            var want = (window.__oberegWantPlay !== false);
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (want && v && v.paused) {
              var p = document.getElementById('movie_player');
              try { if (p && typeof p.playVideo === 'function') { p.playVideo(); } } catch (e) { }
              try { var pr = v.play(); if (pr && pr.catch) { pr.catch(function(){}); } } catch (e) { }
            }
            try { if (typeof window.__oberegUnforce === 'function') { window.__oberegUnforce(); } } catch (e) { }
            try { if (typeof window.__oberegNudge === 'function') { window.__oberegNudge('пробуждение'); } } catch (e) { }
            try { if (typeof window.__oberegReflowRescue === 'function') { window.__oberegReflowRescue('пробуждение'); } } catch (e) { }
            return 'ok';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()

    /** Вставляет/убирает стиль «только ролик на весь кадр» для системного окна. */
    private val APPLY_PIP_JS = """
        (function(){
          function pipCss() {
            return 'html,body{overflow:hidden !important; background:#000 !important;} video{position:fixed !important;top:0 !important;left:0 !important;width:100vw !important;height:100vh !important;max-width:none !important;max-height:none !important;object-fit:contain !important;z-index:2147483646 !important;background:#000 !important;}';
          }
          function enforcePipVideo(force) {
            try {
              var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
              if (!v) { return; }
              if (force) {
                v.style.setProperty('position', 'fixed', 'important');
                v.style.setProperty('top', '0', 'important');
                v.style.setProperty('left', '0', 'important');
                v.style.setProperty('width', '100vw', 'important');
                v.style.setProperty('height', '100vh', 'important');
                v.style.setProperty('max-width', 'none', 'important');
                v.style.setProperty('max-height', 'none', 'important');
                v.style.setProperty('object-fit', 'contain', 'important');
                v.style.setProperty('z-index', '2147483646', 'important');
                v.style.setProperty('background', '#000', 'important');
              } else {
                ['position','top','left','width','height','max-width','max-height','object-fit','z-index','background'].forEach(function(k){
                  try { v.style.removeProperty(k); } catch (e) { }
                });
              }
            } catch (e) { }
          }
          function isolatePip() {
            try {
              var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
              if (!v) { return; }
              if (!window.__oberegPipHidden) { window.__oberegPipHidden = []; }
              var node = v;
              var guard = 0;
              while (node && node !== document.body && guard < 20) {
                var parent = node.parentElement;
                if (!parent) { break; }
                var kids = parent.children;
                for (var i = 0; i < kids.length; i++) {
                  var k = kids[i];
                  if (k === node) { continue; }
                  if (k.contains && k.contains(v)) { continue; }
                  if (k.style && k.style.display !== 'none') {
                    k.style.setProperty('display', 'none', 'important');
                    window.__oberegPipHidden.push(k);
                  }
                }
                node = parent;
                guard++;
              }
            } catch (e) { }
          }
          function restorePip() {
            try {
              var arr = window.__oberegPipHidden || [];
              for (var i = 0; i < arr.length; i++) {
                try { arr[i].style.removeProperty('display'); } catch (e) { }
              }
              window.__oberegPipHidden = [];
            } catch (e) { }
          }
          try {
            var on = __ON__;
            window.__oberegPipMode = on;
            var st = document.getElementById('oberegPipCss');
            if (on) {
              if (!st) {
                st = document.createElement('style');
                st.id = 'oberegPipCss';
                (document.head || document.documentElement).appendChild(st);
              }
              st.textContent = pipCss();
              enforcePipVideo(true);
              isolatePip();
              var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
              if (v) { try { v.setAttribute('playsinline',''); } catch (e) { } }
              setTimeout(function(){
                try {
                  var h = window.__oberegHost;
                  var v2 = document.querySelector('video');
                  var css = document.getElementById('oberegPipCss') ? 'да' : 'нет';
                  if (h && h.layout) {
                    if (v2) {
                      var r = v2.getBoundingClientRect();
                      h.layout('окно: стиль=' + css + ' видео ' + Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.left) + ',' + Math.round(r.top) + ' поз=' + (getComputedStyle(v2).position || ''));
                    } else {
                      h.layout('окно: стиль=' + css + ' видео нет');
                    }
                  }
                } catch (e) { }
              }, 1800);
            } else {
              if (st && st.parentNode) { st.parentNode.removeChild(st); }
              enforcePipVideo(false);
              restorePip();
              setTimeout(function(){
                try { if (window.__oberegNudge) { window.__oberegNudge('выход из окна'); } } catch (e) { }
                try { if (window.__oberegReflowRescue) { window.__oberegReflowRescue('выход из окна'); } } catch (e) { }
              }, 300);
            }
          } catch (e) { }
          return 'ok';
        })()
    """.trimIndent()

    /** Ранний стиль: ставится ещё до отрисовки страницы — чужой шапки и логотипа не видно даже мельком.
     *  Плюс: страница всегда считает себя видимой и «хочет играть», пока мы не сказали обратное, —
     *  иначе сервис сам ставит ролик на паузу при сворачивании, в окне и в фоне. */
    private val TUNING_EARLY = """
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
          try {
            function eater(e) { try { e.stopImmediatePropagation(); } catch (err) { } }
            window.addEventListener('visibilitychange', eater, true);
            window.addEventListener('webkitvisibilitychange', eater, true);
            document.addEventListener('visibilitychange', eater, true);
            document.addEventListener('webkitvisibilitychange', eater, true);
            try { Object.defineProperty(Document.prototype, 'hidden', { get: function () { return false; }, configurable: true }); } catch (e) { }
            try { Object.defineProperty(Document.prototype, 'visibilityState', { get: function () { return 'visible'; }, configurable: true }); } catch (e) { }
            try { Object.defineProperty(Document.prototype, 'webkitHidden', { get: function () { return false; }, configurable: true }); } catch (e) { }
            try { Object.defineProperty(Document.prototype, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true }); } catch (e) { }
          } catch (e) { }
          try {
            // «Страж паузы» (v2): пока мы хотим играть, чужие просьбы о паузе не проходят.
            // «Паузой пользователя» считается только нажатие настоящей кнопки страницы
            // (или наше разрешение). Свайп-жесты (сворачивание) — не пауза: окно жеста
            // глушит всё, что страница пытается делать по пути.
            if (!window.__oberegPauseGuard) {
              window.__oberegPauseGuard = true;
              if (typeof window.__oberegWantPlay !== 'boolean') { window.__oberegWantPlay = true; }
              window.__oberegAllowPause = false;
              window.__oberegPauseBlocks = 0;
              window.__oberegLastGesture = 0;
              window.__oberegLastTapOnButtonAt = 0;
              window.__oberegGestureGuardUntil = 0;
              window.__oberegTouchStartY = null;
              window.__oberegTouchStartX = null;
              ['pointerdown', 'touchstart', 'touchend', 'click', 'keydown'].forEach(function (name) {
                document.addEventListener(name, function () { window.__oberegLastGesture = Date.now(); }, true);
              });
              // Паузой пользователя считаем только нажатие настоящей кнопки страницы.
              ['pointerdown', 'click'].forEach(function (name) {
                document.addEventListener(name, function (e) {
                  try {
                    var t = e.target;
                    if (t && t.closest && t.closest('button, [role="button"], .ytp-play-button')) {
                      window.__oberegLastTapOnButtonAt = Date.now();
                    }
                  } catch (err) { }
                }, true);
              });
              // Вертикальный свайп больше 60px — «окно жеста»: странице нельзя ставить
              // ролик на паузу, пока жест идёт (это не намерение паузы).
              document.addEventListener('touchstart', function (e) {
                try { var t = e.touches[0]; if (t) { window.__oberegTouchStartY = t.clientY; window.__oberegTouchStartX = t.clientX; } } catch (err) { }
              }, { passive: true, capture: true });
              document.addEventListener('touchmove', function (e) {
                try {
                  var t = e.touches[0];
                  if (!t || window.__oberegTouchStartY === null) { return; }
                  var dy = t.clientY - window.__oberegTouchStartY;
                  var dx = Math.abs(t.clientX - window.__oberegTouchStartX);
                  if (dy > 60 && dx < dy) {
                    window.__oberegGestureGuardUntil = Date.now() + 1800;
                  }
                } catch (err) { }
              }, { passive: true, capture: true });
              var origPause = HTMLMediaElement.prototype.pause;
              HTMLMediaElement.prototype.pause = function() {
                try {
                  var want = (window.__oberegWantPlay !== false);
                  var allow = (window.__oberegAllowPause === true);
                  if (want && !allow) {
                    var now = Date.now();
                    var hasUA = false;
                    var act = false;
                    try { hasUA = !!navigator.userActivation; act = hasUA && !!navigator.userActivation.isActive; } catch (e) { }
                    var fresh = (now - (window.__oberegLastGesture || 0)) < 600;
                    var onButton = (now - (window.__oberegLastTapOnButtonAt || 0)) < 900;
                    var gesture = now < (window.__oberegGestureGuardUntil || 0);
                    var userIntent = (act || (!hasUA && fresh)) && onButton && !gesture;
                    if (!userIntent) {
                      window.__oberegPauseBlocks = (window.__oberegPauseBlocks || 0) + 1;
                      try {
                        var h = window.__oberegHost;
                        if (h && h.note && (window.__oberegPauseBlocks <= 3 || window.__oberegPauseBlocks % 20 === 0)) {
                          h.note('просьба о паузе отклонена (всего ' + window.__oberegPauseBlocks + ')');
                        }
                      } catch (e) { }
                      return;
                    }
                  }
                } catch (e) { }
                return origPause.apply(this, arguments);
              };
              document.addEventListener('pause', function (ev) {
                try {
                  var t = ev.target;
                  if (!t || t.tagName !== 'VIDEO') { return; }
                  if (window.__oberegAllowPause === true) { return; }
                  var now = Date.now();
                  var act = false;
                  try { act = !!(navigator.userActivation && navigator.userActivation.isActive); } catch (e) { }
                  var onButton = (now - (window.__oberegLastTapOnButtonAt || 0)) < 900;
                  var gesture = now < (window.__oberegGestureGuardUntil || 0);
                  if (act && onButton && !gesture) {
                    window.__oberegWantPlay = false;
                    try { var h = window.__oberegHost; if (h && h.pausedByUser) { h.pausedByUser(); } } catch (e) { }
                    return;
                  }
                  if (window.__oberegWantPlay !== false) {
                    try { var h2 = window.__oberegHost; if (h2 && h2.note) { h2.note('пауза не по кнопке (верну игру)'); } } catch (e) { }
                  }
                } catch (e) { }
              }, true);
            }
          } catch (e) { }
          try {
            // Фокус: считаем, что окно всегда в фокусе (сервис иногда ставит паузу по потере фокуса).
            try { Object.defineProperty(Document.prototype, 'hasFocus', { get: function () { return true; }, configurable: true }); } catch (e) { }
            window.addEventListener('blur', function (e) { try { e.stopImmediatePropagation(); } catch (err) { } }, true);
            document.addEventListener('blur', function (e) { try { e.stopImmediatePropagation(); } catch (err) { } }, true);
          } catch (e) { }
          try {
            // Активное видео: у страницы бывает несколько <video> — берём самое «живое»:
            // играющее, готовое к показу и самое крупное (иначе можно попасть на мёртвый слой).
            if (!window.__oberegVideo) {
              window.__oberegVideo = function () {
                try {
                  var list = document.querySelectorAll('video');
                  var best = null;
                  var bestScore = -1;
                  for (var i = 0; i < list.length; i++) {
                    var c = list[i];
                    if (!c || c.ended) { continue; }
                    var score = 0;
                    try {
                      var r = c.getBoundingClientRect();
                      score += Math.min(Math.round(r.width * r.height), 2000000);
                    } catch (e) { }
                    try { if (!c.paused) { score += 500000; } } catch (e) { }
                    try { if (c.readyState >= 2) { score += 300000; } } catch (e) { }
                    try { score += Math.min(Math.round((c.currentTime || 0) * 100), 30000); } catch (e) { }
                    if (score > bestScore) { bestScore = score; best = c; }
                  }
                  if (best) { return best; }
                } catch (e) { }
                return document.querySelector('video');
              };
            }
            // Лёгкое «пробуждение»: читаем геометрию и размеры видео — без перемоток и стилей.
            if (!window.__oberegNudge) {
              window.__oberegNudge = function (reason) {
                try {
                  var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  if (!v) { return 'нет видео'; }
                  try { void v.getBoundingClientRect(); void v.videoWidth; } catch (e) { }
                  return 'ок';
                } catch (e) { return 'сбой'; }
              };
            }
            // Ручное «оживление» из меню плеера: снять скрытие, вернуть размер, слегка сдвинуть время.
            if (!window.__oberegRevive) {
              window.__oberegRevive = function () {
                try {
                  var a = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  if (!a) { return 'нет видео'; }
                  try { a.style.display = 'block'; a.style.visibility = ''; } catch (e) { }
                  try { if (a.style.transform && a.style.transform.indexOf('translateZ(0.0001px)') >= 0) { a.style.transform = ''; } } catch (e) { }
                  var r = a.getBoundingClientRect();
                  if (r.height < 40 && a.__oberegGoodH >= 40) { a.style.setProperty('height', a.__oberegGoodH + 'px', 'important'); }
                  if (r.width < 40) { a.style.setProperty('width', (a.__oberegGoodW >= 100 ? a.__oberegGoodW + 'px' : '100%'), 'important'); }
                  var node = a.parentElement;
                  var hops = 0;
                  while (node && node !== document.body && hops < 8) {
                    try {
                      if (node.style && node.style.display === 'none') { node.style.setProperty('display', '', 'important'); }
                    } catch (e) { }
                    node = node.parentElement;
                    hops++;
                  }
                  try { a.currentTime = (a.currentTime || 0) + 0.2; } catch (e) { }
                  return 'ок';
                } catch (e) { return 'сбой'; }
              };
            }
            // Снять наши вынужденные размеры (вызывается при переездах: проверка заново).
            if (!window.__oberegUnforce) {
              window.__oberegUnforce = function () {
                try {
                  var list = document.querySelectorAll('video');
                  for (var i = 0; i < list.length; i++) {
                    var v = list[i];
                    if (v && v.__oberegForced) {
                      try {
                        v.style.removeProperty('height');
                        v.style.removeProperty('width');
                        v.style.removeProperty('display');
                      } catch (e) { }
                      v.__oberegForced = false;
                    }
                  }
                } catch (e) { }
              };
            }
            // Спасение вёрстки: если после переездов ролик «уехал» в середину страницы —
            // возвращаем его на верх и подчищаем наши подтяжки.
            if (!window.__oberegReflowRescue) {
              window.__oberegReflowRescue = function (reason) {
                try {
                  var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  if (!v) { return 'нет видео'; }
                  if ((window.scrollY || 0) > 2) { try { window.scrollTo(0, 0); } catch (e) { } }
                  var player = document.querySelector('ytm-player') || document.querySelector('#player-container') || document.querySelector('#movie_player');
                  if (player) {
                    try {
                      player.style.setProperty('position', 'sticky', 'important');
                      player.style.setProperty('top', '0px', 'important');
                    } catch (e) { }
                  }
                  var before = Math.round(v.getBoundingClientRect().top);
                  var node = v;
                  var hops = 0;
                  while (node && node !== document.documentElement && hops < 20) {
                    if (node !== v && node.style) {
                      try {
                        var cs = window.getComputedStyle(node);
                        var pos = cs.position || '';
                        if (pos === 'fixed' || pos === 'absolute' || pos === 'sticky') {
                          if ((parseFloat(cs.top) || 0) > 0.5) { node.style.setProperty('top', '0px', 'important'); }
                        }
                        if ((parseFloat(cs.paddingTop) || 0) > 0.5) { node.style.setProperty('padding-top', '0px', 'important'); }
                        if ((parseFloat(cs.marginTop) || 0) > 0.5) { node.style.setProperty('margin-top', '0px', 'important'); }
                      } catch (e) { }
                    }
                    node = node.parentElement;
                    hops++;
                  }
                  if (document.body) {
                    try {
                      var bmt = parseFloat(getComputedStyle(document.body).marginTop) || 0;
                      var after = v.getBoundingClientRect().top;
                      if (after > 3) {
                        var next = bmt - after;
                        if (next > -600 && next < 600) { document.body.style.setProperty('margin-top', next + 'px', 'important'); }
                      } else if (bmt < -1) {
                        document.body.style.setProperty('margin-top', '0px', 'important');
                      }
                    } catch (e) { }
                  }
                  var fixed = Math.round(v.getBoundingClientRect().top);
                  try {
                    var h = window.__oberegHost;
                    if (h && h.note && before !== fixed) {
                      h.note('вёрстка: спасение (' + (reason || '') + ') верх видео ' + before + ' → ' + fixed);
                    }
                  } catch (e) { }
                  return 'ок';
                } catch (e) { return 'сбой'; }
              };
            }
          } catch (e) { }
          try {
            if (!window.__oberegSnapshot) {
              window.__oberegSnapshot = function() {
                try {
                  var p = document.getElementById('movie_player');
                  var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  var playing = false;
                  var t = 0;
                  var d = 0;
                  try { if (v && typeof v.paused === 'boolean') { playing = !v.paused; } } catch (e) { }
                  try { if (!playing && p && typeof p.getPlayerState === 'function') { var s = p.getPlayerState(); playing = (s === 1 || s === 3); } } catch (e) { }
                  try { if (p && typeof p.getCurrentTime === 'function') { t = p.getCurrentTime() || 0; } } catch (e) { }
                  try { if (p && typeof p.getDuration === 'function') { d = p.getDuration() || 0; } } catch (e) { }
                  if (!t && v) { try { t = v.currentTime || 0; } catch (e) { } }
                  if (!d && v) { try { d = v.duration || 0; } catch (e) { } }
                  if (!playing && v) { try { playing = !v.paused; } catch (e) { } }
                  var title = '';
                  var author = '';
                  var id = '';
                  try {
                    if (p && typeof p.getVideoData === 'function') {
                      var vd = p.getVideoData() || {};
                      title = vd.title || '';
                      author = vd.author || '';
                      id = vd.video_id || '';
                    }
                  } catch (e) { }
                  if (!title) { try { title = (document.title || '').replace(/ - YouTube$/, ''); } catch (e) { } }
                  if (!title) { try { var og = document.querySelector('meta[property="og:title"]'); if (og) { title = og.content || ''; } } catch (e) { } }
                  if (!author) {
                    try {
                      var sels = ['ytm-slim-owner-renderer a', 'ytm-slim-owner-renderer', 'ytm-video-owner-renderer a', '#owner a', '#owner-name a', '.slim-owner-name'];
                      for (var i = 0; i < sels.length; i++) {
                        var node = document.querySelector(sels[i]);
                        if (node && node.textContent && node.textContent.trim()) { author = node.textContent.trim(); break; }
                      }
                    } catch (e) { }
                  }
                  if (!id && window.__oberegVideoId) { id = window.__oberegVideoId; }
                  var loop = false;
                  try { loop = !!(v && v.loop); } catch (e) { }
                  return JSON.stringify({
                    playing: !!playing,
                    t: Math.max(0, Math.round((t || 0) * 1000)),
                    d: Math.max(0, Math.round((d || 0) * 1000)),
                    title: String(title).slice(0, 160),
                    author: String(author).slice(0, 120),
                    id: String(id).slice(0, 32),
                    loop: !!loop
                  });
                } catch (e) { return '{}'; }
              };
            }
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
                'ytm-app { padding-top: 0 !important; }' +
                'ytm-chip-cloud-renderer, ytm-chips-bar-renderer, ytm-chips-bar, ytm-hashtag-header, #chips-wrapper { display:none !important; }';
              (document.head || document.documentElement).appendChild(styl);
            }
            function hideChrome() {
              ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-header', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container'].forEach(function(sel){
                try { var el = document.querySelector(sel); if (el) { el.style.display = 'none'; } } catch (e) { }
              });
            }
            function hideChips() {
              try {
                var quick = document.querySelectorAll('ytm-chip-cloud-renderer, ytm-chips-bar-renderer, ytm-chips-bar, ytm-hashtag-header, ytm-hashtag-header-view-model, #chips-wrapper, [class*="chip-cloud"], [class*="chips-bar"]');
                for (var i = 0; i < quick.length; i++) { quick[i].style.display = 'none'; }
              } catch (e) { }
            }
            function hideChipsFull() {
              try {
                var nodes = document.getElementsByTagName('*');
                for (var j = 0; j < nodes.length; j++) {
                  var tn = (nodes[j].tagName || '').toLowerCase();
                  if (tn.indexOf('chip') >= 0 || tn.indexOf('hashtag') >= 0) { nodes[j].style.display = 'none'; }
                }
              } catch (e) { }
            }
            function guardPip() {
              try {
                if (window.__oberegPipMode === true) {
                  var st = document.getElementById('oberegPipCss');
                  if (!st) {
                    st = document.createElement('style');
                    st.id = 'oberegPipCss';
                    (document.head || document.documentElement).appendChild(st);
                  }
                  st.textContent = 'html,body{overflow:hidden !important; background:#000 !important;} video{position:fixed !important;top:0 !important;left:0 !important;width:100vw !important;height:100vh !important;max-width:none !important;max-height:none !important;object-fit:contain !important;z-index:2147483646 !important;background:#000 !important;}';
                  var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  if (v) {
                    v.style.setProperty('position', 'fixed', 'important');
                    v.style.setProperty('top', '0', 'important');
                    v.style.setProperty('left', '0', 'important');
                    v.style.setProperty('width', '100vw', 'important');
                    v.style.setProperty('height', '100vh', 'important');
                    v.style.setProperty('max-width', 'none', 'important');
                    v.style.setProperty('max-height', 'none', 'important');
                    v.style.setProperty('object-fit', 'contain', 'important');
                    v.style.setProperty('z-index', '2147483646', 'important');
                    v.style.setProperty('background', '#000', 'important');
                  }
                  if (!window.__oberegPipHidden) { window.__oberegPipHidden = []; }
                  var node = v;
                  var guard = 0;
                  while (node && node !== document.body && guard < 20) {
                    var parent = node.parentElement;
                    if (!parent) { break; }
                    var kids = parent.children;
                    for (var ki = 0; ki < kids.length; ki++) {
                      var k = kids[ki];
                      if (k === node) { continue; }
                      if (k.contains && k.contains(v)) { continue; }
                      if (k.style && k.style.display !== 'none') {
                        k.style.setProperty('display', 'none', 'important');
                        window.__oberegPipHidden.push(k);
                      }
                    }
                    node = parent;
                    guard++;
                  }
                } else {
                  var arr = window.__oberegPipHidden || [];
                  for (var ai = 0; ai < arr.length; ai++) {
                    try { arr[ai].style.removeProperty('display'); } catch (e) { }
                  }
                  if (arr.length) { window.__oberegPipHidden = []; }
                }
              } catch (e) { }
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
                var video = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                if (video) {
                  var vr = video.getBoundingClientRect();
                  parts.push('видео:' + Math.round(vr.height) + '@' + Math.round(vr.top));
                } else {
                  parts.push('видео:нет');
                }
                try {
                  var vs = document.querySelectorAll('video');
                  var lay = [];
                  for (var vi = 0; vi < vs.length && vi < 4; vi++) {
                    var lv = vs[vi];
                    if (!lv) { continue; }
                    var lr = lv.getBoundingClientRect();
                    var fr = -1;
                    try { if (typeof lv.getVideoPlaybackQuality === 'function') { fr = lv.getVideoPlaybackQuality().totalVideoFrames; } } catch (e) { }
                    lay.push('[' + Math.round(lr.width) + 'x' + Math.round(lr.height) + ' rs' + (lv.readyState || 0) + (lv.paused ? ' п' : ' и') + ' f' + fr + ']');
                  }
                  parts.push('слои:' + vs.length + lay.join(''));
                } catch (e) { }
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
            function pulse() {
              try {
                var h = host();
                if (h && h.media && typeof window.__oberegSnapshot === 'function') {
                  h.media(window.__oberegSnapshot());
                }
              } catch (e) { }
            }
            // Видео-спасатель: если звук идёт, а кадры стоят — будим поток и пишем это в журнал.
            var videoFramesLast = -1;
            var videoStallSince = 0;
            var videoRescueCount = 0;
            var videoRescueAt = 0;
            var videoRescueCooldownUntil = 0;
            function videoWatch() {
              try {
                var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                var h = host();
                if (!v || v.paused || v.currentTime < 0.5 || window.__oberegPipMode === true) { videoFramesLast = -1; videoStallSince = 0; return; }
                if (Date.now() < videoRescueCooldownUntil) { return; }
                var frames = -1;
                try {
                  if (typeof v.getVideoPlaybackQuality === 'function') { frames = v.getVideoPlaybackQuality().totalVideoFrames; }
                  else if (typeof v.webkitDecodedFrameCount === 'number') { frames = v.webkitDecodedFrameCount; }
                } catch (e) { }
                if (frames < 0) { return; }
                if (frames !== videoFramesLast) {
                  videoFramesLast = frames;
                  videoStallSince = 0;
                  if (videoRescueCount > 0 && Date.now() - videoRescueAt > 10000) { videoRescueCount = 0; }
                  return;
                }
                if (!videoStallSince) { videoStallSince = Date.now(); return; }
                var stalledMs = Date.now() - videoStallSince;
                if (stalledMs < 6000) { return; }
                if (Date.now() - videoRescueAt < 8000) { return; }
                videoRescueAt = Date.now();
                videoRescueCount++;
                var p = document.getElementById('movie_player');
                if (videoRescueCount === 1) {
                  try { if (p && typeof p.seekTo === 'function') { p.seekTo((v.currentTime || 0) + 0.03, true); } else { v.currentTime = (v.currentTime || 0) + 0.03; } } catch (e) { }
                  if (h && h.note) { h.note('видеопоток встал (' + Math.round(stalledMs / 1000) + ' с без кадров) — микроперемотка'); }
                  return;
                }
                if (videoRescueCount === 2) {
                  try { if (p && typeof p.setPlaybackQualityRange === 'function') { p.setPlaybackQualityRange('small'); } } catch (e) { }
                  try { v.currentTime = (v.currentTime || 0) + 0.05; } catch (e) { }
                  if (h && h.note) { h.note('видеопоток всё ещё стоит — ставлю качество ниже'); }
                  return;
                }
                if (videoRescueCount === 3) {
                  if (h && h.note) { h.note('видеопоток не ожил — перезагружаю страницу'); }
                  videoRescueCooldownUntil = Date.now() + 90000;
                  try { location.reload(); } catch (e) { }
                  return;
                }
              } catch (e) { }
            }
            ensureStyle();
            hideChrome();
            hideChips();
            guardPip();
            attachSwipe();
            pinPlayer();
            setTimeout(function(){ hideChipsFull(); }, 2600);
            setTimeout(function(){ reportLayout('в4с'); }, 4000);
            setTimeout(function(){ reportLayout('в10с'); }, 10000);
            setTimeout(pulse, 900);
            setInterval(pulse, 1000);
            setInterval(function(){
              try {
                ensureStyle();
                hideChrome();
                hideChips();
                guardPip();
                attachSwipe();
                if (window.__oberegPipMode !== true) { pinPlayer(); }
                if (window.__oberegPipMode !== true && window.__oberegReflowRescue && !window.__oberegRescueBusy) {
                  try {
                    var rv = document.querySelector('video');
                    if (rv) {
                      var rtop = rv.getBoundingClientRect().top;
                      if (rtop > 100 || rtop < -80) {
                        window.__oberegRescueBusy = true;
                        window.__oberegReflowRescue('цикл');
                        setTimeout(function(){ window.__oberegRescueBusy = false; }, 1500);
                      }
                    }
                  } catch (e) { }
                }
                videoWatch();
                try {
                  // Сторож показа и размера: если активное играющее видео (или его контейнеры) скрыто
                  // либо слой «сплющился» (высота/ширина в ноль — звук идёт, картинки нет) — лечим.
                  if (window.__oberegPipMode !== true) {
                    var av = (window.__oberegVideo ? window.__oberegVideo() : null);
                    if (av && (!av.paused || (av.currentTime || 0) > 1)) {
                      var shown = false;
                      var acs = getComputedStyle(av);
                      if (acs.display === 'none' || acs.visibility === 'hidden') {
                        av.style.display = '';
                        av.style.visibility = '';
                        if (av.style.transform && av.style.transform.indexOf('translateZ(0.0001px)') >= 0) { av.style.transform = ''; }
                        shown = true;
                      }
                      var node = av.parentElement;
                      var hops = 0;
                      while (node && node !== document.body && hops < 6) {
                        try {
                          var ncs = getComputedStyle(node);
                          if (ncs.display === 'none') {
                            node.style.setProperty('display', '', 'important');
                            shown = true;
                          }
                        } catch (e) { }
                        node = node.parentElement;
                        hops++;
                      }
                      var ar = av.getBoundingClientRect();
                      if (ar.width >= 100 && ar.height >= 40) {
                        av.__oberegGoodW = Math.round(ar.width);
                        av.__oberegGoodH = Math.round(ar.height);
                      } else if (av.__oberegGoodW || av.__oberegGoodH || true) {
                        var targetH = av.__oberegGoodH || 0;
                        if (targetH < 40) {
                          var nd = av.parentElement;
                          var hh = 0;
                          while (nd && nd !== document.body && hh < 8) {
                            var nr = nd.getBoundingClientRect();
                            if (nr.height >= 100 && nr.height <= 900) { targetH = Math.round(nr.height); break; }
                            nd = nd.parentElement;
                            hh++;
                          }
                        }
                        var sized = false;
                        if (ar.height < 40 && targetH >= 40) { av.style.setProperty('height', targetH + 'px', 'important'); sized = true; }
                        if (ar.width < 40) { av.style.setProperty('width', (av.__oberegGoodW >= 100 ? av.__oberegGoodW + 'px' : '100%'), 'important'); sized = true; }
                        if (sized) {
                          av.style.setProperty('display', 'block', 'important');
                          av.__oberegForced = true;
                          shown = true;
                        }
                      }
                      if (shown) {
                        var h4 = host();
                        if (h4 && h4.note) { h4.note('видеослой был скрыт/сплющен — вернул показ (' + Math.round(ar.width) + 'x' + Math.round(ar.height) + ')'); }
                      }
                    }
                  }
                } catch (e) { }
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
            }, 500);
            return 'готово';
          } catch (e) { return 'сбой'; }
        })()
    """.trimIndent()
}
