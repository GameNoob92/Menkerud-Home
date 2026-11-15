package com.noobventure.menkerud.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.noobventure.menkerud.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Where Google's push service reaches this installation of the app. Registration goes through Firebase's
 * installation id (the token API was deprecated in June 2026); the id is what the server sends pushes to.
 * If pushes ever fail to arrive for a phone registered this way, this is the one class to change back to
 * `FirebaseMessaging.getInstance().token` (and the messaging service to `onNewToken`).
 */
class FirebasePushAddress(private val context: Context) {

    /** The address, or null: this build has no push set up, or the address could not be had right now. */
    suspend fun get(): String? {
        if (!BuildConfig.PUSH_CONFIGURED || FirebaseApp.getApps(context).isEmpty()) return null
        return try {
            FirebaseMessaging.getInstance().register().await()
            FirebaseInstallations.getInstance().id.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no push address: " + e.javaClass.simpleName)
            null
        }
    }

    private companion object {
        const val TAG = "MenkerudHome"
    }
}
