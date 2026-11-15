package com.noobventure.menkerud.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noobventure.menkerud.call.AndroidRingNotifier
import com.noobventure.menkerud.call.CallForegroundService
import com.noobventure.menkerud.call.CallState
import com.noobventure.menkerud.call.LiveKitCallSession
import com.noobventure.menkerud.container
import com.noobventure.menkerud.ui.screens.InCallScreen
import com.noobventure.menkerud.ui.screens.IncomingCallScreen
import com.noobventure.menkerud.ui.screens.NightBackground
import com.noobventure.menkerud.ui.screens.RingingHomeScreen
import com.noobventure.menkerud.ui.theme.MenkerudColors
import com.noobventure.menkerud.ui.theme.MenkerudTheme
import kotlinx.coroutines.delay

/**
 * The one screen for a call, from its first ring to its last word. It only draws the controller's state and reports
 * taps to it. Android opens it over the lock screen (the ringing notification's full-screen intent), the banner's
 * answer button opens it, and the app opens it when «Ring hjem» is pressed.
 */
class CallActivity : ComponentActivity() {
    /** The call the notification opened this screen for. After a restart of the app the controller knows nothing of
     *  a call that still rings, but the notification is still there – and so is this id. */
    private val opened = mutableStateOf<String?>(null)
    private val needCamMic = mutableStateOf(false)
    private var waitingToAnswer: String? = null          // «Svar» was pressed and Android is asking for camera and microphone

    private val askForCamMic = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val callId = waitingToAnswer
        waitingToAnswer = null
        needCamMic.value = !hasCamMic()
        if (callId != null && hasCamMic()) container.calls.answer(callId)       // refused: the call keeps ringing
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        opened.value = intent?.getStringExtra(EXTRA_CALL_ID)
        if (savedInstanceState == null) act(intent)
        setContent { MenkerudTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CALL_ID)?.let { opened.value = it }
        act(intent)
    }

    private fun act(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) intent.getStringExtra(EXTRA_CALL_ID)?.let { answer(it) }
    }

    /** The banner's answer button and the «Svar» button both come here. */
    private fun answer(callId: String) {
        if (hasCamMic()) {
            needCamMic.value = false
            container.calls.answer(callId)
            return
        }
        waitingToAnswer = callId
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT       // for a headset; its refusal changes nothing
        askForCamMic.launch(wanted.toTypedArray())
    }

    private fun hasCamMic() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun stillRings(callId: String) = getSystemService(NotificationManager::class.java).activeNotifications
        .any { it.tag == AndroidRingNotifier.TAG_RING && it.id == callId.hashCode() }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    private fun Screen() {
        val calls = container.calls
        val state by calls.state.collectAsStateWithLifecycle()
        val session = calls.session.collectAsStateWithLifecycle().value as? LiveKitCallSession
        val needs by needCamMic
        val fromNotification by opened

        // The picture from home is wide: while they talk, the screen turns with the phone.
        val talking = state is CallState.InCall
        LaunchedEffect(talking) {
            requestedOrientation =
                if (talking) ActivityInfo.SCREEN_ORIENTATION_SENSOR else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // What keeps the call alive when the parent leaves this screen can only be started from here, while it shows.
        val live = state is CallState.Answering || state is CallState.RingingHome || state is CallState.InCall
        LaunchedEffect(live) {
            if (live && hasCamMic()) CallForegroundService.start(this@CallActivity)
        }

        when (val s = state) {
            is CallState.Incoming -> IncomingCallScreen(
                needs, onAnswer = { answer(s.callId) }, onDecline = { calls.decline(s.callId) }, onOpenSettings = ::openAppSettings
            )
            is CallState.Answering -> Words(Texts.connecting)
            is CallState.RingingHome -> RingingHomeScreen(session, onCancel = calls::hangUp)
            is CallState.InCall -> InCallScreen(session, onHangUp = calls::hangUp)
            is CallState.Ended -> Words(endText(s.why), onTap = calls::dismissEnded)
            CallState.Idle -> {
                // Nothing is on – unless the notification that opened this screen still rings (the app was restarted
                // in between). Then this is still the ringing screen, for as long as that notification lives.
                val callId = fromNotification
                val rings by produceState(initialValue = callId != null && stillRings(callId), callId) {
                    while (callId != null && value) {
                        delay(500)
                        value = stillRings(callId)
                    }
                }
                if (callId != null && rings) {
                    IncomingCallScreen(
                        needs, onAnswer = { answer(callId) }, onDecline = { calls.decline(callId) }, onOpenSettings = ::openAppSettings
                    )
                } else {
                    LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    /** A few words in the middle of the night picture: «Kobler til…», and the last word about a call. */
    @Composable
    private fun Words(text: String, onTap: (() -> Unit)? = null) {
        NightBackground(modifier = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MenkerudColors.Text,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    companion object {
        /** The ringing notification was tapped, or Android opened this screen for it. */
        const val ACTION_RING = "com.noobventure.menkerud.action.RING"
        /** The banner's answer button. */
        const val ACTION_ANSWER = "com.noobventure.menkerud.action.ANSWER"
        /** Back to a call that is on. */
        const val ACTION_SHOW = "com.noobventure.menkerud.action.SHOW"
        const val EXTRA_CALL_ID = "callId"
    }
}
