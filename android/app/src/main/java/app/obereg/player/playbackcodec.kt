package app.obereg.player

import org.json.JSONObject

/**
 * Чистая логика для медиа-уведомления: разбор «пульса» страницы и мелкие проверки.
 *
 * Здесь нет зависимостей от Android — файл собирается и тестируется обычным JUnit
 * (см. scripts/run-kotlin-tests.ps1), поэтому разбор данных со страницы можно
 * проверять без устройства.
 */
object PlaybackCodec {

    /** Снимок состояния ролика, который страница присылает раз в секунду. */
    data class Pulse(
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val title: String,
        val channel: String,
        val videoId: String,
        val loop: Boolean,
    )

    private const val MAX_POSITION_MS = 24L * 60 * 60 * 1000
    private const val MAX_TITLE = 160
    private const val MAX_CHANNEL = 120
    private const val MAX_ID = 32

    /** Разобрать «пульс». null — если строка не разбирается или это пустой снимок. */
    fun parsePulse(json: String?): Pulse? {
        val text = json?.trim().orEmpty()
        if (text.isEmpty() || text == "null" || text == "{}") return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return Pulse(
            playing = obj.optBoolean("playing", false),
            positionMs = clampMs(obj.optLong("t", 0L)),
            durationMs = clampMs(obj.optLong("d", 0L)),
            title = obj.optString("title", "").trim().take(MAX_TITLE),
            channel = obj.optString("author", "").trim().take(MAX_CHANNEL),
            videoId = sanitizeVideoId(obj.optString("id", "")),
            loop = obj.optBoolean("loop", false),
        )
    }

    /** Идентификатор ролика: только буквы, цифры, дефис и подчёркивание. */
    fun sanitizeVideoId(raw: String?): String =
        raw.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_ID)

    /** Перемотка: не уходим в минус и не выходим за длительность (если она известна). */
    fun clampSeekMs(positionMs: Long, durationMs: Long): Long {
        val lower = if (positionMs < 0) 0 else positionMs
        return if (durationMs > 0 && lower > durationMs) durationMs else lower
    }

    /** Позиция близка к концу ролика: страховка не должна «перезапускать» досмотренное. */
    fun nearEnd(pulse: Pulse): Boolean =
        pulse.durationMs > 0 && pulse.positionMs >= pulse.durationMs - 1500

    private fun clampMs(value: Long): Long = when {
        value < 0 -> 0
        value > MAX_POSITION_MS -> MAX_POSITION_MS
        else -> value
    }
}
