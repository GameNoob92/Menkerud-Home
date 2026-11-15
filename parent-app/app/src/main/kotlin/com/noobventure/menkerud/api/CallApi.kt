package com.noobventure.menkerud.api

import com.noobventure.menkerud.pairing.PairLink
import com.noobventure.menkerud.store.Credentials

/** What lets this phone into one LiveKit room: the server's address and a ticket for that room. */
data class LiveKitTicket(val url: String, val token: String) {
    override fun toString() = "LiveKitTicket(url=$url)"       // the ticket itself never goes into a log line
}

/** Why a request gave nothing: no answer at all · an answer the app cannot use. */
enum class Failure { UNREACHABLE, SERVER }

/** For requests whose answer carries nothing but «done». UNPAIRED: the server no longer knows this phone. */
enum class Sent { OK, UNPAIRED, FAILED }

sealed interface PairResult {
    data class Paired(val credentials: Credentials) : PairResult
    data object WrongKey : PairResult
    data object Expired : PairResult
    data class Failed(val why: Failure) : PairResult
}

sealed interface AnswerResult {
    data class Joined(val ticket: LiveKitTicket) : AnswerResult
    /** Answered elsewhere, hung up, timed out, or never this phone's call. */
    data object Over : AnswerResult
    data object Unpaired : AnswerResult
    data class Failed(val why: Failure) : AnswerResult
}

sealed interface RingHomeResult {
    data class Ringing(val callId: String, val ticket: LiveKitTicket) : RingHomeResult
    data object Busy : RingHomeResult
    data object KioskOffline : RingHomeResult
    data object TooMany : RingHomeResult
    data object Unpaired : RingHomeResult
    data class Failed(val why: Failure) : RingHomeResult
}

sealed interface StatusResult {
    /** `ringing`, `connected`, `declined`, `missed`, `unreachable`, `cancelled` or `ended` – as the server said it. */
    data class Known(val status: String) : StatusResult
    /** The server has forgotten the call: treat it as over. */
    data object Gone : StatusResult
    data class Failed(val why: Failure) : StatusResult
}

/** The call backend, as far as the app uses it (contract C1–C10 in the plan for part 1). */
interface CallApi {
    suspend fun pair(link: PairLink, phoneName: String): PairResult
    suspend fun whoAmI(): Sent
    suspend fun sendPushAddress(address: String): Sent
    suspend fun answer(callId: String): AnswerResult
    suspend fun decline(callId: String): Sent
    suspend fun ringHome(): RingHomeResult
    suspend fun status(callId: String): StatusResult
    suspend fun cancel(callId: String): Sent
    suspend fun end(callId: String): Sent
}
