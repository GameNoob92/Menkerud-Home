package com.noobventure.menkerud.pairing

import com.noobventure.menkerud.store.CredentialStore

/**
 * What follows when a phone stops being paired. The app has no «unpair» of its own, so a pairing only ever goes away
 * in one of two ways:
 *
 * - the server no longer knows the phone (it was removed on the pairing page): the server has already stopped
 *   sending it calls, and the pairing screen only has to say why it is asking for a key again;
 * - the phone can no longer read its own secret: the server knows nothing of that. It still lists the phone, the
 *   push address still works, and calls would go to an app that cannot answer them – the parent would get neither a
 *   ring nor the Discord link. So the phone leaves the push service; the server's next push bounces, and the server
 *   stops counting the phone as paired.
 */
class PairingLoss(
    private val store: CredentialStore,
    private val showNotice: suspend (Boolean) -> Unit,
    private val leavePushService: suspend () -> Unit
) {
    /** Runs for as long as the app does. */
    suspend fun watch() {
        if (store.unreadable) {
            showNotice(true)
            leavePushService()
        }
        var paired = store.current.value != null
        store.current.collect { now ->
            if (paired && now == null) showNotice(true)
            if (now != null) showNotice(false)
            paired = now != null
        }
    }
}
