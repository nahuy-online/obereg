package app.obereg.player

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
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
 * Оболочка Obereg: Главная (лента сервиса), Shorts, Подписки, Библиотека.
 *
 * Данные лент берём **из браузерного движка** (невидимый WebView): там согласие и вход
 * уже пройдены — именно поэтому экран сервиса показывает те же ленты. Если движок данные
 * не отдал (страница ещё грузится, разметка другая), включается резервный путь —
 * отдельный запрос страницы с собранными cookie. Разбор делает ядро, и в готовый список
 * вставляется наша рекомендация из настроек (третьей позицией по умолчанию).
 *
 * Библиотека — локальная: недавние ролики храним у себя и открываем тем же плеером.
 * Shorts — пока заглушка, вертикальные ленты впереди.
 */
class FeedActivity : AppCompatActivity(), PlayerSession.Host, PlayerSession.PipHost {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private lateinit var shortsView: TextView
    private lateinit var playlistsPill: Button
    private lateinit var miniBar: android.widget.LinearLayout
    private lateinit var miniHolder: android.widget.FrameLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniClose: TextView
    private var items: List<FeedItem> = emptyList()
    private var playlists: List<FeedCodec.PlaylistEntry> = emptyList()
    private var mode: String = MODE_RECOMMENDED
    private var tab: String = TAB_MAIN
    private var pageHandled = false

    /** Режим плеера прямо в оболочке: одно окно приложения, без переездов окон. */
    private var playerMode = false
    private var lastCollapseAt = 0L
    private lateinit var playerPane: android.view.View
    private lateinit var engineHolder: android.widget.FrameLayout
    private lateinit var engineStatus: TextView

    /** Фоны панели плеера до ухода в мини-вид (после — возвращаем). */
    private var paneBgSaved: android.graphics.drawable.Drawable? = null
    private var holderBgSaved: android.graphics.drawable.Drawable? = null

    /** Одна повторная попытка спросить данные у движка: страница иногда достраивается позже. */
    private var engineRetried = false

    /** Оболочка следит, когда ролик закрыт (например, кнопкой в медиа-уведомлении). */
    private val releaseWatcher: () -> Unit = { runOnUiThread { updateMini() } }

    /** Сторож ленты: если на скрытой странице что-то играет — глушим и пишем в журнал. */
    private val strayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStrayLogAt = 0L
    private val strayWatch = object : Runnable {
        override fun run() {
            runCatching {
                webView.evaluateJavascript(
                    "(function(){try{var m=document.querySelectorAll('video,audio');var act=0;var info='';" +
                        "for(var i=0;i<m.length&&i<2;i++){var x=m[i];if(!x.paused&&(x.currentTime||0)>0.5){act++;try{x.pause();}catch(e){}}info+='['+i+':'+x.tagName+(x.paused?'п':'и')+' rs'+x.readyState+']';}" +
                        "return m.length+'/'+act+' '+info+' '+String(location.href).slice(0,64);}catch(e){return 'сбой';}})()",
                ) { raw ->
                    val answer = PlayerApi.decodeJsString(raw).orEmpty()
                    val act = answer.substringAfter('/', "0/").substringBefore(' ').toIntOrNull() ?: 0
                    if (act > 0) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastStrayLogAt > 30_000) {
                            lastStrayLogAt = now
                            DiagLog.add("лента: остановил чужие медиа: $answer")
                        }
                    }
                }
            }
            strayHandler.postDelayed(this, 8_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed)

        listView = findViewById(R.id.feedList)
        statusView = findViewById(R.id.feedStatus)
        webView = findViewById(R.id.feedWebView)
        shortsView = findViewById(R.id.shortsStub)
        playlistsPill = findViewById(R.id.btnLibPlaylists)
        miniBar = findViewById(R.id.miniPlayerBar)
        miniHolder = findViewById(R.id.miniPlayerHolder)
        miniTitle = findViewById(R.id.miniPlayerTitle)
        miniSub = findViewById(R.id.miniPlayerSub)
        miniPlay = findViewById(R.id.btnMiniPlay)
        miniClose = findViewById(R.id.btnMiniClose)
        miniPlay.setOnClickListener { PlayerSession.togglePlayPause() }
        miniClose.setOnClickListener {
            PlayerSession.release()
            updateMini()
        }
        PlayerSession.onReleased = releaseWatcher
        playerPane = findViewById(R.id.playerPane)
        engineHolder = findViewById(R.id.engineHolder)
        engineStatus = findViewById(R.id.engineStatus)
        PlayerSession.loadQueue(this)
        strayHandler.postDelayed(strayWatch, 9_000)
        miniBar.setOnClickListener { expandPlayer() }

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

