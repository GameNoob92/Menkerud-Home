package com.noobventure.menkerud.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.noobventure.menkerud.Notes
import com.noobventure.menkerud.R
import com.noobventure.menkerud.ui.CallActivity
import com.noobventure.menkerud.ui.MainActivity
import com.noobventure.menkerud.ui.Texts
import com.noobventure.menkerud.ui.missedText
import java.time.ZoneId

/**
 * The call as Android shows it outside the app: a call-style notification that rings, and the «missed» notice.
 *
 * Nothing here starts an activity or a service by itself – from a push in the background Android allows neither.
 * The notification carries a full-screen intent instead; Android opens the ringing screen with it when the phone is
 * locked, and shows a banner with two buttons (whose words are Android's own) when the phone is in use.
 */
class AndroidRingNotifier(context: Context) : RingNotifier {
    private val context: Context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    init {
        makeChannels()
    }

    override fun ring(callId: String, forMs: Long) {
        val caller = Person.Builder().setName(Texts.home).setImportant(true).build()
        val base = { NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            // A call-style notification shows the caller's name as its headline and this text under it; without a
            // text Android writes its own «incoming call».
            .setContentTitle(Texts.ringsFromHome)
            .setContentText(Texts.ringsFromHome)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(forMs)                                     // Android takes it away even if the app is gone
            .setContentIntent(toCallScreen(CallActivity.ACTION_RING, callId))
            .setFullScreenIntent(toCallScreen(CallActivity.ACTION_RING, callId), true) }
        val answer = toCallScreen(CallActivity.ACTION_ANSWER, callId)
        val decline = PendingIntent.getBroadcast(
            context, callId.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtra(CallActionReceiver.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callStyle = base().setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer)).build()
        // Should Android ever refuse the call style, the phone must still ring: the same notification with two plain buttons.
        val plain = { base().addAction(0, Texts.answer, answer).addAction(0, Texts.notNow, decline).build() }
        val shown = show(TAG_RING, callId, insist(callStyle)) || show(TAG_RING, callId, insist(plain()))
        Notes.say("ring " + Notes.short(callId) + " for " + forMs / 1000 + " s: " + (if (shown) "notification up" else "NOT SHOWN") +
            (if (manager.areNotificationsEnabled()) "" else " (notifications are off for the app)"))
    }

    override fun stop(callId: String) {
        manager.cancel(TAG_RING, callId.hashCode())
        Notes.say("ring " + Notes.short(callId) + " taken away")
    }

    override fun missed(callId: String, at: Long) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notice = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(missedText(at, ZoneId.systemDefault()))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        show(TAG_MISSED, callId, notice)
        Notes.say("missed call " + Notes.short(callId) + " noted")
    }

    /** The ringtone goes on until the call is answered, declined or timed out. */
    private fun insist(notification: Notification) = notification.apply { flags = flags or Notification.FLAG_INSISTENT }

    /** An activity intent, not a broadcast: Android does not let a notification button's receiver open a screen. */
    private fun toCallScreen(action: String, callId: String): PendingIntent = PendingIntent.getActivity(
        context, callId.hashCode(),
        Intent(context, CallActivity::class.java).setAction(action).putExtra(CallActivity.EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    @SuppressLint("MissingPermission")      // without the permission Android drops the notification; the home screen says so
    private fun show(tag: String, callId: String, notification: Notification): Boolean = try {
        manager.notify(tag, callId.hashCode(), notification)
        true
    } catch (e: RuntimeException) {
        Notes.warn("notification refused: " + e.javaClass.simpleName + ": " + e.message)
        false
    }

    private fun makeChannels() {
        val ringtone = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // A channel's sound and importance cannot be changed once it exists on a phone. If they ever must change,
        // the channel gets a new id.
        val calls = NotificationChannel(CHANNEL_CALLS, Texts.channelCalls, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ringtone)   // follows the phone's own ring setting
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 1400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CHANNEL_MISSED, Texts.channelMissed, NotificationManager.IMPORTANCE_DEFAULT)
        val ongoing = NotificationChannel(CHANNEL_ONGOING, Texts.channelOngoing, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(calls, missed, ongoing))
    }

    companion object {
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_MISSED = "missed"
        const val CHANNEL_ONGOING = "ongoing"
        const val TAG_RING = "ring"
        const val TAG_MISSED = "missed"
    }
}
