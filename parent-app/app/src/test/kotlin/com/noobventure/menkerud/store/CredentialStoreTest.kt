package com.noobventure.menkerud.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {
    /** Stands in for the Android Keystore: every byte XOR 0x5A; refuses to open when told to. */
    private class FakeBox : SecretBox {
        var refuse = false
        override fun seal(plain: ByteArray) = flip(plain)
        override fun open(sealed: ByteArray) = if (refuse) null else flip(sealed)
        private fun flip(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
    }

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

    @Test fun `a phone that was never paired has nothing`() = assertNull(CredentialStore(file, box).current.value)

    @Test fun `a file that cannot be opened means not paired, and the file goes`() {
        CredentialStore(file, box).save(far)
        box.refuse = true
        assertNull(CredentialStore(file, box).current.value)
        assertFalse(file.exists())
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
