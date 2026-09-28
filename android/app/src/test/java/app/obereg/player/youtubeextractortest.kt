package app.obereg.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки оболочки извлечения: разбор ссылок и перевод ответа ядра в вид для экрана.
 *
 * Разбор самого ответа видеосервиса живёт в ядре и покрыт Rust-тестами
 * (`crates/obereg-core/tests/extract.rs`) — здесь только то, что относится к оболочке.
 */
class YouTubeExtractorTest {

    /**
     * Регрессия: раньше в запрос уходил ключ-заглушка (`key=***`), и сервис отвечал HTTP 400.
     */
    @Test
    fun profilesCarryNoHardcodedKey() {
        // Ключ не может быть зашит в код: единственный допустимый источник — сама страница сервиса.
        // Раньше здесь был неполный ключ, и сервис отвечал «HTTP 400».
        for (profile in PlayerApi.profiles) {
            val spec = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123")
            assertTrue("${profile.label}: в адрес должен попасть переданный ключ", spec.url.contains("TEST-KEY-123"))
            assertFalse("${profile.label}: заглушки быть не должно", spec.url.contains("***"))
        }
    }

    @Test
    fun requestSpecHasNoPlaceholders() {
        val videoId = "dQw4w9WgXcQ"
        val key = "TEST-KEY-123"
        for (profile in PlayerApi.profiles) {
            val spec = PlayerApi.spec(profile, videoId, key)
            val joined = spec.url + spec.body + spec.headers.values.joinToString()
            assertFalse("${profile.label}: в запросе осталась заглушка", joined.contains("***"))
            assertFalse("${profile.label}: в ключе многоточие", joined.contains("…"))
            assertTrue("${profile.label}: идентификатор видео должен быть в теле", spec.body.contains(videoId))
            assertEquals(
                "${profile.label}: заголовок имени клиента",
                profile.clientHeaderName,
                spec.headers["X-Youtube-Client-Name"],
            )
            assertEquals(
                "${profile.label}: заголовок версии клиента",
                profile.clientVersion,
                spec.headers["X-Youtube-Client-Version"],
            )
        }
    }

    @Test
    fun androidProfileCarriesSdkVersion() {
        val android = PlayerApi.profiles.first { it.label == "ANDROID" }
        assertTrue(
            "у мобильного клиента должна быть версия системы",
            PlayerApi.spec(android, "dQw4w9WgXcQ", "TEST-KEY-123").body.contains("androidSdkVersion"),
        )
    }

    @Test
    fun extractsPlayerResponseFromPage() {
        // Структура как на настоящей странице: ответ лежит в переменной, вокруг — другой код,
        // а внутри самого ответа встречаются и скобки, и кавычки внутри строк.
        val html = """
            <html><script>var meta = {"a":1};</script>
            <script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},
            "streamingData":{"formats":[{"itag":22,"mimeType":"video/mp4","url":"https://example.invalid/v/22","qualityLabel":"720p {не скобка}","bitrate":2000000}],"adaptiveFormats":[]}};</script>
            <script>var other = 1;</script></html>
        """
        val json = PlayerApi.extractPlayerResponse(html)
        assertTrue("ответ плеера должен найтись", json != null)
        val parsed = JSONObject(json!!)
        assertEquals("OK", parsed.getJSONObject("playabilityStatus").getString("status"))
        assertEquals(22, parsed.getJSONObject("streamingData").getJSONArray("formats").getJSONObject(0).getInt("itag"))
    }

    @Test
    fun handlesBracesInsideStrings() {
        val text = """x = {"text":"a } b { c","inner":{"k":1}};"""
        val start = text.indexOf('{')
        val json = PlayerApi.balancedObject(text, start)
        assertEquals("""{"text":"a } b { c","inner":{"k":1}}""", json)
        assertTrue(JSONObject(json!!).getString("text") == "a } b { c")
    }

