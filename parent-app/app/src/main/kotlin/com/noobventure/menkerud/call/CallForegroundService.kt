package com.noobventure.menkerud.call

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.noobventure.menkerud.R
import com.noobventure.menkerud.container
import com.noobventure.menkerud.ui.CallActivity
import com.noobventure.menkerud.ui.Texts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a call alive – camera and microphone included – when the parent leaves the call screen. It shows a quiet
 * notification with «Legg på» and ends itself when the call does.
 *
 * It is only ever started from [CallActivity], while that is on screen and camera and microphone are granted:
 * Android refuses a camera or microphone service that is started from the background.
 */
class CallForegroundService : LifecycleService() {

    private var watching: Job? = null

    @SuppressLint("InlinedApi")         // the two service types are newer than Android 8; ServiceCompat leaves them out there
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val hangUp = PendingIntent.getBroadcast(
            this, 0, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).setAction(CallActivity.ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, AndroidRingNotifier.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(Texts.ongoingTitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(show)
            .addAction(0, Texts.hangUp, hangUp)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: RuntimeException) {     // not allowed right now (no longer on screen, a permission taken back)
            Log.w(TAG, "the call runs without its foreground service: " + e.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        // Only now: a service that was asked to run in the foreground must not stop before it has done so.
        if (watching == null) {
            watching = lifecycleScope.launch {
                container.calls.state.collect { state ->
                    if (state is CallState.Idle || state is CallState.Ended) stop()
                }
            }
        }
        return START_NOT_STICKY             // a call does not survive the process; nothing to restart
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "MenkerudHome"
        private const val NOTIFICATION_ID = 1

        /** Call this only while the call screen is showing. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not start the call's foreground service: " + e.javaClass.simpleName)
            }
        }
    }
}
