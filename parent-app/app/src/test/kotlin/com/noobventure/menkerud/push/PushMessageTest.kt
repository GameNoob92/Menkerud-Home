package com.noobventure.menkerud.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushMessageTest {
    @Test fun `a call`() = assertEquals(
        PushMessage.Call("c_1", "far", 1794700000000),
        readPush(mapOf("type" to "call", "v" to "1", "callId" to "c_1", "caller" to "home", "who" to "far", "expiresAt" to "1794700000000"))
    )

    @Test fun `the end of a call`() = assertEquals(
        PushMessage.CallEnd("c_1", "missed", 1794700060000),
        readPush(mapOf("type" to "call_end", "v" to "1", "callId" to "c_1", "reason" to "missed", "at" to "1794700060000"))
    )

    @Test fun `a kind of message this version does not know is left alone`() {
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "message", "v" to "1", "text" to "hei")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "2", "callId" to "c_1", "expiresAt" to "1")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "1", "expiresAt" to "1")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "1", "callId" to "c_1", "expiresAt" to "snart")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "1", "callId" to "", "expiresAt" to "1")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call_end", "v" to "1", "reason" to "missed")))
        assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call_end", "callId" to "c_1", "reason" to "missed")))
        assertEquals(PushMessage.Unknown, readPush(emptyMap()))
    }

    @Test fun `an end without a time is still an end`() = assertEquals(
        PushMessage.CallEnd("c_1", "cancelled", null),
        readPush(mapOf("type" to "call_end", "v" to "1", "callId" to "c_1", "reason" to "cancelled", "extra" to "x"))
    )

    @Test fun `an end with a time that is no number, or with no reason, is still an end`() = assertEquals(
        PushMessage.CallEnd("c_1", "", null),
        readPush(mapOf("type" to "call_end", "v" to "1", "callId" to "c_1", "at" to "i går"))
    )

    @Test fun `a call that does not say for whom still rings`() = assertEquals(
        PushMessage.Call("c_1", "", 5),
        readPush(mapOf("type" to "call", "v" to "1", "callId" to "c_1", "expiresAt" to "5"))
    )
}
