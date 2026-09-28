package app.obereg.player

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Библиотека: история просмотров, закладки и недавние поиски.
 *
 * Всё хранится на устройстве. Нажатие на запись открывает видео или повторяет поиск
 * на экране видеосервиса — то есть библиотека работает и без сети, а просмотр идёт
 * обычным путём (экран сервиса), где проверки проходит движок браузера.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var mode: String = MODE_HISTORY
    private var items: List<LibraryItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        listView = findViewById(R.id.libraryList)
        statusView = findViewById(R.id.libraryStatus)

        findViewById<Button>(R.id.btnModeHistory).setOnClickListener { show(MODE_HISTORY) }
        findViewById<Button>(R.id.btnModeBookmarks).setOnClickListener { show(MODE_BOOKMARKS) }
        findViewById<Button>(R.id.btnModeSearches).setOnClickListener { show(MODE_SEARCHES) }
        findViewById<Button>(R.id.btnLibraryClear).setOnClickListener { clearCurrent() }
        findViewById<Button>(R.id.btnLibraryBack).setOnClickListener { finish() }

        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let(::openItem)
        }

        show(MODE_HISTORY)
    }

    private fun show(newMode: String) {
        mode = newMode
        items = when (mode) {
            MODE_BOOKMARKS -> VideoStore.bookmarks(this)
            MODE_SEARCHES -> VideoStore.searches(this)
            else -> VideoStore.history(this)
        }
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            items.map(::label),
        )
        val empty = when (mode) {
            MODE_BOOKMARKS -> "закладок пока нет: откройте видео и нажмите «★ В закладки»"
            MODE_SEARCHES -> "поисков пока нет: выполните поиск на экране YouTube"
            else -> "история пуста: откройте любое видео"
        }
        statusView.text = if (items.isEmpty()) {
            empty
        } else {
            "записей: ${items.size} — нажмите любую, чтобы открыть"
        }
    }

    private fun label(item: LibraryItem): String {
        val date = android.text.format.DateFormat.format("dd.MM HH:mm", item.addedAt)
        return when (item.kind) {
            LibraryCodec.KIND_SEARCH -> "поиск: ${item.title}  ·  $date"
            else -> "${item.title}  ·  $date"
        }
    }

    private fun openItem(item: LibraryItem) {
        val intent = Intent(this, YouTubeActivity::class.java)
        if (item.kind == LibraryCodec.KIND_SEARCH) {
            intent.putExtra(YouTubeActivity.EXTRA_QUERY, item.title)
        } else {
            intent.putExtra(YouTubeActivity.EXTRA_VIDEO_ID, item.id)
        }
        startActivity(intent)
        statusView.text = "открываю: ${item.title}"
    }

    private fun clearCurrent() {
        when (mode) {
            MODE_BOOKMARKS -> VideoStore.clearBookmarks(this)
            MODE_SEARCHES -> VideoStore.clearSearches(this)
            else -> VideoStore.clearHistory(this)
        }
        val what = when (mode) {
            MODE_BOOKMARKS -> "закладки"
            MODE_SEARCHES -> "недавние поиски"
            else -> "историю"
        }
        show(mode)
        statusView.text = "очистил $what"
    }

    companion object {
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_SEARCHES = "searches"
    }
}
