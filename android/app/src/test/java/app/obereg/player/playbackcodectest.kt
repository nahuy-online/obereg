package app.obereg.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверки разбора «пульса» и мелких правил медиа-уведомления (без Android). */
class PlaybackCodecTest {

    @Test
    fun pulseParses() {
        val pulse = PlaybackCodec.parsePulse(
            "{\"playing\":true,\"t\":12345,\"d\":600000,\"title\":\"Ролик\",\"author\":\"Канал\"," +
                "\"id\":\"dQw4w9WgXcQ\",\"loop\":false}",
        )
        assertTrue("пульс должен разобраться", pulse != null)
        assertTrue("играет", pulse!!.playing)
        assertEquals(12345L, pulse.positionMs)
        assertEquals(600000L, pulse.durationMs)
        assertEquals("Ролик", pulse.title)
        assertEquals("Канал", pulse.channel)
        assertEquals("dQw4w9WgXcQ", pulse.videoId)
        assertFalse("повтор выключен", pulse.loop)
    }

    @Test
    fun pulseRejectsGarbage() {
        assertNull(PlaybackCodec.parsePulse("это не json"))
        assertNull(PlaybackCodec.parsePulse(""))
        assertNull(PlaybackCodec.parsePulse("null"))
        assertNull(PlaybackCodec.parsePulse("{}"))
        assertNull(PlaybackCodec.parsePulse(null))
    }

    @Test
    fun pulseClampsBadNumbers() {
        val pulse = PlaybackCodec.parsePulse("{\"t\":-100,\"d\":-1}")!!
        assertEquals(0L, pulse.positionMs)
        assertEquals(0L, pulse.durationMs)
    }

    @Test
    fun videoIdIsSanitized() {
        assertEquals("a-b_C9", PlaybackCodec.sanitizeVideoId("a-b_C9'; <>()"))
        assertEquals(32, PlaybackCodec.sanitizeVideoId("a".repeat(50)).length)
        assertEquals("", PlaybackCodec.sanitizeVideoId(null))
    }

    @Test
    fun seekIsClamped() {
        assertEquals(0L, PlaybackCodec.clampSeekMs(-100, 1000))
        assertEquals(1000L, PlaybackCodec.clampSeekMs(5000, 1000))
        assertEquals(500L, PlaybackCodec.clampSeekMs(500, 1000))
        assertEquals(500L, PlaybackCodec.clampSeekMs(500, 0))
    }

    @Test
    fun nearEndDetects() {
        val atEnd = PlaybackCodec.Pulse(false, 599000, 600000, "", "", "", false)
        assertTrue("почти конец", PlaybackCodec.nearEnd(atEnd))
        val middle = PlaybackCodec.Pulse(false, 1000, 600000, "", "", "", false)
        assertFalse("середина", PlaybackCodec.nearEnd(middle))
        val unknown = PlaybackCodec.Pulse(false, 1000, 0, "", "", "", false)
        assertFalse("длительность неизвестна", PlaybackCodec.nearEnd(unknown))
    }
}
