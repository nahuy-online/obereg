package app.obereg.player

import android.content.Context

/**
 * Локальная библиотека приложения: история просмотров, закладки, недавние поиски.
 *
 * Хранится в настройках приложения, никуда не отправляется и не требует сети.
 */
object VideoStore {

    private const val FILE = "obereg-library"
    private const val KEY_HISTORY = "history"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_SEARCHES = "searches"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): List<LibraryItem> =
        LibraryCodec.decode(prefs(context).getString(key, null))

    private fun write(context: Context, key: String, items: List<LibraryItem>) {
        prefs(context).edit().putString(key, LibraryCodec.encode(items)).apply()
    }

    fun history(context: Context): List<LibraryItem> = read(context, KEY_HISTORY)

    fun bookmarks(context: Context): List<LibraryItem> = read(context, KEY_BOOKMARKS)

    fun searches(context: Context): List<LibraryItem> = read(context, KEY_SEARCHES)

    /** Запомнить просмотр: видео поднимается наверх, повторы не копятся. */
    fun addHistory(context: Context, videoId: String, title: String) {
        if (videoId.isBlank()) return
        val item = LibraryItem(
            id = videoId,
            title = title.ifBlank { videoId },
            kind = LibraryCodec.KIND_VIDEO,
            addedAt = System.currentTimeMillis(),
        )
        write(context, KEY_HISTORY, LibraryCodec.addItem(history(context), item))
    }

    /** Поставить или снять закладку. Возвращает новое состояние: true — в закладках. */
    fun toggleBookmark(context: Context, videoId: String, title: String): Boolean {
        if (videoId.isBlank()) return false
        val items = bookmarks(context)
        return if (LibraryCodec.contains(items, videoId, LibraryCodec.KIND_VIDEO)) {
            write(
                context,
                KEY_BOOKMARKS,
                LibraryCodec.removeItem(items, videoId, LibraryCodec.KIND_VIDEO),
            )
            false
        } else {
            write(
                context,
                KEY_BOOKMARKS,
                LibraryCodec.addItem(
                    items,
                    LibraryItem(
                        id = videoId,
                        title = title.ifBlank { videoId },
                        kind = LibraryCodec.KIND_VIDEO,
                        addedAt = System.currentTimeMillis(),
                    ),
                ),
            )
            true
        }
    }

    fun isBookmarked(context: Context, videoId: String): Boolean =
        LibraryCodec.contains(bookmarks(context), videoId, LibraryCodec.KIND_VIDEO)

    /** Запомнить поисковый запрос. */
    fun addSearch(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val item = LibraryItem(
            id = trimmed.lowercase(),
            title = trimmed,
            kind = LibraryCodec.KIND_SEARCH,
            addedAt = System.currentTimeMillis(),
        )
        write(
            context,
            KEY_SEARCHES,
            LibraryCodec.addItem(searches(context), item, max = LibraryCodec.MAX_SEARCHES),
        )
    }

    fun clearHistory(context: Context) = write(context, KEY_HISTORY, emptyList())

    fun clearSearches(context: Context) = write(context, KEY_SEARCHES, emptyList())

    fun clearBookmarks(context: Context) = write(context, KEY_BOOKMARKS, emptyList())
}
