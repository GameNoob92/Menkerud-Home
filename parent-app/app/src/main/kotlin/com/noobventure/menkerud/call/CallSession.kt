package com.noobventure.menkerud.call

import com.noobventure.menkerud.api.LiveKitTicket
import kotlinx.coroutines.flow.Flow

/** What the room tells the controller. CLOSED: the room is gone, or the connection to it is lost for good. */
enum class SessionEvent { OTHER_JOINED, OTHER_LEFT, CLOSED }

/** One stay in one room: picture and sound. The controller needs to know no more about it than this. */
interface CallSession {
    /** Everything the room has said since the session was made, in order; read by one reader. */
    val events: Flow<SessionEvent>

    /** Joins with camera and microphone on. Throws when it cannot get in. */
    suspend fun connect(ticket: LiveKitTicket)

    /** Leaves and lets go of camera and microphone. Safe to call twice, and while [connect] is still under way. */
    suspend fun close()
}

fun interface SessionFactory {
    fun create(): CallSession
}
