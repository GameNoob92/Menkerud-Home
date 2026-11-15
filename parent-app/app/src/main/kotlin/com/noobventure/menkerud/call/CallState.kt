package com.noobventure.menkerud.call

/** Where the one call there can be stands. The screens and the notifications only show this. */
sealed interface CallState {
    data object Idle : CallState
    /** Ringing on this phone. */
    data class Incoming(val callId: String) : CallState
    /** «Svar» was pressed; getting in. */
    data class Answering(val callId: String) : CallState
    /** «Ring hjem» was pressed. `callId` is null until the server has answered. */
    data class RingingHome(val callId: String?) : CallState
    data class InCall(val callId: String) : CallState
    /** The last word about a call: shown for a moment, then [Idle]. */
    data class Ended(val why: EndReason) : CallState
}

enum class EndReason { HUNG_UP, OTHER_SIDE_LEFT, CALL_OVER, NOBODY_HOME, BUSY_HOME, KIOSK_OFFLINE, TOO_MANY, NO_SERVER, COULD_NOT_JOIN, UNPAIRED }

enum class CallKind { FROM_HOME_ANSWERED, FROM_HOME_MISSED, TO_HOME_ANSWERED, TO_HOME_UNANSWERED }

/** What the home screen shows as «Siste anrop». `at`: ms since 1970. */
data class LastCall(val at: Long, val kind: CallKind)
