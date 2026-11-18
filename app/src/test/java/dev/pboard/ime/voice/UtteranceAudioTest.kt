package dev.pboard.ime.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtteranceAudioTest {
    @Test fun collectsPiecesUntilTaken() {
        val u = UtteranceAudio(maxSamples = 100)
        u.append(floatArrayOf(1f, 2f, 3f), 2)
        u.append(floatArrayOf(4f, 5f), 2)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f, 5f), u.take(), 0f)
        assertNull(u.take()) // cleared for the next utterance
        u.append(floatArrayOf(6f), 1)
        assertArrayEquals(floatArrayOf(6f), u.take(), 0f)
    }

    @Test fun growsBeyondItsInitialBuffer() {
        val u = UtteranceAudio(maxSamples = 20 * MicSource.RATE)
        val piece = FloatArray(1600) { 0.5f }
        repeat(100) { u.append(piece, piece.size) } // 10 s, more than the initial 4 s buffer
        assertEquals(160_000, u.take()!!.size)
    }

    @Test fun tooLongUtterancesSkipTheFinalPass() {
        val u = UtteranceAudio(maxSamples = 5)
        u.append(FloatArray(4), 4)
        u.append(FloatArray(4), 4)
        assertNull(u.take())
        u.append(FloatArray(3), 3) // the next utterance starts afresh
        assertEquals(3, u.take()!!.size)
    }

    @Test fun pickPrefersTheAccurateText() {
        assertEquals("He hoped there would be stew.", UtteranceAudio.pick("he hoped there would be stew", "He hoped there would be stew."))
        assertEquals("streaming", UtteranceAudio.pick("streaming", ""))
        assertEquals("streaming", UtteranceAudio.pick("streaming", null))
        // nothing heard by the streaming model: no final pass on noise
        assertEquals("", UtteranceAudio.pick("", "Thank you."))
    }
}
