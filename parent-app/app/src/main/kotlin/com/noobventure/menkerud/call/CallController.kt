package com.noobventure.menkerud.call

import com.noobventure.menkerud.api.AnswerResult
import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.LiveKitTicket
import com.noobventure.menkerud.api.RingHomeResult
import com.noobventure.menkerud.api.StatusResult
import com.noobventure.menkerud.push.PushMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val RING_MIN_MS = 5_000L
private const val RING_MAX_MS = 60_000L
private const val RING_FALLBACK_MS = 45_000L
private const val ASK_EVERY_MS = 1_000L
private const val RING_HOME_MAX_MS = 65_000L
private const val FAILED_QUESTIONS_MAX = 5
private const val LAST_WORD_MS = 4_000L
private const val OTHER_GONE_MS = 10_000L
private const val NOBODY_CAME_MS = 30_000L
private const val NOTICES_KEPT = 20

/**
 * How long to ring for a call the server stops at `expiresAt`. The phone's clock may be wrong, so the answer is only
 * believed when it is plausible. The server rings for a minute: a clock that says more than that is a little behind,
 * and the phone rings for the minute. A clock that says the call is all but over cannot be right – the push would
 * not have been sent – and the phone rings for 45 seconds. Ringing too long is bounded from outside (the server's
 * `call_end`, the question at the start, the notification's own time-out); ringing too short is not.
 */
fun ringFor(expiresAt: Long, now: Long): Long {
    val left = expiresAt - now
    return when {
        left < RING_MIN_MS -> RING_FALLBACK_MS
        left > RING_MAX_MS -> RING_MAX_MS
        else -> left
    }
}

/**
 * The life of the one call there can be. Pushes, taps and what the room says all come in here; the notification, the
 * screens and the services only show [state] and report back.
 *
 * Everything runs on [scope], which is confined to one thread (the main thread in the app), so the public functions
 * may be called from any thread. Every way out of a call goes through [leave].
 */