        // Шапка: поиск, уведомления, отчёт, профиль.
        findViewById<ImageButton>(R.id.btnHeadSearch).setOnClickListener {
            startActivity(android.content.Intent(this, YouTubeActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnHeadBell).setOnClickListener {
            Toast.makeText(this, "Уведомлений пока нет", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btnHeadReport).setOnClickListener { copyReport() }
        findViewById<ImageButton>(R.id.btnHeadProfile).setOnClickListener {
            startActivity(android.content.Intent(this, ProfileActivity::class.java))
        }

        // Нижняя навигация.
        findViewById<Button>(R.id.navMain).setOnClickListener { showTab(TAB_MAIN) }
        findViewById<Button>(R.id.navShorts).setOnClickListener { showTab(TAB_SHORTS) }
        findViewById<Button>(R.id.navSubs).setOnClickListener { showTab(TAB_SUBS) }
        findViewById<Button>(R.id.navLib).setOnClickListener { showTab(TAB_LIB) }
        playlistsPill.setOnClickListener { load(MODE_PLAYLISTS) }
        findViewById<Button>(R.id.btnLibLogin).setOnClickListener { openLogin() }

        listView.itemsCanFocus = false
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val it0 = items.getOrNull(position)
            if (it0 == null || it0.videoId.length != 11) {
                false
            } else {
                val vid = it0.videoId
                val ttl = it0.title.ifEmpty { vid }
                android.app.AlertDialog.Builder(this)
                    .setTitle(ttl.take(80))
                    .setItems(arrayOf("+ В очередь", "Скопировать ссылку")) { _, which ->
                        when (which) {
                            0 -> {
                                val n = PlayerSession.addToQueue(this, vid, ttl)
                                android.widget.Toast.makeText(this, "В очередь: " + ttl.take(40) + " (всего " + n + ")", android.widget.Toast.LENGTH_SHORT).show()
                                DiagLog.add("лента: в очередь — " + vid + " (всего " + n + ")")
                            }
                            1 -> {
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Obereg", "https://youtu.be/" + vid))
                                android.widget.Toast.makeText(this, "Ссылка скопирована", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Закрыть", null)
                    .show()
                true
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            when {
                mode == MODE_PLAYLISTS -> playlists.getOrNull(position)?.let(::openPlaylist)
                else -> items.getOrNull(position)?.let(::openItem)
            }
        }

        showTab(TAB_MAIN)

        val wantedId = intent.getStringExtra("videoId")
        if (!wantedId.isNullOrBlank()) {
            playerPane.post { showPlayerPane(wantedId, intent.getStringExtra("title") ?: "") }
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerSession.setAppForeground(true)
        if (tab == TAB_LIB) {
            findViewById<Button>(R.id.btnLibLogin).text =
                if (loggedIn()) "Вход выполнен ✓" else "Войти в аккаунт"
        }
        if (playerMode) {
            PlayerSession.attachTo(engineHolder)
            PlayerSession.startPlayerWatch(this)
            PlayerSession.maybeRecoverOnReturn(this)
        } else {
            updateMini()
        }
    }

    override fun onPause() {
        super.onPause()
        if (playerMode) {
            PlayerSession.stopPlayerWatch()
        }
        PlayerSession.setAppForeground(false)
        // Мини-плеер продолжает играть, когда приложение уходит в фон.
        if (PlayerSession.active) {
            PlayerSession.keepAlive()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra("videoId")
        if (!id.isNullOrBlank()) {
            playerPane.post { showPlayerPane(id, intent.getStringExtra("title") ?: "") }
        }
    }

    override fun onBackPressed() {
        if (playerMode) {
            collapsePlayer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        PlayerSession.stopPlayerWatch()
        if (PlayerSession.onReleased === releaseWatcher) {
            PlayerSession.onReleased = null
        }
        strayHandler.removeCallbacks(strayWatch)
        webView.destroy()
        super.onDestroy()
    }

    /** Мини-плеер: показываем, пока ролик жив и не ушёл в системное окно. */
    private fun updateMini() {
        if (playerMode) {
            miniBar.visibility = android.view.View.GONE
            return
        }
        if (PlayerSession.active && !PlayerSession.pipActive) {
            miniBar.visibility = android.view.View.VISIBLE
            // WebView остаётся в панели (мини-вид «просвечивает» её область) — не переносим.
            // Поверх мини-видео — «крышка»: тап по нему разворачивает плеер,
            // а не уходит нечаянно в страницу (оттуда раньше прилетала пауза).
            if (miniHolder.findViewWithTag<android.view.View>("mini-cover") == null) {
                val cover = android.view.View(this)
                cover.tag = "mini-cover"
                cover.isClickable = true
                cover.contentDescription = "Развернуть плеер"
                cover.setOnClickListener { expandPlayer() }
                miniHolder.addView(
                    cover,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            miniTitle.text = PlayerSession.title.ifBlank { PlayerSession.videoId }
            miniSub.text = "играет в мини-плеере"
            miniPlay.postDelayed(playStateCheck, 900)
        } else {
            miniBar.visibility = android.view.View.GONE
        }
    }

    private val playStateCheck = object : Runnable {
        override fun run() {
            if (miniBar.visibility != android.view.View.VISIBLE) {
                return
            }
            PlayerSession.videoPaused { paused ->
                runOnUiThread {
                    if (miniBar.visibility == android.view.View.VISIBLE) {
                        miniPlay.text = if (paused) "▶" else "⏸"
                    }
                }
            }
            miniPlay.postDelayed(this, 2500)
        }
    }

    /**
     * Открыть ролик прямо в оболочке — одно окно приложения, без пересоздания окон:
     * именно переезды между окнами рвали видеослой и давали чёрный экран.
     */
    private fun showPlayerPane(videoId: String, title: String) {
        if (videoId.isBlank()) { return }
        playerMode = true
        PlayerSession.markExpand()
        PlayerSession.closeStalePip()
        playerPane.visibility = android.view.View.VISIBLE
        miniBar.visibility = android.view.View.GONE
        PlayerSession.setMiniMode(false)
        exitMiniView()
        PlayerSession.host = this
        PlayerSession.lastStatus = { text -> runOnUiThread { engineStatus.text = text } }
        PlayerSession.panicResetPip()
        PlayerSession.open(this, videoId, title)
        PlayerSession.attachTo(engineHolder)
        PlayerSession.setAppForeground(true)
        PlayerSession.startPlayerWatch(this)
        PlayerSession.maybeRecoverOnReturn(this)
    }

    /** Свернуть в мини-плеер: экран прячется, ролик переезжает вниз оболочки. */
    /** Включает мини-вид: WebView остаётся на месте и не меняет размер (от переноса
     *  и уменьшения на этом телефоне пропадала картинка) — страница сама усыхает
     *  в прямоугольник миниатюры, а обрезка вывода показывает ровно этот прямоугольник. */
    private fun applyMiniView() {
        engineStatus.visibility = android.view.View.GONE
        if (paneBgSaved == null) {
            paneBgSaved = playerPane.background ?: android.graphics.drawable.ColorDrawable(0xFF0B0D11.toInt())
            holderBgSaved = engineHolder.background ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)
        }
        playerPane.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        engineHolder.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val wv = PlayerSession.attachedView()
        if (wv == null) {
            playerPane.visibility = android.view.View.GONE
            return
        }
        playerPane.visibility = android.view.View.VISIBLE
        playerPane.post {
            if (playerMode || PlayerSession.attachedView() !== wv) { return@post }
            val hv = IntArray(2)
            miniHolder.getLocationOnScreen(hv)
            val wvLoc = IntArray(2)
            wv.getLocationOnScreen(wvLoc)
            val wpx = wv.width
            if (wpx <= 0 || miniHolder.width <= 0) { return@post }
            val s = miniHolder.width.toFloat() / wpx.toFloat()
            val dens = resources.displayMetrics.density
            val tx = (hv[0] - wvLoc[0]).toFloat() / dens
            val ty = (hv[1] - wvLoc[1]).toFloat() / dens
            wv.clipBounds = android.graphics.Rect(
                hv[0] - wvLoc[0],
                hv[1] - wvLoc[1],
                hv[0] - wvLoc[0] + miniHolder.width,
                hv[1] - wvLoc[1] + miniHolder.height,
            )
            DiagLog.add("движок-плеер: мини — окно ${miniHolder.width}x${miniHolder.height}, масштаб " + String.format(java.util.Locale.US, "%.3f", s))
            PlayerSession.enterMiniView(s, tx, ty)
            PlayerSession.watchMiniPicture()
        }
    }

    /** Возвращает обычный вид плеера: снимаем мини-усыхание и обрезку, показываем панель. */
    private fun exitMiniView() {
        PlayerSession.exitMiniView()
        PlayerSession.attachedView()?.clipBounds = null
        engineStatus.visibility = android.view.View.VISIBLE
        restorePaneBackgrounds()
        PlayerSession.panicResetPip()
        DiagLog.add("движок-плеер: мини — фон панели возвращён")
    }

    /** Возврат фонов панели: после мини-вида прозрачность не остаётся никогда. */
    private fun restorePaneBackgrounds() {
        playerPane.background = paneBgSaved ?: android.graphics.drawable.ColorDrawable(0xFF0B0D11.toInt())
        engineHolder.background = holderBgSaved ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)
    }

    private fun collapsePlayer() {
        if (!playerMode) { return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCollapseAt < 1200) { return }
        lastCollapseAt = now
        DiagLog.add("движок-плеер: сворачиваю в мини-плеер")
        playerMode = false
        PlayerSession.captureWantPlay()
        PlayerSession.scheduleResumeAttempts()
        PlayerSession.stopPlayerWatch()
        PlayerSession.lastStatus = null
        PlayerSession.setMiniMode(true)
        // Мини-вид: WebView не переносим и не уменьшаем (от этого пропадала картинка).
        // Сначала показываем полосу, затем «усыхаем» страницу в её миниатюру.
        updateMini()
        applyMiniView()
    }

    /** Развернуть из мини обратно на весь экран. */
    private fun expandPlayer() {
        if (PlayerSession.active) {
            DiagLog.add("движок-плеер: разворот — полный экран")
            showPlayerPane(PlayerSession.videoId, PlayerSession.title)
        }
    }

    override fun onMinimizeRequested() {
        runOnUiThread { collapsePlayer() }
    }

    override fun onSpeedRequested() {
        runOnUiThread { showSettings() }
    }

    override fun onPipRequested() {
        runOnUiThread { enterPip() }
    }

    override fun onToggleRequested() {
        runOnUiThread { PlayerSession.togglePlayPause() }
    }

    override fun onQueueRequested() {
        runOnUiThread {
            val id = PlayerSession.videoId
            if (id.isBlank()) { return@runOnUiThread }
            val n = PlayerSession.addToQueue(this, id, PlayerSession.title)
            android.widget.Toast.makeText(this, "В очередь: " + PlayerSession.title.take(40) + " (всего " + n + ")", android.widget.Toast.LENGTH_SHORT).show()
            DiagLog.add("движок-плеер: в очередь — " + id + " (всего " + n + ")")
        }
    }

    override fun onQueueNext(id: String, title: String) {
        runOnUiThread { showPlayerPane(id, title) }
    }

    override fun canAutoAdvance(): Boolean = playerMode

    /** Системное окно «картинка-в-картинке» — отдельная кнопка. В окошке — только ролик и play/pause. */
    private fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || !playerMode) { return }
        val ok = runCatching { enterPictureInPictureMode(pipParams(playing = true)) }.getOrDefault(false)
        if (ok) {
            DiagLog.add("движок-плеер: свернул в окно")
            PlayerSession.markPip(this)
        } else {
            DiagLog.add("движок-плеер: окно недоступно")
        }
    }

    /** Параметры окна: пропорции ролика и кнопка play/pause. */
    private fun pipParams(playing: Boolean): android.app.PictureInPictureParams {
        val toggle = android.app.PendingIntent.getBroadcast(
            this,
            1,
            android.content.Intent(this, PipControls::class.java).setAction(PipControls.ACTION_TOGGLE),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (playing) "Пауза" else "Играть"
        val action = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            ),
            label,
            label,
            toggle,
        )
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .setActions(listOf(action))
            .build()
    }

    override fun applyPipParams(playing: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && isInPictureInPictureMode) {
            runCatching { setPictureInPictureParams(pipParams(playing)) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DiagLog.add(
            if (isInPictureInPictureMode) "движок-плеер: в окне" else "движок-плеер: развёрнут",
        )
        engineStatus.visibility = if (isInPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE
        if (isInPictureInPictureMode) {
            PlayerSession.markPip(this)
            PlayerSession.keepAlive()
            PlayerSession.applyPipLayout(true)
            restorePaneBackgrounds()
            PlayerSession.captureWantPlay()
            PlayerSession.scheduleResumeAttempts()
            engineHolder.postDelayed({ PlayerSession.updatePipParams() }, 700)
        } else {
            PlayerSession.clearPip(this)
            PlayerSession.applyPipLayout(false)
            PlayerSession.nudgeSurface()
            PlayerSession.clearMiniTouchBlock()
            PlayerSession.panicResetPip()
        }
    }

    /** Скорость и служебное: список скоростей, прямой поток, оживление, перезагрузка. */
    private fun showSettings() {
        val rates = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0)
        val labels = rates.map { "Скорость: " + speedLabel(it) }
        val entries = labels + listOf(
            "Прямой поток",
            "Оживить видео (если чёрный экран)",
            "Обновить страницу (с этого места)",
            "Перезагрузить страницу",
            "Играть следующую из очереди (" + PlayerSession.queueSize() + ")",
            "Очистить очередь",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Плеер")
            .setItems(entries.toTypedArray()) { _, which ->
                when {
                    which < rates.size -> {
                        PlayerSession.applySpeed(this, rates[which])
                        DiagLog.add("движок-плеер: скорость ${speedLabel(rates[which])}")
                    }
                    which == rates.size -> {
                        val id = PlayerSession.videoId
                        if (id.isEmpty()) {
                            engineStatus.text = "движок-плеер: нет ролика для прямого потока"
                        } else {
                            DiagLog.add("движок-плеер: переключаю на прямой поток")
                            startActivity(
                                android.content.Intent(this, PlayerActivity::class.java)
                                    .putExtra("videoId", id),
                            )
                        }
                    }
                    which == rates.size + 1 -> {
                        PlayerSession.reviveVideo(this)
                        engineStatus.text = "движок-плеер: оживляю видео"
                    }
                    which == rates.size + 2 -> {
                        PlayerSession.reloadFromPosition()
                        engineStatus.text = "движок-плеер: обновляю страницу с места"
                    }
                    which == rates.size + 3 -> {
                        PlayerSession.reload()
                        engineStatus.text = "движок-плеер: перезагрузка"
                    }
                    which == rates.size + 4 -> {
                        if (!PlayerSession.playNextFromQueue(this)) {
                            android.widget.Toast.makeText(this, "Очередь пуста", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    which == rates.size + 5 -> {
                        PlayerSession.clearQueue(this)
                        android.widget.Toast.makeText(this, "Очередь очищена", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
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

    /** Переключение вкладок оболочки. */
    private fun showTab(name: String) {
        tab = name
        val active = 0xFF7AA2FF.toInt()
        val calm = 0xFF8B93A5.toInt()
        findViewById<Button>(R.id.navMain).setTextColor(if (name == TAB_MAIN) active else calm)
        findViewById<Button>(R.id.navShorts).setTextColor(if (name == TAB_SHORTS) active else calm)
        findViewById<Button>(R.id.navSubs).setTextColor(if (name == TAB_SUBS) active else calm)
        findViewById<Button>(R.id.navLib).setTextColor(if (name == TAB_LIB) active else calm)

        shortsView.visibility = if (name == TAB_SHORTS) TextView.VISIBLE else TextView.GONE
        listView.visibility = if (name == TAB_SHORTS) ListView.GONE else ListView.VISIBLE
        playlistsPill.visibility = if (name == TAB_LIB) TextView.VISIBLE else TextView.GONE
        val loginButton = findViewById<Button>(R.id.btnLibLogin)
        loginButton.visibility = if (name == TAB_LIB) TextView.VISIBLE else TextView.GONE
        loginButton.text = if (loggedIn()) "Вход выполнен ✓" else "Войти в аккаунт"

        when (name) {
            TAB_MAIN -> load(MODE_RECOMMENDED)
            TAB_SUBS -> load(MODE_SUBSCRIPTIONS)
            TAB_LIB -> showRecents()
            else -> {
                pageHandled = true
                statusView.text = "Shorts: скоро в этой оболочке"
            }
        }
    }

    /** Вход в аккаунт: окно входа; после него подписки и ленты оживают сами. */
    private fun openLogin() {
        if (loggedIn()) {
            Toast.makeText(this, "Вход выполнен ✓", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(android.content.Intent(this, LoginActivity::class.java))
    }

    private fun loggedIn(): Boolean = PlayerApi.hasLoginCookies(
        runCatching {
            android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com/")
        }.getOrNull(),
    )

    /** Библиотека: недавние ролики из локального списка. */
    private fun showRecents() {
        pageHandled = true
        val recents = parseRecents()
        items = recents
        listView.adapter = FeedAdapter(recents.map(::cardOf))
        statusView.text = if (recents.isEmpty()) {
            "Недавние видео: пусто — откройте любой ролик, и он появится здесь"
        } else {
            "Недавние видео: ${recents.size} — нажмите, чтобы открыть снова"
        }
        DiagLog.add("библиотека: недавних ${recents.size}")
    }

    private fun parseRecents(): List<FeedItem> {
        val raw = AppSettings.recents(this)
        if (raw.isBlank()) return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<FeedItem>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val videoId = obj.optString("videoId")
            if (videoId.isBlank()) continue
            out += FeedItem(
                videoId = videoId,
                title = obj.optString("title").ifBlank { videoId },
                channel = obj.optString("channel"),
                duration = null,
                views = null,
                thumbnailUrl = null,
                injected = false,
            )
        }
        return out
    }

    private fun load(newMode: String) {
        if (tab == TAB_LIB && newMode != MODE_PLAYLISTS) {
            // Библиотека показывает свой список; ленты грузятся во вкладках Главная/Подписки.
            showRecents()
            return
        }
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
        targetMode == MODE_PLAYLISTS && mobile -> "https://m.youtube.com/feed/playlists"
        targetMode == MODE_PLAYLISTS -> "https://www.youtube.com/feed/playlists"
        mobile -> "https://m.youtube.com/"
        else -> "https://www.youtube.com/?hl=ru"
    }

    /** Спросить у движка данные страницы. */
    private fun tryEngineData() {
        if (pageHandled || tab == TAB_SHORTS || tab == TAB_LIB) return
        val js = "(function(){try{return JSON.stringify(window.ytInitialData||null);}" +
            "catch(e){return 'null';}})()"
        webView.evaluateJavascript(js) { raw ->
            if (pageHandled || tab == TAB_SHORTS || tab == TAB_LIB) return@evaluateJavascript
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
                if (pageHandled || tab == TAB_SHORTS || tab == TAB_LIB) return@runOnUiThread
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
        if (mode == MODE_PLAYLISTS) {
            // Плейлисты — отдельные записи: разбирает ядро, показываем тем же списком-карточками.
            playlists = FeedCodec.mapPlaylists(CoreHolder.parsePlaylists(json))
            items = emptyList()
            listView.adapter = FeedAdapter(
                playlists.map {
                    FeedCodec.FeedRow(
                        label = FeedCodec.playlistLabel(it),
                        thumbUrl = it.thumbnailUrl,
                        tag = it.playlistId,
                        title = it.title.ifEmpty { it.playlistId },
                        sub = it.videoCount.orEmpty(),
                    )
                },
            )
            DiagLog.add("плейлистов: ${playlists.size}")
            statusView.text = if (playlists.isEmpty()) {
                FeedCodec.emptyMessage(mode)
            } else {
                "плейлистов: ${playlists.size} — нажмите, чтобы открыть"
            }
            return
        }
        val coreJson = CoreHolder.analyzeFeed(
            json,
            AppSettings.promoVideoId(this),
            AppSettings.promoTitle(this),
            AppSettings.promoPosition(this),
        )
        items = FeedCodec.mapItems(coreJson)
        listView.adapter = FeedAdapter(items.map(::cardOf))
        DiagLog.add("лента ($mode): роликов ${items.size}")
        statusView.text = if (items.isEmpty()) {
            FeedCodec.emptyMessage(mode)
        } else {
            "роликов: ${items.size}" +
                if (items.any { it.injected }) ", наша рекомендация на месте" else ""
        }
    }

    /** Карточка списка из данных ленты. */
    private fun cardOf(item: FeedItem): FeedCodec.FeedRow = FeedCodec.FeedRow(
        label = FeedCodec.label(item),
        thumbUrl = item.thumbnailUrl,
        tag = item.videoId,
        title = item.title.ifEmpty { item.videoId },
        sub = listOfNotNull(
            item.channel.takeIf { it.isNotEmpty() },
            item.views,
            item.duration,
        ).joinToString(" · "),
        promo = item.injected,
    )

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

    /** Открыть ролик: запоминаем в недавних и играем движком (он открывается всегда). */
    private fun openItem(item: FeedItem) {
        DiagLog.add("лента: открываю ${item.videoId}")
        AppSettings.pushRecent(this, item.videoId, item.title.ifEmpty { item.videoId }, item.channel)
        showPlayerPane(item.videoId, item.title.ifEmpty { item.videoId })
    }

    /** Плейлист открываем на экране сервиса — там его ролики и порядок. */
    private fun openPlaylist(entry: FeedCodec.PlaylistEntry) {
        DiagLog.add("плейлист: открываю ${entry.playlistId}")
        val intent = android.content.Intent(this, YouTubeActivity::class.java)
        intent.putExtra(
            YouTubeActivity.EXTRA_URL,
            "https://m.youtube.com/playlist?list=${entry.playlistId}",
        )
        startActivity(intent)
    }

    /** Копия отчёта прямо из шапки: тот же общий журнал, что на других экранах. */
    private fun copyReport() {
        val core = runCatching { CoreHolder.status() }.getOrNull()
        val header = buildString {
            appendLine("Obereg · отчёт диагностики (оболочка)")
            appendLine(
                "время: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                    .format(java.util.Date()),
            )
            appendLine("сборка: " + reportBuildLabel())
            if (core != null) {
                appendLine("ядро: ${core.state} / ${core.mode} / прокси ${core.listenAddr ?: "нет"}")
            }
            appendLine("--- журнал ---")
        }
        val report = header + DiagLog.dump()
        runCatching {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("Obereg отчёт", report),
            )
        }
        DiagLog.add("отчёт скопирован (${report.length} символов)")
        Toast.makeText(this, "Отчёт скопирован (${report.length} символов)", Toast.LENGTH_SHORT).show()
    }

    private fun reportBuildLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val installed = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT)
            .format(java.util.Date(info.lastUpdateTime))
        "${info.versionName} · установлено $installed"
    }.getOrDefault("0.1.0")

    companion object {
        const val MODE_RECOMMENDED = "recommended"
        const val MODE_SUBSCRIPTIONS = "subscriptions"
        const val MODE_PLAYLISTS = "playlists"

        const val TAB_MAIN = "main"
        const val TAB_SHORTS = "shorts"
        const val TAB_SUBS = "subs"
        const val TAB_LIB = "lib"
    }
}
