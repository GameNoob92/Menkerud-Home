package com.noobventure.menkerud.call

/**
 * What the LiveKit room says, turned into what the controller needs to hear. Kept apart from the room itself so that
 * it can be tested without one.
 */
internal class RoomSignals {
    private var closed = false

    /** Right after getting in: LiveKit does not announce those who were in the room first. */
    fun connected(othersInRoom: Int): SessionEvent? = if (othersInRoom > 0) SessionEvent.OTHER_JOINED else null

    fun joined(): SessionEvent = SessionEvent.OTHER_JOINED

    /** `othersInRoom`: how many are left after this one went. */
    fun left(othersInRoom: Int): SessionEvent? = if (othersInRoom == 0) SessionEvent.OTHER_LEFT else null

    /** The room is gone or the connection lost for good. Said once: leaving the room oneself looks the same. */
    fun disconnected(): SessionEvent? = if (closed) null else { closed = true; SessionEvent.CLOSED }
}
