package app.obereg.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Список ленты: обложка и подпись.
 *
 * Обложки грузятся своим загрузчиком ([ThumbLoader]) и кэшируются в памяти. Тег строки
 * защищает от показа чужой картинки при быстрой прокрутке: результат применяется только
 * если строка всё ещё показывает то же видео.
 */
class FeedAdapter(private val items: List<FeedItem>) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        val item = items[position]

        val label = view.findViewById<TextView>(R.id.feedRowLabel)
        val thumb = view.findViewById<ImageView>(R.id.feedThumb)

        label.text = FeedCodec.label(item)
        thumb.tag = item.videoId
        thumb.setBackgroundColor(PLACEHOLDER_COLOR)

        val url = item.thumbnailUrl
        if (url == null) {
            thumb.setImageBitmap(null)
            return view
        }
        val ready = ThumbLoader.cached(url)
        if (ready != null) {
            thumb.setImageBitmap(ready)
            return view
        }
        thumb.setImageBitmap(null)
        ThumbLoader.load(url) { bitmap ->
            if (thumb.tag == item.videoId) {
                thumb.setImageBitmap(bitmap)
            }
        }
        return view
    }

    companion object {
        /** Цвет подложки, пока картинка не пришла. */
        private const val PLACEHOLDER_COLOR = 0xFF20242C.toInt()
    }
}
