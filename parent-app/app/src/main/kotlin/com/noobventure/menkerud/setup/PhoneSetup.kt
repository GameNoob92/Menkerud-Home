package com.noobventure.menkerud.setup

/** What the phone must allow before a call from home can ring on it, and whether each piece is in place. */
data class PhoneSetup(
    val pushConfigured: Boolean,        // this build knows the push service (it had a google-services.json)
    val notifications: Boolean,         // notifications, and the «Anrop hjemmefra» kind of them, are allowed
    val fullScreen: Boolean,            // the ringing screen may show over the lock screen
    val cameraAndMic: Boolean,
    val batteryUnrestricted: Boolean,   // battery saving does not hold the app back
    val notPausedWhenUnused: Boolean    // Android does not put the app to sleep after months without use
)

/** In the order they are shown, and asked for. */
enum class SetupItem { PUSH_MISSING, NOTIFICATIONS, FULL_SCREEN, CAMERA_MIC, BATTERY, UNUSED_PAUSE }

/** What is still missing, in the order of [SetupItem]. Empty: the phone is ready. */
fun missing(setup: PhoneSetup): List<SetupItem> = buildList {
    if (!setup.pushConfigured) add(SetupItem.PUSH_MISSING)
    if (!setup.notifications) add(SetupItem.NOTIFICATIONS)
    if (!setup.fullScreen) add(SetupItem.FULL_SCREEN)
    if (!setup.cameraAndMic) add(SetupItem.CAMERA_MIC)
    if (!setup.batteryUnrestricted) add(SetupItem.BATTERY)
    if (!setup.notPausedWhenUnused) add(SetupItem.UNUSED_PAUSE)
}

/** Without these two the phone will not ring at all. The other pieces make it ring every time, and well. */
fun canRing(setup: PhoneSetup): Boolean = setup.pushConfigured && setup.notifications
