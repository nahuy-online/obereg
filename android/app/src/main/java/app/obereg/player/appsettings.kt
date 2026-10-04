package app.obereg.player

import android.content.Context

/**
 * Настройки приложения: одна точка правды для маршрута и наших рекомендаций.
 *
 * Раньше маршрут экрана сервиса жил галочкой на самом экране — теперь это настройка
 * в профиле, единая для всего приложения (как и просил пользователь).
 */
object AppSettings {

    private const val FILE = "obereg-settings"
    private const val KEY_TUNNEL = "tunnel.desired"
    private const val KEY_WEB_VIA_TUNNEL = "screen.viaTunnel"
    private const val KEY_PROMO_ID = "promo.videoId"
    private const val KEY_PROMO_TITLE = "promo.title"
    private const val KEY_PROMO_POSITION = "promo.position"
    private const val KEY_TOKENS = "tokens.state"
    private const val KEY_FORGE = "forge.state"
    private const val KEY_VISITOR = "visitor.data"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Нужен ли наш туннель (выключатель в профиле). */
    fun tunnelDesired(context: Context): Boolean = prefs(context).getBoolean(KEY_TUNNEL, false)

    fun setTunnelDesired(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TUNNEL, value).apply()
    }

    /** Состояние токенов (между запусками приложения). */
    fun tokenState(context: Context): String = prefs(context).getString(KEY_TOKENS, "") ?: ""

    fun saveTokenState(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKENS, value).apply()
    }

    /** Данные для выдачи токенов в плеере (машина проверки и токен целостности). */
    fun forgeState(context: Context): String = prefs(context).getString(KEY_FORGE, "") ?: ""

    fun saveForgeState(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FORGE, value).apply()
    }

    /** Гостевые данные (visitor) для запросов к сервису. */
    fun visitorData(context: Context): String = prefs(context).getString(KEY_VISITOR, "") ?: ""

    fun saveVisitorData(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VISITOR, value).apply()
    }

    /** Идёт ли экран сервиса через наш туннель. */
    fun screenViaTunnel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_VIA_TUNNEL, false)

    fun setScreenViaTunnel(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_VIA_TUNNEL, value).apply()
    }

    /** Наша рекомендация: ролик, который подставляется в ленту. */
    fun promoVideoId(context: Context): String = prefs(context).getString(KEY_PROMO_ID, "") ?: ""

    fun promoTitle(context: Context): String = prefs(context).getString(KEY_PROMO_TITLE, "") ?: ""

    fun promoPosition(context: Context): Int = prefs(context).getInt(KEY_PROMO_POSITION, 3)

    fun setPromo(context: Context, videoId: String, title: String, position: Int) {
        prefs(context).edit()
            .putString(KEY_PROMO_ID, videoId.trim())
            .putString(KEY_PROMO_TITLE, title.trim())
            .putInt(KEY_PROMO_POSITION, SettingsCodec.clampPosition(position.toString()))
            .apply()
    }
}

/** Разбор значений настроек: чистые функции, проверяются тестами. */
object SettingsCodec {

    const val MIN_POSITION = 1
    const val MAX_POSITION = 50

    /** Позиция вставки: принимаем текст, держим в разумных границах. */
    fun clampPosition(text: String?): Int {
        val value = text?.trim()?.toIntOrNull() ?: 3
        return value.coerceIn(MIN_POSITION, MAX_POSITION)
    }

    /** Похоже ли значение на идентификатор видео (11 символов из допустимых). */
    fun looksLikeVideoId(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        return value.length == 11 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}