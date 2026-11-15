package com.noobventure.menkerud.setup

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.noobventure.menkerud.BuildConfig
import com.noobventure.menkerud.call.AndroidRingNotifier

/** Reads from Android whether the phone is ready to ring, and knows what can be done about each missing piece. */
class AndroidPhoneSetup(context: Context, private val pushRegistered: () -> Boolean?) {
    private val context: Context = context.applicationContext
    private val packageUri: Uri = Uri.fromParts("package", this.context.packageName, null)

    fun read(): PhoneSetup {
        val manager = context.getSystemService(NotificationManager::class.java)
        return PhoneSetup(
            pushConfigured = BuildConfig.PUSH_CONFIGURED,
            pushRegistered = pushRegistered() != false,         // not yet tried in this run of the app: nothing is known against it
            notifications = callsWillShow(notificationsOn(), callsImportance()),
            fullScreen = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
            cameraAndMic = granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            notPausedWhenUnused = Build.VERSION.SDK_INT < 30 || context.packageManager.isAutoRevokeWhitelisted,
            // «Restricted» in the battery settings: Android then hands the app no pushes at all.
            backgroundAllowed = Build.VERSION.SDK_INT < 28 || !context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        )
    }

    private fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun callsImportance(): Int? =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(AndroidRingNotifier.CHANNEL_CALLS)?.importance

    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** What Android can simply be asked for, for this piece. Empty: there is nothing to ask – see [settings]. */
    fun permissions(item: SetupItem): List<String> = when (item) {
        SetupItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        SetupItem.CAMERA_MIC -> listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        else -> emptyList()
    }

    /** The settings screen that decides this piece, or null when there is none to open. */
    @SuppressLint("BatteryLife")    // asking to be let off battery saving is what a phone that must ring needs; this app is not in a store
    fun settings(item: SetupItem): Intent? = when (item) {
        SetupItem.PUSH_MISSING, SetupItem.PUSH_UNREGISTERED -> null      // nothing in Android's settings decides these
        SetupItem.NOTIFICATIONS ->
            // Notifications are on but the «Anrop hjemmefra» kind was turned down: that kind's own page. Else the app's.
            if (notificationsOn() && !callsWillShow(true, callsImportance())) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidRingNotifier.CHANNEL_CALLS)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        SetupItem.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri) else null
        SetupItem.CAMERA_MIC -> appSettings()
        SetupItem.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        // Android stops pushes to an app that nobody has opened for months, unless it is told not to.
        SetupItem.UNUSED_PAUSE -> IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    }

    /** The app's own page in Android's settings: where everything can be changed by hand. */
    fun appSettings(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
}
