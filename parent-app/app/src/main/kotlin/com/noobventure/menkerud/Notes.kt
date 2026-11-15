package com.noobventure.menkerud

import android.util.Log

/**
 * What the app writes to the phone's log – all under one tag (`adb logcat -s MenkerudHome`), so that «it did not
 * ring» can be told apart into: no push came, no notification went up, or it went up without sound.
 * Never a secret, a ticket or a push address; a call is named by the last four characters of its id only, because
 * knowing the whole id is the permission to end that call.
 */
object Notes {
    private const val TAG = "MenkerudHome"

    fun say(text: String) {
        Log.i(TAG, text)
    }

    fun warn(text: String) {
        Log.w(TAG, text)
    }

    fun short(callId: String?): String = if (callId == null) "–" else "…" + callId.takeLast(4)
}
