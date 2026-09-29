package app.obereg.core

import android.util.Log

/**
 * Мост к нативному ядру Obereg (`libobereg_jni.so`).
 *
 * Контракт зафиксирован в `crates/obereg-jni/src/lib.rs` и должен меняться только
 * вместе с ним. Все методы безопасны при отсутствии библиотеки: вместо падения
 * возвращается понятное сообщение (см. [availabilityError]).
 */
object CoreBridge {
    private const val TAG = "OberegCore"

    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("obereg_jni")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            loadError = "Нативная библиотека libobereg_jni.so не найдена. " +
                "Соберите её командой ./scripts/build-android.sh (см. docs/build-guide.md)."
            Log.e(TAG, loadError ?: "loadLibrary failed", error)
        }
    }

    /** Загрузилась ли нативная библиотека. */
    val isAvailable: Boolean get() = loaded

    /** Причина недоступности ядра или null. */
    val availabilityError: String? get() = loadError

    // --- Нативные методы (реализованы в crates/obereg-jni/src/lib.rs) ---

    private external fun version(): String
    private external fun create(configJson: String): Long
    private external fun free(handle: Long)
    private external fun start(handle: Long): String
    private external fun stop(handle: Long): String
    private external fun status(handle: Long): String
    private external fun setTransportMode(handle: Long, mode: String): String
    private external fun journalJson(handle: Long): String
    private external fun catalogSearch(handle: Long, query: String, limit: Int): String
    private external fun localHttpProxyAddr(handle: Long): String
    private external fun analyzePlayerResponse(handle: Long, playerResponseJson: String): String
    private external fun parsePlaylists(handle: Long, playlistsJson: String): String
    private external fun analyzeFeed(
        handle: Long,
        feedJson: String,
        promoId: String,
        promoTitle: String,
        position: Int,
    ): String

    // --- Безопасные обёртки ---

    /** Версия публичного API ядра. */
    fun coreVersion(): String? = if (loaded) runCatching { version() }.getOrNull() else null

    /** Создать сессию ядра. 0 — ошибка. */
    fun createSession(configJson: String): Long {
        if (!loaded) return 0L
        val handle = runCatching { create(configJson) }.getOrDefault(0L)
        if (handle == 0L) Log.e(TAG, "create failed (проверьте JSON конфигурации)")
        return handle
    }

    /** Уничтожить сессию. */
    fun freeSession(handle: Long) {
        if (loaded && handle != 0L) runCatching { free(handle) }
    }

    /** Запустить сессию: JSON `StateSnapshot` либо `{"error": ...}`. */
    fun startSession(handle: Long): String = call(handle) { start(handle) }

    /** Остановить сессию. */
    fun stopSession(handle: Long): String = call(handle) { stop(handle) }

    /** Текущее состояние: JSON `StateSnapshot`. */
    fun statusJson(handle: Long): String = call(handle) { status(handle) }

    /** Переключить режим транспорта: `direct` или `tunnel` (JSON `StateSnapshot` либо `{"error": ...}`). */
    fun setTransportModeJson(handle: Long, mode: String): String =
        call(handle) { setTransportMode(handle, mode) }

    /** Журнал событий: JSON-массив. */
    fun journalJsonText(handle: Long): String = call(handle) { journalJson(handle) }

    /** Поиск в каталоге видео: JSON-массив элементов. */
    fun catalogSearchJson(handle: Long, query: String, limit: Int): String =
        call(handle) { catalogSearch(handle, query, limit) }

    /** Адрес HTTP CONNECT-прокси ядра или пустая строка. */
    fun httpProxyAddrOrEmpty(handle: Long): String = call(handle) { localHttpProxyAddr(handle) }

    /** Разбор ответа видеосервиса силами ядра: выбор потока для своего плеера. */
    fun analyzePlayerResponseJson(handle: Long, json: String): String =
        call(handle) { analyzePlayerResponse(handle, json) }

    /** Разбор ленты и вставка нашей рекомендации: JSON-массив роликов. */
    fun analyzeFeedJson(
        handle: Long,
        feedJson: String,
        promoId: String,
        promoTitle: String,
        position: Int,
    ): String = call(handle) { analyzeFeed(handle, feedJson, promoId, promoTitle, position) }

    /** Список плейлистов: JSON-массив записей. */
    fun parsePlaylistsJson(handle: Long, json: String): String =
        call(handle) { parsePlaylists(handle, json) }

    /** JSON-объект ошибки в том же формате, что возвращает ядро. */
    fun errorJson(message: String): String = """{"error":"${message.replace('"', '\'')}"}"""

    private inline fun call(handle: Long, block: () -> String): String {
        if (!loaded) return errorJson(loadError ?: "ядро недоступно")
        if (handle == 0L) return errorJson("сессия не создана")
        return runCatching { block() }.getOrElse { error ->
            Log.e(TAG, "native call failed", error)
            errorJson(error.message ?: "native call failed")
        }
    }
}
