package app.obereg.player

import android.content.Context
import app.obereg.core.CoreBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Снимок состояния сессии ядра. */
data class TunnelStatus(
    val state: String,
    val mode: String,
    val listenAddr: String?,
    val lastError: String?,
    val bytesUp: Long,
    val bytesDown: Long,
    val sessionsServed: Long,
    val raw: String,
) {
    companion object {
        fun from(json: String): TunnelStatus {
            val root = runCatching { JSONObject(json) }.getOrNull()
                ?: return TunnelStatus("error", "direct", null, json, 0, 0, 0, json)
            root.optString("error").takeIf { it.isNotEmpty() }?.let { message ->
                return TunnelStatus("error", "direct", null, message, 0, 0, 0, json)
            }
            return TunnelStatus(
                state = root.optString("state", "unknown"),
                mode = root.optString("mode", "direct"),
                listenAddr = root.optString("listen_addr").takeIf { it.isNotEmpty() && it != "null" },
                lastError = root.optString("last_error").takeIf { it.isNotEmpty() && it != "null" },
                bytesUp = root.optLong("bytes_up", 0),
                bytesDown = root.optLong("bytes_down", 0),
                sessionsServed = root.optLong("sessions_served", 0),
                raw = json,
            )
        }
    }
}

/** Одно событие журнала. */
data class JournalEntry(val seq: Long, val kind: String, val message: String, val tsMs: Long)

