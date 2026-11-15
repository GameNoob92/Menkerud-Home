package com.noobventure.menkerud.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSignalsTest {
    @Test fun `somebody who was in the room first is announced when we get in`() {
        assertEquals(SessionEvent.OTHER_JOINED, RoomSignals().connected(1))
        assertNull(RoomSignals().connected(0))
    }

    @Test fun `somebody who comes in later is announced`() = assertEquals(SessionEvent.OTHER_JOINED, RoomSignals().joined())

    @Test fun `the other side leaving counts only when nobody is left`() {
        assertEquals(SessionEvent.OTHER_LEFT, RoomSignals().left(0))
        assertNull(RoomSignals().left(1))
    }

    @Test fun `the room closing is said once`() {
        val signals = RoomSignals()
        assertEquals(SessionEvent.CLOSED, signals.disconnected())
        assertNull(signals.disconnected())
    }
}
