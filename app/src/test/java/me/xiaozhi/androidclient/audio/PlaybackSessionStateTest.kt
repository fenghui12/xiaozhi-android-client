package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionStateTest {
    @Test
    fun `transport close preserves frames accepted before finishing`() {
        val session = PlaybackSessionState()
        val epoch = session.begin()
        val queuedFrames = List(50) { requireNotNull(session.frameEpoch()) }

        assertEquals(epoch, session.finish())
        assertNull(session.frameEpoch())
        assertTrue(queuedFrames.all(session::isCurrent))
        assertTrue(session.complete(epoch))
        assertFalse(session.isActive)
    }

    @Test
    fun `tts stop followed by disconnect completes playback once`() {
        val session = PlaybackSessionState()
        val epoch = session.begin()

        assertEquals(epoch, session.finish())
        assertEquals(epoch, session.finish())
        assertTrue(session.complete(epoch))
        assertFalse(session.complete(epoch))
        assertTrue(session.isFinished(epoch))
        assertNull(session.finish())
    }

    @Test
    fun `user interruption immediately invalidates queued audio and drain`() {
        val session = PlaybackSessionState()
        val epoch = session.begin()
        session.finish()

        session.interrupt()

        assertFalse(session.isCurrent(epoch))
        assertFalse(session.complete(epoch))
        assertNull(session.frameEpoch())
        assertNull(session.finish())
        assertFalse(session.isActive)
    }

    @Test
    fun `new reply cannot be stopped by an old drain callback`() {
        val session = PlaybackSessionState()
        val previousEpoch = session.begin()
        session.finish()

        val nextEpoch = session.begin()

        assertFalse(session.complete(previousEpoch))
        assertFalse(session.isFinished(previousEpoch))
        assertFalse(session.isCurrent(previousEpoch))
        assertTrue(session.isCurrent(nextEpoch))
        assertEquals(nextEpoch, session.frameEpoch())
    }

    @Test
    fun `barge in remains available while buffered speech drains`() {
        val session = PlaybackSessionState()
        val epoch = session.begin()

        session.finish()

        assertTrue(session.isActive)
        assertTrue(session.isCurrent(epoch))
        session.interrupt()
        assertFalse(session.isActive)
    }

    @Test
    fun `next reply accepts audio after interruption or natural completion`() {
        val session = PlaybackSessionState()
        session.interrupt()
        val firstEpoch = session.begin()
        assertEquals(firstEpoch, session.frameEpoch())
        session.finish()
        session.complete(firstEpoch)

        val nextEpoch = session.begin()

        assertTrue(nextEpoch != firstEpoch)
        assertEquals(nextEpoch, session.frameEpoch())
        assertTrue(session.isActive)
    }
}
