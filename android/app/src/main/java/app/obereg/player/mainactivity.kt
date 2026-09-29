package app.obereg.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.obereg.core.CoreBridge
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран приёмки ядра и встроенного туннеля.
 *
 * Задача экрана — не быть красивым, а показывать, что происходит: каждая кнопка
 * пишет в видимый лог, состояние обновляется раз в секунду, есть автотест,
 * который прогоняет сценарий целиком и печатает вердикт.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logLines = ArrayDeque<String>()
    private var refreshing = false
    private var lastJournalSeq = 0L

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshStatus()
            drainJournal()
            if (refreshing) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.output)

        log("экран открыт; версия ядра: ${CoreHolder.coreVersion() ?: "не загружено"}")
        CoreHolder.coreAvailabilityError()?.let { log("ОШИБКА ядра: $it") }
        log("журнал на устройстве: ${CoreHolder.journalPath(this)}")
        if (AppSettings.tunnelDesired(this)) {
            log("настройка: туннель включён — поднимаю")
            TunnelService.start(this)
        }


        findViewById<Button>(R.id.btnOpenYouTube).setOnClickListener {
            log("нажато: YouTube (встроенный плеер)")
            safe("открытие YouTube") {
                startActivity(android.content.Intent(this, YouTubeActivity::class.java))
            }
        }

        findViewById<Button>(R.id.btnOpenFeed).setOnClickListener {
            log("нажато: лента")
            safe("открытие ленты") {
                startActivity(android.content.Intent(this, FeedActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnOpenProfile).setOnClickListener {
            log("нажато: профиль и настройки")
            safe("открытие профиля") {
                startActivity(android.content.Intent(this, ProfileActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnOpenPlayer).setOnClickListener {
            log("нажато: открыть плеер")
            safe("открытие плеера") {
                startActivity(android.content.Intent(this, PlayerActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener { runSelfTest() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            log("нажато: запустить туннель")
            toast("Запускаю туннель…")
            safe("запуск сервиса") { TunnelService.start(this) }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            log("нажато: остановить")
            toast("Останавливаю…")
            safe("остановка сервиса") { TunnelService.stop(this) }
        }
        findViewById<Button>(R.id.btnMode).setOnClickListener { toggleMode() }
        findViewById<Button>(R.id.btnProbe).setOnClickListener { probeTraffic() }
        findViewById<Button>(R.id.btnJournal).setOnClickListener {
            lastJournalSeq = 0
            drainJournal(force = true)
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logLines.clear()
            renderLog()
            log("лог очищен")
        }

        requestNotificationPermissionIfNeeded()

        // Автозапуск: пользователь должен увидеть результат, ничего не нажимая.
        log("автостарт: поднимаю туннель")
        safe("автозапуск") { TunnelService.start(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        handler.post(refreshTick)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(refreshTick)
        super.onPause()
    }

    // --- отображение ---

    private fun refreshStatus() {
        val status = runCatching { CoreHolder.status() }.getOrElse {
            log("ошибка чтения статуса: ${it.message}")
            return
        }
        statusView.text = buildString {
            appendLine("состояние: ${status.state}")
            appendLine("режим: ${status.mode}")
            appendLine("локальный прокси: ${status.listenAddr ?: "—"}")
            appendLine("трафик: ↑${status.bytesUp} Б ↓${status.bytesDown} Б, соединений: ${status.sessionsServed}")
            status.lastError?.let { appendLine("последняя ошибка: $it") }
        }
    }

    /** Дописывает в видимый лог новые события журнала ядра. */
    private fun drainJournal(force: Boolean = false) {
        val events = runCatching { CoreHolder.journal(200) }.getOrNull() ?: return
        val fresh = events.filter { it.seq > lastJournalSeq }
        if (fresh.isEmpty()) {
            if (force) log("журнал ядра пуст")
            return
        }
        fresh.forEach { event ->
            log("ядро[${event.seq}] ${event.kind}: ${event.message}")
            lastJournalSeq = maxOf(lastJournalSeq, event.seq)
        }
    }

    private fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        Log.i(TAG, line)
        logLines.addLast(line)
        while (logLines.size > 200) logLines.removeFirst()
        renderLog()
    }

    private fun renderLog() {
        logView.text = logLines.joinToString("\n")
        // Автопрокрутка вниз, чтобы свежие строки были видны.
        val scroll = findViewById<android.view.View>(R.id.logScroll) as? android.widget.ScrollView
        scroll?.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Выполнить действие, показав исключение вместо молчаливого отказа. */
    private fun safe(what: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            log("ИСКЛЮЧЕНИЕ при действии «$what»: ${error.javaClass.simpleName}: ${error.message}")
            toast("Ошибка: ${error.message}")
        }
    }

    // --- действия ---

    private fun toggleMode() {
        val next = if (CoreHolder.desiredMode(this) == "tunnel") "direct" else "tunnel"
        CoreHolder.rememberMode(this, next)
        log("режим переключён на $next, сессия перезапускается")
        toast("Режим: $next")
        safe("перезапуск с новым режимом") {
            TunnelService.stop(this)
            handler.postDelayed({ TunnelService.start(this) }, 800)
        }
    }

    /** Автотест: запуск → проверка трафика → журнал → вердикт. */
    private fun runSelfTest() {
        log("=== АВТОТЕСТ ===")
        toast("Автотест запущен")
        Thread {
            try {
                val mode = CoreHolder.desiredMode(this)
                uiLog("автотест: желаемый режим — $mode")

                if (!CoreHolder.isCoreAvailable()) {
                    uiLog("ВЕРДИКТ: ядро недоступно — ${CoreHolder.coreAvailabilityError()}")
                    return@Thread
                }

                val handle = CoreHolder.ensureSession(this, mode)
                uiLog("автотест: сессия создана, handle=${if (handle != 0L) "ок" else "0 (ошибка)"}")
                if (handle == 0L) {
                    uiLog("ВЕРДИКТ: ядро не создало сессию (см. logcat -s OberegCore)")
                    return@Thread
                }

                // Не запускаем сессию повторно: сервис уже мог её поднять при автозапуске.
                val before = CoreHolder.status()
                uiLog("автотест: состояние до проверки — ${before.state}, режим ${before.mode}, соединений ${before.sessionsServed}")
                val started = if (before.state == "connected" || before.state == "degraded") {
                    before.raw
                } else {
                    uiLog("автотест: старт сессии…")
                    CoreHolder.start()
                }
                uiLog("автотест: ответ старта — $started")
                val status = TunnelStatus.from(started)
                val proxy = status.listenAddr ?: CoreHolder.status().listenAddr
                uiLog("автотест: состояние=${status.state}, режим=${status.mode}, прокси=${proxy ?: "нет"}")

                if (proxy == null) {
                    uiLog("ВЕРДИКТ: туннель не поднялся (состояние ${status.state})")
                    return@Thread
                }

                val (host, portText) = proxy.split(":")
                val proxyType = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, portText.toInt()))
                val viaTunnel = runCatching { httpGet(proxyType) }.getOrElse { "ошибка: ${it.message}" }
                val direct = runCatching { httpGet(null) }.getOrElse { "ошибка: ${it.message}" }
                uiLog("автотест: адрес через туннель — $viaTunnel")
                uiLog("автотест: адрес напрямую — $direct")

                val verdict = when {
                    viaTunnel.startsWith("ошибка") -> "ТУННЕЛЬ НЕ ОТВЕЧАЕТ ($viaTunnel)"
                    viaTunnel == direct -> "трафик идёт, но адрес совпал (прямой upstream — это ожидаемо для MVP)"
                    else -> "ТРАФИК ИДЁТ ЧЕРЕЗ ТУННЕЛЬ"
                }
                uiLog("ВЕРДИКТ: $verdict")
                uiLog("автотест: журнал ядра — ${CoreHolder.journal(10).joinToString("; ") { it.kind }}")

                val after = CoreHolder.status()
                val servedDelta = after.sessionsServed - before.sessionsServed
                val downDelta = after.bytesDown - before.bytesDown
                uiLog("автотест: туннель обслужил ещё $servedDelta соединений, скачал $downDelta Б")
                if (servedDelta > 0) {
                    uiLog("вывод: трафик проверок шёл через встроенный туннель")
                } else {
                    uiLog("вывод: туннель не обслужил ни одного соединения — проверьте, что поднят режим tunnel")
                }
            } catch (error: Throwable) {
                uiLog("ИСКЛЮЧЕНИЕ в автотесте: ${error.javaClass.simpleName}: ${error.message}")
                uiLog("ВЕРДИКТ: автотест упал")
            }
        }.start()
    }

    /** Проверка прохождения трафика: адрес через туннель против адреса напрямую. */
    private fun probeTraffic() {
        val proxyAddr = runCatching { CoreHolder.status().listenAddr }.getOrNull()
        if (proxyAddr == null) {
            log("туннель не поднят: сначала «Запустить туннель» и дождаться состояния connected")
            toast("Туннель не поднят")
            return
        }
        log("проверка трафика через $proxyAddr …")
        toast("Проверяю трафик…")
        Thread {
            val (host, portText) = proxyAddr.split(":")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, portText.toInt()))
            val viaTunnel = runCatching { httpGet(proxy) }.getOrElse { "ошибка: ${it.message}" }
            val direct = runCatching { httpGet(null) }.getOrElse { "ошибка: ${it.message}" }
            uiLog("через туннель: $viaTunnel")
            uiLog("напрямую: $direct")
            uiLog(if (viaTunnel.startsWith("ошибка")) "вывод: туннель недоступен" else "вывод: запросы выполнены")
        }.start()
    }

    private fun uiLog(message: String) = runOnUiThread { log(message) }

    /** Простой HTTP GET; при передаче SOCKS-прокси соединение идёт через туннель ядра. */
    private fun httpGet(proxy: Proxy?): String {
        val host = "api.ipify.org"
        val socket = if (proxy != null) Socket(proxy) else Socket()
        socket.use {
            it.connect(InetSocketAddress(host, 80), 8000)
            it.soTimeout = 8000
            val request = "GET / HTTP/1.0\r\nHost: $host\r\nUser-Agent: obereg-android\r\nConnection: close\r\n\r\n"
            it.getOutputStream().write(request.toByteArray())
            val response = it.getInputStream().readBytes().toString(Charsets.UTF_8)
            return response.substringAfter("\r\n\r\n").trim().ifEmpty { response.trim() }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            log("запрашиваю разрешение на уведомления (нужно для сервиса туннеля)")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private companion object {
        const val TAG = "OberegCore"
    }
}
