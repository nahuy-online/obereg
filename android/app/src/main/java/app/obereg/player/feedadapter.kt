package app.obereg.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Список карточек: обложка и подпись.
 *
 * Работает и для роликов, и для плейлистов — строка одна и та же ([FeedCodec.FeedRow]).
 * Обложки грузятся своим загрузчиком ([ThumbLoader]) и кэшируются в памяти. Метка строки
 * защищает от показа чужой картинки при быстрой прокрутке: результат применяется только
 * если строка всё ещё показывает тот же элемент.
 */
class FeedAdapter(private val rows: List<FeedCodec.FeedRow>) : BaseAdapter() {

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        val row = rows[position]

        val label = view.findViewById<TextView>(R.id.feedRowLabel)
        val thumb = view.findViewById<ImageView>(R.id.feedThumb)

        label.text = row.label
        thumb.tag = row.tag
        thumb.setBackgroundColor(PLACEHOLDER_COLOR)

        val url = row.thumbUrl
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
            if (thumb.tag == row.tag) {
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
