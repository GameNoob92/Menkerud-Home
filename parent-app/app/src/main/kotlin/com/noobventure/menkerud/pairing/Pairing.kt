package com.noobventure.menkerud.pairing

import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.PairResult
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.ui.Texts

/** What happens when a parent presses «Koble til», or has scanned the QR code. */
class Pairing(
    private val api: CallApi,
    private val store: CredentialStore,
    private val pushSync: PushAddressSync,
    private val policy: PairPolicy,
    private val phoneName: String
) {
    /** What to tell the parent – or null: the phone is paired now, and the server knows where its pushes go. */
    suspend fun submit(text: String): String? {
        val link = when (val input = readPairInput(text, policy)) {
            PairInput.NotAKey -> return Texts.pairNotKey
            is PairInput.ForeignServer -> return Texts.pairForeign
            is PairInput.Ok -> input.link
        }
        val credentials = when (val said = api.pair(link, phoneName)) {
            PairResult.WrongKey -> return Texts.pairWrong
            PairResult.Expired -> return Texts.pairExpired
            is PairResult.Failed -> return Texts.noServer
            is PairResult.Paired -> said.credentials
        }
        try {
            store.save(credentials)
        } catch (e: Exception) {
            // The key is used up and the server now lists a phone that does not know its own secret: the parent
            // needs a new key, and the operator removes the dead entry on the pairing page.
            return Texts.pairNotSaved
        }
        pushSync.run()
        return null
    }
}
