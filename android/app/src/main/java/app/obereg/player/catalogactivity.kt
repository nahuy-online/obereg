package app.obereg.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Каталог видео: поиск и список.
 *
 * Источник каталога — в ядре (сейчас локальный список, далее сетевой источник).
 * Экран не знает, откуда приходят элементы: он получает список и передаёт
 * выбранную ссылку в плеер.
 */
class CatalogActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private val items = ArrayList<CatalogItem>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catalog)

        searchInput = findViewById(R.id.searchInput)
        listView = findViewById(R.id.resultsList)
        statusView = findViewById(R.id.catalogStatus)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            openPlayer(item)
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener { load(searchInput.text.toString()) }

        Handler(Looper.getMainLooper()).post { load("") }
    }

    private fun load(query: String) {
        if (!CoreHolder.isCoreAvailable()) {
            statusView.text = "ядро недоступно: ${CoreHolder.coreAvailabilityError()}"
            return
        }
        // Сессия нужна, чтобы обратиться к каталогу ядра.
        CoreHolder.ensureSession(this, CoreHolder.desiredMode(this))

        val found = runCatching { CoreHolder.catalog(query, 50) }.getOrElse { error ->
            statusView.text = "ошибка каталога: ${error.message}"
            return
        }

        items.clear()
        items.addAll(found)
        adapter.clear()
        adapter.addAll(items.map { "${it.title}   (${it.durationLabel()})\n${it.url}" })
        adapter.notifyDataSetChanged()

        statusView.text = if (items.isEmpty()) {
            "ничего не найдено по запросу «$query»"
        } else {
            "найдено: ${items.size} · источник каталога: local"
        }
    }

    private fun openPlayer(item: CatalogItem) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, item.url)
            .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        startActivity(intent)
    }
}
