package com.noobventure.menkerud.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noobventure.menkerud.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The notification buttons that need no screen: «decline» on the ringing notification, «Legg på» on the one that
 * shows while a call is on.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        when (intent.action) {
            ACTION_DECLINE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
                // Android may put the process to sleep the moment this returns, and then the screen at home would
                // ring on for a minute. Hold the broadcast open until the server has been told – a little while at most.
                val broadcast = goAsync()
                val told = container.calls.decline(callId)
                container.scope.launch {
                    withTimeoutOrNull(HOLD_MS) { told.join() }
                    broadcast.finish()
                }
            }
            ACTION_HANG_UP -> container.calls.hangUp()      // a hang-up that gets lost is caught by the server: the room empties
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.noobventure.menkerud.action.DECLINE"
        const val ACTION_HANG_UP = "com.noobventure.menkerud.action.HANG_UP"
        const val EXTRA_CALL_ID = "callId"
        private const val HOLD_MS = 8_000L                  // under Android's ten seconds for a receiver
    }
}
