package com.noobventure.menkerud.api

import com.noobventure.menkerud.pairing.PairLink
import com.noobventure.menkerud.store.Credentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HttpCallApiTest {
    @get:Rule val server = MockWebServer()
    private val base by lazy { server.url("").toString().trimEnd('/') }
    private var paired = true
    private val api by lazy { HttpCallApi(OkHttpClient(), { if (paired) Credentials(base, "d_1", "s3", "far", "Far") else null }, "1.2.3") }

    private fun reply(code: Int, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    private fun line() = server.takeRequest().let { it.method + " " + it.path }
    private val ticket = """{"callId":"c_1","livekit":{"url":"wss://rtc.test","token":"tok"},"somethingNew":1}"""

    @Test fun `pairing sends the key and gives back who the phone is`() = runTest {
        server.enqueue(MockResponse().setBody("""{"deviceId":"d_9","secret":"abc","who":"mor","label":"Mor"}"""))
        assertEquals(PairResult.Paired(Credentials(base, "d_9", "abc", "mor", "Mor")), api.pair(PairLink(base, "0123-4567"), "Galaxy S26"))
        val sent = server.takeRequest()
        assertEquals("POST /api/pair", sent.method + " " + sent.path)
        assertEquals("""{"code":"0123-4567","name":"Galaxy S26","appVersion":"1.2.3"}""", sent.body.readUtf8())
        assertNull(sent.getHeader("Authorization"))
    }

    @Test fun `answering carries the phone's secret and brings back the ticket`() = runTest {
        server.enqueue(MockResponse().setBody(ticket))
        assertEquals(AnswerResult.Joined(LiveKitTicket("wss://rtc.test", "tok")), api.answer("c_1"))
        val sent = server.takeRequest()
        assertEquals("POST /api/calls/c_1/answer", sent.method + " " + sent.path)
        assertEquals("Bearer d_1.s3", sent.getHeader("Authorization"))
        assertEquals("1.2.3", sent.getHeader("X-App-Version"))
    }

    @Test fun `pairing tells a wrong key from an expired one and from a server that is unwell`() = runTest {
        val link = PairLink(base, "0123-4567")
        reply(400); assertEquals(PairResult.WrongKey, api.pair(link, "x"))
        reply(429); assertEquals(PairResult.WrongKey, api.pair(link, "x"))
        reply(410); assertEquals(PairResult.Expired, api.pair(link, "x"))
        reply(500); assertEquals(PairResult.Failed(Failure.SERVER), api.pair(link, "x"))
        reply(200, "<html>"); assertEquals(PairResult.Failed(Failure.SERVER), api.pair(link, "x"))
        reply(200, """{"deviceId":"d_9"}"""); assertEquals(PairResult.Failed(Failure.SERVER), api.pair(link, "x"))
        // a secret that could not travel in a header would break every later request: better not to be paired at all
        reply(200, """{"deviceId":"d_9","secret":"two\nlines","who":"mor","label":"Mor"}""")
        assertEquals(PairResult.Failed(Failure.SERVER), api.pair(link, "x"))
    }

    @Test fun `asking who the phone is tells a removed phone from a server that is unwell`() = runTest {
        reply(200, """{"deviceId":"d_1","who":"far","label":"Far","name":"x"}"""); assertEquals(Sent.OK, api.whoAmI())
        val sent = server.takeRequest()
        assertEquals("GET /api/devices/me", sent.method + " " + sent.path)
        assertEquals("Bearer d_1.s3", sent.getHeader("Authorization"))
        reply(401); assertEquals(Sent.UNPAIRED, api.whoAmI())
        reply(500); assertEquals(Sent.FAILED, api.whoAmI())
    }

    @Test fun `the push address goes out as token`() = runTest {
        reply(204, ""); assertEquals(Sent.OK, api.sendPushAddress("fid-123"))
        val sent = server.takeRequest()
        assertEquals("PUT /api/devices/me/push-token", sent.method + " " + sent.path)
        assertEquals("""{"token":"fid-123"}""", sent.body.readUtf8())
        assertEquals("Bearer d_1.s3", sent.getHeader("Authorization"))
        reply(401); assertEquals(Sent.UNPAIRED, api.sendPushAddress("fid-123"))
        reply(400); assertEquals(Sent.FAILED, api.sendPushAddress("fid-123"))
    }

    @Test fun `an answer that comes too late says the call is over`() = runTest {
        for (code in listOf(403, 404, 409, 410)) { reply(code); assertEquals("$code", AnswerResult.Over, api.answer("c_1")) }
        reply(401); assertEquals(AnswerResult.Unpaired, api.answer("c_1"))
        reply(500); assertEquals(AnswerResult.Failed(Failure.SERVER), api.answer("c_1"))
        reply(200, """{"callId":"c_1"}"""); assertEquals(AnswerResult.Failed(Failure.SERVER), api.answer("c_1"))
    }

    @Test fun `declining a call that is already over is as good as declining it`() = runTest {
        for (code in listOf(200, 403, 404, 409, 410)) { reply(code); assertEquals("$code", Sent.OK, api.decline("c_1")) }
        val sent = server.takeRequest()
        assertEquals("POST /api/calls/c_1/decline", sent.method + " " + sent.path)
        assertEquals("{}", sent.body.readUtf8())
        assertEquals("Bearer d_1.s3", sent.getHeader("Authorization"))
        reply(401); assertEquals(Sent.UNPAIRED, api.decline("c_1"))
        reply(500); assertEquals(Sent.FAILED, api.decline("c_1"))
    }

    @Test fun `ringing home brings back the call and the ticket, or says why not`() = runTest {
        reply(200, """{"callId":"c_7","status":"ringing","livekit":{"url":"wss://rtc.test","token":"tok7"},"expiresAt":1763200000000}""")
        assertEquals(RingHomeResult.Ringing("c_7", LiveKitTicket("wss://rtc.test", "tok7")), api.ringHome())
        val sent = server.takeRequest()
        assertEquals("POST /api/calls/home", sent.method + " " + sent.path)
        assertEquals("Bearer d_1.s3", sent.getHeader("Authorization"))
        reply(409); assertEquals(RingHomeResult.Busy, api.ringHome())
        reply(503); assertEquals(RingHomeResult.KioskOffline, api.ringHome())
        reply(429); assertEquals(RingHomeResult.TooMany, api.ringHome())
        reply(401); assertEquals(RingHomeResult.Unpaired, api.ringHome())
        reply(500); assertEquals(RingHomeResult.Failed(Failure.SERVER), api.ringHome())
        reply(200, "{}"); assertEquals(RingHomeResult.Failed(Failure.SERVER), api.ringHome())
    }

    @Test fun `the state of a call is asked for without the secret`() = runTest {
        reply(200, """{"callId":"c_1","status":"unreachable","target":"far","direction":"parent_to_home"}""")
        assertEquals(StatusResult.Known("unreachable"), api.status("c_1"))
        val sent = server.takeRequest()
        assertEquals("GET /api/calls/c_1", sent.method + " " + sent.path)
        assertNull(sent.getHeader("Authorization"))
        reply(404); assertEquals(StatusResult.Gone, api.status("c_1"))
        reply(500); assertEquals(StatusResult.Failed(Failure.SERVER), api.status("c_1"))
        reply(200, "[]"); assertEquals(StatusResult.Failed(Failure.SERVER), api.status("c_1"))
    }

    @Test fun `hanging up a call the server has forgotten is as good as hanging it up`() = runTest {
        reply(200); assertEquals(Sent.OK, api.cancel("c_1"))
        val cancel = server.takeRequest()
        assertEquals("POST /api/calls/c_1/cancel", cancel.method + " " + cancel.path)
        assertNull(cancel.getHeader("Authorization"))
        reply(200); assertEquals(Sent.OK, api.end("c_1"))
        assertEquals("POST /api/calls/c_1/end", line())
        reply(404); assertEquals(Sent.OK, api.cancel("c_1"))
        reply(404); assertEquals(Sent.OK, api.end("c_1"))
        reply(500); assertEquals(Sent.FAILED, api.cancel("c_1"))
        reply(500); assertEquals(Sent.FAILED, api.end("c_1"))
    }

    @Test fun `a server that is not there is UNREACHABLE`() = runTest {
        base                     // the address must be read while the server still has one
        server.shutdown()
        assertEquals(PairResult.Failed(Failure.UNREACHABLE), api.pair(PairLink(base, "0123-4567"), "x"))
        assertEquals(AnswerResult.Failed(Failure.UNREACHABLE), api.answer("c_1"))
        assertEquals(RingHomeResult.Failed(Failure.UNREACHABLE), api.ringHome())
        assertEquals(StatusResult.Failed(Failure.UNREACHABLE), api.status("c_1"))
        assertEquals(Sent.FAILED, api.whoAmI())
        assertEquals(Sent.FAILED, api.end("c_1"))
    }

    @Test fun `nothing is sent when the phone is not paired`() = runTest {
        paired = false
        assertEquals(Sent.UNPAIRED, api.whoAmI())
        assertEquals(Sent.UNPAIRED, api.sendPushAddress("fid-123"))
        assertEquals(AnswerResult.Unpaired, api.answer("c_1"))
        assertEquals(Sent.UNPAIRED, api.decline("c_1"))
        assertEquals(RingHomeResult.Unpaired, api.ringHome())
        assertEquals(StatusResult.Gone, api.status("c_1"))
        assertEquals(Sent.UNPAIRED, api.cancel("c_1"))
        assertEquals(Sent.UNPAIRED, api.end("c_1"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `a call id cannot reach outside its place in the address`() = runTest {
        reply(404); api.status("../devices/me?x=1")
        assertEquals("GET /api/calls/..%2Fdevices%2Fme%3Fx=1", line())
        // «..» alone would be taken as «one level up» when the address is built: such an id is no id, and nothing is sent
        assertEquals(StatusResult.Gone, api.status(".."))
        assertEquals(AnswerResult.Over, api.answer("."))
        assertEquals(Sent.OK, api.end(""))
        assertEquals(1, server.requestCount)
    }
}
