package com.noobventure.menkerud.devcopy

import com.noobventure.menkerud.FakeBox
import com.noobventure.menkerud.FakeNotifier
import com.noobventure.menkerud.FakeSession
import com.noobventure.menkerud.api.HttpCallApi
import com.noobventure.menkerud.api.Sent
import com.noobventure.menkerud.call.CallController
import com.noobventure.menkerud.call.CallKind
import com.noobventure.menkerud.call.CallState
import com.noobventure.menkerud.call.EndReason
import com.noobventure.menkerud.call.LastCall
import com.noobventure.menkerud.call.SessionFactory
import com.noobventure.menkerud.pairing.PairPolicy
import com.noobventure.menkerud.pairing.Pairing
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.push.PushMessage
import com.noobventure.menkerud.push.readPush
import com.noobventure.menkerud.store.CredentialStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials as BasicAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's own pairing, API client and call controller against a RUNNING dev copy of the backend – everything but
 * Android, the push service and the room itself. It answers one question: do the app and the server mean the same
 * by the contract? Skipped unless the dev copy's address is given:
 *
 *   MENKERUD_DEV_COPY=http://127.0.0.1:3100 ./gradlew :app:testDebugUnitTest --tests '*DevCopyTest*'
 *
 * The dev copy must run with PUSH_TRANSPORT=file (`npm run dev` in call-backend/ with .dev/callapi.env as it comes):
 * its «pushes» are then lines in a file, which this test reads the way the phone would get them. It pairs a phone
 * as far, and removes it again.
 */
class DevCopyTest {
    private val api = System.getenv("MENKERUD_DEV_COPY").orEmpty().trimEnd('/')
    private val admin = System.getenv("MENKERUD_DEV_ADMIN") ?: "http://127.0.0.1:3101"
    private val outbox = File(System.getenv("MENKERUD_DEV_OUTBOX") ?: "../../.dev/callapi-data/push-outbox.jsonl")
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    @get:Rule val tmp = TemporaryFolder()
    private val oneThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()     // as the main thread is, in the app
    private val scope = CoroutineScope(SupervisorJob() + oneThread)
    private val store by lazy { CredentialStore(File(tmp.root, "device.bin"), FakeBox()) }
    private val client by lazy { HttpCallApi(OkHttpClient(), { store.current.value }, "dev-copy-test") }
    private val notifier = FakeNotifier()
    private val sessions = CopyOnWriteArrayList<FakeSession>()
    private val remembered = CopyOnWriteArrayList<LastCall>()
    private val calls by lazy {
        CallController(
            client, SessionFactory { FakeSession().also { sessions += it } }, notifier,
            unpair = { store.clear() }, remember = { remembered += it }, now = System::currentTimeMillis, scope = scope
        )
    }

    @Before fun onlyWithADevCopy() {
        assumeTrue("needs a running dev copy: set MENKERUD_DEV_COPY", api.isNotEmpty())
    }

    @After fun tidyUp() {
        store.current.value?.let { me -> send("DELETE", "$admin/api/devices/${me.deviceId}", null, asAdmin = true) }
        scope.cancel()
        oneThread.close()
    }

    // --- what stands in for the operator's browser, the screen at home, and the push service ---

    /** One request as the operator on the pairing page, as the screen at home (its device key), or as nobody. */
    private fun send(method: String, url: String, body: String?, asAdmin: Boolean = false, asKiosk: Boolean = false): Pair<Int, String> {
        val request = Request.Builder().url(url).method(method, body?.toRequestBody(jsonType))
        if (asAdmin) request.header("Authorization", BasicAuth.basic("operator", System.getenv("MENKERUD_DEV_ADMIN_PASSWORD") ?: "dev"))
        if (asKiosk) request.header("X-Device-Key", System.getenv("MENKERUD_DEV_DEVICE_KEY") ?: "dev-key")
        return http.newCall(request.build()).execute().use { it.code to it.body!!.string() }
    }

    private fun field(text: String, name: String) = json.parseToJsonElement(text).jsonObject[name]!!.jsonPrimitive.content
    private fun statusOf(callId: String) = field(send("GET", "$api/api/calls/$callId", null).second, "status")
    private fun asKiosk(callId: String, what: String) = send("POST", "$api/api/calls/$callId/$what", "{}", asKiosk = true).first

