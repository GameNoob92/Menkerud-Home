package com.noobventure.menkerud

import android.content.Context
import android.os.Build
import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.HttpCallApi
import com.noobventure.menkerud.call.AndroidRingNotifier
import com.noobventure.menkerud.call.CallController
import com.noobventure.menkerud.call.CallState
import com.noobventure.menkerud.call.LiveKitCallSession
import com.noobventure.menkerud.call.RingNotifier
import com.noobventure.menkerud.call.SessionFactory
import com.noobventure.menkerud.pairing.PairPolicy
import com.noobventure.menkerud.pairing.Pairing
import com.noobventure.menkerud.pairing.PairingLoss
import com.noobventure.menkerud.push.FirebasePushAddress
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.setup.AndroidPhoneSetup
import com.noobventure.menkerud.store.AppPrefs
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.KeystoreSecretBox
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The one place the app's objects are made and handed to each other. One per process, made by [App]. */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** For work that must outlive a screen. Lives as long as the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The pairing, in a file Android never backs up. */
    val store = CredentialStore(File(appContext.noBackupFilesDir, "device.bin"), KeystoreSecretBox())

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: CallApi = HttpCallApi(http, { store.current.value }, BuildConfig.VERSION_NAME)

    private val pushAddress = FirebasePushAddress(appContext)

    val pushSync = PushAddressSync(api, store) { pushAddress.get() }

    val prefs = AppPrefs(appContext)

    val notifier: RingNotifier = AndroidRingNotifier(appContext)

    val sessions = SessionFactory { LiveKitCallSession(appContext) }

    /** The one call there can be. */
    val calls = CallController(
        api, sessions, notifier,
        unpair = store::clear,
        remember = { call -> scope.launch { quietly { prefs.setLastCall(call) } } },
        now = System::currentTimeMillis,
        scope = scope
    )

    val pairing = Pairing(
        api, store, pushSync,
        PairPolicy(BuildConfig.DEFAULT_API, setOf(BuildConfig.ALLOWED_API_HOSTS), BuildConfig.ALLOW_ANY_API),
        phoneName = Build.MODEL
    )

    val phoneSetup = AndroidPhoneSetup(appContext) { pushSync.registered.value }

    init {
        scope.launch {
            PairingLoss(store, showNotice = { show -> quietly { prefs.setUnpairedNotice(show) } }, leavePushService = pushAddress::leave).watch()
        }
        // The phone's log: every turn a call takes, and whether the server knows where to send the pushes.
        scope.launch { calls.state.collect { Notes.say("call: " + describe(it)) } }
        scope.launch { pushSync.registered.collect { if (it != null) Notes.say("the server knows where to push: $it") } }
    }

    /** A note that cannot be written (a full disk) is a note lost, not a reason for the app to stop. */
    private suspend fun quietly(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: IOException) {
            Notes.warn("could not write a note: " + e.javaClass.simpleName)
        }
    }

    private fun describe(state: CallState): String = when (state) {
        CallState.Idle -> "idle"
        is CallState.Incoming -> "ringing here " + Notes.short(state.callId)
        is CallState.Answering -> "answering " + Notes.short(state.callId)
        is CallState.RingingHome -> "ringing home " + Notes.short(state.callId)
        is CallState.InCall -> "in the call " + Notes.short(state.callId)
        is CallState.Ended -> "ended: " + state.why
    }
}

/** The container, from anywhere that has a Context. */
val Context.container: AppContainer get() = (applicationContext as App).container
