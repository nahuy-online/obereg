package app.obereg.player

/**
 * Токен подтверждения клиента (шаг P3 этапа аттестации).
 *
 * Пока токенов нет — эта часть готовит место: хранение с учётом срока жизни и подстановку
 * в запросы. Как только движок начнёт выдавать токены, менять конвейер будет не нужно.
 */
data class PoToken(
    val value: String,
    /** Когда токен перестаёт быть годным (миллисекунды). */
    val expiresAtMs: Long,
)

/**
 * Кэш токенов: у каждого видео свой токен, и он живёт ограниченное время.
 * Логика чистая — проверяется тестами.
 */
object TokenStore {

    private val tokens = mutableMapOf<String, PoToken>()

    /** Последний сохранённый токен — им можно пользоваться, если для видео своего нет. */
    private var last: PoToken? = null

    /** Положить токен для видео. */
    fun put(videoId: String, token: PoToken) {
        if (videoId.isBlank() || token.value.isBlank()) return
        synchronized(tokens) {
            tokens[videoId] = token
            last = token
        }
    }

    /** Самый свежий годный токен (для видео без своего токена). */
    fun latest(nowMs: Long = System.currentTimeMillis()): PoToken? {
        val token = synchronized(tokens) { last } ?: return null
        if (!isValid(token, nowMs)) {
            synchronized(tokens) { if (last === token) last = null }
            return null
        }
        return token
    }

    /** Состояние в строку — для сохранения между запусками. */
    fun toJson(): String {
        val root = org.json.JSONObject()
        synchronized(tokens) {
            for ((videoId, token) in tokens) {
                root.put(videoId, org.json.JSONArray().put(token.value).put(token.expiresAtMs))
            }
        }
        return root.toString()
    }

    /** Восстановление состояния из строки. */
    fun fromJson(json: String?) {
        if (json.isNullOrEmpty()) return
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        synchronized(tokens) {
            tokens.clear()
            last = null
            for (key in root.keys()) {
                val arr = root.optJSONArray(key) ?: continue
                val value = arr.optString(0)
                val expires = arr.optLong(1)
                if (value.isEmpty() || expires <= 0) continue
                val token = PoToken(value, expires)
                tokens[key] = token
                val current = last
                if (current == null || token.expiresAtMs > current.expiresAtMs) last = token
            }
        }
    }

    /** Взять годный токен для видео (или null, если его нет или срок вышел). */
    fun get(videoId: String, nowMs: Long = System.currentTimeMillis()): PoToken? {
        val token = synchronized(tokens) { tokens[videoId] } ?: return null
        if (!isValid(token, nowMs)) {
            synchronized(tokens) { tokens.remove(videoId) }
            return null
        }
        return token
    }

    /** Годен ли токен к указанному моменту. */
    fun isValid(token: PoToken, nowMs: Long): Boolean =
        token.value.isNotBlank() && token.expiresAtMs > nowMs

    /** Очистить кэш (например, при выходе из аккаунта). */
    fun clear() {
        synchronized(tokens) {
            tokens.clear()
            last = null
        }
    }

    /** Сколько токенов хранится — для журнала. */
    fun size(): Int = synchronized(tokens) { tokens.size }
}

/** Последний адрес потока, пойманный со страницы YouTube (для сравнения в пробе). */
object StreamCatcher {
    @Volatile
    var lastPageStream: String? = null

    /** Последний адрес потока, пойманный со скрытой страницы движка. */
    @Volatile
    var lastEngineStream: String? = null
}