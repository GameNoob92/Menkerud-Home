package com.noobventure.menkerud.push

import com.noobventure.menkerud.FakeBox
import com.noobventure.menkerud.FakeCallApi
import com.noobventure.menkerud.api.Sent
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.Credentials
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PushAddressSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private val api = FakeCallApi()
    private val store by lazy {
        CredentialStore(File(tmp.root, "device.bin"), FakeBox()).also { it.save(Credentials("https://call.noobventure.com", "d_1", "s3", "far", "Far")) }
    }
    private var address: String? = "fid-1"
    private var asked = 0
    private val sync by lazy { PushAddressSync(api, store) { asked++; address } }

    @Test fun `the address is sent at every start, changed or not`() = runTest {
        sync.run(); sync.run()
        assertEquals(listOf("sendPushAddress fid-1", "sendPushAddress fid-1"), api.calls)
    }

    @Test fun `nothing is asked or sent while the phone is not paired`() = runTest {
        store.clear()
        assertNull(sync.run())
        assertEquals(0, asked)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `a build without push sends nothing`() = runTest {
        address = null
        assertNull(sync.run())
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `an address Firebase hands over is sent as it is`() = runTest {
        sync.run("fid-9")
        assertEquals(listOf("sendPushAddress fid-9"), api.calls)
        assertEquals(0, asked)
    }

    @Test fun `a refused push address forgets the pairing`() = runTest {
        api.sent = Sent.UNPAIRED
        assertEquals(Sent.UNPAIRED, sync.run())
        assertNull(store.current.value)
    }

    @Test fun `a server that is unwell leaves the pairing alone`() = runTest {
        api.sent = Sent.FAILED
        assertEquals(Sent.FAILED, sync.run())
        assertNotNull(store.current.value)
    }
}
