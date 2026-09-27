package app.obereg.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