    @Test
    fun returnsNullWhenPageHasNoPlayerData() {
        assertNull(PlayerApi.extractPlayerResponse("<html>ничего полезного</html>"))
        assertNull(PlayerApi.extractPlayerResponse("ytInitialPlayerResponse без скобок"))
    }

    @Test
    fun extractsKeyAndVersionFromPage() {
        val html = """<script>{"INNERTUBE_API_KEY":"KEY-FROM-PAGE","INNERTUBE_CLIENT_VERSION":"2.2024.01.00.00"}</script>"""
        assertEquals("KEY-FROM-PAGE", PlayerApi.extractApiKey(html))
        assertEquals("2.2024.01.00.00", PlayerApi.extractClientVersion(html))
        assertNull(PlayerApi.extractApiKey("<html>нет ключа</html>"))
    }

    @Test
    fun variantsAreDeduplicatedByUrl() {
        val first = ExtractResult.Success(StreamFormat(18, "video/mp4", "https://example.invalid/a", "360p", 500000), 1)
        val same = ExtractResult.Success(StreamFormat(18, "video/mp4", "https://example.invalid/a", "360p", 500000), 2)
        val second = ExtractResult.Success(StreamFormat(22, "video/mp4", "https://example.invalid/b", "720p", 2000000), 2)
        val failure = ExtractResult.Failure("нет")
        val unique = PlayerApi.distinctByUrl(listOf(first, same, second, failure))
        assertEquals("повторы и отказы отбрасываются", 2, unique.size)
        assertEquals("https://example.invalid/b", (unique[1] as ExtractResult.Success).format.url)
    }

    @Test
    fun vrClientIsAvailableWithoutAttestation() {
        // Клиент очков известен тем, что не требует токена подтверждения,
        // и его ссылки обычно воспроизводятся — нужен как запасной вариант.
        val labels = PlayerApi.profiles.map { it.label }
        assertTrue("клиент очков должен быть в списке: $labels", labels.contains("ANDROID_VR"))
        val vr = PlayerApi.profiles.first { it.label == "ANDROID_VR" }
        assertNull("клиенту очков не нужен источник встраивания", vr.embedUrl)
        assertTrue(
            "у клиента очков должна быть версия системы",
            PlayerApi.spec(vr, "dQw4w9WgXcQ", "TEST-KEY-123").body.contains("androidSdkVersion"),
        )
    }

    @Test
    fun probeSummaryShowsServiceReason() {
        // «403» сам по себе не говорит ничего: причину сервис пишет в теле ответа.
        assertEquals("HTTP 200: ok", PlayerApi.probeSummary(200, "  ok  "))
        assertEquals("HTTP 403 (пустой ответ)", PlayerApi.probeSummary(403, ""))
        val long = PlayerApi.probeSummary(403, "a".repeat(400))
        assertTrue("длинный ответ обрезается: ${long.length}", long.length <= 172)
        assertTrue(long.startsWith("HTTP 403: a"))
    }

    @Test
    fun reasonOfHandlesEveryOutcome() {
        // Регрессия: приведение к «отказ» валило приложение вылетом,
        // когда непригодный исход был «только раздельные потоки».
        val format = StreamFormat(137, "video/mp4", "https://example.invalid/v", "1080p", 4000000)
        assertEquals("сервис отказал", PlayerApi.reasonOf(ExtractResult.Failure("сервис отказал")))
        assertTrue(
            "в причине должно быть число потоков",
            PlayerApi.reasonOf(ExtractResult.NoMuxedStream(5)).contains("5"),
        )
        assertTrue(PlayerApi.reasonOf(ExtractResult.Success(format, 1)).isNotEmpty())
        assertTrue(PlayerApi.reasonOf(ExtractResult.SeparateStreams(format, format, 2)).isNotEmpty())
    }

