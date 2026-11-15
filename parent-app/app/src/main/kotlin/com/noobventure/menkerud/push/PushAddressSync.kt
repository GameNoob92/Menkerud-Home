package com.noobventure.menkerud.push

import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.Sent
import com.noobventure.menkerud.store.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the server where pushes for this phone go. It runs at every start of the app, right after a pairing, and
 * whenever the push service hands over an address – changed or not: a phone whose address the push service once
 * reported dead only counts as reachable again when it has sent one (contract C3).
 */
class PushAddressSync(
    private val api: CallApi,
    private val store: CredentialStore,
    private val address: suspend () -> String?
) {
    private val _registered = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the server knows where this phone's pushes go – without that the phone cannot be rung, whatever its own
     * settings say. Null until it has been tried in this run of the app.
     */
    val registered: StateFlow<Boolean?> = _registered.asStateFlow()

    /** `known`: an address just handed over by the push service. Returns null when there was nothing to do. */
    suspend fun run(known: String? = null): Sent? {
        if (store.current.value == null) {
            _registered.value = null
            return null
        }
        val to = known ?: address()
        if (to == null) {                               // the push service gave no address
            _registered.value = false
            return null
        }
        val sent = api.sendPushAddress(to)
        _registered.value = sent == Sent.OK
        if (sent == Sent.UNPAIRED) store.clear()        // the phone was removed on the pairing page
        return sent
    }
}
