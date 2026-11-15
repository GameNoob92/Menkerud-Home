package com.noobventure.menkerud.store

import com.noobventure.menkerud.FakeBox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { File(tmp.root, "device.bin") }
    private val box = FakeBox()
    private val far = Credentials("https://call.noobventure.com", "d_1", "hemmelig-0123456789", "far", "Far")

    @Test fun `what is saved is there the next time the app starts`() {
        CredentialStore(file, box).save(far)
        assertEquals(far, CredentialStore(file, box).current.value)
    }

    @Test fun `saving shows at once to whoever is watching`() {
        val store = CredentialStore(file, box)
        store.save(far)
        assertEquals(far, store.current.value)
    }

    @Test fun `the file does not hold the secret in the clear`() {
        CredentialStore(file, box).save(far)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("hemmelig"))
    }

    @Test fun `a phone that was never paired has nothing`() {
        val store = CredentialStore(file, box)
        assertNull(store.current.value)
        assertFalse(store.unreadable)
    }

    @Test fun `a file that cannot be opened means not paired, and the file goes`() {
        CredentialStore(file, box).save(far)
        box.refuse = true
        val again = CredentialStore(file, box)
        assertNull(again.current.value)
        assertTrue(again.unreadable)                    // so that the app can say why it asks for a key again
        assertFalse(file.exists())
    }

    /** A keystore that does not answer the first `silences` times it is asked. */
    private class MoodyBox(var silences: Int) : SecretBox {
        private val real = FakeBox()
        override fun seal(plain: ByteArray) = real.seal(plain)
        override fun open(sealed: ByteArray): ByteArray? {
            if (silences > 0) { silences--; throw SecretBoxUnavailable() }
            return real.open(sealed)
        }
    }

    // The Android Keystore now and then fails without the key being gone. Reading the pairing happens at every start
    // of the app – at almost every push – so one such hiccup must not unpair the phone.
    @Test fun `a keystore that does not answer at once does not cost the phone its pairing`() {
        CredentialStore(file, box).save(far)
        val pauses = mutableListOf<Long>()
        val store = CredentialStore(file, MoodyBox(silences = 2)) { pauses += it }
        assertEquals(far, store.current.value)
        assertEquals(listOf(100L, 300L), pauses)
        assertFalse(store.unavailable)
    }

    @Test fun `a keystore that stays silent leaves the pairing where it is, for the next time`() {
        CredentialStore(file, box).save(far)
        val moody = MoodyBox(silences = Int.MAX_VALUE)
        val store = CredentialStore(file, moody) { }
        assertNull(store.current.value)                 // not paired for now …
        assertTrue(store.unavailable)
        assertFalse(store.unreadable)                   // … which is not the same as «the pairing is gone»
        assertTrue(file.exists())
        moody.silences = 0
        store.retry()
        assertEquals(far, store.current.value)
        assertFalse(store.unavailable)
    }

    @Test fun `a damaged file means not paired, and the file goes`() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(CredentialStore(file, box).current.value)
        assertFalse(file.exists())
    }

    @Test fun `clear forgets the pairing`() {
        val store = CredentialStore(file, box)
        store.save(far)
        store.clear()
        assertNull(store.current.value)
        assertFalse(file.exists())
    }

    @Test fun `saving again replaces what was there`() {
        val store = CredentialStore(file, box)
        store.save(far)
        val mor = far.copy(deviceId = "d_2", who = "mor", label = "Mor")
        store.save(mor)
        assertEquals(mor, CredentialStore(file, box).current.value)
    }

    @Test fun `the secret does not show when the credentials are printed`() =
        assertFalse(far.toString().contains("hemmelig"))
}
