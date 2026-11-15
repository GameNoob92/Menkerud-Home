package com.noobventure.menkerud.call

import com.noobventure.menkerud.FakeCallApi
import com.noobventure.menkerud.FakeNotifier
import com.noobventure.menkerud.FakeSession
import com.noobventure.menkerud.api.AnswerResult
import com.noobventure.menkerud.api.Failure
import com.noobventure.menkerud.api.LiveKitTicket
import com.noobventure.menkerud.api.RingHomeResult
import com.noobventure.menkerud.api.StatusResult
import com.noobventure.menkerud.push.PushMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val T0 = 1_794_700_000_000
    private val ticket = LiveKitTicket("wss://rtc.test", "tok")

    private var api = FakeCallApi()
    private var notifier = FakeNotifier()
    private var sessions = mutableListOf<FakeSession>()
    private var remembered = mutableListOf<LastCall>()
    private var unpaired = 0
    private var failConnect = false
    private var othersWaiting = true
    private var failCreate = false
    private var failClose = false

    /** A new controller on the test's virtual clock; `now` follows that clock from T0. */
    private fun TestScope.make() = CallController(
        api,
        SessionFactory {
            if (failCreate) throw IllegalStateException("no room to be had")
            FakeSession(failConnect, othersWaiting, failClose).also { sessions += it }
        },
        notifier,
        unpair = { unpaired++ }, remember = { remembered += it }, now = { T0 + testScheduler.currentTime }, scope = backgroundScope
    )

    /** Lets `ms` pass and everything due by then happen. */
    private fun TestScope.pass(ms: Long = 0) { advanceTimeBy(ms); runCurrent() }

    private fun call(id: String = "c_1", inMs: Long = 60_000) = PushMessage.Call(id, "far", T0 + inMs)
    private fun asked(what: String) = api.calls.count { it == what }

    private fun TestScope.ringing(): CallController = make().also { it.onPush(call()); pass() }

    private fun TestScope.inCall(): CallController {
        api.answerResult = AnswerResult.Joined(ticket)
        return ringing().also { it.answer("c_1"); pass(); assertEquals(CallState.InCall("c_1"), it.state.value) }
    }

    private fun TestScope.ringingHome(): CallController {
        api.ringHomeResult = RingHomeResult.Ringing("c_2", ticket)
        return make().also { it.ringHome(); pass(); assertEquals(CallState.RingingHome("c_2"), it.state.value) }
    }

    private fun fresh() {
        api = FakeCallApi(); notifier = FakeNotifier(); sessions = mutableListOf(); remembered = mutableListOf(); unpaired = 0
    }

    // --- a call comes in ---

    @Test fun `a call from home rings`() = runTest {
        val c = ringing()
        assertEquals(CallState.Incoming("c_1"), c.state.value)
        assertEquals(listOf("ring c_1 60000"), notifier.log)
        assertEquals(listOf("status c_1"), api.calls)
    }

    @Test fun `a push for a call that is already over stops ringing at once`() = runTest {
        api.statuses += StatusResult.Known("missed")
        val c = ringing()
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
        pass(60_000)
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
        assertTrue(remembered.isEmpty())
    }

    @Test fun `no network for the check keeps it ringing`() = runTest {
        api.statuses += StatusResult.Failed(Failure.UNREACHABLE)
        assertEquals(CallState.Incoming("c_1"), ringing().state.value)
        assertEquals(listOf("ring c_1 60000"), notifier.log)
    }

    @Test fun `a phone clock that is wrong neither cuts the ring short nor lets it ring for ever`() {
        assertEquals(45_000, ringFor(T0 - 600_000, T0))     // «it ended ten minutes ago»: not to be believed
        assertEquals(45_000, ringFor(T0 + 4_000, T0))
        assertEquals(30_000, ringFor(T0 + 30_000, T0))
        assertEquals(5_000, ringFor(T0 + 5_000, T0))
        assertEquals(60_000, ringFor(T0 + 60_000, T0))
        // A phone whose clock is a little behind the server's sees more than a minute left. Home rings for a minute,
        // so the phone does too – not for the 45 seconds that would leave the kids waiting with nobody able to answer.
        assertEquals(60_000, ringFor(T0 + 61_500, T0))
        assertEquals(60_000, ringFor(T0 + 600_000, T0))
    }

    @Test fun `nobody answers - the ring ends and leaves a missed call`() = runTest {
        val c = ringing()
        pass(59_999)
        assertEquals(CallState.Incoming("c_1"), c.state.value)
        pass(1)
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1", "missed c_1 ${T0 + 60_000}"), notifier.log)
        assertEquals(listOf(LastCall(T0 + 60_000, CallKind.FROM_HOME_MISSED)), remembered)
    }

    @Test fun `the kids hang up while it rings - a missed call`() = runTest {
        val c = ringing()
        c.onPush(PushMessage.CallEnd("c_1", "cancelled", T0 + 5_000)); pass()
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1", "missed c_1 ${T0 + 5_000}"), notifier.log)
        pass(60_000)                                    // the ring's own clock was stopped with it
        assertEquals(3, notifier.log.size)
        assertEquals(listOf(LastCall(T0 + 5_000, CallKind.FROM_HOME_MISSED)), remembered)
    }

    @Test fun `answered on the other phone - it just stops`() = runTest {
        val c = ringing()
        c.onPush(PushMessage.CallEnd("c_1", "answered_elsewhere", null)); pass()
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
        assertTrue(remembered.isEmpty())
    }

    @Test fun `a missed call the phone never rang for still leaves its notice`() = runTest {
        val c = make()
        c.onPush(PushMessage.CallEnd("c_9", "missed", T0 - 3_600_000)); pass()
        assertEquals(listOf("stop c_9", "missed c_9 ${T0 - 3_600_000}"), notifier.log)
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf(LastCall(T0 - 3_600_000, CallKind.FROM_HOME_MISSED)), remembered)
    }

    // Android may have ended the app while the notification rang on. The push that ends the call then starts a new
    // process that knows nothing of it – and the notification is still there.
    @Test fun `a call that ends after the app was restarted takes its notification away`() = runTest {
        val c = make()
        c.onPush(PushMessage.CallEnd("c_1", "answered_elsewhere", null)); pass()
        assertEquals(listOf("stop c_1"), notifier.log)
        assertEquals(CallState.Idle, c.state.value)
        assertTrue(remembered.isEmpty())
    }

    @Test fun `the end of some other call does not disturb a call that is on`() = runTest {
        val c = inCall()
        val before = notifier.log.toList()
        c.onPush(PushMessage.CallEnd("c_1", "ended", null)); c.onPush(PushMessage.CallEnd("c_0", "answered_elsewhere", null)); pass()
        assertEquals(before, notifier.log)
        assertEquals(CallState.InCall("c_1"), c.state.value)
    }

    @Test fun `the same missed call told twice is remembered once`() = runTest {
        val c = make()
        repeat(2) { c.onPush(PushMessage.CallEnd("c_9", "missed", T0 - 3_600_000)); pass() }
        assertEquals(1, notifier.log.count { it.startsWith("missed c_9") })
        assertEquals(1, remembered.size)
        // and a ring that ran out here, followed by the server saying so as well
        c.onPush(call()); pass(60_000)
        c.onPush(PushMessage.CallEnd("c_1", "missed", T0 + 60_500)); pass()
        assertEquals(1, notifier.log.count { it.startsWith("missed c_1") })
        assertEquals(2, remembered.size)
    }

    @Test fun `the same call pushed twice rings once`() = runTest {
        val c = ringing()
        c.onPush(call()); pass()
        assertEquals(listOf("ring c_1 60000"), notifier.log)
        assertEquals(1, asked("status c_1"))
        assertEquals(CallState.Incoming("c_1"), c.state.value)
    }

    @Test fun `a new call while an old one still rings here replaces it`() = runTest {
        val c = ringing()
        c.onPush(call("c_5")); pass()
        assertEquals(listOf("ring c_1 60000", "stop c_1", "ring c_5 60000"), notifier.log)
        assertEquals(CallState.Incoming("c_5"), c.state.value)
        assertTrue(remembered.isEmpty())
    }

    @Test fun `unknown messages and unknown reasons do no harm`() = runTest {
        val c = ringing()
        c.onPush(PushMessage.Unknown); pass()
        assertEquals(CallState.Incoming("c_1"), c.state.value)
        assertEquals(listOf("ring c_1 60000"), notifier.log)
        c.onPush(PushMessage.CallEnd("c_1", "something-new", null)); pass()
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
    }

    @Test fun `a call that comes while the last word is still on screen rings`() = runTest {
        val c = ringing()
        c.answer("c_1"); pass()                          // the server says the call is over
        assertEquals(CallState.Ended(EndReason.CALL_OVER), c.state.value)
        c.onPush(call("c_6")); pass()
        assertEquals(CallState.Incoming("c_6"), c.state.value)
        pass(4_000)                                      // the old «Samtalen er over» must not sweep the new call away
        assertEquals(CallState.Incoming("c_6"), c.state.value)
    }

    // --- «Svar» and «Ikke nå» ---

    @Test fun `Svar gets in`() = runTest {
        val c = inCall()
        assertEquals(ticket, sessions.single().connected)
        assertSame(sessions.single(), c.session.value)
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
        assertEquals(1, asked("answer c_1"))
        pass(120_000)                                   // the ring's clock went with the ringing: no «missed» later
        assertEquals(CallState.InCall("c_1"), c.state.value)
        assertEquals(2, notifier.log.size)
    }

    @Test fun `Svar after the call was taken elsewhere says the call is over`() = runTest {
        val c = ringing()                               // the fake server answers «over» unless told otherwise
        c.answer("c_1"); pass()
        assertEquals(CallState.Ended(EndReason.CALL_OVER), c.state.value)
        assertTrue(sessions.isEmpty())
        pass(3_999)
        assertEquals(CallState.Ended(EndReason.CALL_OVER), c.state.value)
        pass(1)
        assertEquals(CallState.Idle, c.state.value)
    }

    @Test fun `Svar twice starts one call`() = runTest {
        api.answerResult = AnswerResult.Joined(ticket)
        api.hold = CompletableDeferred()
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(CallState.Answering("c_1"), c.state.value)
        c.answer("c_1"); pass()
        api.hold!!.complete(Unit); pass()
        c.answer("c_1"); pass()
        assertEquals(1, asked("answer c_1"))
        assertEquals(1, sessions.size)
        assertEquals(CallState.InCall("c_1"), c.state.value)
    }

    @Test fun `Svar from the notification works after a restart`() = runTest {
        api.answerResult = AnswerResult.Joined(ticket)
        val c = make()                                  // a new process: it never saw the push
        c.answer("c_1"); pass()
        assertEquals(CallState.InCall("c_1"), c.state.value)
        assertEquals(listOf("stop c_1"), notifier.log)
    }

    @Test fun `a removed phone that answers is sent back to pairing`() = runTest {
        api.answerResult = AnswerResult.Unpaired
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(1, unpaired)
        assertEquals(CallState.Ended(EndReason.UNPAIRED), c.state.value)
    }

    @Test fun `a server that cannot be reached when answering says so`() = runTest {
        api.answerResult = AnswerResult.Failed(Failure.UNREACHABLE)
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(CallState.Ended(EndReason.NO_SERVER), c.state.value)
        assertEquals(0, unpaired)
    }

    @Test fun `a room that cannot be joined ends the call on the server too`() = runTest {
        failConnect = true
        api.answerResult = AnswerResult.Joined(ticket)
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(1, asked("end c_1"))
        assertEquals(CallState.Ended(EndReason.COULD_NOT_JOIN), c.state.value)
        assertEquals(1, sessions.single().closed)
        assertNull(c.session.value)
        assertTrue(remembered.isEmpty())
    }

    @Test fun `a room that cannot even be made ends the call cleanly, on the server too`() = runTest {
        failCreate = true
        api.answerResult = AnswerResult.Joined(ticket)
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(CallState.Ended(EndReason.COULD_NOT_JOIN), c.state.value)
        assertEquals(1, asked("end c_1"))

        fresh()
        api.ringHomeResult = RingHomeResult.Ringing("c_2", ticket)
        val d = make(); d.ringHome(); pass()
        assertEquals(CallState.Ended(EndReason.COULD_NOT_JOIN), d.state.value)
        assertEquals(1, asked("cancel c_2"))
    }

    @Test fun `a room that will not close does not take the app down with it`() = runTest {
        failClose = true
        val c = inCall()
        c.hangUp(); pass()
        assertEquals(CallState.Ended(EndReason.HUNG_UP), c.state.value)
        assertEquals(1, sessions.single().closed)
        pass(4_000)
        assertEquals(CallState.Idle, c.state.value)
        c.onPush(call("c_8")); pass()                    // and the next call rings as if nothing had happened
        assertEquals(CallState.Incoming("c_8"), c.state.value)
    }

    @Test fun `hanging up while Svar is still on its way never enters the room`() = runTest {
        api.answerResult = AnswerResult.Joined(ticket)
        api.hold = CompletableDeferred()
        val c = ringing()
        c.answer("c_1"); pass()
        c.hangUp(); pass()
        assertEquals(CallState.Ended(EndReason.HUNG_UP), c.state.value)
        assertEquals(1, asked("end c_1"))
        api.hold!!.complete(Unit); pass()               // the server's «you are in» arrives now
        assertTrue(sessions.isEmpty())
        assertEquals(CallState.Ended(EndReason.HUNG_UP), c.state.value)
        assertEquals(1, asked("end c_1"))
    }

    @Test fun `Ikke nå tells the server and stops`() = runTest {
        val c = ringing()
        c.decline("c_1"); pass()
        assertEquals(1, asked("decline c_1"))
        assertEquals(listOf("ring c_1 60000", "stop c_1"), notifier.log)
        assertEquals(CallState.Idle, c.state.value)
        pass(60_000)
        assertEquals(2, notifier.log.size)              // declined is not missed
        assertTrue(remembered.isEmpty())
    }

    @Test fun `Ikke nå from the notification works after a restart`() = runTest {
        val c = make()
        c.decline("c_1"); pass()
        assertEquals(listOf("decline c_1"), api.calls)
        assertEquals(listOf("stop c_1"), notifier.log)
        assertEquals(CallState.Idle, c.state.value)
    }

    // The push service and the notification's «decline» button run in a process that Android may put to sleep the
    // moment they return. They wait for these two jobs first.
    @Test fun `the one who hands over a push can wait until the phone rings`() = runTest {
        val c = make()
        val handled = c.onPush(call()); pass()
        assertTrue(handled.isCompleted)                 // although the call itself rings on for a minute
        assertEquals(listOf("ring c_1 60000"), notifier.log)
        assertEquals(CallState.Incoming("c_1"), c.state.value)
    }

    @Test fun `the one who declines can wait until the server has been told`() = runTest {
        val c = ringing()
        api.hold = CompletableDeferred()
        val told = c.decline("c_1"); pass()
        assertEquals(CallState.Idle, c.state.value)     // the ringing stops at once
        assertFalse(told.isCompleted)                   // but the server has not answered yet
        api.hold!!.complete(Unit); pass()
        assertTrue(told.isCompleted)
        assertEquals(1, asked("decline c_1"))
    }

    @Test fun `the last word goes away when it is dismissed`() = runTest {
        val c = ringing()
        c.answer("c_1"); pass()
        assertEquals(CallState.Ended(EndReason.CALL_OVER), c.state.value)
        c.dismissEnded(); pass()
        assertEquals(CallState.Idle, c.state.value)
    }

    // --- in a call ---

    @Test fun `hanging up ends it for both`() = runTest {
        val c = inCall()
        pass(90_000)                                    // they talk for a minute and a half
        c.hangUp(); pass()
        assertEquals(1, asked("end c_1"))
        assertEquals(CallState.Ended(EndReason.HUNG_UP), c.state.value)
        assertEquals(1, sessions.single().closed)
        assertNull(c.session.value)
        assertEquals(listOf(LastCall(T0, CallKind.FROM_HOME_ANSWERED)), remembered)     // when they got in, not when it ended
    }

    @Test fun `the room closing ends the call`() = runTest {
        val c = inCall()
        sessions.single().say(SessionEvent.CLOSED); pass()
        assertEquals(1, asked("end c_1"))
        assertEquals(CallState.Ended(EndReason.OTHER_SIDE_LEFT), c.state.value)
        assertEquals(1, sessions.single().closed)
    }

    @Test fun `the other side gone for ten seconds ends the call`() = runTest {
        val c = inCall()
        sessions.single().say(SessionEvent.OTHER_LEFT); pass(9_999)
        assertEquals(CallState.InCall("c_1"), c.state.value)
        pass(1)
        assertEquals(CallState.Ended(EndReason.OTHER_SIDE_LEFT), c.state.value)
        assertEquals(1, asked("end c_1"))
        assertEquals(1, sessions.single().closed)
    }

    @Test fun `the other side coming back keeps the call`() = runTest {
        val c = inCall()
        sessions.single().say(SessionEvent.OTHER_LEFT); pass(5_000)
        sessions.single().say(SessionEvent.OTHER_JOINED); pass(10_000)
        assertEquals(CallState.InCall("c_1"), c.state.value)
        pass(600_000)
        assertEquals(CallState.InCall("c_1"), c.state.value)
        assertEquals(0, asked("end c_1"))
    }

    @Test fun `a call nobody else ever joins ends after thirty seconds`() = runTest {
        othersWaiting = false                           // the screen at home vanished between «Svar» and getting in
        val c = inCall()
        pass(29_999)
        assertEquals(CallState.InCall("c_1"), c.state.value)
        pass(1)
        assertEquals(CallState.Ended(EndReason.OTHER_SIDE_LEFT), c.state.value)
        assertEquals(1, asked("end c_1"))
    }

    @Test fun `a call always lets go of its session exactly once`() = runTest {
        val c = inCall()
        c.hangUp(); pass()
        sessions.single().say(SessionEvent.CLOSED); pass()
        c.hangUp(); pass()
        assertEquals(1, sessions.single().closed)
        assertEquals(1, asked("end c_1"))
        assertEquals(1, remembered.size)
    }

    @Test fun `a call cannot start while another is on`() = runTest {
        val c = inCall()
        c.ringHome(); c.onPush(call("c_3")); c.answer("c_3"); pass()
        assertEquals(0, asked("ringHome"))
        assertEquals(0, asked("answer c_3"))
        assertEquals(CallState.InCall("c_1"), c.state.value)
        assertFalse(notifier.log.any { it.startsWith("ring c_3") })
        assertEquals(1, sessions.size)
    }

    // --- «Ring hjem» ---

    @Test fun `ring home - they answer`() = runTest {
        api.statuses += listOf(StatusResult.Known("ringing"), StatusResult.Known("ringing"), StatusResult.Known("connected"))
        val c = ringingHome()
        assertEquals(ticket, sessions.single().connected)       // the parent waits in the room while it rings
        assertSame(sessions.single(), c.session.value)
        pass(2_000)
        assertEquals(CallState.RingingHome("c_2"), c.state.value)
        pass(1_000)
        assertEquals(CallState.InCall("c_2"), c.state.value)
        pass(120_000)                                           // no more questions, and no 65-second limit, once they talk
        assertEquals(3, asked("status c_2"))
        assertEquals(CallState.InCall("c_2"), c.state.value)
        c.hangUp(); pass()
        assertEquals(1, asked("end c_2"))
        assertEquals(listOf(LastCall(T0, CallKind.TO_HOME_ANSWERED)), remembered)
    }

    @Test fun `ring home - each way it ends without an answer`() = runTest {
        val unanswered: CallKind? = CallKind.TO_HOME_UNANSWERED
        for ((said, why, kind) in listOf(                       // what the server says while it rings
            Triple(StatusResult.Known("declined"), EndReason.NOBODY_HOME, unanswered),
            Triple(StatusResult.Known("missed"), EndReason.NOBODY_HOME, unanswered),
            Triple(StatusResult.Known("unreachable"), EndReason.KIOSK_OFFLINE, unanswered),
            Triple(StatusResult.Known("cancelled"), EndReason.HUNG_UP, null),
            Triple(StatusResult.Known("ended"), EndReason.HUNG_UP, null),
            Triple(StatusResult.Gone, EndReason.HUNG_UP, null)
        )) {
            fresh(); api.statuses += said
            val c = ringingHome(); pass(1_000)
            assertEquals("$said", CallState.Ended(why), c.state.value)
            assertEquals("$said", listOfNotNull(kind), remembered.map { it.kind })
            assertEquals("$said", 1, sessions.single().closed)
            assertEquals("$said", 0, asked("cancel c_2"))        // the server ended it: nothing left to cancel
        }
        for ((said, why, kind) in listOf(                       // what the server says to «Ring hjem» itself
            Triple(RingHomeResult.Busy, EndReason.BUSY_HOME, unanswered),
            Triple(RingHomeResult.KioskOffline, EndReason.KIOSK_OFFLINE, unanswered),
            Triple(RingHomeResult.TooMany, EndReason.TOO_MANY, null),
            Triple(RingHomeResult.Failed(Failure.UNREACHABLE), EndReason.NO_SERVER, null),
            Triple(RingHomeResult.Unpaired, EndReason.UNPAIRED, null)
        )) {
            fresh(); api.ringHomeResult = said
            val c = make(); c.ringHome(); pass()
            assertEquals("$said", CallState.Ended(why), c.state.value)
            assertEquals("$said", listOfNotNull(kind), remembered.map { it.kind })
            assertTrue("$said", sessions.isEmpty())
            assertEquals("$said", if (said == RingHomeResult.Unpaired) 1 else 0, unpaired)
        }
    }

    @Test fun `ring home gives up after 65 seconds`() = runTest {
        val c = ringingHome()                                   // the fake server says «ringing» for ever
        pass(64_999)
        assertEquals(CallState.RingingHome("c_2"), c.state.value)
        pass(1)
        assertEquals(1, asked("cancel c_2"))
        assertEquals(CallState.Ended(EndReason.NOBODY_HOME), c.state.value)
        assertEquals(1, sessions.single().closed)
        assertEquals(listOf(CallKind.TO_HOME_UNANSWERED), remembered.map { it.kind })
    }

    @Test fun `ring home survives four failed questions but not five`() = runTest {
        val failed = StatusResult.Failed(Failure.UNREACHABLE)
        api.statuses += List(4) { failed } + StatusResult.Known("ringing") + List(4) { failed } + StatusResult.Known("connected")
        val c = ringingHome(); pass(10_000)
        assertEquals(CallState.InCall("c_2"), c.state.value)    // five in all were not five in a row
        assertEquals(0, asked("cancel c_2"))

        fresh(); api.statuses += failed
        val d = ringingHome(); pass(4_000)
        assertEquals(CallState.RingingHome("c_2"), d.state.value)
        pass(1_000)
        assertEquals(1, asked("cancel c_2"))
        assertEquals(CallState.Ended(EndReason.NO_SERVER), d.state.value)
    }

    @Test fun `ring home with a room that cannot be joined cancels the call`() = runTest {
        failConnect = true
        api.ringHomeResult = RingHomeResult.Ringing("c_2", ticket)
        val c = make(); c.ringHome(); pass()
        assertEquals(1, asked("cancel c_2"))
        assertEquals(CallState.Ended(EndReason.COULD_NOT_JOIN), c.state.value)
        assertEquals(1, sessions.single().closed)
        pass(70_000)
        assertEquals(0, asked("status c_2"))
    }

    @Test fun `the room closing while it rings home is left to the server to explain`() = runTest {
        val c = ringingHome()
        sessions.single().say(SessionEvent.CLOSED); pass()
        assertEquals(CallState.RingingHome("c_2"), c.state.value)
        api.statuses += StatusResult.Known("declined"); pass(1_000)
        assertEquals(CallState.Ended(EndReason.NOBODY_HOME), c.state.value)
    }

    @Test fun `Avbryt while it rings home cancels the call`() = runTest {
        val c = ringingHome()
        c.hangUp(); pass()
        assertEquals(1, asked("cancel c_2"))
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(1, sessions.single().closed)
        pass(70_000)                                            // nothing keeps asking, nothing gives up later
        assertEquals(0, asked("status c_2"))
        assertEquals(1, asked("cancel c_2"))
        assertTrue(remembered.isEmpty())
    }

    @Test fun `Avbryt before the server has answered cancels as soon as it does`() = runTest {
        api.ringHomeResult = RingHomeResult.Ringing("c_2", ticket)
        api.hold = CompletableDeferred()
        val c = make(); c.ringHome(); pass()
        assertEquals(CallState.RingingHome(null), c.state.value)
        c.hangUp(); pass()
        assertEquals(CallState.Idle, c.state.value)
        assertEquals(listOf("ringHome"), api.calls)
        api.hold!!.complete(Unit); pass()
        assertEquals(listOf("ringHome", "cancel c_2"), api.calls)
        assertTrue(sessions.isEmpty())
        assertEquals(CallState.Idle, c.state.value)
    }
}
