package com.noobventure.menkerud.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.noobventure.menkerud.BuildConfig
import com.noobventure.menkerud.Notes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Where Google's push service reaches this installation of the app. Registration goes through Firebase's
 * installation id (the token API was deprecated in June 2026); the id is what the server sends pushes to.
 *
 * Should pushes ever fail to arrive for a phone registered this way, the way back to the token API takes THREE
 * changes, not one: `get()` here returns `FirebaseMessaging.getInstance().token.await()`; the messaging service
 * overrides `onNewToken` instead of `onRegistered`; and the meta-data `firebase_messaging_installation_id_enabled`
 * is taken out of the manifest – for as long as it is there, asking for a token fails («API disabled»).
 */
class FirebasePushAddress(private val context: Context) {

    private fun configured() = BuildConfig.PUSH_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    /** The address, or null: this build has no push set up, or the address could not be had right now. */
    suspend fun get(): String? {
        if (!configured()) return null
        return try {
            FirebaseMessaging.getInstance().register().await()
            FirebaseInstallations.getInstance().id.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Notes.warn("no push address: " + e.javaClass.simpleName + ": " + e.message)     // Firebase's own words; no secret in them
            null
        }
    }

    /**
     * Leaves the push service: pushes sent to this phone from now on bounce, and the server learns from that. For a
     * phone that has lost its pairing without the server knowing (see pairing/PairingLoss.kt). Pairing again
     * registers again.
     */
    suspend fun leave() {
        if (!configured()) return
        try {
            FirebaseMessaging.getInstance().unregister().await()
            Notes.say("left the push service: this phone can no longer read its pairing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Notes.warn("could not leave the push service: " + e.javaClass.simpleName + ": " + e.message)
        }
    }
}
