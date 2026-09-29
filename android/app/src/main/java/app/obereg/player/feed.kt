package app.obereg.player

import org.json.JSONArray

/** Ролик в ленте — то, что ядро отдаёт оболочке. */
data class FeedItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String?,
    val views: String?,
    /** Обложка ролика (первая ссылка, которую отдал сервис). */
    val thumbnailUrl: String?,
    /** Наша рекомендация: подставлена приложением, а не сервисом. */
    val injected: Boolean,
)

/**
 * Перевод ответа ядра в список для экрана.
 *
 * Разбор самого ответа сервиса живёт в ядре (`crates/obereg-core/src/feed.rs`) —
 * там логика, тесты и переносимость. Здесь только перевод в вид экрана.
 */
object FeedCodec {

    fun mapItems(coreJson: String): List<FeedItem> {
        val array = runCatching { JSONArray(coreJson) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<FeedItem>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val videoId = obj.optString("video_id")
            if (videoId.isEmpty()) continue
            items += FeedItem(
                videoId = videoId,
                title = obj.optString("title"),
                channel = obj.optString("channel"),
                duration = obj.optString("duration").takeIf { it.isNotEmpty() && it != "null" },
                views = obj.optString("views").takeIf { it.isNotEmpty() && it != "null" },
                thumbnailUrl = obj.optString("thumbnail_url")
                    .takeIf { it.isNotEmpty() && it != "null" },
                injected = obj.optBoolean("injected"),
            )
        }
        return items
    }

    /** Подпись строки списка: название, канал, длительность, просмотры. */
    fun label(item: FeedItem): String = buildString {
        if (item.injected) append("★ ")
        append(item.title.ifEmpty { item.videoId })
        val parts = listOfNotNull(
            item.channel.takeIf { it.isNotEmpty() },
            item.duration,
            item.views,
        )
        if (parts.isNotEmpty()) {
            append("  ·  ")
            append(parts.joinToString(" · "))
        }
    }

    /**
     * Объяснение пустой ленты — по разделу, а не одним "пусто".
     *
     * Проверено на устройстве: в «Рекомендациях» ноль роликов чаще всего не из-за ошибки,
     * а потому что история просмотров выключена в аккаунте — на самом сервисе главная тоже пуста.
     */
    fun emptyMessage(mode: String): String = when (mode) {
        "subscriptions" ->
            "подписок нет: подпишитесь на каналы на экране сервиса — ролики появятся здесь"
        else ->
            "рекомендаций нет: сервис не отдал ни одного ролика. Чаще всего потому, что " +
                "в аккаунте выключена история просмотров (на главной самого сервиса тоже пусто). " +
                "Откройте «Подписки» — там ролики с ваших каналов"
    }
}
