package com.noobventure.menkerud.pairing

import com.noobventure.menkerud.FakeBox
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.Credentials
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PairingLossTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { File(tmp.root, "device.bin") }
    private val box = FakeBox()
    private val far = Credentials("https://call.noobventure.com", "d_1", "s3", "far", "Far")
    private val notices = mutableListOf<Boolean>()      // what the pairing screen was told to say, in order
    private var leftPushService = 0

    private fun TestScope.watch(store: CredentialStore) {
        backgroundScope.launch { PairingLoss(store, { notices += it }, { leftPushService++ }).watch() }
        runCurrent()
    }

    // The server still lists such a phone, and its push address still works: calls would be sent to an app that can
    // no longer answer them, and the parent would get neither a ring nor the Discord link. Leaving the push service
    // makes the server's next push bounce, and the server then stops counting the phone as paired.
    @Test fun `a pairing that can no longer be read is said, and the phone leaves the push service`() = runTest {
        CredentialStore(file, box).save(far)
        box.refuse = true
        watch(CredentialStore(file, box))
        assertEquals(listOf(true), notices)
        assertEquals(1, leftPushService)
    }

    @Test fun `a phone the server removed is told why, and stays with the push service`() = runTest {
        val store = CredentialStore(file, box).also { it.save(far) }
        watch(store)
        store.clear(); runCurrent()                     // the server said 401: it already knows
        assertEquals(true, notices.last())
        assertEquals(0, leftPushService)
        store.save(far); runCurrent()                   // paired again: nothing left to explain
        assertEquals(false, notices.last())
    }

    @Test fun `a phone that was never paired is told nothing`() = runTest {
        watch(CredentialStore(file, box))
        assertTrue(notices.isEmpty())
        assertEquals(0, leftPushService)
    }
}
