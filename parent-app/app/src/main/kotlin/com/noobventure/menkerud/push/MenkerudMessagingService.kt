package com.noobventure.menkerud.push

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
        val handled = container.calls.onPush(readPush(message.data))
        // This runs on a worker thread that Firebase keeps the phone awake for. Stay in it until the notification is
        // up (or gone): once this returns the phone may go back to sleep before the main thread has had its turn.
        runBlocking { withTimeoutOrNull(WAIT_MS) { handled.join() } }
    }

    /** The push service has (re)registered this installation: tell the server where pushes go – changed or not. */
    override fun onRegistered(installationId: String) {
        container.scope.launch { container.pushSync.run(installationId) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
