package com.noobventure.menkerud.push

/** What the server can tell a phone through the push service (contract C9). Every value arrives as text. */
sealed interface PushMessage {
    /** The kids are calling. `expiresAt`: when the server stops the ringing (ms since 1970). */
    data class Call(val callId: String, val who: String, val expiresAt: Long) : PushMessage

    /** That call is over. `reason`: as the server said it; `at`: when, or null when the push did not say. */
    data class CallEnd(val callId: String, val reason: String, val at: Long?) : PushMessage

    /** A kind or a version of message this build does not know. It is left alone, so that newer servers can send
     *  new kinds without breaking the apps that are already installed. */
    data object Unknown : PushMessage
}

fun readPush(data: Map<String, String>): PushMessage {
    if (data["v"] != "1") return PushMessage.Unknown
    val callId = data["callId"].orEmpty()
    return when (data["type"]) {
        "call" -> {
            val expiresAt = data["expiresAt"]?.toLongOrNull()
            if (callId.isEmpty() || expiresAt == null) PushMessage.Unknown
            else PushMessage.Call(callId, data["who"].orEmpty(), expiresAt)
        }
        "call_end" ->
            if (callId.isEmpty()) PushMessage.Unknown
            else PushMessage.CallEnd(callId, data["reason"].orEmpty(), data["at"]?.toLongOrNull())
        else -> PushMessage.Unknown
    }
}