/** Элемент каталога видео. */
data class CatalogItem(
    val id: String,
    val title: String,
    val url: String,
    val durationSecs: Long,
) {
    /** Длительность в формате мм:сс, если известна. */
    fun durationLabel(): String {
        if (durationSecs <= 0) return "—"
        val minutes = durationSecs / 60
        val seconds = durationSecs % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * Общее состояние ядра для приложения: хэндл сессии, конфигурация и разбор ответов.
 * Держится в одном месте, чтобы сервис и экран работали с одной сессией.
 */
object CoreHolder {
    @Volatile
    var handle: Long = 0L
        private set

    @Volatile
    private var configJson: String? = null

    /** Доступно ли нативное ядро. */
    fun isCoreAvailable(): Boolean = CoreBridge.isAvailable

    /** Причина недоступности ядра или null. */
    fun coreAvailabilityError(): String? = CoreBridge.availabilityError

    /** Версия публичного API ядра. */
    fun coreVersion(): String? = CoreBridge.coreVersion()

    /** Конфигурация ядра: журнал в файлах приложения, идентификатор устройства из настроек. */
    fun ensureConfig(context: Context, mode: String = "tunnel"): String {
        configJson?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        val journalPath = java.io.File(context.filesDir, "journal.jsonl").absolutePath
        val config = JSONObject()
            .put("config_version", 1)
            .put("device_id", deviceId)
            .put("locale", "ru")
            .put("log_level", "debug")
            .put("journal_path", journalPath)
            .put(
                "tunnel",
                JSONObject()
                    .put("listen_addr", "127.0.0.1:0")
                    .put("mode", mode)
                    .put("upstream", JSONObject.NULL)
                    .put("handshake_timeout_ms", 5000)
                    .put("idle_timeout_ms", 30000),
            )
        val json = config.toString()
        configJson = json
        return json
    }

    /** Желаемый режим транспорта из настроек. */
    fun desiredMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "tunnel") ?: "tunnel"

    /** Запомнить выбранный режим. */
    fun rememberMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
        configJson = null // режим входит в конфигурацию — пересоберём при следующем старте
    }

    /** Создать сессию, если её ещё нет. */
    fun ensureSession(context: Context, mode: String): Long {
        if (handle != 0L) return handle
        val json = ensureConfig(context, mode)
        handle = CoreBridge.createSession(json)
        return handle
    }

    /** Создать сессию с явной конфигурацией. */
    fun createSession(json: String): Long {
        if (handle != 0L) return handle
        configJson = json
        handle = CoreBridge.createSession(json)
        return handle
    }

    /** Текущий статус. */
    fun status(): TunnelStatus {
        if (handle == 0L) {
            return if (!CoreBridge.isAvailable) {
                TunnelStatus("error", "direct", null, CoreBridge.availabilityError, 0, 0, 0, "{}")
            } else {
                TunnelStatus("idle", "direct", null, null, 0, 0, 0, "{}")
            }
        }
        return TunnelStatus.from(CoreBridge.statusJson(handle))
    }

    /** Последние события журнала. */
    fun journal(limit: Int = 50): List<JournalEntry> {
        if (handle == 0L) return emptyList()
        val json = CoreBridge.journalJsonText(handle)
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val entries = ArrayList<JournalEntry>(array.length())
        for (index in maxOf(0, array.length() - limit) until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            entries.add(
                JournalEntry(
                    seq = item.optLong("seq"),
                    kind = item.optString("kind"),
                    message = item.optString("message"),
                    tsMs = item.optLong("ts_ms"),
                ),
            )
        }
        return entries
    }

    /** Поиск в каталоге видео. Пустой запрос возвращает подборку. */
    fun catalog(query: String, limit: Int = 50): List<CatalogItem> {
        if (handle == 0L) return emptyList()
        val json = CoreBridge.catalogSearchJson(handle, query, limit)
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val items = ArrayList<CatalogItem>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items.add(
                CatalogItem(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    url = item.optString("url"),
                    durationSecs = item.optLong("duration_secs", 0),
                ),
            )
        }
        return items
    }

    /** Адрес локального HTTP CONNECT-прокси ядра (для WebView) или null. */
    fun httpProxyAddr(): String? {
        if (handle == 0L) return null
        return CoreBridge.httpProxyAddrOrEmpty(handle).takeIf { it.isNotEmpty() }
    }

    /** Разбор ответа видеосервиса силами ядра (логика и её тесты — в ядре). */
    fun analyzePlayerResponse(playerResponseJson: String): String {
        if (handle == 0L) {
            return """{"status":"malformed","reason":"сессия не создана"}"""
        }
        return CoreBridge.analyzePlayerResponseJson(handle, playerResponseJson)
    }

    /** Лента: разбор ответа раздела и вставка нашей рекомендации (логика — в ядре). */
    fun analyzeFeed(feedJson: String, promoId: String, promoTitle: String, position: Int): String {
        if (handle == 0L) {
            return CoreBridge.errorJson("сессия не создана")
        }
        return CoreBridge.analyzeFeedJson(handle, feedJson, promoId, promoTitle, position)
    }

    /** Список плейлистов (разбор — в ядре). */
    fun parsePlaylists(playlistsJson: String): String {
        if (handle == 0L) {
            return CoreBridge.errorJson("сессия не создана")
        }
        return CoreBridge.parsePlaylistsJson(handle, playlistsJson)
    }

    /** Запустить сессию (JSON-ответ ядра). */
    fun start(): String {
        if (handle == 0L) return CoreBridge.errorJson("сессия не создана")
        return CoreBridge.startSession(handle)
    }

    /** Остановить сессию. */
    fun stop(): String {
        if (handle == 0L) return CoreBridge.errorJson("сессия не создана")
        return CoreBridge.stopSession(handle)
    }

    /** Переключить режим. */
    fun setTransportMode(mode: String): String {
        if (handle == 0L) return CoreBridge.errorJson("сессия не создана")
        return CoreBridge.setTransportModeJson(handle, mode)
    }

    /** Остановить и освободить сессию. */
    fun release() {
        if (handle != 0L) {
            runCatching { CoreBridge.stopSession(handle) }
            CoreBridge.freeSession(handle)
            handle = 0L
        }
    }

    /** Путь к файлу журнала на устройстве. */
    fun journalPath(context: Context): String =
        java.io.File(context.filesDir, "journal.jsonl").absolutePath

    const val PREFS = "obereg"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_MODE = "transport_mode"
}
