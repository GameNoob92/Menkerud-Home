package com.noobventure.menkerud.pairing

import com.noobventure.menkerud.FakeBox
import com.noobventure.menkerud.FakeCallApi
import com.noobventure.menkerud.api.Failure
import com.noobventure.menkerud.api.PairResult
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.Credentials
import com.noobventure.menkerud.store.SecretBox
import com.noobventure.menkerud.ui.Texts
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val api = FakeCallApi()
    private var box: SecretBox = FakeBox()
    private val store by lazy { CredentialStore(File(tmp.root, "device.bin"), box) }
    private val policy = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = false)
    private val pairing by lazy { Pairing(api, store, PushAddressSync(api, store) { "fid-1" }, policy, "Galaxy S26") }
    private val key = "0123456789ABCDEFGHJK"
    private val far = Credentials("https://call.noobventure.com", "d_1", "s3", "far", "Far")

    @Test fun `a good key pairs the phone and tells the server where pushes go`() = runTest {
        api.pairResult = PairResult.Paired(far)
        assertNull(pairing.submit("  $key "))                   // nothing to say: the phone is paired
        assertEquals(far, store.current.value)
        assertEquals(listOf("pair $key", "sendPushAddress fid-1"), api.calls)
    }

    @Test fun `what is no key, or a key for another server, never reaches the server`() = runTest {
        assertEquals(Texts.pairNotKey, pairing.submit("hei"))
        assertEquals(Texts.pairForeign, pairing.submit("menkerudhome://pair?api=https%3A%2F%2Fevil.example&code=$key"))
        assertTrue(api.calls.isEmpty())
        assertNull(store.current.value)
    }

    @Test fun `the server's no is said in plain words, and the phone stays unpaired`() = runTest {
        api.pairResult = PairResult.WrongKey
        assertEquals(Texts.pairWrong, pairing.submit(key))
        api.pairResult = PairResult.Expired
        assertEquals(Texts.pairExpired, pairing.submit(key))
        api.pairResult = PairResult.Failed(Failure.UNREACHABLE)
        assertEquals(Texts.noServer, pairing.submit(key))
        assertNull(store.current.value)
        assertEquals(0, api.calls.count { it.startsWith("sendPushAddress") })
    }

    @Test fun `a phone that cannot keep its secret says so instead of looking paired`() = runTest {
        box = object : SecretBox {
            override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("the keystore will not answer")
            override fun open(sealed: ByteArray): ByteArray? = null
        }
        api.pairResult = PairResult.Paired(far)
        assertEquals(Texts.pairNotSaved, pairing.submit(key))
        assertNull(store.current.value)
    }
}
