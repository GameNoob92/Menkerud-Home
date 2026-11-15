package com.noobventure.menkerud.call

/** The part of a call that Android shows outside the app. */
interface RingNotifier {
    /** Show the incoming call and ring, for at most `forMs`. */
    fun ring(callId: String, forMs: Long)

    /** Take the ringing away. */
    fun stop(callId: String)

    /** Leave «Tapt anrop hjemmefra kl. …». `at`: ms since 1970. */
    fun missed(callId: String, at: Long)
}
