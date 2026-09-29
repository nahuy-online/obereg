package app.obereg.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Загрузчик обложек для ленты.
 *
 * Свой, без сторонних библиотек: картинки небольшие, кэш в памяти на десятки штук,
 * три потока на загрузку. Обложки отдаёт обычный CDN сервиса, поэтому туннель здесь
 * не нужен — но если когда-нибудь понадобится, сюда легко добавить маршрут.
 */
object ThumbLoader {

    private val cache = LruCache<String, Bitmap>(80)

    private val client = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(15))
        .build()

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Уже загруженная картинка — чтобы список не мигал при прокрутке. */
    fun cached(url: String): Bitmap? = cache.get(url)

    /** Загрузить обложку и вернуть её в поток экрана. */
    fun load(url: String, onReady: (Bitmap) -> Unit) {
        cache.get(url)?.let { ready ->
            onReady(ready)
            return
        }
        executor.execute {
            val bitmap = try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        null
                    } else {
                        BitmapFactory.decodeStream(response.body?.byteStream())
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                cache.put(url, bitmap)
                main.post { onReady(bitmap) }
            }
        }
    }
}
