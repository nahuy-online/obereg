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
    fun playbackHintExplainsServiceRefusal() {
        // Код ошибки сам по себе ничего не говорит пользователю — подсказка говорит.
        val http = PlayerApi.playbackHint("ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertTrue("подсказка про отказ сервиса: $http", http.contains("подтверждением клиента"))
        assertTrue("ведёт на рабочий путь", http.contains("Играть через движок"))
        val container = PlayerApi.playbackHint("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED")
        assertTrue("подсказка про служебный формат: $container", container.contains("UMP"))
        assertTrue("для прочих ошибок молчим", PlayerApi.playbackHint("ERROR_CODE_UNSPECIFIED").isEmpty())
    }

    @Test
    fun fingerprintDistinguishesMediaFromPages() {
        // «Не распознан контейнер» одинаково выглядит и при WebM, и при HTML вместо видео.
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x01, 0x00)
        assertTrue(
            PlayerApi.responseFingerprint(200, "video/webm", webm).contains("WebM"),
        )
        val html = "<!DOCTYPE html><html>".toByteArray()
        val page = PlayerApi.responseFingerprint(200, "text/html", html)
        assertTrue("HTML должен опознаваться: $page", page.contains("HTML"))
        val mp4 = "ftypisom....".toByteArray()
        assertTrue(PlayerApi.responseFingerprint(200, "video/mp4", mp4).contains("MP4"))
        assertTrue(PlayerApi.responseFingerprint(403, null, ByteArray(0)).contains("HTTP 403"))
    }

    @Test
    fun streamWithoutMimeIsTreatedAsVideo() {
        // Регрессия: на устройстве было 12 обращений к медиасерверу и ни одного пойманного
        // потока — строгое требование mime отсекало запросы без этого параметра.
        val capture = "https://rr4---sn-x.googlevideo.com/videoplayback?expire=177&itag=18&id=o-A&pl=24"
        assertEquals("video", PlayerApi.streamKind(capture))
        assertTrue(PlayerApi.looksLikeStream(capture))
    }

    @Test
    fun diagLogKeepsOrderAndTrimsOverflow() {
        val before = DiagLog.size()
        DiagLog.add("строка один\nс переносом")
        val dump = DiagLog.dump()
        assertTrue("запись попала в журнал", dump.contains("строка один"))
        assertTrue("перенос схлопнут", !dump.contains("строка один\n"))
        assertTrue("число записей растёт", DiagLog.size() > before)
    }

    @Test
    fun detectsStreamUrlsFromBrowser() {
        val capture = "https://rr3---sn-abc.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-65536&itag=137"
        assertTrue("поток должен распознаваться", PlayerApi.looksLikeStream(capture))
        assertEquals("video", PlayerApi.streamKind(capture))
        assertEquals(
            "audio",
            PlayerApi.streamKind("https://rr3---sn-abc.googlevideo.com/videoplayback?mime=audio%2Fmp4&itag=140"),
        )
        assertNull("страница — не поток", PlayerApi.streamKind("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull("картинка — не поток", PlayerApi.streamKind("https://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun capturedUrlLosesBrowserRange() {
        // Браузер запрашивает кусками; плеер управляет диапазонами сам.
        val capture = "https://rr3.googlevideo.com/videoplayback?itag=137&range=65536-131072&rn=2&mime=video%2Fmp4"
        val forPlayer = PlayerApi.streamUrlForPlayer(capture)
        assertFalse("диапазон браузера убирается", forPlayer.contains("range="))
        assertFalse("номер куска убирается", forPlayer.contains("rn="))
        assertTrue("остальное на месте", forPlayer.contains("itag=137") && forPlayer.contains("mime=video"))
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
    fun playerBodyCarriesPlaybackContextAndContextUserAgent() {
        // Урок 2026: тело запроса плеера несёт playbackContext, а контекст клиента - userAgent.
        val visionos = PlayerApi.profiles.first { it.label == "VISIONOS" }
        val body = PlayerApi.spec(visionos, "dQw4w9WgXcQ", "TEST-KEY-123").body
        assertTrue("в теле должен быть playbackContext", body.contains("playbackContext"))
        assertTrue("в теле должен быть html5Preference", body.contains("HTML5_PREF_WANTS"))
        assertTrue("в контексте должен быть userAgent", body.contains("\"userAgent\""))
    }

    @Test
    fun apiHeadersCarryOriginForAllClients() {
        // У эталона Origin есть у всех клиентов - даже у мобильных.
        for (profile in PlayerApi.profiles) {
            val spec = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123")
            assertEquals("${profile.label}: Origin", "https://www.youtube.com", spec.headers["Origin"])
        }
    }

    @Test
    fun attestationRefusalIsRecognised() {
        // «Мигающий» отказ: по нему стоит повторить запрос клиента.
        assertTrue(
            PlayerApi.isAttestationRefusal("сервис отказал: Войдите в аккаунт, чтобы подтвердить, что вы не бот"),
        )
        assertTrue(PlayerApi.isAttestationRefusal("Sign in to confirm you're not a bot"))
        assertFalse(PlayerApi.isAttestationRefusal("Видео недоступно"))
    }
    @Test
    fun authHeaderIsComputedFromSapisidCookies() {
        // Для вошедшего сеанса запрос подписывается SAPISIDHASH - как у эталона.
        val auth = PlayerApi.authHeader("SAPISID=abc123; __Secure-3PAPISID=zzz; VISITOR_INFO1_LIVE=vv")
        assertTrue("подпись должна быть: $auth", auth != null && auth.startsWith("SAPISIDHASH "))
        assertTrue("у подписи две части: $auth", auth!!.split(" ")[1].contains("_"))
        assertTrue("подпись 3P тоже есть: $auth", auth.contains("SAPISID3PHASH "))
        assertNull("без печенек подписи нет", PlayerApi.authHeader("VISITOR_INFO1_LIVE=vv"))
        assertNull("пусто - не ошибка", PlayerApi.authHeader(null))
    }
    @Test
    fun authedSpecCarriesAccountHeaders() {
        // Для вошедшего сеанса эталон добавляет подпись, номер пользователя и признак входа.
        val profile = PlayerApi.profiles.first()
        val authed = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123", null, null, "SAPISIDHASH 1_ab")
        assertEquals("https://www.youtube.com", authed.headers["X-Origin"])
        assertEquals("0", authed.headers["X-Goog-AuthUser"])
        assertEquals("true", authed.headers["X-Youtube-Bootstrap-Logged-In"])
        assertEquals("SAPISIDHASH 1_ab", authed.headers["Authorization"])
        val guest = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123")
        assertNull("без входа подписи нет", guest.headers["Authorization"])
    }

    @Test
    fun loginCookiesRequireLoginMarker() {
        // «Вход выполнен» - только по настоящим cookie входа, а не по гостевому 3PAPISID.
        assertTrue(PlayerApi.hasLoginCookies("SAPISID=abc; VISITOR_INFO1_LIVE=vv"))
        assertTrue(PlayerApi.hasLoginCookies("LOGIN_INFO=zzz"))
        assertFalse(PlayerApi.hasLoginCookies("__Secure-3PAPISID=abc; VISITOR_INFO1_LIVE=vv"))
    }
    @Test
    fun watchUrlCarriesVideoId() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=ru", PlayerApi.watchUrl("dQw4w9WgXcQ"))
    }

    @Test
    fun embeddedProfilesGoFirstAndCarryEmbedUrl() {
        // С 2026 года главный клиент - visionos: он отдаёт поток без токенов подтверждения.
        // Встроенные профили по-прежнему должны нести thirdParty.embedUrl.
        val labels = PlayerApi.profiles.map { it.label }
        assertTrue("Главный клиент (visionos) должен идти первым: $labels", labels.first() == "VISIONOS")
        assertTrue("visionos идёт до веб-клиентов: $labels", labels.indexOf("VISIONOS") < labels.indexOf("WEB"))
        for (profile in PlayerApi.profiles.filter { it.embedUrl != null }) {
            val body = PlayerApi.spec(profile, "dQw4w9WgXcQ", "TEST-KEY-123").body
            assertTrue(
                "${profile.label}: встроенный профиль должен содержать thirdParty.embedUrl",
                body.contains("\"embedUrl\"") && body.contains("thirdParty"),
            )
        }
    }

    @Test
    fun visionosProfileIsPotlessMainClient() {
        // Урок 2026: главный гостевой клиент - visionos; токены подтверждения ему не нужны,
        // и заголовок номера клиента у него 101.
        val visionos = PlayerApi.profiles.first { it.label == "VISIONOS" }
        assertEquals("101", visionos.clientHeaderName)
        assertNull("visionos не встроенный", visionos.embedUrl)
        val body = PlayerApi.spec(visionos, "dQw4w9WgXcQ", "TEST-KEY-123").body
        assertFalse(
            "без токена тело не должно нести serviceIntegrityDimensions",
            body.contains("serviceIntegrityDimensions"),
        )
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

    @Test
    fun libraryAddsNewestFirst() {
        var items = LibraryCodec.addItem(emptyList(), LibraryItem("a", "A", "video", 1))
        items = LibraryCodec.addItem(items, LibraryItem("b", "B", "video", 2))
        assertEquals("b", items.first().id)
        assertEquals(2, items.size)
    }

    @Test
    fun libraryMovesRepeatToTopWithoutGrowing() {
        var items = LibraryCodec.addItem(emptyList(), LibraryItem("a", "A", "video", 1))
        items = LibraryCodec.addItem(items, LibraryItem("b", "B", "video", 2))
        items = LibraryCodec.addItem(items, LibraryItem("a", "A", "video", 3))
        assertEquals("повторов быть не должно", 2, items.size)
        assertEquals("a", items.first().id)
        assertEquals(3L, items.first().addedAt)
    }

    @Test
    fun libraryLimitsSize() {
        var items = emptyList<LibraryItem>()
        for (index in 1..10) {
            items = LibraryCodec.addItem(
                items,
                LibraryItem("id$index", "t", "video", index.toLong()),
                max = 5,
            )
        }
        assertEquals(5, items.size)
        assertEquals("id10", items.first().id)
        assertFalse("старое вытесняется", items.any { it.id == "id5" })
    }

    @Test
    fun librarySurvivesRoundTrip() {
        val items = listOf(
            LibraryItem("dQw4w9WgXcQ", "видео", "video", 42),
            LibraryItem("котики", "котики", "search", 43),
        )
        assertEquals(items, LibraryCodec.decode(LibraryCodec.encode(items)))
        assertTrue("пустое хранилище", LibraryCodec.decode("").isEmpty())
        assertTrue("испорченное хранилище", LibraryCodec.decode("{не массив}").isEmpty())
        assertTrue("запись без идентификатора отбрасывается", LibraryCodec.decode("""[{"title":"без id"}]""").isEmpty())
    }

    @Test
    fun libraryRemovesAndFindsByKind() {
        val items = listOf(
            LibraryItem("a", "A", "video", 1),
            LibraryItem("a", "A", "search", 2),
        )
        assertTrue(LibraryCodec.contains(items, "a", "video"))
        assertEquals("удаляется только нужный вид", 1, LibraryCodec.removeItem(items, "a", "video").size)
        assertEquals(2, LibraryCodec.removeItem(items, "a", "audio").size)
    }

    @Test
    fun settingsClampPromoPosition() {
        // Позиция в ленте: принимаем текст, держим в разумных границах, по умолчанию третья.
        assertEquals(3, SettingsCodec.clampPosition(""))
        assertEquals(3, SettingsCodec.clampPosition(null))
        assertEquals(3, SettingsCodec.clampPosition("нет числа"))
        assertEquals(1, SettingsCodec.clampPosition("0"))
        assertEquals(1, SettingsCodec.clampPosition("-5"))
        assertEquals(7, SettingsCodec.clampPosition("7"))
        assertEquals(50, SettingsCodec.clampPosition("999"))
    }

    @Test
    fun settingsCheckPromoVideoId() {
        assertTrue(SettingsCodec.looksLikeVideoId("dQw4w9WgXcQ"))
        assertTrue(SettingsCodec.looksLikeVideoId("aqz-KE-bpKQ"))
        assertFalse("короткий — не идентификатор", SettingsCodec.looksLikeVideoId("коротко"))
        assertFalse("лишние символы", SettingsCodec.looksLikeVideoId("dQw4w9WgXcQ!"))
        assertFalse(SettingsCodec.looksLikeVideoId(""))
        assertFalse(SettingsCodec.looksLikeVideoId(null))
    }

    @Test
    fun feedMapsItems() {
        val core = """[{"video_id":"aaaaaaaaaaa","title":"Первый","channel":"Канал А","duration":"12:34","views":"1 234 просмотра","injected":false},{"video_id":"zzzzzzzzzzz","title":"Наш выбор","channel":"","duration":null,"views":null,"injected":true}]"""
        val items = FeedCodec.mapItems(core)
        assertEquals(2, items.size)
        assertEquals("aaaaaaaaaaa", items[0].videoId)
        assertEquals("12:34", items[0].duration)
        assertEquals("1 234 просмотра", items[0].views)
        assertTrue("наша рекомендация помечена", items[1].injected)
        assertNull("пустых полей нет", items[1].duration)
        val label = FeedCodec.label(items[1])
        assertTrue("значок рекомендации в подписи: $label", label.startsWith("★"))
        assertTrue("канал и длительность в подписи", FeedCodec.label(items[0]).contains("Канал А"))
    }

    @Test
    fun feedIgnoresBrokenCoreAnswer() {
        assertTrue(FeedCodec.mapItems("{не массив}").isEmpty())
        assertTrue(FeedCodec.mapItems("""[{"title":"без id"}]""").isEmpty())
    }

    @Test
    fun extractsFeedDataFromPage() {
        val html = """<script>var ytInitialData = {"contents":{"richGridRenderer":{"contents":[{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"Из ленты"}]}}}}}]}}};</script>"""
        val data = PlayerApi.extractInitialData(html)
        assertTrue("данные ленты должны найтись", data != null && data.contains("videoRenderer"))
        assertNull("страница без данных", PlayerApi.extractInitialData("<html>пусто</html>"))
    }

    @Test
    fun feedExtractionHandlesEscapedData() {
        // Живые страницы иногда вкладывают данные строкой с экранированием.
        val escaped = "<script>var ytInitialData = {\"contents\":\"{\\\"richItemRenderer\\\":{\\\"videoRenderer\\\":{\\\"videoId\\\":\\\"aaaaaaaaaaa\\\"}}}\"};</script>"
        val data = PlayerApi.extractInitialData(escaped)
        assertTrue("данные с экранированием должны распознаваться", data != null)
        assertTrue("экранирование снимается", data!!.contains("\"videoRenderer\""))
    }

    @Test
    fun feedExtractionSkipsUnrelatedBlocks() {
        // Первое вхождение имени переменной может быть в служебном коде — берём нужное.
        val html = "<script>ytInitialData = null; some.ytInitialData = {a:1};</script>" +
            "<script>var ytInitialData = {\"contents\":{\"richItemRenderer\":{\"videoRenderer\":{\"videoId\":\"aaaaaaaaaaa\"}}}};</script>"
        val data = PlayerApi.extractInitialData(html)
        assertTrue("нужный блок найден", data != null && data.contains("aaaaaaaaaaa"))
        assertNull("страница без данных", PlayerApi.extractInitialData("<html>пусто</html>"))
    }

    @Test
    fun pageDigestNamesWhatCame() {
        val consent = PlayerApi.pageDigest("Прежде чем продолжить:<html>consent</html>")
        assertTrue("согласие опознаётся: $consent", consent.contains("согласия"))
        val bot = PlayerApi.pageDigest("<html>unusual traffic — подтвердите, что вы не робот</html>")
        assertTrue("проверка «не бот» опознаётся: $bot", bot.contains("не бот"))
        val plain = PlayerApi.pageDigest("<html>обычная страница без данных</html>")
        assertTrue("пустая страница опознаётся: $plain", plain.contains("знакомых признаков нет"))
        val withData = PlayerApi.pageDigest("<html>ytInitialData videoRenderer</html>")
        assertTrue("признаки данных: $withData", withData.contains("ytInitialData есть"))
    }

    @Test
    fun cookieHeaderMergesAndAddsConsent() {
        // Без cookie согласия сервис отдаёт страницу «Прежде чем продолжить» — на этом лента и пустела.
        val header = PlayerApi.buildCookieHeader("VISITOR_INFO1_LIVE=aaa; SAPISID=bbb", "SOCS=ccc")
        assertTrue("cookie сайта на месте", header.contains("VISITOR_INFO1_LIVE=aaa"))
        assertTrue("cookie общего домена добавлено", header.contains("SOCS=ccc"))
        assertTrue("согласие добавлено: $header", header.contains("CONSENT="))
        val merged = PlayerApi.buildCookieHeader("CONSENT=site", "CONSENT=shared; SOCS=ccc")
        assertEquals("повторы не дублируются", 1, Regex("CONSENT=").findAll(merged).count())
        assertTrue("значение сайта главнее", merged.contains("CONSENT=site"))
    }

    @Test
    fun engineDataStringIsDecoded() {
        // evaluateJavascript возвращает JSON-строку с экранированием — её надо распаковать.
        assertEquals("""{"a":1}""", PlayerApi.decodeJsString("\"{\\\"a\\\":1}\""))
        assertNull("null из движка — это отсутствие данных", PlayerApi.decodeJsString("null"))
        assertNull(PlayerApi.decodeJsString(""))
        assertNull(PlayerApi.decodeJsString(null))
        assertNull("мусор не должен ломать", PlayerApi.decodeJsString("{не json}"))
    }

    @Test
    fun feedMapsPlaylists() {
        val core = """[{"playlist_id":"PL1111111111111111","title":"Мой плейлист","video_count":"12 videos","thumbnail_url":"https://i.example/pl1.jpg"},{"playlist_id":"PL2222222222222222","title":"Без обложки","video_count":null,"thumbnail_url":null}]"""
        val entries = FeedCodec.mapPlaylists(core)
        assertEquals(2, entries.size)
        assertEquals("PL1111111111111111", entries[0].playlistId)
        assertEquals("Мой плейлист", entries[0].title)
        assertEquals("12 videos", entries[0].videoCount)
        assertEquals("https://i.example/pl1.jpg", entries[0].thumbnailUrl)
        assertNull("у плейлиста без обложки пусто", entries[1].thumbnailUrl)
        val label = FeedCodec.playlistLabel(entries[0])
        assertTrue("подпись с названием и числом: $label", label.contains("Мой плейлист") && label.contains("12 videos"))
        assertTrue("подпись без обложки — по названию", FeedCodec.playlistLabel(entries[1]).contains("Без обложки"))
        assertTrue(
            "без названия показывается идентификатор",
            FeedCodec.playlistLabel(FeedCodec.PlaylistEntry("PL9999", "", null, null)).contains("PL9999"),
        )
        assertTrue("битое ядро — пусто", FeedCodec.mapPlaylists("{не массив}").isEmpty())
        assertTrue("записи без идентификатора отбрасываются", FeedCodec.mapPlaylists("""[{"title":"без id"}]""").isEmpty())
    }

    @Test
    fun emptyFeedExplainsWhy() {
        // Проверено на устройстве: пустые рекомендации — обычно из-за выключенной истории,
        // поэтому сообщение должно объяснять, а не просто говорить «пусто».
        val recommended = FeedCodec.emptyMessage("recommended")
        assertTrue("причина названа: $recommended", recommended.contains("история просмотров"))
        assertTrue("ведёт в подписки", recommended.contains("Подписки"))
        val subscriptions = FeedCodec.emptyMessage("subscriptions")
        assertTrue("для подписок своё объяснение", subscriptions.contains("подпишитесь"))
    }

    @Test
    fun feedMapsThumbnail() {
        val core = """[{"video_id":"aaaaaaaaaaa","title":"Первый","channel":"К","duration":"1:00","views":"10","thumbnail_url":"https://i.example/1.jpg","injected":false},{"video_id":"zzzzzzzzzzz","title":"Наш выбор","channel":"","duration":null,"views":null,"thumbnail_url":null,"injected":true}]"""
        val items = FeedCodec.mapItems(core)
        assertEquals("https://i.example/1.jpg", items[0].thumbnailUrl)
        assertNull("у нашей рекомендации картинки нет", items[1].thumbnailUrl)
        // Старый формат без обложки тоже должен читаться.
        val legacy = FeedCodec.mapItems("""[{"video_id":"aaaaaaaaaaa","title":"Без картинки"}]""")
        assertNull(legacy[0].thumbnailUrl)
    }

    @Test
    fun tokenProbeSummaryReportsFacts() {
        val full = """{"hasYtcfg":true,"visitorData":"CgtYWVpBQkM","hasPlayer":false,"hasVideo":true,"botguardKeys":["a"],"jslKeys":null,"scriptCount":12}"""
        val summary = TokenProbe.summarize("\"" + full.replace("\"", "\\\"") + "\"")
        assertTrue("конфигурация: $summary", summary.contains("конфигурация: есть"))
        assertTrue("гостевые данные сокращены", summary.contains("есть (CgtYXY".take(10)) || summary.contains("есть ("))
        assertTrue("объект проверки найден", summary.contains("объект проверки: найден"))
        assertTrue("число скриптов", summary.contains("скриптов на странице: 12"))
        val empty = TokenProbe.summarize("\"{\\\"hasYtcfg\\\":false,\\\"scriptCount\\\":3}\"")
        assertTrue("без конфигурации: $empty", empty.contains("конфигурация: нет"))
        assertTrue("без гостевых данных", empty.contains("гостевые данные: нет"))
        assertTrue("движок молчит", TokenProbe.summarize(null).contains("не ответил"))
        assertTrue("ошибка движка видна", TokenProbe.summarize("\"{\\\"error\\\":\\\"ReferenceError\\\"}\"").contains("ReferenceError"))
    }

    @Test
    fun challengeSummaryReportsFacts() {
        val ok = """{"status":200,"length":4096,"keys":["challenge","bgConfig"],"head":"start"}"""
        val summary = TokenProbe.summarizeChallenge(ok)
        assertTrue("код и размер: $summary", summary.contains("HTTP 200") && summary.contains("4096"))
        assertTrue("поля названы", summary.contains("challenge"))
        val denied = TokenProbe.summarizeChallenge("""{"status":403,"length":40,"keys":[],"head":"no access"}""")
        assertTrue("отказ виден: $denied", denied.contains("HTTP 403") && denied.contains("no access"))
        assertTrue("движок молчит", TokenProbe.summarizeChallenge(null).contains("не ответил"))
        assertTrue("ошибка видна", TokenProbe.summarizeChallenge("\"{\\\"error\\\":\\\"нет ключа\\\"}\"").contains("нет ключа"))
    }

    @Test
    fun splitPollKeepsStages() {
        val (result, stages) = TokenProbe.splitPoll("\"{\\\"error\\\":\\\"нет ответа\\\"}||контекст есть → запрос отправлен\"")
        assertTrue("результат отделён", result.contains("нет ответа"))
        assertTrue("этапы отделены: $stages", stages.contains("запрос отправлен"))
        val (empty, none) = TokenProbe.splitPoll("\"||\"")
        assertTrue(empty.isEmpty() && none.isEmpty())
    }

    @Test
    fun challengeSummaryShowsStages() {
        val summary = TokenProbe.summarizeChallenge("""{"error":"сервис не ответил за 7 секунд","stages":"контекст есть"}""")
        assertTrue("этапы в сообщении: $summary", summary.contains("этапы:") && summary.contains("контекст есть"))
    }

    @Test
    fun streamUrlTakesToken() {
        // Токен ждёт отдельным параметром; если его нет — адрес не меняется.
        assertEquals(
            "https://x.example/videoplayback?itag=18&pot=T",
            PlayerApi.appendPoToken("https://x.example/videoplayback?itag=18", "T"),
        )
        assertEquals(
            "https://x.example/videoplayback?pot=T",
            PlayerApi.appendPoToken("https://x.example/videoplayback", "T"),
        )
        assertEquals("https://x.example/videoplayback", PlayerApi.appendPoToken("https://x.example/videoplayback", null))
        assertEquals("https://x.example/videoplayback", PlayerApi.appendPoToken("https://x.example/videoplayback", "  "))
        assertEquals(
            "https://x.example/videoplayback?pot=OLD",
            PlayerApi.appendPoToken("https://x.example/videoplayback?pot=OLD", "NEW"),
        )
    }

    @Test
    fun requestBodyTakesToken() {
        val body = """{"context":{"client":{"clientName":"WEB"}},"videoId":"x"}"""
        val withToken = PlayerApi.withPoToken(body, "TOKEN")
        assertTrue("токен добавлен блоком: $withToken", withToken.contains("serviceIntegrityDimensions") && withToken.contains("TOKEN"))
        assertEquals("без токена тело не меняется", body, PlayerApi.withPoToken(body, null))
        assertEquals("битое тело не ломаем", "{не json}", PlayerApi.withPoToken("{не json}", "TOKEN"))
        val already = """{"serviceIntegrityDimensions":{"poToken":"OLD"}}"""
        assertEquals("повторно не добавляем", already, PlayerApi.withPoToken(already, "NEW"))
    }

    @Test
    fun tokenStoreHonoursExpiry() {
        TokenStore.clear()
        TokenStore.put("v1", PoToken("T1", expiresAtMs = 2_000))
        assertTrue("годный токен отдаётся", TokenStore.get("v1", nowMs = 1_000) != null)
        assertTrue("просроченный не отдаётся", TokenStore.get("v1", nowMs = 3_000) == null)
        assertTrue("просроченный удалён", TokenStore.size() == 0)
        TokenStore.put("v2", PoToken("T2", expiresAtMs = 50_000))
        assertEquals("новый токен на месте", 1, TokenStore.size())
        TokenStore.put("", PoToken("T3", expiresAtMs = 50_000))
        assertEquals("пустой идентификатор не хранится", 1, TokenStore.size())
        TokenStore.clear()
        assertEquals("очистка работает", 0, TokenStore.size())
    }

    @Test
    fun challengeDetailSummary() {
        val ok = """{"status":200,"length":36634,"challengeKeys":["interpreterHash","program","globalName"],"botguardKeys":["program","interpreterUrl"],"loadedKeys":["a","b"],"programType":"string"}"""
        val summary = TokenProbe.summarizeChallengeDetail(ok)
        assertTrue("код: $summary", summary.contains("HTTP 200"))
        assertTrue("что в проверке: $summary", summary.contains("interpreterHash"))
        assertTrue("что у программы: $summary", summary.contains("interpreterUrl"))
        assertTrue("после загрузки: $summary", summary.contains("после загрузки"))
        val err = TokenProbe.summarizeChallengeDetail("""{"error":"не ответил","stages":"запрос проверки"}""")
        assertTrue("ошибка со этапами: $err", err.contains("не ответил") && err.contains("этапы"))
        assertTrue("пустой вход", TokenProbe.summarizeChallengeDetail(null).contains("ответа нет"))
    }

    @Test
    fun challengeReuseIsVisible() {
        val summary = TokenProbe.summarizeChallenge("""{"status":200,"length":36242,"keys":[],"head":"","reused":true,"ageSeconds":42}""")
        assertTrue("сохранённая проверка видна: $summary", summary.contains("сохранённая") && summary.contains("42"))
        assertTrue("размер назван", summary.contains("36242"))
    }

    @Test
    fun challengeScriptIsSyntacticallySane() {
        // Регрессия: в адресе запроса оказались звёздочки-заглушки — скрипт не выполнялся вовсе.
        val script = TokenProbe.challengeScript()
        assertFalse("заглушек быть не должно", script.contains("***"))
        assertTrue("запрос проверки на месте", script.contains("att/get"))
        assertTrue("результат складывается в страницу", script.contains("__oberegChallenge"))
        assertFalse("многоточий быть не должно", script.contains("…"))
    }

    @Test
    fun probeShowsPageAddress() {
        val json = """{"url":"https://www.youtube.com/embed/xyz","hasYtcfg":true,"visitorData":"Cgt","hasPlayer":true,"hasVideo":true,"scriptCount":16}"""
        val summary = TokenProbe.summarize("\"" + json.replace("\"", "\\\"") + "\"")
        assertTrue("адрес страницы виден: $summary", summary.contains("youtube.com/embed"))
        val emptySummary = TokenProbe.summarize("\"{\\\"url\\\":\\\"about:blank\\\",\\\"hasYtcfg\\\":false,\\\"scriptCount\\\":0}\"")
        assertTrue("пустая страница распознаётся: $emptySummary", emptySummary.contains("скриптов на странице: 0"))
    }

    @Test
    fun programCheckSummary() {
        val ok = """{"botguardType":"object","botguardKeys":["create","close"],"jslType":"undefined"}"""
        val summary = TokenProbe.summarizeProgramCheck(ok)
        assertTrue("тип и ключи: $summary", summary.contains("botguard=object") && summary.contains("create"))
        assertTrue("jsl назван", summary.contains("jsl=undefined"))
        val err = TokenProbe.summarizeProgramCheck("""{"error":"нет конфигурации страницы"}""")
        assertTrue("ошибка видна: $err", err.contains("нет конфигурации"))
        assertTrue("движок молчит", TokenProbe.summarizeProgramCheck(null).contains("не ответил"))
    }

    @Test
    fun globalsDiffSummary() {
        val ok = """{"added":["botguard:object","vmok:function"]}"""
        val summary = TokenProbe.summarizeGlobalsDiff(ok)
        assertTrue("новые сущности названы: $summary", summary.contains("botguard:object"))
        val none = TokenProbe.summarizeGlobalsDiff("""{"added":[]}""")
        assertTrue("пустое сравнение видно", none.contains("новых сущностей нет"))
        assertTrue("движок молчит", TokenProbe.summarizeGlobalsDiff(null).contains("не ответил"))
    }

    @Test
    fun normalizeInterpreterUrlWorks() {
        assertEquals(
            "https://www.google.com/js/th/x.js",
            TokenProbe.normalizeInterpreterUrl("//www.google.com/js/th/x.js"),
        )
        assertEquals("https://www.youtube.com/embed/x", TokenProbe.normalizeInterpreterUrl("/embed/x"))
        assertEquals("https://a.b/c.js", TokenProbe.normalizeInterpreterUrl("https://a.b/c.js"))
        assertEquals("", TokenProbe.normalizeInterpreterUrl("null"))
        assertEquals("", TokenProbe.normalizeInterpreterUrl(""))
    }

    @Test
    fun step7Summaries() {
        val run = """{"stage":"запущена","name":"vmok","program":35747}"""
        assertTrue("имя машины видно: ${TokenProbe.summarizeStep7(run)}", TokenProbe.summarizeStep7(run).contains("vmok"))
        val snap = """{"stage":"снимок","len":1234,"head":"abc","signals":1}"""
        val s = TokenProbe.summarizeStep7(snap)
        assertTrue("размер снимка: $s", s.contains("1234"))
        assertTrue("сигналы видны: $s", s.contains("сигналов: 1"))
        assertTrue("снимок финален", TokenProbe.step7Final("снимок"))
        assertTrue("старт не финален", !TokenProbe.step7Final("старт"))
        assertTrue("этап читается", TokenProbe.step7Stage(snap) == "снимок")
        val vm = """{"candidates":["vmok(a:function,ad:function)"],"fromChallenge":"5:vmok","picked":"vmok"}"""
        assertTrue("машина найдена: ${TokenProbe.summarizeVmFind(vm)}", TokenProbe.summarizeVmFind(vm).contains("vmok"))
        assertTrue("ошибки видны", TokenProbe.summarizeErrors("""{"errs":["SyntaxError x"]}""").contains("ошибки страницы"))
        assertTrue("без ошибок пусто", TokenProbe.summarizeErrors("""{"errs":[]}""").isEmpty())
    }

    @Test
    fun integrityExchangeHelpers() {
        val info = TokenProbe.parseIntegrityResponse("""["ТОКЕН-ДОСТАТОЧНО-ДЛИННЫЙ-ВОТ-ТАК",7200,600,"запас"]""")
        assertTrue("токен прочитан", info != null && info.token?.startsWith("ТОКЕН") == true)
        assertTrue("срок прочитан", info?.ttlSecs == 7200)
        assertTrue("битый ответ не ломает", TokenProbe.parseIntegrityResponse("не json") == null)
        assertTrue("пустой ответ отсеян", TokenProbe.parseIntegrityResponse("""[null,1,null,null]""") == null)
        val fb = TokenProbe.parseIntegrityResponse(
            """[null,43200,null,"Mla98z86kVD3vb25wyVvfIlZStG2rD-oGOT3IMwhQqULeMXe2mzp25nTKcrqiF-M889ZmvSUwCbWLqPl6X0Xb1SkAimK7qNYZTPvuPbWrlOf-xjuOqi0oQ"]""",
        )
        assertTrue("запасной прочитан", fb != null && fb.token == null && fb.fallback.length > 60)
        assertTrue("срок запасного", fb?.ttlSecs == 43200)
        val ok = """{"stage":"по-токен","video":"dQw4w9WgXcQ","len":180,"head":"abc"}"""
        assertTrue(TokenProbe.summarizeMint(ok).contains("180"))
        assertTrue(TokenProbe.summarizeMint("""{"stage":"нет-минтера","signals":0}""").contains("минтер"))
        assertTrue("финал виден", TokenProbe.mintFinal("по-токен"))
        assertTrue("старт не финал", !TokenProbe.mintFinal("старт"))
        val v = """{"stage":"по-токен","token":"zxc","len":3,"video":"v"}"""
        assertTrue("значение читается", TokenProbe.mintValue(v) == "zxc")
    }

    @Test
    fun tokenStoreLatestAndRoundtrip() {
        TokenStore.clear()
        assertTrue("пусто - нет последнего", TokenStore.latest(nowMs = 1_000) == null)
        TokenStore.put("v1", PoToken("T1", expiresAtMs = 5_000))
        assertTrue("последний виден", TokenStore.latest(nowMs = 1_000)?.value == "T1")
        TokenStore.put("v2", PoToken("T2", expiresAtMs = 9_000))
        assertTrue("последний обновился", TokenStore.latest(nowMs = 1_000)?.value == "T2")
        val json = TokenStore.toJson()
        TokenStore.clear()
        TokenStore.fromJson(json)
        assertEquals("после перезапуска оба", 2, TokenStore.size())
        assertTrue("после перезапуска последний", TokenStore.latest(nowMs = 1_000)?.value == "T2")
        assertTrue("просроченный последний не отдаётся", TokenStore.latest(nowMs = 10_000) == null)
        TokenStore.clear()
    }

    @Test
    fun speedCycler() {
        assertEquals("1 -> 1.5", 1.5f, PlayerApi.nextSpeed(1.0f), 0.001f)
        assertEquals("1.5 -> 2", 2.0f, PlayerApi.nextSpeed(1.5f), 0.001f)
        assertEquals("2 -> 2.5", 2.5f, PlayerApi.nextSpeed(2.0f), 0.001f)
        assertEquals("2.5 -> 3", 3.0f, PlayerApi.nextSpeed(2.5f), 0.001f)
        assertEquals("3 -> 1", 1.0f, PlayerApi.nextSpeed(3.0f), 0.001f)
        assertEquals("нестандарт -> 1", 1.0f, PlayerApi.nextSpeed(1.7f), 0.001f)
        assertEquals("1x", "1×", PlayerApi.speedLabel(1.0f))
        assertEquals("1.5x", "1.5×", PlayerApi.speedLabel(1.5f))
        assertEquals("3x", "3×", PlayerApi.speedLabel(3.0f))
    }

    @Test
    fun createChallengeHelpers() {
        val url = "//www.google.com/js/th/test.js"
        val payload = "[null,null,[\"" + url + "\"],\"hash\",\"" + "x".repeat(200) + "\",\"vmok\",null,null]"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val scrambled = ByteArray(bytes.size)
        for (i in bytes.indices) {
            scrambled[i] = (((bytes[i].toInt() and 0xFF) - 97) and 0xFF).toByte()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(scrambled)
        val response = """["msg","$b64"]"""
        assertTrue("прямой сдвиг совпадает", TokenProbe.descrambleChallenge(b64) == payload)
        val root = TokenProbe.parseCreateResponse(response)
        assertTrue("разбор удался", root != null)
        val summary = TokenProbe.summarizeCreate(root!!)
        assertTrue("программа видна: $summary", summary.contains("200 символов"))
        assertTrue("имя видно: $summary", summary.contains("vmok"))
        assertTrue("адрес виден: $summary", summary.contains("адрес есть"))
        assertTrue("битый ответ не ломает", TokenProbe.parseCreateResponse("не json") == null)
    }

    @Test
    fun mintVisitorHelper() {
        val ok = """{"stage":"по-токен","video":"v","len":3,"token":"a","visitorToken":"b","visitor":"Cgtpe"}"""
        assertTrue("гостевой читается", TokenProbe.mintVisitorValue(ok) == "b")
        val only = """{"stage":"по-токен","video":"v","len":3,"token":"a","visitorToken":""}"""
        assertTrue("без гостевого - пусто", TokenProbe.mintVisitorValue(only) == null)
        assertTrue("битый не ломает", TokenProbe.mintVisitorValue("не json") == null)
    }

    @Test
    fun potFromUrlWorks() {
        assertEquals("среди параметров", "P123", PlayerApi.potFromUrl("https://x/videoplayback?a=1&pot=P123&b=2"))
        assertEquals("первым параметром", "P_2-x", PlayerApi.potFromUrl("https://x/videoplayback?pot=P_2-x"))
        assertEquals("без токена - null", null, PlayerApi.potFromUrl("https://x/videoplayback?a=1"))
    }

    @Test
    fun tvProfilesPresent() {
        assertTrue("TV есть", PlayerApi.profiles.any { it.label == "TV" })
        assertTrue("MWEB есть", PlayerApi.profiles.any { it.label == "MWEB" })
        assertTrue("WEB остался", PlayerApi.profiles.any { it.label == "WEB" })
    }

    @Test
    fun paramSummaryWorks() {
        val s = PlayerApi.paramSummary("https://x/videoplayback?a=1&pot=abcdefghijklmnopqrstuvwxyz012&b=2")
        assertTrue("короткие имена: $s", s.contains("a") && s.contains("b"))
        assertTrue("длинное значение с длиной: $s", s.contains("pot(29)"))
    }
    @Test
    fun recentsKeepNewestFirstWithoutDuplicates() {
        // Недавние: новый — первым, без повторов, старые держатся в хвосте.
        val first = SettingsCodec.recentOrder("", "a1", "Первый", "Канал")
        val second = SettingsCodec.recentOrder(first, "b2", "Второй", "Канал")
        val again = SettingsCodec.recentOrder(second, "a1", "Первый", "Канал")
        val arr = org.json.JSONArray(again)
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("videoId") }
        assertEquals(listOf("a1", "b2"), ids)
    }
}