    @Test
    fun onlyPlayableOutcomesCountAsUsable() {
        // Регрессия: «только раздельные потоки» ошибочно считалось пригодным исходом,
        // из-за чего попытка могла закончиться молчанием вместо понятного сообщения.
        val format = StreamFormat(18, "video/mp4", "https://example.invalid/a", "360p", 500000)
        assertTrue(ExtractResult.Success(format, 1).isUsable)
        assertTrue(
            ExtractResult.SeparateStreams(format, format, 2).isUsable,
        )
        assertFalse("без звуковой дорожки играть нечего", ExtractResult.NoMuxedStream(3).isUsable)
        assertFalse(ExtractResult.Failure("нет").isUsable)
    }

    @Test
    fun loginStateFollowsAuthorizationCookies() {
        assertTrue(
            "SAPISID — признак входа",
            PlayerApi.hasLoginCookies("VISITOR_INFO1_LIVE=x; SAPISID=abc; YSC=y"),
        )
        assertTrue(
            "защищённые идентификаторы — тоже признак входа",
            PlayerApi.hasLoginCookies("__Secure-3PSID=abc"),
        )
        assertFalse(
            "без авторизационных cookie входа нет",
            PlayerApi.hasLoginCookies("VISITOR_INFO1_LIVE=x; YSC=y"),
        )
        assertFalse("пустая строка — входа нет", PlayerApi.hasLoginCookies(""))
        assertEquals(
            "сообщение о входе",
            "вход выполнен — cookie авторизации на месте",
            PlayerApi.loginStateLabel("SAPISID=abc"),
        )
        assertTrue(
            "без cookie — подсказка открыть экран",
            PlayerApi.loginStateLabel(null).contains("ещё не открывали"),
        )
    }

    @Test
    fun mediaHeadersRepeatIssuingClient() {
        // Адрес потока выдаётся конкретному клиенту: запрос с другим агентом сервис отбивает
        // (в плеере это выглядит как «неверный код ответа»), поэтому агент и источник обязательны.
        val headers = PlayerApi.mediaHeaders("com.google.android.youtube/19.09.37")
        assertEquals("com.google.android.youtube/19.09.37", headers["User-Agent"])
        assertEquals("https://www.youtube.com/", headers["Referer"])
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertTrue("без агента заголовок не добавляется", !PlayerApi.mediaHeaders("").containsKey("User-Agent"))
        // Ссылка выдаётся сеансу: cookie должны идти и в запрос видео, иначе сервис отвечает 403.
        assertEquals("сеанс=cookie", PlayerApi.mediaHeaders("UA", "сеанс=cookie")["Cookie"])
        assertTrue("пустые cookie не добавляются", !PlayerApi.mediaHeaders("UA", "  ").containsKey("Cookie"))
        assertTrue("без cookie заголовка нет", !PlayerApi.mediaHeaders("UA").containsKey("Cookie"))
    }

    @Test
    fun agentIsAttachedToUsableResults() {
        val success = ExtractResult.Success(
            StreamFormat(22, "video/mp4", "https://example.invalid/v/22", "720p", 2000000),
            1,
        )
        val withAgent = PlayerApi.withAgent(success, "TV-UA") as ExtractResult.Success
        assertEquals("TV-UA", withAgent.clientUserAgent)
        val failure = ExtractResult.Failure("нет")
        assertTrue(PlayerApi.withAgent(failure, "TV-UA") is ExtractResult.Failure)
    }

