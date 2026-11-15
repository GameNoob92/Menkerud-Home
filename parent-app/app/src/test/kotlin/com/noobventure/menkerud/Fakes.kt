package com.noobventure.menkerud

import com.noobventure.menkerud.api.AnswerResult
import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.LiveKitTicket
import com.noobventure.menkerud.api.PairResult
import com.noobventure.menkerud.api.RingHomeResult
import com.noobventure.menkerud.api.Sent
import com.noobventure.menkerud.api.StatusResult
import com.noobventure.menkerud.pairing.PairLink
import com.noobventure.menkerud.call.CallSession
import com.noobventure.menkerud.call.RingNotifier
import com.noobventure.menkerud.call.SessionEvent
import com.noobventure.menkerud.store.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Stands in for the Android Keystore: every byte XOR 0x5A; refuses to open when told to. */
class FakeBox : SecretBox {
    var refuse = false
    override fun seal(plain: ByteArray) = flip(plain)
    override fun open(sealed: ByteArray) = if (refuse) null else flip(sealed)
    private fun flip(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
}

/** Stands in for the server: writes down what it was asked, in order, and answers what the test told it to. */
class FakeCallApi : CallApi {
    val calls = mutableListOf<String>()                 // "sendPushAddress fid-1", "answer c_1", "status c_1", "end c_1" …
    var sent: Sent = Sent.OK                            // the answer to whoAmI, sendPushAddress, decline, cancel and end
    var pairResult: PairResult = PairResult.WrongKey
    var answerResult: AnswerResult = AnswerResult.Over
    var ringHomeResult: RingHomeResult = RingHomeResult.Busy
    val statuses = ArrayDeque<StatusResult>()           // answers to status(), in order; the last one repeats
    var hold: CompletableDeferred<Unit>? = null         // answer(), decline() and ringHome() wait for this before they answer

    override suspend fun pair(link: PairLink, phoneName: String): PairResult { calls += "pair ${link.code}"; return pairResult }
    override suspend fun whoAmI(): Sent { calls += "whoAmI"; return sent }
    override suspend fun sendPushAddress(address: String): Sent { calls += "sendPushAddress $address"; return sent }
    override suspend fun answer(callId: String): AnswerResult { calls += "answer $callId"; hold?.await(); return answerResult }
    override suspend fun decline(callId: String): Sent { calls += "decline $callId"; hold?.await(); return sent }
    override suspend fun ringHome(): RingHomeResult { calls += "ringHome"; hold?.await(); return ringHomeResult }
    override suspend fun status(callId: String): StatusResult {
        calls += "status $callId"
        return if (statuses.size > 1) statuses.removeFirst() else statuses.firstOrNull() ?: StatusResult.Known("ringing")
    }
    override suspend fun cancel(callId: String): Sent { calls += "cancel $callId"; return sent }
    override suspend fun end(callId: String): Sent { calls += "end $callId"; return sent }
}

/** Stands in for the LiveKit room. The test says what the room «says» with [say]. */
class FakeSession(private val failConnect: Boolean = false, private val othersWaiting: Boolean = true) : CallSession {
    private val feed = Channel<SessionEvent>(Channel.UNLIMITED)
    override val events: Flow<SessionEvent> = feed.receiveAsFlow()
    var connected: LiveKitTicket? = null
    var closed = 0

    fun say(event: SessionEvent) { feed.trySend(event) }

    override suspend fun connect(ticket: LiveKitTicket) {
        if (failConnect) throw IllegalStateException("cannot join")
        connected = ticket
        if (othersWaiting) say(SessionEvent.OTHER_JOINED)      // as the real one does when somebody was in the room first
    }

    override suspend fun close() { closed++ }
}

/** Stands in for the notification that rings: writes down what it was told, in order. */
class FakeNotifier : RingNotifier {
    val log = mutableListOf<String>()                   // "ring c_1 60000", "stop c_1", "missed c_1 1794700060000"
    override fun ring(callId: String, forMs: Long) { log += "ring $callId $forMs" }
    override fun stop(callId: String) { log += "stop $callId" }
    override fun missed(callId: String, at: Long) { log += "missed $callId $at" }
}
