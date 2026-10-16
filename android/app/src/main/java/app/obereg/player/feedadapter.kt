package app.obereg.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Список-карточки оболочки: миниатюра, название, подпись и метка «Рекомендуем».
 *
 * Строки приходят из [FeedCodec.FeedRow]: у лент это ролики (название, канал, просмотры,
 * длительность), у плейлистов — их записи, у библиотеки — недавние ролики. Наша
 * рекомендация отмечается меткой, а не отдельной полкой, — так она выглядит частью ленты.
 * Миниатюры грузит [ThumbLoader] с кэшем; повторная привязка защищена меткой строки.
 */
class FeedAdapter(private val rows: List<FeedCodec.FeedRow>) : BaseAdapter() {

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        val row = rows[position]

        val title = view.findViewById<TextView>(R.id.feedRowTitle)
        val sub = view.findViewById<TextView>(R.id.feedRowSub)
        val badge = view.findViewById<TextView>(R.id.feedRowBadge)
        val thumb = view.findViewById<ImageView>(R.id.feedThumb)
        val queueBtn = view.findViewById<TextView>(R.id.feedQueueBtn)

        val vid = row.tag
        val isVideo = vid.length == 11 && !vid.contains(":")
        queueBtn.visibility = if (isVideo) View.VISIBLE else View.GONE
        queueBtn.setOnClickListener {
            val n = PlayerSession.addToQueue(parent.context, vid, row.title ?: vid)
            android.widget.Toast.makeText(
                parent.context,
                "В очередь: " + (row.title ?: vid).take(40) + " (всего " + n + ")",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            DiagLog.add("лента: в очередь — " + vid + " (всего " + n + ")")
        }

        title.text = row.title ?: row.label
        sub.text = row.sub.orEmpty()
        sub.visibility = if (row.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        badge.visibility = if (row.promo) View.VISIBLE else View.GONE

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
        /** Цвет заглушки, пока миниатюра не пришла. */
        private const val PLACEHOLDER_COLOR = 0xFF20242C.toInt()
    }
}
