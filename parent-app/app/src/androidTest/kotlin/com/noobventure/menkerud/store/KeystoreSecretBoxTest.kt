package com.noobventure.menkerud.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import kotlin.experimental.xor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a phone: the real Android Keystore, under a key name of its own that is removed afterwards. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    private val alias = "menkerud.test." + System.nanoTime()
    private val box = KeystoreSecretBox(alias)
    private val text = "hemmelig-0123456789".toByteArray()

    @After fun removeTheKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun whatIsSealedOpensAgain() = assertArrayEquals(text, box.open(box.seal(text)))

    @Test fun oneChangedByteCannotBeOpened() {
        val sealed = box.seal(text)
        sealed[sealed.size - 1] = sealed[sealed.size - 1] xor 1
        assertNull(box.open(sealed))
    }

    @Test fun twoSealsOfTheSameTextDiffer() = assertFalse(box.seal(text).contentEquals(box.seal(text)))

    @Test fun aKeyThatIsGoneCannotOpen() {
        val sealed = box.seal(text)
        removeTheKey()
        assertNull(box.open(sealed))
    }

    @Test fun whatIsTooShortToBeSealedBytesCannotBeOpened() {
        box.seal(text)
        assertNull(box.open(ByteArray(12)))
    }
}
