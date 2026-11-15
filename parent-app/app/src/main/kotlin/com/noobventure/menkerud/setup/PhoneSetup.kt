package com.noobventure.menkerud.setup

/** What the phone must allow before a call from home can ring on it, and whether each piece is in place. */
data class PhoneSetup(
    val pushConfigured: Boolean,        // this build knows the push service (it had a google-services.json)
    val pushRegistered: Boolean,        // the server has been told where this phone's pushes go
    val notifications: Boolean,         // notifications are on, and the «Anrop hjemmefra» kind of them still shows as a call
    val fullScreen: Boolean,            // the ringing screen may show over the lock screen
    val cameraAndMic: Boolean,
    val batteryUnrestricted: Boolean,   // battery saving does not hold the app back
    val notPausedWhenUnused: Boolean,   // Android does not put the app to sleep after months without use
    val backgroundAllowed: Boolean      // the app has not been shut out of the background altogether
)

/** In the order they are shown, and asked for. */
enum class SetupItem { PUSH_MISSING, PUSH_UNREGISTERED, NOTIFICATIONS, FULL_SCREEN, CAMERA_MIC, BATTERY, UNUSED_PAUSE }

/** What is still missing, in the order of [SetupItem]. Empty: the phone is ready. */
fun missing(setup: PhoneSetup): List<SetupItem> = buildList {
    // A build without push cannot be registered either; that is one thing to say, not two.
    if (!setup.pushConfigured) add(SetupItem.PUSH_MISSING) else if (!setup.pushRegistered) add(SetupItem.PUSH_UNREGISTERED)
    if (!setup.notifications) add(SetupItem.NOTIFICATIONS)
    if (!setup.fullScreen) add(SetupItem.FULL_SCREEN)
    if (!setup.cameraAndMic) add(SetupItem.CAMERA_MIC)
    // Shut out of the background, or merely held back by battery saving: the same setting decides both.
    if (!setup.batteryUnrestricted || !setup.backgroundAllowed) add(SetupItem.BATTERY)
    if (!setup.notPausedWhenUnused) add(SetupItem.UNUSED_PAUSE)
}

/** Without these the phone will not ring at all. The other pieces make it ring every time, and well. */
fun canRing(setup: PhoneSetup): Boolean =
    setup.pushConfigured && setup.pushRegistered && setup.notifications && setup.backgroundAllowed

private const val IMPORTANCE_HIGH = 4       // android.app.NotificationManager.IMPORTANCE_HIGH

/**
 * Whether a call from home will show as a call. `callsImportance`: Android's level for the «Anrop hjemmefra» kind of
 * notification, or null while that kind has not been made. Anything below «urgent» – one long press on the ringing
 * notification is enough – means no banner and no ringing screen, however loud the phone is.
 */
fun callsWillShow(notificationsOn: Boolean, callsImportance: Int?): Boolean =
    notificationsOn && (callsImportance == null || callsImportance >= IMPORTANCE_HIGH)
