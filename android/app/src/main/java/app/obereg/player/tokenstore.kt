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

    /** Положить токен для видео. */
    fun put(videoId: String, token: PoToken) {
        if (videoId.isBlank() || token.value.isBlank()) return
        synchronized(tokens) { tokens[videoId] = token }
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
        synchronized(tokens) { tokens.clear() }
    }

    /** Сколько токенов хранится — для журнала. */
    fun size(): Int = synchronized(tokens) { tokens.size }
}
