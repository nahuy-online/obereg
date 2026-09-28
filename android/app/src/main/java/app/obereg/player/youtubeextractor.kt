package app.obereg.player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

    /** Сервис отдал видео и звук раздельно — играем их вместе одним плеером. */
    data class SeparateStreams(
        val video: StreamFormat,
        val audio: StreamFormat,
        val total: Int,
    ) : ExtractResult

    /** Извлечение недоступно: сеть, отказ сервиса или новый формат ответа. */
    data class Failure(val reason: String) : ExtractResult

    /** Плееру можно отдавать только успешные исходы. */
    val isUsable: Boolean
        get() = this !is Failure
}

/**
 * Профиль клиента видеосервиса: чем приложение представляется сервису.
 *
 * Ключ здесь намеренно отсутствует: настоящий ключ берётся у самого сервиса
 * (со страницы просмотра), чтобы в коде не было ни зашитых, ни тем более
 * неполных ключей. Неполный ключ — уже однажды полученная ошибка «HTTP 400».
 * `embedUrl` — для встроенных клиентов: им сервису нужно сообщить,
 * откуда видео «встроено». Эти клиенты обходятся без токена подтверждения.
 */
data class ClientProfile(
    val label: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val clientHeaderName: String,
    val extraContext: Map<String, Any>,
    val embedUrl: String? = null,
)

