package com.noobventure.menkerud.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupTest {
    private val ok = PhoneSetup(
        pushConfigured = true, pushRegistered = true, notifications = true, fullScreen = true,
        cameraAndMic = true, batteryUnrestricted = true, notPausedWhenUnused = true, backgroundAllowed = true
    )
    private val nothing = PhoneSetup(false, false, false, false, false, false, false, false)

    @Test fun `a phone that will not ring never looks ready`() {
        assertTrue(missing(ok).isEmpty())
        assertTrue(canRing(ok))
        assertFalse(canRing(ok.copy(notifications = false)))
        assertFalse(canRing(ok.copy(pushConfigured = false)))
        assertFalse(canRing(ok.copy(pushRegistered = false)))                // the server does not know where to send the push
        assertFalse(canRing(ok.copy(backgroundAllowed = false)))             // Android hands no pushes to an app shut out of the background
        assertTrue(canRing(ok.copy(batteryUnrestricted = false)))            // it rings today – and is still named as missing
        assertEquals(listOf(SetupItem.BATTERY), missing(ok.copy(batteryUnrestricted = false)))
    }

    @Test fun `each missing piece is named in order`() {
        assertEquals(
            listOf(SetupItem.PUSH_MISSING, SetupItem.NOTIFICATIONS, SetupItem.FULL_SCREEN, SetupItem.CAMERA_MIC, SetupItem.BATTERY, SetupItem.UNUSED_PAUSE),
            missing(nothing)                                    // a build without push cannot be registered either: that is said once
        )
        assertEquals(
            listOf(SetupItem.PUSH_UNREGISTERED, SetupItem.NOTIFICATIONS, SetupItem.FULL_SCREEN, SetupItem.CAMERA_MIC, SetupItem.BATTERY, SetupItem.UNUSED_PAUSE),
            missing(nothing.copy(pushConfigured = true))
        )
    }

    @Test fun `each piece is named for its own switch and no other`() {
        assertEquals(listOf(SetupItem.PUSH_MISSING), missing(ok.copy(pushConfigured = false)))
        assertEquals(listOf(SetupItem.PUSH_UNREGISTERED), missing(ok.copy(pushRegistered = false)))
        assertEquals(listOf(SetupItem.NOTIFICATIONS), missing(ok.copy(notifications = false)))
        assertEquals(listOf(SetupItem.FULL_SCREEN), missing(ok.copy(fullScreen = false)))
        assertEquals(listOf(SetupItem.CAMERA_MIC), missing(ok.copy(cameraAndMic = false)))
        assertEquals(listOf(SetupItem.BATTERY), missing(ok.copy(backgroundAllowed = false)))    // the same row: the same setting decides it
        assertEquals(listOf(SetupItem.UNUSED_PAUSE), missing(ok.copy(notPausedWhenUnused = false)))
    }

    // Android's levels for a kind of notification: 4 «urgent» (banner, ringing screen), 3 «standard» (a sound, no
    // banner), 2 «silent», 0 off. A long press on the ringing notification is all it takes to turn it down.
    @Test fun `a call kind that was turned down does not count as notifications`() {
        assertTrue(callsWillShow(notificationsOn = true, callsImportance = 4))
        assertTrue(callsWillShow(notificationsOn = true, callsImportance = null))       // not made yet: nothing is known against it
        assertFalse(callsWillShow(notificationsOn = true, callsImportance = 3))
        assertFalse(callsWillShow(notificationsOn = true, callsImportance = 2))
        assertFalse(callsWillShow(notificationsOn = true, callsImportance = 0))
        assertFalse(callsWillShow(notificationsOn = false, callsImportance = 4))
    }
}
