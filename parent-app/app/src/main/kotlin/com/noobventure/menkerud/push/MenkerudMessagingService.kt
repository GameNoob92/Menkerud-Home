package com.noobventure.menkerud.push

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noobventure.menkerud.Notes
import com.noobventure.menkerud.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where pushes arrive. A push is read and handed to the controller; nothing is decided here.
 *
 * There is no onNewToken on purpose: the app registers through Firebase's installation id (FirebasePushAddress),
 * and the push service announces a new registration through [onRegistered].
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class MenkerudMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val push = readPush(message.data)
        Notes.say("push: " + describe(push))
        val handled = container.scope.launch {
            container.store.retry()                     // the keystore may not have answered when the app started
            container.calls.onPush(push).join()
        }
        // This runs on a worker thread that Firebase keeps the phone awake for. Stay in it until the notification is
        // up (or gone): once this returns the phone may go back to sleep before the main thread has had its turn.
        runBlocking { withTimeoutOrNull(WAIT_MS) { handled.join() } }
    }

    /** The push service has (re)registered this installation: tell the server where pushes go – changed or not. */
    override fun onRegistered(installationId: String) {
        container.scope.launch { container.pushSync.run(installationId) }
    }

    private fun describe(push: PushMessage): String = when (push) {
        is PushMessage.Call -> "a call " + Notes.short(push.callId)
        is PushMessage.CallEnd -> "the end of " + Notes.short(push.callId) + " (" + push.reason + ")"
        PushMessage.Unknown -> "of a kind this version does not know"
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
