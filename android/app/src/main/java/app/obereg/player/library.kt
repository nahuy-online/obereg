package app.obereg.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Запись библиотеки: просмотренное видео или поисковый запрос.
 *
 * Библиотека живёт только на устройстве и никуда не отправляется.
 */
data class LibraryItem(
    val id: String,
    val title: String,
    /** «video» или «search». */
    val kind: String,
    val addedAt: Long,
)

/**
 * Логика библиотеки без привязки к Android: список, повторы, ограничение размера, кодирование.
 *
 * Вынесена отдельно, чтобы всё это проверялось тестами, а на стороне Android осталась
 * только тонкая обёртка над настройками приложения.
 */
object LibraryCodec {

    /** Потолок истории: больше не нужно ни человеку, ни устройству. */
    const val MAX_ITEMS = 200

    /** Потолок недавних поисков. */
    const val MAX_SEARCHES = 50

    const val KIND_VIDEO = "video"
    const val KIND_SEARCH = "search"

    fun encode(items: List<LibraryItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("kind", item.kind)
                    .put("addedAt", item.addedAt),
            )
        }
        return array.toString()
    }

    fun decode(text: String?): List<LibraryItem> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<LibraryItem>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            items += LibraryItem(
                id = id,
                title = obj.optString("title"),
                kind = obj.optString("kind", KIND_VIDEO),
                addedAt = obj.optLong("addedAt"),
            )
        }
        return items
    }

    /** Добавить запись наверх; прежняя запись с тем же идентификатором и видом убирается. */
    fun addItem(
        items: List<LibraryItem>,
        item: LibraryItem,
        max: Int = MAX_ITEMS,
    ): List<LibraryItem> {
        val rest = items.filterNot { it.id == item.id && it.kind == item.kind }
        return (listOf(item) + rest).take(max)
    }

    /** Убрать запись (например, снять закладку). */
    fun removeItem(items: List<LibraryItem>, id: String, kind: String): List<LibraryItem> =
        items.filterNot { it.id == id && it.kind == kind }

    /** Есть ли такая запись. */
    fun contains(items: List<LibraryItem>, id: String, kind: String): Boolean =
        items.any { it.id == id && it.kind == kind }
}