/** Готовый HTTP-запрос: адрес, тело, заголовки. Вынесен отдельно, чтобы проверялся тестами. */
data class RequestSpec(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

/** Результат сетевого обращения. */
sealed interface Fetch {
    data class Ok(val text: String) : Fetch
    data class Failed(val reason: String) : Fetch
}

/**
 * Сборка запросов к видеосервису и разбор страницы просмотра.
 *
 * Основной путь — страница просмотра: в ней сервис сам отдаёт готовый ответ плеера,
 * и никакой ключ не нужен. Запасной путь — запрос к программному интерфейсу,
 * где ключ тоже берётся со страницы, а не из кода.
 */
object PlayerApi {
    const val ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    const val WATCH_ENDPOINT = "https://www.youtube.com/watch"

    /** Обычный браузерный агент: страница просмотра отдаётся именно ему. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    /**
     * Порядок попыток: сначала встроенные клиенты — им не нужен токен подтверждения
     * (PO token), который мобильные клиенты обязаны предоставлять с 2024 года;
     * затем мобильные и веб.
     */
    val profiles: List<ClientProfile> = listOf(
        ClientProfile(
            label = "TV_EMBEDDED",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36",
            clientHeaderName = "85",
            extraContext = emptyMap(),
            embedUrl = "https://www.youtube.com/",
        ),
        ClientProfile(
            label = "WEB_EMBEDDED",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20240723.01.00",
            userAgent = BROWSER_USER_AGENT,
            clientHeaderName = "56",
            extraContext = emptyMap(),
            embedUrl = "https://www.youtube.com/",
        ),
        ClientProfile(
            label = "ANDROID",
            clientName = "ANDROID",
            clientVersion = "19.09.37",
            userAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
            clientHeaderName = "3",
            extraContext = mapOf("androidSdkVersion" to 30),
        ),
        ClientProfile(
            label = "IOS",
            clientName = "IOS",
            clientVersion = "19.09.3",
            userAgent = "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4_1 like Mac OS X)",
            clientHeaderName = "5",
            extraContext = mapOf(
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "17.4.1.21E236",
            ),
        ),
        ClientProfile(
            label = "WEB",
            clientName = "WEB",
            clientVersion = "2.20240926.01.00",
            userAgent = BROWSER_USER_AGENT,
            clientHeaderName = "1",
            extraContext = emptyMap(),
        ),
    )

    /** Тело запроса: контекст клиента + идентификатор видео. */
    fun requestBody(profile: ClientProfile, videoId: String): String {
        val client = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("hl", "ru")
            .put("gl", "US")
        for ((name, value) in profile.extraContext) {
            client.put(name, value)
        }
        val context = JSONObject().put("client", client)
        if (profile.embedUrl != null) {
            // Встроенным клиентам нужно указать, откуда видео «встроено».
            context.put("thirdParty", JSONObject().put("embedUrl", profile.embedUrl))
        }
        return JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
    }

    /**
     * Полное описание запроса для профиля.
     *
     * Ключ передаётся аргументом: единственный допустимый источник — сама страница сервиса.
     */
    fun spec(profile: ClientProfile, videoId: String, apiKey: String): RequestSpec = RequestSpec(
        url = "$ENDPOINT?key=$apiKey&prettyPrint=false",
        body = requestBody(profile, videoId),
        headers = mapOf(
            "User-Agent" to profile.userAgent,
            "Content-Type" to "application/json",
            "X-Youtube-Client-Name" to profile.clientHeaderName,
            "X-Youtube-Client-Version" to profile.clientVersion,
            "Accept-Language" to "ru-RU,ru;q=0.9",
        ),
    )

    /** Адрес страницы просмотра для видео. */
    fun watchUrl(videoId: String): String = "$WATCH_ENDPOINT?v=$videoId&hl=ru"

    /**
     * Достать готовый ответ плеера из страницы просмотра.
     *
     * Сервис кладёт его в переменную `ytInitialPlayerResponse`. Ищем все вхождения
     * и берём первое, которое действительно похоже на ответ плеера: так устойчиво
     * к тому, что имя переменной встречается в странице несколько раз.
     */
    fun extractPlayerResponse(html: String): String? {
        val marker = "ytInitialPlayerResponse"
        var from = html.indexOf(marker)
        while (from >= 0) {
            val brace = html.indexOf('{', from)
            if (brace < 0) return null
            val candidate = balancedObject(html, brace)
            if (candidate != null &&
                (candidate.contains("\"streamingData\"") || candidate.contains("\"playabilityStatus\""))
            ) {
                return candidate
            }
            from = html.indexOf(marker, from + marker.length)
        }
        return null
    }

    /** Ключ программного интерфейса, который сервис сам сообщает на странице. */
    fun extractApiKey(html: String): String? =
        Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    /** Версия веб-клиента, которую сервис сам сообщает на странице. */
    fun extractClientVersion(html: String): String? =
        Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    /**
     * Взять объект JSON с учётом вложенности, строк и экранирования.
     *
     * Просто найти закрывающую скобку нельзя: внутри строк встречаются и скобки,
     * и кавычки. Поэтому идём по символам и считаем глубину только вне строк.
     */
    internal fun balancedObject(text: String, start: Int): String? {
        if (start < 0 || start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < text.length) {
            val symbol = text[index]
            when {
                escaped -> escaped = false
                inString && symbol == '\\' -> escaped = true
                symbol == '"' -> inString = !inString
                !inString && symbol == '{' -> depth++
                !inString && symbol == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }
}

/**
 * Извлечение потоков у видеосервиса.
 *
 * Оболочка отвечает только за сетевые обращения: HTTPS-клиент у неё уже есть,
 * и он маршрутизируется через встроенный туннель (SOCKS5 ядра). Разбор ответа и выбор
 * потока делает ядро — там эта логика покрыта тестами и переиспользуется на других
 * платформах.
 */
class YouTubeExtractor(
    private val proxyAddress: String? = null,
    private val client: OkHttpClient = buildClient(proxyAddress),
    /** Cookie домена сервиса (например, из встроенного браузера, где проверка уже пройдена). */
    private val cookieProvider: (() -> String?)? = null,
) {

    /**
     * Найти поток для видео по идентификатору.
     *
     * Сначала читаем страницу просмотра и берём из неё готовый ответ плеера;
     * если там пусто — берём со страницы ключ и спрашиваем программный интерфейс
     * по очереди от лица нескольких клиентов. В отчёте об ошибке видны причины всех попыток.
     */
    fun resolve(videoId: String): ExtractResult {
        val notes = mutableListOf<String>()

        val page = when (val fetched = fetch(PlayerApi.watchUrl(videoId), PlayerApi.BROWSER_USER_AGENT)) {
            is Fetch.Failed -> null.also { notes += "страница: ${fetched.reason}" }
            is Fetch.Ok -> fetched.text
        }

        if (page != null) {
            val playerResponse = PlayerApi.extractPlayerResponse(page)
            if (playerResponse == null) {
                notes += "на странице нет данных плеера"
            } else {
                val result = interpret(CoreHolder.analyzePlayerResponse(playerResponse))
                if (result.isUsable) return result
                notes += "страница: ${(result as ExtractResult.Failure).reason}"
            }

            val apiKey = PlayerApi.extractApiKey(page)
            if (apiKey == null) {
                notes += "на странице нет ключа интерфейса"
            } else {
                val pageVersion = PlayerApi.extractClientVersion(page)
                for (profile in PlayerApi.profiles) {
                    val tuned = if (profile.label == "WEB" && pageVersion != null) {
                        profile.copy(clientVersion = pageVersion)
                    } else {
                        profile
                    }
                    when (val result = attempt(tuned, videoId, apiKey)) {
                        is ExtractResult.Failure -> notes += "${tuned.label}: ${result.reason}"
                        else -> return result
                    }
                }
            }
        }

        return ExtractResult.Failure(notes.joinToString(" | "))
    }

    /** Одна попытка конкретным профилем клиента. */
    private fun attempt(profile: ClientProfile, videoId: String, apiKey: String): ExtractResult {
        val spec = PlayerApi.spec(profile, videoId, apiKey)
        val raw = when (val fetched = post(spec)) {
            is Fetch.Failed -> return ExtractResult.Failure(fetched.reason)
            is Fetch.Ok -> fetched.text
        }
        return interpret(CoreHolder.analyzePlayerResponse(raw))
    }

    /** Забрать страницу или текст по адресу. */
    private fun fetch(url: String, userAgent: String): Fetch {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "ru-RU,ru;q=0.9")
        cookieHeader()?.let { builder.header("Cookie", it) }
        val request = builder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Fetch.Failed("HTTP ${response.code} — ${excerpt(text)}")
                } else {
                    Fetch.Ok(text)
                }
            }
        } catch (error: IOException) {
            Fetch.Failed("сеть недоступна: ${error.message}")
        }
    }

    /** Отправить запрос к программному интерфейсу. */
    private fun post(spec: RequestSpec): Fetch {
        val builder = Request.Builder()
            .url(spec.url)
            .post(spec.body.toRequestBody("application/json".toMediaType()))
            .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
        cookieHeader()?.let { builder.header("Cookie", it) }
        val request = builder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Fetch.Failed("HTTP ${response.code} — ${excerpt(text)}")
                } else {
                    Fetch.Ok(text)
                }
            }
        } catch (error: IOException) {
            Fetch.Failed("сеть недоступна: ${error.message}")
        }
    }

    /** Заголовок Cookie, если источник его дал. Ошибку источника не пробрасываем: без cookie просто пробуем снова. */
    private fun cookieHeader(): String? =
        cookieProvider?.let { provider -> runCatching(provider).getOrNull() }
            ?.takeIf { it.isNotBlank() }

    companion object {
        /** Совместимость: адрес сервиса, как раньше. */
        const val PLAYER_ENDPOINT = PlayerApi.ENDPOINT

        /** Короткий читаемый фрагмент ответа для строки статуса. */
        internal fun excerpt(text: String, limit: Int = 220): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            if (flat.isEmpty()) return "пустой ответ"
            return if (flat.length <= limit) flat else flat.take(limit) + "…"
        }

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
            val root = runCatching { JSONObject(coreJson) }.getOrNull()
                ?: return ExtractResult.Failure("ядро вернуло нечитаемый ответ")
            return when (root.optString("status")) {
                "success" -> {
                    val format = root.optJSONObject("format")?.let(::toFormat)
                        ?: return ExtractResult.Failure("ядро не вернуло поток")
                    ExtractResult.Success(format, root.optInt("total"))
                }
                "separate_streams" -> {
                    val video = root.optJSONObject("video")?.let(::toFormat)
                    val audio = root.optJSONObject("audio")?.let(::toFormat)
                    if (video == null || audio == null) {
                        ExtractResult.Failure("ядро вернуло неполную пару потоков")
                    } else {
                        ExtractResult.SeparateStreams(video, audio, root.optInt("total"))
                    }
                }
                "no_muxed_stream" -> ExtractResult.NoMuxedStream(root.optInt("adaptive"))
                "refused" -> ExtractResult.Failure("сервис отказал: ${root.optString("reason")}")
                "malformed" -> ExtractResult.Failure(root.optString("reason"))
                else -> ExtractResult.Failure("неожиданный ответ ядра: $coreJson")
            }
        }

        /** Перевести один поток из ответа ядра. */
        private fun toFormat(json: JSONObject): StreamFormat = StreamFormat(
            itag = json.optInt("itag"),
            mimeType = json.optString("mime_type"),
            url = json.optString("url"),
            qualityLabel = json.optString("quality_label").takeIf { it.isNotEmpty() && it != "null" },
            bitrate = json.optInt("bitrate"),
        )
    }
}
