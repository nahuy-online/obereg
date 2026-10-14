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

    /** Кто умеет обновлять кнопку play/pause в системном окне. */
    interface PipHost {
        fun applyPipParams(playing: Boolean)
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

    /** Когда страница ролика последний раз догрузилась: защита от «возвратных» перезагрузок на свежем открытии. */
    private var pageLoadedAtMs = 0L

    /** Когда последний раз возвращали поток перезагрузкой с места (общая защита от циклов). */
    private var lastRecoveryAtMs = 0L

    /** Проверка кадра уже идёт — второй параллельной быть не должно. */
    private var recoveryChecking = false

    /** Ступень диагностики вёрстки: 0 — полный тюнинг, 1 — без привязки к верху, 2 — без наших правок, 3 — + программный слой. */
    private var lastTunerLevel = 0

    /** Счётчик подряд идущих лечений и время предыдущего. */
    private var healStrikes = 0
    private var prevHealAtMs = 0L

    /** Когда последний раз писали про «тёмный экран, но кадры идут» (редкий случай). */
    private var lastCaseBNoteAtMs = 0L

    /** Сколько раз «мягкое пробуждение» не помогло подряд (после двух — сразу перезагрузка). */
    private var softHealFails = 0

    /** Экран плеера (для проверки кадра) — пока он на виду. */
    private var watchActivity: WeakReference<Activity>? = null

    /** «Пульс»: двигалась ли позиция ролика в последние секунды — признак живого воспроизведения. */
    private var lastPulsePosMs = 0L
    private var lastPulseMoveAt = 0L

    /** Сторож экрана (проверка чёрного кадра) — работает только пока экран плеера на виду. */
    private var screenWatch: Runnable? = null
    private var screenWatchHandler: Handler? = null
    private var screenVisible = false

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
                pageLoadedAtMs = android.os.SystemClock.elapsedRealtime()
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

    /**
     * Сообщить странице, видно ли приложение: в фоне проверку показа не гоняем
     * (экран выключен — кадры не выводятся, но звук играет — это не чёрный экран).
     */
    fun setAppForeground(on: Boolean) {
        val flag = if (on) "true" else "false"
        web?.evaluateJavascript(
            "(function(){ try { window.__oberegAppForeground = $flag; return 'ok'; } catch (e) { return 'сбой'; } })()",
        ) { _ -> }
    }

    /**
     * Отметить мини-плеер: на странице показ кадров в мини-окошке проверять нельзя
     * (проверка врёт, а лечение без проверки — это неожиданная перезагрузка).
     */
    fun setMiniMode(on: Boolean) {
        val flag = if (on) "true" else "false"
        web?.evaluateJavascript(
            "(function(){ try { window.__oberegMiniMode = $flag; return 'ok'; } catch (e) { return 'сбой'; } })()",
        ) { _ -> }
    }

    /** Ступень диагностики вёрстки (для поиска причины чёрного экрана). */
    fun tunerLevel(context: Context): Int =
        context.getSharedPreferences("obereg_tuner", 0).getInt("level", 0)

    fun cycleTunerLevel(context: Context): Int {
        val next = (tunerLevel(context) + 1) % 3
        context.getSharedPreferences("obereg_tuner", 0).edit().putInt("level", next).apply()
        return next
    }

    /** Пауза между лечениями: короткая и ровная — чтобы восстановление не «выдыхалось» при тестах. */
    private fun healCooldownMs(): Long = 25000L

    private fun noteHealStrike() {
        val now = android.os.SystemClock.elapsedRealtime()
        healStrikes = if (now - prevHealAtMs < 90000) minOf(healStrikes + 1, 6) else 0
        prevHealAtMs = now
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
            pageLoadedAtMs = 0L
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

    /**
     * Проверка кадра глазами экрана: берём полосу в области ролика из окна и смотрим,
     * не чёрная ли она. Ловит случай, невидимый изнутри страницы (кадры «показываются»,
     * а на экране черно). true — чёрный, false — есть картинка, null — не удалось прочитать.
     */
    private fun sampleBlack(activity: Activity, callback: (Boolean?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) { callback(null); return }
        val window = activity.window
        val view = window?.decorView
        val w = view?.width ?: 0
        val h = view?.height ?: 0
        if (window == null || w <= 0 || h <= 0) { callback(null); return }
        val rect = android.graphics.Rect(
            (w * 0.25).toInt(),
            (h * 0.12).toInt(),
            (w * 0.75).toInt(),
            (h * 0.25).toInt(),
        )
        val bitmap = android.graphics.Bitmap.createBitmap(64, 24, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, rect, bitmap, { result ->
                if (result != android.view.PixelCopy.SUCCESS) {
                    callback(null)
                    return@request
                }
                var dark = 0
                var total = 0
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val p = bitmap.getPixel(x, y)
                        val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                        total++
                        if (lum < 20) { dark++ }
                    }
                }
                callback(total > 0 && dark * 100 / total >= 98)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { callback(null) }
    }

    /** Двойной замер: «чёрный» — только если оба раза чёрно (защита от случайного кадра). */
    fun checkScreenBlack(activity: Activity, callback: (Boolean?) -> Unit) {
        sampleBlack(activity) { first ->
            if (first == false) {
                callback(false)
                return@sampleBlack
            }
            Handler(Looper.getMainLooper()).postDelayed({
                sampleBlack(activity) { second ->
                    when {
                        second == false -> callback(false)
                        first == true && second == true -> callback(true)
                        else -> callback(null)
                    }
                }
            }, 650)
        }
    }

    /** Видео сейчас «живое»: играем и позиция двигалась в последние секунды. */
    private fun positionAlive(): Boolean =
        wantPlay && lastPlaying == true &&
            android.os.SystemClock.elapsedRealtime() - lastPulseMoveAt < 4000

    /**
     * Если кадр чёрный (или не читается — когда reloadOnUnknown), вернуть поток обновлением с места.
     * Общая защита от циклов: не чаще раза в 20 секунд.
     */
    fun recoverIfBlack(activity: Activity, reason: String, reloadOnUnknown: Boolean) {
        if (!screenVisible) { return }
        if (!active || videoId.isBlank() || pipActive) { return }
        if (recoveryChecking) { return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecoveryAtMs < healCooldownMs()) { return }
        if (!positionAlive()) { return }
        // Сначала убеждаемся, что ролик уже «прогрет» (есть данные и кадры):
        // иначе первые секунды загрузки (чёрный кадр при rs0) выглядели бы как сбой.
        web?.evaluateJavascript(JS_WARM) { raw ->
            if (PlayerApi.decodeJsString(raw) != "1") { return@evaluateJavascript }
            recoveryChecking = true
            checkScreenBlack(activity) { black ->
                recoveryChecking = false
                if (black == true || (black == null && reloadOnUnknown)) {
                    val now2 = android.os.SystemClock.elapsedRealtime()
                    if (now2 - lastRecoveryAtMs < healCooldownMs()) { return@checkScreenBlack }
                    lastRecoveryAtMs = now2
                    // Отметим редкий случай: экран тёмный, хотя страница считает, что кадры идут.
                    web?.evaluateJavascript(JS_PRESENTED) { raw2 ->
                        if (PlayerApi.decodeJsString(raw2).orEmpty() == "1" && now2 - lastCaseBNoteAtMs > 60000) {
                            lastCaseBNoteAtMs = now2
                            noteJs("экран тёмный, но кадры показываются — редкий случай, лечу")
                        }
                    }
                    healNow(activity, reason)
                }
            }
        }
    }

    /** Заметка в журнал (и на стенд) со страницы. */
    private fun noteJs(text: String) {
        web?.evaluateJavascript(
            "(function(){ try { var h = window.__oberegHost; if (h && h.note) { h.note('$text'); } } catch (e) { } return 'ok'; })()",
        ) { _ -> }
    }

    /**
     * Лечение кадра: сначала пробуем «мягко» — без перезагрузки;
     * если мягкое пробуждение дважды подряд не помогло — возвращаем поток обновлением с места.
     */
    private fun healNow(activity: Activity, reason: String) {
        if (softHealFails < 2 && lastTunerLevel < 1) {
            noteJs("$reason — кадра нет — пробую мягко оживить (без перезагрузки)")
            softHeal(activity) { ok ->
                if (ok) {
                    softHealFails = 0
                    noteJs("мягкое пробуждение вернуло картинку — без перезагрузки")
                } else {
                    softHealFails++
                    noteJs("мягкое пробуждение не помогло — обновляю поток с места")
                    reloadFromPosition()
                }
            }
        } else {
            noteJs("$reason — кадра нет — обновляю поток с места")
            reloadFromPosition()
        }
    }

    /** Мягкое пробуждение: пересобрать вывод кадров без перезагрузки страницы. */
    private fun softHeal(activity: Activity, onDone: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        runCatching { web?.onPause(); web?.resumeTimers(); web?.onResume(); web?.invalidate() }
        handler.postDelayed({
            runCatching { web?.visibility = View.GONE }
            handler.postDelayed({
                runCatching { web?.visibility = View.VISIBLE; web?.invalidate() }
                handler.postDelayed({
                    runCatching {
                        web?.setLayerType(View.LAYER_TYPE_NONE, null)
                        web?.postDelayed({ runCatching { web?.setLayerType(View.LAYER_TYPE_HARDWARE, null) } }, 60)
                    }
                    web?.evaluateJavascript(JS_WAKE) { _ -> }
                    handler.postDelayed({
                        checkScreenBlack(activity) { black -> onDone(black == false) }
                    }, 700)
                }, 220)
            }, 160)
        }, 120)
    }

    /** Толчок после выхода из окна: пересобрать вывод кадров заранее, без полной процедуры. */
    fun nudgeSurface() {
        if (lastTunerLevel < 1) {
            runCatching { web?.onPause(); web?.resumeTimers(); web?.onResume(); web?.invalidate() }
        }
        web?.evaluateJavascript(JS_WAKE) { _ -> }
    }

    /** Вызов со страницы («кадры идут, но не показываются»): лечение по общим правилам. */
    private fun healFromPage() {
        val activity = watchActivity?.get()
        if (!screenVisible || activity == null) {
            // Экран плеера не на виду (мини-плеер): без честной проверки не лечим —
            // вернёмся к этому при развороте. Иначе получаются неожиданные перезагрузки.
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecoveryAtMs < healCooldownMs()) { return }
        lastRecoveryAtMs = now
        healNow(activity, "страж страницы")
    }

    /** Возврат к экрану плеера: быстро проверить кадр (после окна/мини/фона он часто чёрный). */
    fun maybeRecoverOnReturn(activity: Activity) {
        if (!active || videoId.isBlank() || pageLoadedAtMs <= 0L) { return }
        Handler(Looper.getMainLooper()).postDelayed({
            recoverIfBlack(activity, "возврат в плеер", reloadOnUnknown = true)
        }, 1500)
    }

    /** Сторож экрана: пока экран плеера на виду — раз в 6 секунд проверяем кадр. */
    fun startPlayerWatch(activity: Activity) {
        stopPlayerWatch()
        watchActivity = WeakReference(activity)
        screenVisible = true
        val handler = Handler(Looper.getMainLooper())
        val run = object : Runnable {
            override fun run() {
                recoverIfBlack(activity, "страж экрана", reloadOnUnknown = false)
                handler.postDelayed(this, 6000)
            }
        }
        screenWatch = run
        screenWatchHandler = handler
        handler.postDelayed(run, 6000)
    }

    fun stopPlayerWatch() {
        screenVisible = false
        screenWatchHandler?.removeCallbacksAndMessages(null)
        screenWatchHandler = null
        screenWatch = null
        watchActivity = null
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
        val pipHost = pipActivity?.get() as? PipHost ?: return
        web?.evaluateJavascript(JS_PAUSED) { raw ->
            val playing = PlayerApi.decodeJsString(raw) != "1"
            Handler(Looper.getMainLooper()).post { pipHost.applyPipParams(playing) }
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
        if (lastTunerLevel >= 1) { return }
        web?.evaluateJavascript(APPLY_PIP_JS.replace("__ON__", if (on) "true" else "false")) { _ -> }
    }

    fun applySpeed(context: Context, rate: Double) {
        speed = rate
        AppSettings.setPlayerSpeed(context, rate.toFloat())
        web?.evaluateJavascript(
            "window.__oberegRate = $rate; (function(){var v=document.querySelector('video'); if(v){v.playbackRate=$rate;}})(); 'ok'",
        ) { _ -> }
    }

    fun reload() {
        pageLoadedAtMs = 0L
        lastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
        web?.reload()
    }

    /** Обновить страницу с текущего места — проверенный способ вернуть видеопоток. */
    fun reloadFromPosition() {
        val sec = (lastPositionMs / 1000).coerceAtLeast(0L)
        val url = if (videoId.startsWith("http")) {
            // Отладочный адрес (стенд): возвращаем как есть, с меткой места.
            videoId + (if (videoId.contains("?")) "&" else "?") + "t=${sec}s"
        } else {
            val id = PlaybackCodec.sanitizeVideoId(videoId)
            if (id.isBlank()) {
                DiagLog.add("движок-плеер: обновление с места недоступно")
                return
            }
            "https://www.youtube.com/watch?v=$id&hl=ru&t=${sec}s"
        }
        DiagLog.add("движок-плеер: обновляю страницу с места ${sec}с")
        DiagLog.add("движок-плеер: аудио — " + audioAudit())
        wantPlay = true
        lastPlaying = null
        pageLoadedAtMs = 0L
        lastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
        // Согласуем защиты: страничные сторожа не должны перезагружать повторно после нашего лечения.
        web?.evaluateJavascript(
            "(function(){ try { sessionStorage.setItem('oberegAutoReloadAt', String(Date.now())); } catch (e) { } return 'ok'; })()",
        ) { _ -> }
        Handler(Looper.getMainLooper()).postDelayed({ web?.loadUrl(url) }, 60)
    }

    /** Ручное «оживление» видео (кнопка в меню): встряхнуть и проверить кадр по-настоящему; чёрный — обновляем с места. */
    fun reviveVideo(activity: Activity) {
        DiagLog.add("движок-плеер: оживляю видео вручную")
        DiagLog.add("движок-плеер: аудио — " + audioAudit())
        web?.evaluateJavascript(JS_REVIVE) { _ -> }
        Handler(Looper.getMainLooper()).postDelayed({
            checkScreenBlack(activity) { black ->
                if (black == false) {
                    DiagLog.add("движок-плеер: оживление — картинка на месте")
                } else {
                    DiagLog.add("движок-плеер: оживление — кадра нет — лечу")
                    lastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
                    healNow(activity, "оживление")
                }
            }
        }, 1400)
    }

    /** Кто сейчас «играет» на телефоне: наш процесс или чужой (для звука без картинки). */
    private fun audioAudit(): String = runCatching {
        val am = appContext?.getSystemService(android.media.AudioManager::class.java)
        val music = am?.isMusicActive == true
        val list = am?.activePlaybackConfigurations ?: emptyList()
        val myUid = android.os.Process.myUid()
        var mine = 0
        var others = 0
        var unknown = 0
        for (cfg in list) {
            val uid = runCatching {
                val method = cfg.javaClass.getMethod("getClientUid")
                (method.invoke(cfg) as? Int) ?: -2
            }.getOrElse { -2 }
            when {
                uid == myUid -> mine++
                uid == -2 -> unknown++
                else -> others++
            }
        }
        "музыка:${if (music) 1 else 0} игроков:${list.size} наших:$mine чужих:$others" +
            (if (unknown > 0) " н/д:$unknown" else "")
    }.getOrElse { "аудио-аудит недоступен" }

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
        if (pulse.positionMs != lastPulsePosMs) {
            lastPulsePosMs = pulse.positionMs
            lastPulseMoveAt = android.os.SystemClock.elapsedRealtime()
        }
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

        /** Аудит экрана по кнопке «Оживить»: что за медиа, что сверху, что показывает плеер. */
        @android.webkit.JavascriptInterface
        fun audit(text: String) {
            val trimmed = text.take(1100)
            Handler(Looper.getMainLooper()).post {
                DiagLog.add("движок-плеер: аудит — $trimmed")
            }
        }

        /** Со страницы: кадры идут, но не показываются — лечить по общим правилам. */
        @android.webkit.JavascriptInterface
        fun heal() {
            Handler(Looper.getMainLooper()).post { healFromPage() }
        }
    }

    private fun injectTuning(view: WebView?) {
        view ?: return
        val safeId = PlaybackCodec.sanitizeVideoId(videoId)
        if (safeId.isNotBlank()) {
            view.evaluateJavascript("try{ window.__oberegVideoId = '$safeId'; }catch(e){} 'ok'") { _ -> }
        }
        pushWantToJs()
        val level = tunerLevel(view.context)
        lastTunerLevel = level
        runCatching {
            view.setLayerType(
                if (level >= 3) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE,
                null,
            )
        }
        val script = TUNING
            .replace("__RATE__", speed.toString())
            .replace("__LEVEL__", level.toString())
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

    /** «Прогрето» ли видео: есть данные и уже декодированные кадры, не на паузе. */
    private val JS_WARM = """
        (function(){
          try {
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (!v || v.paused) { return '0'; }
            if ((v.readyState || 0) < 2) { return '0'; }
            var fr = -1;
            try { if (typeof v.getVideoPlaybackQuality === 'function') { fr = v.getVideoPlaybackQuality().totalVideoFrames; } } catch (e) { }
            if (fr === 0) { return '0'; }
            return '1';
          } catch (e) { return '0'; }
        })()
    """.trimIndent()

    /** Есть ли картинка: спрашиваем страж показа. '1' — есть, 'п' — пауза, '0' — нет, 'н' — нет видео. */
    private val JS_PRESENTED = """
        (function(){
          try {
            var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
            if (!v) { return 'н'; }
            if (v.paused) { return 'п'; }
            if (typeof window.__oberegPresented === 'function') { return window.__oberegPresented() ? '1' : '0'; }
            return '0';
          } catch (e) { return '0'; }
        })()
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
            try { window.scrollTo(0, 0); } catch (e) { }
            try { if (typeof window.__oberegUnforce === 'function') { window.__oberegUnforce(); } } catch (e) { }
            try { if (typeof window.__oberegNudge === 'function') { window.__oberegNudge('пробуждение'); } } catch (e) { }
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
              var keys = ['position','top','left','width','height','max-width','max-height','object-fit','z-index','background'];
              if (force) {
                // Запоминаем ТОЧНЫЕ стили страницы — вернём их при выходе из окна (нельзя терять ни байта).
                if (!v.__oberegStyleSaved) {
                  v.__oberegStyleSaved = {};
                  for (var i = 0; i < keys.length; i++) {
                    try {
                      v.__oberegStyleSaved[keys[i]] = {
                        value: v.style.getPropertyValue(keys[i]),
                        priority: v.style.getPropertyPriority(keys[i])
                      };
                    } catch (e) { }
                  }
                }
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
                var saved = v.__oberegStyleSaved;
                for (var j = 0; j < keys.length; j++) {
                  try {
                    var item = saved ? saved[keys[j]] : null;
                    if (item && item.value) {
                      v.style.setProperty(keys[j], item.value, item.priority || '');
                    } else {
                      v.style.removeProperty(keys[j]);
                    }
                  } catch (e) { }
                }
                v.__oberegStyleSaved = null;
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
                    try {
                      window.__oberegPipHidden.push({ el: k, value: k.style.getPropertyValue('display'), priority: k.style.getPropertyPriority('display') });
                    } catch (e) {
                      window.__oberegPipHidden.push({ el: k, value: '', priority: '' });
                    }
                    k.style.setProperty('display', 'none', 'important');
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
                try {
                  var item = arr[i];
                  if (item && item.el) {
                    if (item.value) { item.el.style.setProperty('display', item.value, item.priority || ''); }
                    else { item.el.style.removeProperty('display'); }
                  } else if (item) {
                    item.style.removeProperty('display');
                  }
                } catch (e) { }
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
              if (typeof window.__oberegAppForeground !== 'boolean') { window.__oberegAppForeground = true; }
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
                    var fresh = (now - (window.__oberegLastGesture || 0)) < 800;
                    var gesture = now < (window.__oberegGestureGuardUntil || 0);
                    // Любое свежее касание — действие человека (в т.ч. тап по самому видео —
                    // обычный способ паузы). Служебные попытки паузы идут без касаний — их и блокируем.
                    var userIntent = (act || fresh) && !gesture;
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
            // Ручное «оживление» из меню плеера: вернуть стили страницы, снять скрытие, вернуть размер.
            if (!window.__oberegRevive) {
              window.__oberegRevive = function () {
                try {
                  var a = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                  if (!a) { return 'нет видео'; }
                  var wantA = (window.__oberegWantPlay !== false);
                  var wasBadA = (wantA && (a.paused || (a.readyState || 0) <= 1)) ? '1' : '0';
                  try {
                    var s = a.__oberegStyleSaved;
                    if (s) {
                      ['position','top','left','width','height','max-width','max-height','object-fit','z-index','background','display'].forEach(function (k) {
                        try {
                          if (s[k] && s[k].value) { a.style.setProperty(k, s[k].value, s[k].priority || ''); }
                          else { a.style.removeProperty(k); }
                        } catch (e) { }
                      });
                      a.__oberegStyleSaved = null;
                      a.__oberegForced = false;
                    }
                  } catch (e) { }
                  try { a.style.visibility = ''; } catch (e) { }
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
                  // Настоящий цикл пауза/игра: заставляет медиа-конвейер заново вывести картинку.
                  try {
                    window.__oberegAllowPause = true;
                    a.pause();
                    setTimeout(function () {
                      try { window.__oberegAllowPause = false; } catch (e) { }
                      try { var pr2 = a.play(); if (pr2 && pr2.catch) { pr2.catch(function () { }); } } catch (e) { }
                    }, 180);
                  } catch (e) { }
                  // Заодно — аудит экрана в журнал (что за медиа, что сверху, что говорит плеер).
                  try { var hh = window.__oberegHost; if (hh && hh.audit) { hh.audit(window.__oberegAudit()); } } catch (e) { }
                  return wasBadA + ' ' + (a.paused ? 'п' : 'и') + ' rs' + (a.readyState || 0);
                } catch (e) { return 'сбой'; }
              };
            }
            // Аудит экрана: все медиа-элементы, что лежит поверх плеера, состояние плеера.
            if (!window.__oberegAudit) {
              window.__oberegAudit = function () {
                try {
                  var parts = [];
                  var media = document.querySelectorAll('video,audio');
                  parts.push('медиа:' + media.length);
                  for (var i = 0; i < media.length && i < 3; i++) {
                    var m = media[i];
                    if (!m) { continue; }
                    var r = {};
                    try { r = m.getBoundingClientRect(); } catch (e) { }
                    var fr = -1;
                    try { if (typeof m.getVideoPlaybackQuality === 'function') { fr = m.getVideoPlaybackQuality().totalVideoFrames; } } catch (e) { }
                    parts.push('[' + i + ':' + m.tagName + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.top) + ' rs' + (m.readyState || 0) + (m.paused ? 'п' : 'и') + ' f' + fr + ' vw' + (m.videoWidth || 0) + 'x' + (m.videoHeight || 0) + (m.muted ? ' мут' : '') + ']');
                  }
                  var p = document.getElementById('movie_player');
                  var st = -9;
                  try { if (p && typeof p.getPlayerState === 'function') { st = p.getPlayerState(); } } catch (e) { }
                  parts.push('плеер:' + st);
                  var av = (window.__oberegVideo ? window.__oberegVideo() : null);
                  if (av) {
                    try {
                      var ar = av.getBoundingClientRect();
                      var cx = Math.round(ar.left + ar.width / 2);
                      var cy = Math.round(ar.top + ar.height / 2);
                      var t1 = document.elementFromPoint(cx, cy);
                      var t2 = document.elementFromPoint(cx, Math.max(0, Math.round(ar.top + ar.height * 0.25)));
                      parts.push('центр:' + (t1 ? (t1.tagName + '.' + String(t1.className || '').slice(0, 36)) : '?') + '|верх:' + (t2 ? (t2.tagName + '.' + String(t2.className || '').slice(0, 36)) : '?'));
                      var acs = getComputedStyle(av);
                      parts.push('вид:' + acs.display + '/' + acs.visibility + '/op' + (acs.opacity || '?') + ' pos:' + (acs.position || '?'));
                    } catch (e) { }
                  }
                  return parts.join(' ; ');
                } catch (e) { return 'сбой аудита'; }
              };
            }
            // Перезагрузка страницы с текущего места — проверенный способ вернуть видеопоток.
            if (!window.__oberegReloadFromPosition) {
              window.__oberegReloadFromPosition = function (reason) {
                try {
                  var id = window.__oberegVideoId || '';
                  if (!id) { return 'нет id'; }
                  var av2 = (window.__oberegVideo ? window.__oberegVideo() : null);
                  var sec2 = 0;
                  if (av2) { sec2 = Math.max(0, Math.round(av2.currentTime || 0)); }
                  try { var h2 = window.__oberegHost; if (h2 && h2.note) { h2.note('видео не выводится на экран — обновляю страницу с места ' + sec2 + 'с'); } } catch (e) { }
                  location.href = 'https://www.youtube.com/watch?v=' + id + '&hl=ru&t=' + sec2 + 's';
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
                        var s = v.__oberegStyleSaved;
                        ['height','width','display'].forEach(function (k) {
                          try {
                            if (s && s[k] && s[k].value) { v.style.setProperty(k, s[k].value, s[k].priority || ''); }
                            else { v.style.removeProperty(k); }
                          } catch (e) { }
                        });
                      } catch (e) { }
                      v.__oberegStyleSaved = null;
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
            window.__oberegLevel = __LEVEL__;
            var LEVEL = __LEVEL__;
            window.__oberegLoadedAt = Date.now();
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
              ['ytm-header-bar', 'ytm-masthead', 'ytm-mobile-topbar-header', 'ytm-mobile-topbar-renderer', 'ytm-app-header', '#masthead-container', 'header.ghost-header', '.ghost-header'].forEach(function(sel){
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
            function hideTopPlate() {
              try {
                if (LEVEL >= 2) { return; }
                var els = document.querySelectorAll('header.ghost-header, .ghost-header');
                for (var ti = 0; ti < els.length; ti++) {
                  var el = els[ti];
                  if (el.style && el.style.display !== 'none') {
                    el.style.setProperty('display', 'none', 'important');
                    try {
                      var pr = el.parentElement;
                      var ptn = pr && pr.tagName ? pr.tagName.toLowerCase() : '';
                      if (pr && ptn !== 'ytm-app' && pr !== document.body && pr !== document.documentElement) {
                        var rp = pr.getBoundingClientRect();
                        if (rp.height <= 160) { pr.style.setProperty('display', 'none', 'important'); }
                      }
                    } catch (e) { }
                    var hp = host();
                    if (hp && hp.note) { hp.note('убрал верхнюю плашку: ' + (el.tagName || '').toLowerCase() + '.' + String(el.className || '').slice(0, 40)); }
                  }
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
                    try {
                      var item = arr[ai];
                      if (item && item.el) {
                        if (item.value) { item.el.style.setProperty('display', item.value, item.priority || ''); }
                        else { item.el.style.removeProperty('display'); }
                      } else if (item) {
                        item.style.removeProperty('display');
                      }
                    } catch (e) { }
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
            // Сторож «показа»: кадры декодируются, но на экран не выводятся (чёрный экран со звуком).
            var presEl = null;
            var presAt = 0;
            var presBindAt = 0;
            var presCount = 0;
            var presFramesAt = -1;
            var presDisabled = false;
            function autoReload(noteText, reason) {
              try {
                var last = 0;
                var cnt = 0;
                try { last = Number(sessionStorage.getItem('oberegAutoReloadAt') || 0); cnt = Number(sessionStorage.getItem('oberegAutoReloadCount') || 0); } catch (e) { }
                if (Date.now() - last < 90000) { return false; }
                if (cnt >= 12) { return false; }
                try { sessionStorage.setItem('oberegAutoReloadAt', String(Date.now())); sessionStorage.setItem('oberegAutoReloadCount', String(cnt + 1)); } catch (e) { }
                if (noteText) { try { var h = host(); if (h && h.note) { h.note(noteText); } } catch (e) { } }
                try {
                  var hh = host();
                  if (hh && hh.heal) { hh.heal(); return true; }
                } catch (e) { }
                if (typeof window.__oberegReloadFromPosition === 'function') { window.__oberegReloadFromPosition(reason || 'авто'); return true; }
                return false;
              } catch (e) { return false; }
            }
            window.__oberegPresented = function () { try { return (Date.now() - presAt) < 2500; } catch (e) { return true; } };
            function bindPresentation() {
              try {
                if (presDisabled) { return; }
                var v = (window.__oberegVideo ? window.__oberegVideo() : null);
                if (!v || typeof v.requestVideoFrameCallback !== 'function') { return; }
                if (v === presEl) { return; }
                presEl = v;
                presAt = Date.now();
                presBindAt = Date.now();
                presCount = 0;
                presFramesAt = -1;
                var loop = function (now, meta) {
                  try {
                    if (window.__oberegVideo && window.__oberegVideo() !== presEl) { presEl = null; return; }
                    presAt = Date.now();
                    presCount++;
                  } catch (e) { }
                  try { if (presEl && presEl.requestVideoFrameCallback) { presEl.requestVideoFrameCallback(loop); } } catch (e) { }
                };
                try { v.requestVideoFrameCallback(loop); } catch (e) { }
              } catch (e) { }
            }
            var stuckSince = 0;
            function watchStuck() {
              try {
                var v = (window.__oberegVideo ? window.__oberegVideo() : null);
                if (!v || window.__oberegPipMode === true || window.__oberegMiniMode === true || window.__oberegAppForeground === false) { stuckSince = 0; return; }
                // Первые 20 секунд после загрузки страницы — не сбой, а сеть/буферизация.
                if (Date.now() - (window.__oberegLoadedAt || 0) < 20000) { stuckSince = 0; return; }
                if (window.__oberegWantPlay === false) { stuckSince = 0; return; }
                var ct = 0;
                try { ct = v.currentTime || 0; } catch (e) { }
                if (ct < 3) { stuckSince = 0; return; }
                var bad = false;
                try { bad = (v.paused || (v.readyState || 0) <= 1); } catch (e) { }
                if (!bad) { stuckSince = 0; return; }
                if (!stuckSince) { stuckSince = Date.now(); return; }
                if (Date.now() - stuckSince < 9000) { return; }
                if (!autoReload('ролик стоит без данных (' + (v.paused ? 'пауза' : 'загрузка') + ', rs' + (v.readyState || 0) + ') — обновляю с места ' + Math.round(ct) + 'с', 'авто-стояние')) { stuckSince = Date.now(); return; }
              } catch (e) { }
            }
            function watchPresentation() {
              try {
                if (presDisabled) { return; }
                bindPresentation();
                if (!presEl) { return; }
                var v = presEl;
                // В фоне показ не проверяем (звук без картинки там — норма); в «окне» — проверяем: оно видно.
                if (!v || v.paused || window.__oberegMiniMode === true || (window.__oberegAppForeground === false && window.__oberegPipMode !== true)) {
                  presAt = Date.now();
                  return;
                }
                // Совсем нет обратных вызовов о показе — значит, среда не поддерживает проверку: отключаемся.
                if (presCount === 0 && Date.now() - presBindAt > 12000) { presDisabled = true; return; }
                if (Date.now() - presAt < 3000) { return; }
                // Кадры декодируются? Если стоят или их ещё нет — это загрузка/буферизация, не наш случай.
                var q = null;
                try { q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null; } catch (e) { }
                var dec = q ? q.totalVideoFrames : -1;
                if (dec <= 0) { presAt = Date.now(); return; }
                if (dec <= presFramesAt) { presAt = Date.now(); return; }
                presFramesAt = dec;
                // Кадры идут, а на экран не выводятся — запускаем лечение (мягкое, потом перезагрузка).
                if (!autoReload('кадры идут, но не показываются — лечу (авто)', 'авто-кадры')) { presAt = Date.now(); }
              } catch (e) { }
            }
            function videoWatch() {
              try {
                var v = (window.__oberegVideo ? window.__oberegVideo() : document.querySelector('video'));
                var h = host();
                if (!v || v.paused || v.currentTime < 0.5 || window.__oberegPipMode === true || window.__oberegMiniMode === true) { videoFramesLast = -1; videoStallSince = 0; return; }
                // Первые 12 секунд после загрузки страницы — это ещё загрузка, а не стояние потока.
                if (Date.now() - (window.__oberegLoadedAt || 0) < 12000) { videoFramesLast = -1; videoStallSince = 0; return; }
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
                  videoRescueCooldownUntil = Date.now() + 90000;
                  autoReload('видеопоток не ожил — лечу', 'авто-поток');
                  return;
                }
              } catch (e) { }
            }
            if (LEVEL < 2) {
              ensureStyle();
              hideChrome();
              hideChips();
              hideTopPlate();
            }
            guardPip();
            attachSwipe();
            setTimeout(function(){ if (LEVEL < 2) { hideChipsFull(); } }, 2600);
            setTimeout(function(){ reportLayout('в4с'); }, 4000);
            setTimeout(function(){ reportLayout('в10с'); }, 10000);
            setTimeout(pulse, 900);
            setInterval(pulse, 1000);
            setInterval(function(){
              try {
                if (LEVEL < 2) {
                  ensureStyle();
                  hideChrome();
                  hideChips();
                  hideTopPlate();
                }
                guardPip();
                attachSwipe();
                videoWatch();
                watchPresentation();
                watchStuck();
                try {
                  // Сторож показа и размера отключён: его правки вёрстки убивали картинку на переходах
                  // (проверено уровнями: без этих подтяжек сворачивание/разворот держат видеопоток).
                  if (false) {
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
                      } else {
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
                        if (!av.__oberegStyleSaved) {
                          av.__oberegStyleSaved = {};
                          ['height','width','display'].forEach(function (k) {
                            try { av.__oberegStyleSaved[k] = { value: av.style.getPropertyValue(k), priority: av.style.getPropertyPriority(k) }; } catch (e) { }
                          });
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