    private suspend fun until(what: String, ms: Long = 10_000, done: suspend () -> Boolean) {
        val give = System.currentTimeMillis() + ms
        while (!done()) {
            if (System.currentTimeMillis() > give) throw AssertionError("waited $ms ms for: $what (the app's state: ${calls.state.value})")
            delay(50)
        }
    }

    /** Pairs this «phone» as far with a key made on the pairing page, the way a scanned QR code would. */
    private suspend fun pair() {
        val key = field(send("POST", "$admin/api/pair-code", """{"who":"far"}""", asAdmin = true).second, "code")
        val anyServer = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = true)
        val pairing = Pairing(client, store, PushAddressSync(client, store) { "dev-copy-test-address" }, anyServer, "DevCopyTest")
        assertNull(pairing.submit("menkerudhome://pair?api=" + java.net.URLEncoder.encode(api, "UTF-8") + "&code=" + key))
        seen = if (outbox.exists()) outbox.readLines().size else 0
    }

    private var seen = 0
    private val waiting = ArrayDeque<Map<String, String>>()

    /** The next «push» of that kind for this phone: a line the dev copy wrote to its outbox instead of sending it. */
    private suspend fun push(type: String): PushMessage {
        var found: Map<String, String>? = null
        until("a `$type` push in ${outbox.path}") {
            val lines = if (outbox.exists()) outbox.readLines() else emptyList()
            val me = store.current.value?.deviceId
            for (line in lines.drop(seen)) {
                val entry = json.parseToJsonElement(line).jsonObject
                if (entry["deviceId"]!!.jsonPrimitive.content == me) {
                    waiting += entry["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                }
            }
            seen = lines.size
            found = waiting.firstOrNull { it["type"] == type }
            found != null
        }
        waiting.remove(found)
        return readPush(found!!)
    }

    /** The kids press «Ring far» on the screen at home. Returns the call's id. */
    private fun kidsRing(): String {
        val (code, body) = send("POST", "$api/api/calls", """{"target":"far"}""", asKiosk = true)
        assertEquals(body, 200, code)
        return field(body, "callId")
    }

    /** Stands in for the screen at home on its event stream. `events` fills as the server speaks. */
    private inner class Kiosk : AutoCloseable {
        val events = CopyOnWriteArrayList<JsonObject>()
        private val call = http.newCall(
            Request.Builder().url("$api/api/kiosk/events").header("Accept", "text/event-stream")
                .header("X-Device-Key", System.getenv("MENKERUD_DEV_DEVICE_KEY") ?: "dev-key").build()
        )
        private val reader = thread(isDaemon = true) {
            try {
                call.execute().use { answer ->
                    val source = answer.body!!.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) events += json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                    }
                }
            } catch (e: java.io.IOException) {
                // closed
            }
        }
        fun type(event: JsonObject) = event["type"]!!.jsonPrimitive.content
        override fun close() { call.cancel(); reader.join(2_000) }
    }

    // --- the tests ---

    @Test fun `a key from the pairing page makes the phone far, and the server learns where its pushes go`() = runBlocking {
        pair()
        val me = store.current.value!!
        assertEquals("far", me.who)
        assertEquals("Far", me.label)
        assertEquals(api, me.apiBase)
        assertEquals(Sent.OK, client.whoAmI())
        val listed = send("GET", "$admin/api/devices", null, asAdmin = true).second
        assertTrue(listed, listed.contains(""""deviceId":"${me.deviceId}""""))
        assertTrue(listed, Regex(""""name":"DevCopyTest"[^}]*"push":"ok"""").containsMatchIn(listed))
    }

    @Test fun `the kids call - the push rings, Svar gets a ticket for the room, hanging up ends it on the server`() = runBlocking {
        pair()
        val callId = kidsRing()
        val rang = push("call")
        assertEquals(callId, (rang as PushMessage.Call).callId)
        assertEquals("far", rang.who)
        calls.onPush(rang).join()
        delay(500)                                                  // the «is it still ringing?» question must not stop it
        assertEquals(CallState.Incoming(callId), calls.state.value)

        calls.answer(callId)
        until("being in the call") { calls.state.value == CallState.InCall(callId) }
        val ticket = sessions.single().connected!!
        assertTrue(ticket.url, ticket.url.startsWith("ws"))
        assertEquals("a LiveKit ticket is three parts with dots between", 3, ticket.token.split('.').size)
        assertEquals("connected", statusOf(callId))

        calls.hangUp()
        until("the server knowing it is over") { statusOf(callId) == "ended" }
        assertEquals(listOf(CallKind.FROM_HOME_ANSWERED), remembered.map { it.kind })
    }

    @Test fun `Ikke nå reaches the server`() = runBlocking {
        pair()
        val callId = kidsRing()
        calls.onPush(push("call")).join()
        calls.decline(callId).join()
        assertEquals("declined", statusOf(callId))
        assertEquals(CallState.Idle, calls.state.value)
    }

    @Test fun `the kids hang up while it rings - the phone is told, and leaves a missed call`() = runBlocking {
        pair()
        val callId = kidsRing()
        calls.onPush(push("call")).join()
        assertEquals(200, send("POST", "$api/api/calls/$callId/cancel", "{}").first)
        val over = push("call_end") as PushMessage.CallEnd
        assertEquals(callId, over.callId)
        assertEquals("cancelled", over.reason)
        assertNotNull(over.at)
        calls.onPush(over).join()
        assertEquals(CallState.Idle, calls.state.value)
        assertEquals(listOf("ring", "stop", "missed"), withContext(oneThread) { notifier.log.map { it.substringBefore(' ') } })
    }

    @Test fun `ring home with no screen connected at home says so`() = runBlocking {
        pair()
        calls.ringHome()
        until("the answer") { calls.state.value == CallState.Ended(EndReason.KIOSK_OFFLINE) }
    }

    @Test fun `ring home - the screen at home answers, and another time declines`() = runBlocking {
        pair()
        Kiosk().use { kiosk ->
            until("the screen being connected") { kiosk.events.any { kiosk.type(it) == "hello" } }

            calls.ringHome()
            until("the server naming the call") { (calls.state.value as? CallState.RingingHome)?.callId != null }
            val callId = (calls.state.value as CallState.RingingHome).callId!!
            until("the screen being told") { kiosk.events.any { kiosk.type(it) == "incoming" && it["callId"]!!.jsonPrimitive.content == callId } }
            assertEquals("far", kiosk.events.first { kiosk.type(it) == "incoming" }["from"]!!.jsonPrimitive.content)
            assertEquals(200, asKiosk(callId, "ringing"))
            assertEquals(200, asKiosk(callId, "answer"))
            until("the app noticing that they answered") { calls.state.value == CallState.InCall(callId) }
            calls.hangUp()
            until("the server knowing it is over") { statusOf(callId) == "ended" }

            calls.dismissEnded()
            until("the last word going away") { calls.state.value == CallState.Idle }
            calls.ringHome()
            until("the server naming the second call") { (calls.state.value as? CallState.RingingHome)?.callId != null }
            val second = (calls.state.value as CallState.RingingHome).callId!!
            assertEquals(200, asKiosk(second, "ringing"))
            assertEquals(200, asKiosk(second, "decline"))
            until("«Ingen svarte hjemme»") { calls.state.value == CallState.Ended(EndReason.NOBODY_HOME) }
        }
        assertEquals(listOf(CallKind.TO_HOME_ANSWERED, CallKind.TO_HOME_UNANSWERED), remembered.map { it.kind })
    }

    @Test fun `a phone removed on the pairing page is told so, and forgets its pairing`() = runBlocking {
        pair()
        assertEquals(204, send("DELETE", "$admin/api/devices/${store.current.value!!.deviceId}", null, asAdmin = true).first)
        assertEquals(Sent.UNPAIRED, client.whoAmI())
        calls.ringHome()
        until("being sent back to pairing") { calls.state.value == CallState.Ended(EndReason.UNPAIRED) }
        assertNull(store.current.value)
    }
}
