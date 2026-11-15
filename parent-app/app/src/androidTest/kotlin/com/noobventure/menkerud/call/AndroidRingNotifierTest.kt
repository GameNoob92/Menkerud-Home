package com.noobventure.menkerud.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a phone. It rings for a moment: every test takes its notification away again. */
@RunWith(AndroidJUnit4::class)
class AndroidRingNotifierTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var notifier: AndroidRingNotifier

    @Before fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        notifier = AndroidRingNotifier(context)
    }

    @After fun takeEverythingAway() {
        notifier.stop("c_1")
        manager.cancel("missed", "c_1".hashCode())
    }

    private fun active(tag: String) = manager.activeNotifications.filter { it.tag == tag }

    @Test fun ringingShowsOneCallNotificationThatInsists() {
        notifier.ring("c_1", 30_000)
        val ringing = active("ring").single().notification
        notifier.stop("c_1")                                    // quiet again before anything is checked
        assertEquals("calls", ringing.channelId)
        assertEquals(Notification.CATEGORY_CALL, ringing.category)
        assertTrue("ongoing", ringing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("insistent", ringing.flags and Notification.FLAG_INSISTENT != 0)
        // Android 14 and newer take the full-screen intent away when the user has not allowed it, and mark that.
        val askedButDenied = ringing.flags and FSI_REQUESTED_BUT_DENIED != 0
        assertTrue("a full-screen intent", ringing.fullScreenIntent != null || askedButDenied)
    }

    @Test fun stopTakesTheRingingAway() {
        notifier.ring("c_1", 30_000)
        notifier.stop("c_1")
        assertTrue(active("ring").isEmpty())
    }

    @Test fun aMissedCallLeavesItsNotice() {
        notifier.missed("c_1", System.currentTimeMillis())
        val notice = active("missed").single().notification
        assertEquals("missed", notice.channelId)
        assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("Tapt anrop hjemmefra kl."))
    }

    @Test fun theChannelsAreWhatAndroidNeedsToRing() {
        val calls = manager.getNotificationChannel("calls")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, calls.importance)
        assertNotNull(calls.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, calls.audioAttributes.usage)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("missed").importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("ongoing").importance)
    }

    private companion object {
        const val FSI_REQUESTED_BUT_DENIED = 1 shl 14            // Notification.FLAG_FSI_REQUESTED_BUT_DENIED (API 34)
    }
}
