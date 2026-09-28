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
        /** Кто выдал поток: плеер должен обращаться к нему с тем же агентом. */
        val clientUserAgent: String = "",
    ) : ExtractResult

    /** Сервис отдал только раздельные потоки: нужна склейка. */
    data class NoMuxedStream(val adaptive: Int) : ExtractResult

    /** Сервис отдал видео и звук раздельно — играем их вместе одним плеером. */
    data class SeparateStreams(
        val video: StreamFormat,
        val audio: StreamFormat,
        val total: Int,
        /** Кто выдал потоки: плеер должен обращаться к ним с тем же агентом. */
        val clientUserAgent: String = "",
    ) : ExtractResult

    /** Извлечение недоступно: сеть, отказ сервиса или новый формат ответа. */
    data class Failure(val reason: String) : ExtractResult

    /** Плееру можно отдавать только то, что реально играется. */
    val isUsable: Boolean
        get() = this is Success || this is SeparateStreams
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
            label = "ANDROID_VR",
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
            clientHeaderName = "28",
            extraContext = mapOf(
                "androidSdkVersion" to 32,
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "osName" to "Android",
                "osVersion" to "12",
            ),
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

    /** Описание запроса для профиля. */
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
     * Заголовки для запроса самого видеофайла.
     *
     * Адрес потока выдаётся конкретному клиенту и его сеансу: если запросить его с другим
     * агентом, без источника или без cookie сеанса, сервис отвечает отказом
     * (в Media3 это «неверный код ответа», обычная причина — 403).
     */
    fun mediaHeaders(clientUserAgent: String, cookies: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "https://www.youtube.com/",
            "Origin" to "https://www.youtube.com",
            "Accept-Language" to "ru-RU,ru;q=0.9",
        )
        if (clientUserAgent.isNotBlank()) {
            headers["User-Agent"] = clientUserAgent
        }
        if (!cookies.isNullOrBlank()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    /** Убрать повторы: разные клиенты могут выдать одну и ту же ссылку. */
    fun distinctByUrl(results: List<ExtractResult>): List<ExtractResult> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<ExtractResult>()
        for (result in results) {
            val key = when (result) {
                is ExtractResult.Success -> result.format.url
                is ExtractResult.SeparateStreams -> "${result.video.url}|${result.audio.url}"
                else -> null
            } ?: continue
            if (seen.add(key)) unique += result
        }
        return unique
    }

    /** Нужен ли вход: признаки авторизации в cookie встроенного браузера. */
    fun hasLoginCookies(cookies: String?): Boolean {
        if (cookies.isNullOrBlank()) return false
        val markers = listOf("SAPISID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO")
        return markers.any { marker -> cookies.contains(marker) }
    }

    /** Описание состояния входа — для строки статуса. */
    fun loginStateLabel(cookies: String?): String = when {
        cookies.isNullOrBlank() ->
            "cookie нет: экран видеосервиса ещё не открывали"
        hasLoginCookies(cookies) ->
            "вход выполнен — cookie авторизации на месте"
        else ->
            "вход не выполнен: cookie есть, но авторизации среди них нет"
    }

    /** Короткая сводка пробы ссылки на поток: код и собственный ответ сервиса. */
    fun probeSummary(code: Int, body: String): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        val short = if (flat.length <= 160) flat else flat.take(160) + "…"
        return "HTTP $code" + if (short.isEmpty()) " (пустой ответ)" else ": $short"
    }

    /**
     * Что именно отдал сервис: код, тип содержимого и вид первых байтов.
     *
     * Без этого не отличить одно от другого: «не распознан контейнер» одинаково выглядит
     * и когда пришёл WebM, и когда вместо видео пришла HTML-страница.
     */
    fun responseFingerprint(code: Int, contentType: String?, head: ByteArray): String {
        val text = String(head, Charsets.ISO_8859_1)
        val magic = when {
            text.startsWith("\u001A\u0045\u00DF\u00A3") -> "WebM (Матрёшка)"
            text.startsWith("ftyp") -> "MP4"
            text.length >= 4 && text[3] == 'y' -> "MP4 (ftyp)"
            text.startsWith("<!DO") || text.startsWith("<htm") -> "HTML-страница (не видео!)"
            text.startsWith("\u001F\u008B") -> "сжатый ответ (gzip)"
            else -> "неизвестное начало"
        }
        return "HTTP $code, тип: ${contentType ?: "нет"}, начало: $magic"
    }

    /**
     * Что сказать пользователю при отказе воспроизведения.
     *
     * На устройстве подтвердилось: обычные ссылки на файл сервис отдаёт только с подтверждением
     * клиента (403), а адреса, которые играет браузер, используют потоковый протокол,
     * которого нет в открытых плеерах (потому «не распознан контейнер»).
     * Честная подсказка важнее технического кода.
     */
    fun playbackHint(errorCodeName: String): String = when (errorCodeName) {
        "ERROR_CODE_IO_BAD_HTTP_STATUS" ->
            ". Сервис отдаёт этот файл только с подтверждением клиента; рабочий путь — «Экран YouTube»"
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ->
            ". Браузер получает поток в служебном формате (UMP), который плеер не разбирает; рабочий путь — «Экран YouTube»"
        else -> ""
    }

    /** Человекочитаемая причина любого непригодного исхода — без рискованных приведений типа. */
    fun reasonOf(result: ExtractResult): String = when (result) {
        is ExtractResult.Failure -> result.reason
        is ExtractResult.NoMuxedStream ->
            "раздельные потоки (${result.adaptive}) без звуковой дорожки"
        is ExtractResult.Success -> "поток найден"
        is ExtractResult.SeparateStreams -> "пара видео+звук найдена"
    }

    /** Похоже ли на поток видеофайла (а не на страницу или картинку). */
    fun looksLikeStream(url: String): Boolean =
        url.contains("googlevideo.com") && url.contains("videoplayback")

    /** Что это за поток: «video», «audio» или ничего. */
    fun streamKind(url: String): String? = when {
        !looksLikeStream(url) -> null
        url.contains("mime=audio") -> "audio"
        // Запросы к медиасерверу бывают и без параметра mime — такие считаем видео:
        // раньше из-за строгой проверки они не попадали в перехват (на устройстве
        // было видно 12 обращений и ни одного пойманного потока).
        else -> "video"
    }

    /**
     * Привести пойманный адрес к виду, пригодному для нашего плеера.
     *
     * Браузер запрашивает файл кусками и добавляет в адрес диапазон (`range=…`).
     * Плеер сам управляет диапазонами, поэтому этот параметр нужно убрать — иначе
     * он запросит только один кусок и остановится.
     */
    fun streamUrlForPlayer(url: String): String =
        url.replace(Regex("[&?]range=[^&]*"), "").replace(Regex("[&?]rn=[^&]*"), "")

    /** Проставить в результате агента того клиента, который его выдал. */
    fun withAgent(result: ExtractResult, agent: String): ExtractResult = when (result) {
        is ExtractResult.Success -> result.copy(clientUserAgent = agent)
        is ExtractResult.SeparateStreams -> result.copy(clientUserAgent = agent)
        else -> result
    }

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

    /** Итог попыток: пригодные варианты по порядку предпочтения и причины отказов. */
    data class ResolveOutcome(
        /** Варианты для воспроизведения: первый — основной, остальные — запасные. */
        val usable: List<ExtractResult>,
        /** Почему что-то не получилось — для строки статуса. */
        val notes: List<String>,
    )

    /**
     * Собрать варианты воспроизведения.
     *
     * Раньше возвращался только первый успешный ответ, и если его ссылка не воспроизводилась
     * (сервис отвечает 403 на чужой запрос), других попыток не было. Теперь собираем
     * несколько вариантов от разных клиентов — плеер переберёт их по очереди.
     */
    fun resolveDetailed(videoId: String): ResolveOutcome {
        val usable = mutableListOf<ExtractResult>()
        val notes = mutableListOf<String>()

        val page = when (val fetched = fetch(PlayerApi.watchUrl(videoId), PlayerApi.BROWSER_USER_AGENT)) {
            is Fetch.Failed -> null.also { notes += "страница: ${fetched.reason}" }
            is Fetch.Ok -> fetched.text
        }

        if (page != null) {
            // Первая строка отчёта — что именно пришло со страницы: без этого не понять,
            // где именно стоит стена (согласие, проверка «не бот», пустая страница).
            notes += "страница: ${page.length} символов, cookie: " +
                if (cookieHeader() != null) "есть" else "нет"

            val playerResponse = PlayerApi.extractPlayerResponse(page)
            if (playerResponse == null) {
                notes += "ответа плеера на странице нет"
            } else {
                val result = interpret(CoreHolder.analyzePlayerResponse(playerResponse))
                if (result.isUsable) {
                    usable += PlayerApi.withAgent(result, PlayerApi.BROWSER_USER_AGENT)
                } else {
                    notes += "ответ плеера: ${PlayerApi.reasonOf(result)}"
                }
            }

            // Второй заход: мобильная страница — иногда отдаёт другой ответ, чем обычная.
            if (usable.isEmpty()) {
                val mobile = when (
                    val fetched = fetch(
                        "https://m.youtube.com/watch?v=$videoId",
                        "Mozilla/5.0 (Linux; Android 12; SM-S908B) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36",
                    )
                ) {
                    is Fetch.Failed -> null.also { notes += "мобильная страница: ${fetched.reason}" }
                    is Fetch.Ok -> fetched.text
                }
                if (mobile != null) {
                    val mobileResponse = PlayerApi.extractPlayerResponse(mobile)
                    if (mobileResponse == null) {
                        notes += "мобильная страница: ${mobile.length} символов, ответа плеера нет"
                    } else {
                        val result = interpret(CoreHolder.analyzePlayerResponse(mobileResponse))
                        if (result.isUsable) {
                            usable += PlayerApi.withAgent(result, PlayerApi.BROWSER_USER_AGENT)
                        } else {
                            notes += "мобильная страница: ${PlayerApi.reasonOf(result)}"
                        }
                    }
                }
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
                        is ExtractResult.Success, is ExtractResult.SeparateStreams -> usable += result
                        else -> notes += "${tuned.label}: ${PlayerApi.reasonOf(result)}"
                    }
                    // Двух запасных вариантов достаточно: больше — лишние запросы к сервису.
                    if (usable.size >= 3) break
                }
            }
        }

        return ResolveOutcome(PlayerApi.distinctByUrl(usable), notes)
    }

    /** Найти поток для видео по идентификатору (первый пригодный вариант). */
    fun resolve(videoId: String): ExtractResult {
        val outcome = resolveDetailed(videoId)
        return outcome.usable.firstOrNull()
            ?: ExtractResult.Failure(outcome.notes.joinToString(" | ").ifBlank { "сервис не дал потоков" })
    }

    /** Одна попытка конкретным профилем клиента. */
    private fun attempt(profile: ClientProfile, videoId: String, apiKey: String): ExtractResult {
        val spec = PlayerApi.spec(profile, videoId, apiKey)
        val raw = when (val fetched = post(spec)) {
            is Fetch.Failed -> return ExtractResult.Failure(fetched.reason)
            is Fetch.Ok -> fetched.text
        }
        val result = interpret(CoreHolder.analyzePlayerResponse(raw))
        return if (result is ExtractResult.Failure) result else PlayerApi.withAgent(result, profile.userAgent)
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