    @Test
    fun watchUrlCarriesVideoId() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=ru", PlayerApi.watchUrl("dQw4w9WgXcQ"))
    }

    @Test
    fun embeddedProfilesGoFirstAndCarryEmbedUrl() {
        // С 2024 года мобильные клиенты обязаны предоставлять токен подтверждения (PO token),
        // поэтому первыми идут встроенные и клиент очков — им токен не нужен.
        val labels = PlayerApi.profiles.map { it.label }
        assertTrue("первым должен идти ТВ-клиент: $labels", labels.first() == "TV_EMBEDDED")
        assertTrue("вторым — клиент очков (не требует токена): $labels", labels[1] == "ANDROID_VR")
        assertTrue("третьим — веб-встроенный: $labels", labels[2] == "WEB_EMBEDDED")
        for (profile in PlayerApi.profiles.filter { it.embedUrl != null }) {
            val body = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123").body
            assertTrue(
                "${profile.label}: встроенный клиент должен сообщать thirdParty.embedUrl",
                body.contains("\"embedUrl\"") && body.contains("thirdParty"),
            )
        }
        for (profile in PlayerApi.profiles.filter { it.embedUrl == null }) {
            val body = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123").body
            assertFalse(
                "${profile.label}: обычному клиенту thirdParty не нужен",
                body.contains("thirdParty"),
            )
        }
    }

    @Test
    fun excerptShowsShortServiceAnswer() {
        assertTrue("длинный ответ обрезается", YouTubeExtractor.excerpt("a".repeat(500)).length <= 221)
        assertEquals("пустой ответ", YouTubeExtractor.excerpt("  \n  "))
    }

    @Test
    fun extractsVideoIdFromAllLinkForms() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdFrom("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdFrom("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdFrom("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdFrom("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.videoIdFrom("dQw4w9WgXcQ"))
    }

    @Test
    fun rejectsUnrecognizedInput() {
        assertNull(YouTubeExtractor.videoIdFrom(""))
        assertNull(YouTubeExtractor.videoIdFrom("https://example.com/video"))
        assertNull(YouTubeExtractor.videoIdFrom("слишком-короткий"))
    }

    @Test
    fun mapsCoreSuccessToPlayableFormat() {
        val core = JSONObject()
            .put("status", "success")
            .put("total", 7)
            .put(
                "format",
                JSONObject()
                    .put("itag", 22)
                    .put("mime_type", "video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"")
                    .put("url", "https://example.invalid/v/22")
                    .put("quality_label", "720p")
                    .put("bitrate", 2000000),
            )
            .toString()

        val result = YouTubeExtractor.interpret(core)
        assertTrue("ожидался Success, получено $result", result is ExtractResult.Success)
        val success = result as ExtractResult.Success
        assertEquals(22, success.format.itag)
        assertEquals("720p", success.format.qualityLabel)
        assertEquals("https://example.invalid/v/22", success.format.url)
        assertEquals(7, success.total)
    }

    @Test
    fun mapsCoreNoMuxedStream() {
        val result = YouTubeExtractor.interpret("""{"status":"no_muxed_stream","adaptive":5}""")
        assertTrue(result is ExtractResult.NoMuxedStream)
        assertEquals(5, (result as ExtractResult.NoMuxedStream).adaptive)
    }

    @Test
    fun mapsCoreRefusal() {
        val result = YouTubeExtractor.interpret("""{"status":"refused","reason":"Видео недоступно"}""")
        assertTrue(result is ExtractResult.Failure)
        assertTrue((result as ExtractResult.Failure).reason.contains("недоступно"))
    }

    @Test
    fun mapsCoreSeparateStreams() {
        val core = """{"status":"separate_streams","total":5,"video":{"itag":137,"mime_type":"video/mp4","url":"https://example.invalid/v/137","quality_label":"1080p","bitrate":4000000},"audio":{"itag":140,"mime_type":"audio/mp4","url":"https://example.invalid/a/140","bitrate":128000}}"""
        val result = YouTubeExtractor.interpret(core)
        assertTrue("ожидался SeparateStreams, получено $result", result is ExtractResult.SeparateStreams)
        val pair = result as ExtractResult.SeparateStreams
        assertEquals(137, pair.video.itag)
        assertEquals(140, pair.audio.itag)
        assertEquals(5, pair.total)
    }

    @Test
    fun failsOnIncompleteSeparatePair() {
        val core = """{"status":"separate_streams","video":{"itag":137,"url":"https://example.invalid/v/137"}}"""
        assertTrue(YouTubeExtractor.interpret(core) is ExtractResult.Failure)
    }

    @Test
    fun failsOnUnreadableCoreResponse() {
        assertTrue(YouTubeExtractor.interpret("{не json}") is ExtractResult.Failure)
        assertTrue(YouTubeExtractor.interpret("""{"status":"unknown"}""") is ExtractResult.Failure)
    }
}
