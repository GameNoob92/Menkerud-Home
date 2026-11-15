package com.noobventure.menkerud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test fun `the app knows its server and which server it may be paired with`() {
        assertEquals("https://call.noobventure.com", BuildConfig.DEFAULT_API)
        assertEquals("call.noobventure.com", BuildConfig.ALLOWED_API_HOSTS)
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.noobventure.menkerud"))
    }
}