class CallController(
    private val api: CallApi,
    private val sessions: SessionFactory,
    private val notifier: RingNotifier,
    private val unpair: () -> Unit,                 // forget the stored secret: the server no longer knows this phone
    private val remember: (LastCall) -> Unit,       // once per call, when it is over
    private val now: () -> Long,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _session = MutableStateFlow<CallSession?>(null)
    /** The live session, for the call screen to draw from. */
    val session: StateFlow<CallSession?> = _session.asStateFlow()

    /** The call that is on – ringing here, being answered, ringing home or in progress – and what belongs to it. */
    private class Live(val fromHome: Boolean, val startedAt: Long) {
        var job: Job? = null                // its coroutine: clocks, questions to the server, listening to the room
        var session: CallSession? = null
        var otherThere = false              // the room has said that the other side is in it
        var alone: Job? = null              // the clock that ends a call in which this phone is alone
        var talkedAt: Long? = null          // when it got as far as InCall
    }

    private var live: Live? = null
    private var lastWord: Job? = null                   // turns Ended into Idle
    private val noticed = ArrayDeque<String>()          // the calls that have left their «missed» notice

    /**
     * The returned job is done when the push has had its effect on the phone – the notification is up, or gone.
     * The push service waits for it: Android may put the process to sleep the moment that service returns.
     */
    fun onPush(message: PushMessage): Job = scope.launch {
        when (message) {
            is PushMessage.Call -> incoming(message)
            is PushMessage.CallEnd -> over(message)
            PushMessage.Unknown -> Unit
        }
    }

    fun answer(callId: String) { scope.launch { doAnswer(callId) } }

    /** The returned job is done when the server has been told (its answer does not matter). */
    fun decline(callId: String): Job = scope.launch { if (doDecline(callId)) api.decline(callId) }

    fun ringHome() { scope.launch { doRingHome() } }

    /** «Legg på», and «Avbryt» while it rings home. */
    fun hangUp() { scope.launch { doHangUp() } }

    fun dismissEnded() {
        scope.launch {
            if (_state.value is CallState.Ended) { lastWord?.cancel(); lastWord = null; _state.value = CallState.Idle }
        }
    }

    // --- a call comes in ---

    private fun incoming(push: PushMessage.Call) {
        when (val s = _state.value) {
            CallState.Idle, is CallState.Ended -> Unit
            is CallState.Incoming ->
                if (s.callId == push.callId) return     // the same push twice
                else quiet(s.callId)                    // the server has one call at a time: the older one is over
            else -> return
        }
        val forMs = ringFor(push.expiresAt, now())
        val call = begin(Live(fromHome = true, startedAt = now()), CallState.Incoming(push.callId))
        notifier.ring(push.callId, forMs)
        call.run {
            launch {                                    // the push may have come after the call was over
                val said = api.status(push.callId)
                val isOver = said is StatusResult.Gone || (said is StatusResult.Known && said.status != "ringing")
                if (isOver && live === call) { notifier.stop(push.callId); leave(call, CallState.Idle) }
            }
            delay(forMs)
            if (live === call) { notifier.stop(push.callId); leave(call, CallState.Idle); notice(push.callId, now()) }
        }
    }

    private fun over(push: PushMessage.CallEnd) {
        val s = _state.value
        val call = live
        if (call != null && s is CallState.Incoming && s.callId == push.callId) {
            notifier.stop(push.callId)
            leave(call, CallState.Idle)
        } else if (s == CallState.Idle || s is CallState.Ended || s is CallState.Incoming) {
            // Nothing rings here for that call – but Android may have ended the app while its notification rang on,
            // and then this is a new process that knows nothing of it. Taking away what is not there costs nothing.
            notifier.stop(push.callId)
        }
        // Also for a call this phone never rang for: it was off, or without a network.
        if (push.reason == "cancelled" || push.reason == "missed") notice(push.callId, push.at ?: now())
    }

    /** «Tapt anrop …», at most once per call. */
    private fun notice(callId: String, at: Long) {
        if (callId in noticed) return
        noticed.addLast(callId)
        if (noticed.size > NOTICES_KEPT) noticed.removeFirst()
        notifier.missed(callId, at)
        remember(LastCall(at, CallKind.FROM_HOME_MISSED))
    }

    // --- «Svar» and «Ikke nå» ---

    private fun doAnswer(callId: String) {
        val s = _state.value
        val ringing = (s as? CallState.Incoming)?.callId
        if (s != CallState.Idle && s !is CallState.Ended && ringing == null) return
        if (ringing != null) quiet(ringing)
        if (ringing != callId) notifier.stop(callId)    // after a restart the notification is there but nothing rings here
        val call = begin(Live(fromHome = true, startedAt = now()), CallState.Answering(callId))
        call.run {
            val said = api.answer(callId)
            if (live !== call) return@run               // hung up meanwhile: the room is never entered
            when (said) {
                is AnswerResult.Joined -> {
                    val session = open(call)
                    if (session == null) {
                        tell { api.end(callId) }
                        leave(call, CallState.Ended(EndReason.COULD_NOT_JOIN))
                        return@run
                    }
                    try {
                        session.connect(said.ticket)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (live === call) { tell { api.end(callId) }; leave(call, CallState.Ended(EndReason.COULD_NOT_JOIN)) }
                        return@run
                    }
                    if (live === call) talk(call, callId)
                }
                AnswerResult.Over -> leave(call, CallState.Ended(EndReason.CALL_OVER))
                AnswerResult.Unpaired -> { unpair(); leave(call, CallState.Ended(EndReason.UNPAIRED)) }
                is AnswerResult.Failed -> leave(call, CallState.Ended(EndReason.NO_SERVER))
            }
        }
    }

    /** Stops the ringing. False when there is nothing to decline: a call is being answered, or is on. */
    private fun doDecline(callId: String): Boolean {
        when (val s = _state.value) {
            CallState.Idle -> notifier.stop(callId)
            is CallState.Ended -> { notifier.stop(callId); lastWord?.cancel(); lastWord = null; _state.value = CallState.Idle }
            is CallState.Incoming -> {
                notifier.stop(callId)
                val call = live
                if (s.callId == callId && call != null) leave(call, CallState.Idle)
            }
            else -> return false
        }
        return true
    }

    // --- «Ring hjem» ---

    private fun doRingHome() {
        val s = _state.value
        if (s != CallState.Idle && s !is CallState.Ended) return
        val call = begin(Live(fromHome = false, startedAt = now()), CallState.RingingHome(null))
        call.run {
            val said = api.ringHome()
            if (live !== call) {                        // «Avbryt» came before the server's answer
                if (said is RingHomeResult.Ringing) api.cancel(said.callId)
                if (said is RingHomeResult.Unpaired) unpair()
                return@run
            }
            when (said) {
                is RingHomeResult.Ringing -> ringUntilAnswered(call, said.callId, said.ticket)
                RingHomeResult.Busy -> leave(call, CallState.Ended(EndReason.BUSY_HOME))
                RingHomeResult.KioskOffline -> leave(call, CallState.Ended(EndReason.KIOSK_OFFLINE))
                RingHomeResult.TooMany -> leave(call, CallState.Ended(EndReason.TOO_MANY))
                RingHomeResult.Unpaired -> { unpair(); leave(call, CallState.Ended(EndReason.UNPAIRED)) }
                is RingHomeResult.Failed -> leave(call, CallState.Ended(EndReason.NO_SERVER))
            }
        }
    }

    /** The parent waits in the room; the server is asked once a second how the call stands (contract C6). */
    private suspend fun CoroutineScope.ringUntilAnswered(call: Live, callId: String, ticket: LiveKitTicket) {
        _state.value = CallState.RingingHome(callId)
        val session = open(call)
        if (session == null) {
            tell { api.cancel(callId) }
            leave(call, CallState.Ended(EndReason.COULD_NOT_JOIN))
            return
        }
        launch {
            try {
                session.connect(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (live === call) {
                    val talking = _state.value is CallState.InCall
                    tell { if (talking) api.end(callId) else api.cancel(callId) }
                    leave(call, CallState.Ended(EndReason.COULD_NOT_JOIN))
                }
            }
        }
        launch {
            delay(RING_HOME_MAX_MS)
            if (live === call && _state.value is CallState.RingingHome) {
                tell { api.cancel(callId) }
                leave(call, CallState.Ended(EndReason.NOBODY_HOME))
            }
        }
        var failed = 0
        while (true) {
            delay(ASK_EVERY_MS)
            val said = api.status(callId)
            if (live !== call) return
            when (said) {
                is StatusResult.Known -> when (said.status) {
                    "connected" -> { talk(call, callId); return }
                    "declined", "missed" -> { leave(call, CallState.Ended(EndReason.NOBODY_HOME)); return }
                    "unreachable" -> { leave(call, CallState.Ended(EndReason.KIOSK_OFFLINE)); return }
                    "cancelled", "ended" -> { leave(call, CallState.Ended(EndReason.HUNG_UP)); return }
                    else -> failed = 0                  // «ringing», or a word a newer server has learned: keep asking
                }
                StatusResult.Gone -> { leave(call, CallState.Ended(EndReason.HUNG_UP)); return }
                is StatusResult.Failed -> if (++failed >= FAILED_QUESTIONS_MAX) {
                    tell { api.cancel(callId) }
                    leave(call, CallState.Ended(EndReason.NO_SERVER))
                    return
                }
            }
        }
    }

    private fun doHangUp() {
        val call = live ?: return
        when (val s = _state.value) {
            is CallState.InCall -> { tell { api.end(s.callId) }; leave(call, CallState.Ended(EndReason.HUNG_UP)) }
            is CallState.Answering -> { tell { api.end(s.callId) }; leave(call, CallState.Ended(EndReason.HUNG_UP)) }
            is CallState.RingingHome ->
                if (s.callId != null) { tell { api.cancel(s.callId) }; leave(call, CallState.Idle) }
                // The server has not named the call yet. Its coroutine lives on to cancel the call when it does.
                else leave(call, CallState.Idle, letItFinish = true)
            else -> Unit
        }
    }

    // --- in a call ---

    private fun talk(call: Live, callId: String) {
        call.talkedAt = now()
        _state.value = CallState.InCall(callId)
        // A call the other side never shows up in: the screen at home vanished between «Svar» and getting in.
        if (!call.otherThere) aloneFor(call, callId, NOBODY_CAME_MS)
    }

    private fun heard(call: Live, event: SessionEvent) {
        if (live !== call) return
        val s = _state.value
        when (event) {
            SessionEvent.OTHER_JOINED -> { call.otherThere = true; call.alone?.cancel(); call.alone = null }
            SessionEvent.OTHER_LEFT -> { call.otherThere = false; if (s is CallState.InCall) aloneFor(call, s.callId, OTHER_GONE_MS) }
            // While it rings home a closed room is left to the server's answers to explain (declined, missed, …).
            SessionEvent.CLOSED -> if (s is CallState.InCall) otherSideLeft(call, s.callId)
        }
    }

    private fun aloneFor(call: Live, callId: String, ms: Long) {
        call.alone?.cancel()
        call.alone = scope.launch { delay(ms); if (live === call) otherSideLeft(call, callId) }
    }

    private fun otherSideLeft(call: Live, callId: String) {
        tell { api.end(callId) }
        leave(call, CallState.Ended(EndReason.OTHER_SIDE_LEFT))
    }

    // --- beginning and leaving ---

    private fun begin(call: Live, state: CallState): Live {
        lastWord?.cancel()
        lastWord = null
        live = call
        _state.value = state
        return call
    }

    /** Starts the call's coroutine. The job is known before any of it runs, so that the coroutine can end itself. */
    private fun Live.run(block: suspend CoroutineScope.() -> Unit) {
        val started = scope.launch(start = CoroutineStart.LAZY, block = block)
        job = started
        started.start()
    }

    /**
     * Makes the call's session and listens to its room for as long as the call's coroutine lives.
     * Null when a room cannot even be made: that is a call that could not be joined, not a reason to crash.
     */
    private fun CoroutineScope.open(call: Live): CallSession? {
        val session = try {
            sessions.create()
        } catch (e: Exception) {
            return null
        }
        call.session = session
        _session.value = session
        launch { session.events.collect { heard(call, it) } }
        return session
    }

    /** Stops a call that rings here without a word: another call has taken its place. */
    private fun quiet(callId: String) {
        val call = live ?: return
        live = null
        call.job?.cancel()
        notifier.stop(callId)
    }

    /**
     * The one way out of a call: its clocks stop, its session is closed – exactly once –, it is remembered, and the
     * state moves on. A call that is no longer the live one is left alone, so being told twice does nothing.
     */
    private fun leave(call: Live, next: CallState, letItFinish: Boolean = false) {
        if (live !== call) return
        live = null
        call.alone?.cancel()
        if (!letItFinish) call.job?.cancel()
        call.session?.let { session ->
            call.session = null
            _session.value = null
            scope.launch {
                try {
                    session.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A room that will not close cleanly is over all the same; it must not take the app down with it.
                }
            }
        }
        val talkedAt = call.talkedAt
        val why = (next as? CallState.Ended)?.why
        if (talkedAt != null) {
            if (call.fromHome) remember(LastCall(talkedAt, CallKind.FROM_HOME_ANSWERED))
            else remember(LastCall(call.startedAt, CallKind.TO_HOME_ANSWERED))
        } else if (!call.fromHome && (why == EndReason.NOBODY_HOME || why == EndReason.KIOSK_OFFLINE || why == EndReason.BUSY_HOME)) {
            remember(LastCall(call.startedAt, CallKind.TO_HOME_UNANSWERED))
        }
        _state.value = next
        lastWord?.cancel()
        lastWord = if (next is CallState.Ended) scope.launch { delay(LAST_WORD_MS); _state.value = CallState.Idle } else null
    }

    /** Something the server must hear even though the call it belongs to is being left. */
    private fun tell(what: suspend () -> Unit) {
        scope.launch { what() }
    }
}
