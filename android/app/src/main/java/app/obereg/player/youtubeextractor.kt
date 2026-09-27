package app.obereg.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Один доступный поток видео (заполняется ядром). */
data class StreamFormat(
    val itag: Int,
    val mimeType: String,
    val url: String,
    val qualityLabel: String?,
    val bitrate: Int,
)

/** Результат извлечения. */
sealed interface ExtractResult {
    /** Найден поток, который можно отдать плееру сразу. */
    data class Success(
        val format: StreamFormat,
        val total: Int,
    ) : ExtractResult

    /** Сервис отдал только раздельные потоки: нужна склейка. */
    data class NoMuxedStream(val adaptive: Int) : ExtractResult

    /** Извлечение недоступно: сеть, отказ сервиса или новый формат ответа. */
    data class Failure(val reason: String) : ExtractResult
}

/**
 * Извлечение потоков у видеосервиса.
 *
 * Оболочка отвечает только за сетевой запрос: HTTPS-клиент у неё уже есть,
 * и он маршрутизируется через встроенный туннель (SOCKS5 ядра). Разбор ответа и выбор
 * потока делает ядро — там эта логика покрыта тестами и переиспользуется на других
 * платформах.
 */
class YouTubeExtractor(
    private val proxyAddress: String? = null,
    private val client: OkHttpClient = buildClient(proxyAddress),
) {

    /** Найти поток для видео по идентификатору. */
    fun resolve(videoId: String): ExtractResult {
        val body = PLAYER_REQUEST_BODY.replace("\$VIDEO_ID", videoId)

        val request = Request.Builder()
            .url("$PLAYER_ENDPOINT?key=$PUBLIC_KEY&prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header(
                "User-Agent",
                "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
            )
            .header("Content-Type", "application/json")
            .build()

        val raw = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ExtractResult.Failure("сервис ответил HTTP ${response.code}")
                }
                text
            }
        } catch (error: IOException) {
            return ExtractResult.Failure("сеть недоступна: ${error.message}")
        }

        return interpret(CoreHolder.analyzePlayerResponse(raw))
    }

    companion object {
        /** Сборка запроса: подстановка идентификатора делается строкой, чтобы не тянуть JSON-писатель. */
        private val PLAYER_REQUEST_BODY = """
            {
              "context": {
                "client": {
                  "clientName": "ANDROID",
                  "clientVersion": "19.09.37",
                  "androidSdkVersion": 30,
                  "hl": "ru",
                  "gl": "US"
                }
              },
              "videoId": "${'$'}VIDEO_ID",
              "contentCheckOk": true,
              "racyCheckOk": true
            }
        """.trimIndent()

        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
        private const val PUBLIC_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"

        private fun buildClient(proxyAddress: String?): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
            if (proxyAddress != null) {
                val parts = proxyAddress.split(":")
                if (parts.size == 2) {
                    builder.proxy(
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt())),
                    )
                }
            }
            return builder.build()
        }

        /** Достать идентификатор видео из ссылки YouTube или из «голого» идентификатора. */
        fun videoIdFrom(raw: String): String? {
            if (raw.isEmpty()) return null
            val patterns = listOf(
                Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
                Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
                Regex("""/embed/([A-Za-z0-9_-]{11})"""),
                Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
                Regex("""^([A-Za-z0-9_-]{11})$"""),
            )
            for (pattern in patterns) {
                val match = pattern.find(raw)
                if (match != null) return match.groupValues[1]
            }
            return null
        }

        /**
         * Превратить ответ ядра в результат для экрана.
         *
         * Разбор делает ядро; здесь только перевод его ответа в понятный экрану вид.
         */
        fun interpret(coreJson: String): ExtractResult {
            val root = runCatching { org.json.JSONObject(coreJson) }.getOrNull()
                ?: return ExtractResult.Failure("ядро вернуло нечитаемый ответ")
            val status = root.optString("status")
            return when (status) {
                "success" -> {
                    val format = root.optJSONObject("format")
                        ?: return ExtractResult.Failure("ядро не вернуло поток")
                    ExtractResult.Success(
                        format = StreamFormat(
                            itag = format.optInt("itag"),
                            mimeType = format.optString("mime_type"),
                            url = format.optString("url"),
                            qualityLabel = format.optString("quality_label")
                                .takeIf { it.isNotEmpty() && it != "null" },
                            bitrate = format.optInt("bitrate"),
                        ),
                        total = root.optInt("total"),
                    )
                }
                "no_muxed_stream" -> ExtractResult.NoMuxedStream(root.optInt("adaptive"))
                "refused" -> ExtractResult.Failure("сервис отказал: ${root.optString("reason")}")
                "malformed" -> ExtractResult.Failure(root.optString("reason"))
                else -> ExtractResult.Failure("неожиданный ответ ядра: $coreJson")
            }
        }
    }
}
