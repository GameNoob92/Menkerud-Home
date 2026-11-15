package com.noobventure.menkerud.api

import com.noobventure.menkerud.pairing.PairLink
import com.noobventure.menkerud.store.Credentials
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [CallApi] over HTTP. Every request but pairing goes to the server the phone was paired with; with no pairing
 * stored nothing is sent at all. Nothing here is logged: the requests carry the phone's secret.
 */
class HttpCallApi(
    private val http: OkHttpClient,
    private val credentials: () -> Credentials?,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : CallApi {

    @Serializable private class PairBody(val code: String, val name: String, val appVersion: String)
    @Serializable private class PairAnswer(val deviceId: String, val secret: String, val who: String, val label: String)
    @Serializable private class TokenBody(val token: String)
    @Serializable private class Ticket(val url: String, val token: String)
    @Serializable private class AnswerBody(val livekit: Ticket)
    @Serializable private class RingHomeBody(val callId: String, val livekit: Ticket)
    @Serializable private class StatusBody(val status: String)

    private class Reply(val code: Int, val body: String)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun pair(link: PairLink, phoneName: String): PairResult {
        val url = url(link.apiBase, "api", "pair") ?: return PairResult.Failed(Failure.SERVER)
        val body = json.encodeToString(PairBody.serializer(), PairBody(link.code, phoneName, appVersion))
        val reply = send(request(url, "POST", body, null)) ?: return PairResult.Failed(Failure.UNREACHABLE)
        return when (reply.code) {
            200 -> read(PairAnswer.serializer(), reply)
                ?.takeIf { HEADER_SAFE.matches(it.deviceId) && HEADER_SAFE.matches(it.secret) }
                ?.let { PairResult.Paired(Credentials(link.apiBase, it.deviceId, it.secret, it.who, it.label)) }
                ?: PairResult.Failed(Failure.SERVER)
            400, 429 -> PairResult.WrongKey
            410 -> PairResult.Expired
            else -> PairResult.Failed(Failure.SERVER)
        }
    }

    override suspend fun whoAmI() = sent("GET", null, true, setOf(200), "api", "devices", "me")

    override suspend fun sendPushAddress(address: String) =
        sent("PUT", json.encodeToString(TokenBody.serializer(), TokenBody(address)), true, setOf(204), "api", "devices", "me", "push-token")

    override suspend fun answer(callId: String): AnswerResult {
        val me = credentials() ?: return AnswerResult.Unpaired
        if (!isCallId(callId)) return AnswerResult.Over
        val url = url(me.apiBase, "api", "calls", callId, "answer") ?: return AnswerResult.Failed(Failure.SERVER)
        val reply = send(request(url, "POST", "{}", me)) ?: return AnswerResult.Failed(Failure.UNREACHABLE)
        return when (reply.code) {
            200 -> read(AnswerBody.serializer(), reply)
                ?.let { AnswerResult.Joined(LiveKitTicket(it.livekit.url, it.livekit.token)) }
                ?: AnswerResult.Failed(Failure.SERVER)
            403, 404, 409, 410 -> AnswerResult.Over
            401 -> AnswerResult.Unpaired
            else -> AnswerResult.Failed(Failure.SERVER)
        }
    }

    override suspend fun decline(callId: String) =
        if (!isCallId(callId)) Sent.OK else sent("POST", "{}", true, setOf(200, 403, 404, 409, 410), "api", "calls", callId, "decline")

    override suspend fun ringHome(): RingHomeResult {
        val me = credentials() ?: return RingHomeResult.Unpaired
        val url = url(me.apiBase, "api", "calls", "home") ?: return RingHomeResult.Failed(Failure.SERVER)
        val reply = send(request(url, "POST", "{}", me)) ?: return RingHomeResult.Failed(Failure.UNREACHABLE)
        return when (reply.code) {
            200 -> read(RingHomeBody.serializer(), reply)
                ?.let { RingHomeResult.Ringing(it.callId, LiveKitTicket(it.livekit.url, it.livekit.token)) }
                ?: RingHomeResult.Failed(Failure.SERVER)
            409 -> RingHomeResult.Busy
            503 -> RingHomeResult.KioskOffline
            429 -> RingHomeResult.TooMany
            401 -> RingHomeResult.Unpaired
            else -> RingHomeResult.Failed(Failure.SERVER)
        }
    }

    // The state of a call, and hanging up, need no secret: knowing the long random call id is the permission.
    override suspend fun status(callId: String): StatusResult {
        val me = credentials() ?: return StatusResult.Gone
        if (!isCallId(callId)) return StatusResult.Gone
        val url = url(me.apiBase, "api", "calls", callId) ?: return StatusResult.Failed(Failure.SERVER)
        val reply = send(request(url, "GET", null, null)) ?: return StatusResult.Failed(Failure.UNREACHABLE)
        return when (reply.code) {
            200 -> read(StatusBody.serializer(), reply)?.let { StatusResult.Known(it.status) } ?: StatusResult.Failed(Failure.SERVER)
            404 -> StatusResult.Gone
            else -> StatusResult.Failed(Failure.SERVER)
        }
    }

    override suspend fun cancel(callId: String) = hangUp(callId, "cancel")

    override suspend fun end(callId: String) = hangUp(callId, "end")

    private suspend fun hangUp(callId: String, how: String) =
        if (!isCallId(callId)) Sent.OK else sent("POST", "{}", false, setOf(200, 404), "api", "calls", callId, how)

    /** «.» and «..» would be read as «here» and «one level up» when the address is built, and an empty id names no
     *  call. Such an id is no call's id: the call it would name is over, and nothing is sent. */
    private fun isCallId(id: String) = id.isNotEmpty() && id != "." && id != ".."

    /** A request whose answer only says «done» or not. */
    private suspend fun sent(method: String, body: String?, withSecret: Boolean, done: Set<Int>, vararg path: String): Sent {
        val me = credentials() ?: return Sent.UNPAIRED
        val url = url(me.apiBase, *path) ?: return Sent.FAILED
        val reply = send(request(url, method, body, me.takeIf { withSecret })) ?: return Sent.FAILED
        return when {
            reply.code in done -> Sent.OK
            reply.code == 401 && withSecret -> Sent.UNPAIRED
            else -> Sent.FAILED
        }
    }

    /** Each part becomes one piece of the path, escaped, so that nothing in a call id can change where a request goes. */
    private fun url(base: String, vararg path: String): HttpUrl? =
        base.toHttpUrlOrNull()?.newBuilder()?.apply { path.forEach { addPathSegment(it) } }?.build()

    private fun request(url: HttpUrl, method: String, body: String?, me: Credentials?): Request {
        val builder = Request.Builder().url(url).header("X-App-Version", appVersion)
        if (me != null) builder.header("Authorization", "Bearer ${me.deviceId}.${me.secret}")
        return builder.method(method, body?.toRequestBody(JSON)).build()
    }

    /** The answer, or null when there was none at all. */
    private suspend fun send(request: Request): Reply? = withContext(io) {
        try {
            http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            null
        }
    }

    private fun <T> read(serializer: KSerializer<T>, reply: Reply): T? = try {
        json.decodeFromString(serializer, reply.body)
    } catch (e: IllegalArgumentException) {      // SerializationException is one
        null
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** What may stand in an Authorization header. An id or a secret with anything else in it would make every
         *  later request fail, so a pairing that hands one out is refused at once. */
        val HEADER_SAFE = Regex("[A-Za-z0-9._~+/=-]+")
    }
}
