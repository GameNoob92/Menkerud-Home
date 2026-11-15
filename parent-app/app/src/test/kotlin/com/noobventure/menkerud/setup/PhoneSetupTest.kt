package com.noobventure.menkerud.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupTest {
    private val ok = PhoneSetup(
        pushConfigured = true, notifications = true, fullScreen = true,
        cameraAndMic = true, batteryUnrestricted = true, notPausedWhenUnused = true
    )

    @Test fun `a phone that will not ring never looks ready`() {
        assertTrue(missing(ok).isEmpty())
        assertTrue(canRing(ok))
        assertFalse(canRing(ok.copy(notifications = false)))
        assertFalse(canRing(ok.copy(pushConfigured = false)))
        assertTrue(canRing(ok.copy(batteryUnrestricted = false)))            // it rings today – and is still named as missing
        assertEquals(listOf(SetupItem.BATTERY), missing(ok.copy(batteryUnrestricted = false)))
    }

    @Test fun `each missing piece is named in order`() =
        assertEquals(SetupItem.entries.toList(), missing(PhoneSetup(false, false, false, false, false, false)))

    @Test fun `each piece is named for its own switch and no other`() {
        assertEquals(listOf(SetupItem.PUSH_MISSING), missing(ok.copy(pushConfigured = false)))
        assertEquals(listOf(SetupItem.NOTIFICATIONS), missing(ok.copy(notifications = false)))
        assertEquals(listOf(SetupItem.FULL_SCREEN), missing(ok.copy(fullScreen = false)))
        assertEquals(listOf(SetupItem.CAMERA_MIC), missing(ok.copy(cameraAndMic = false)))
        assertEquals(listOf(SetupItem.UNUSED_PAUSE), missing(ok.copy(notPausedWhenUnused = false)))
    }
}
