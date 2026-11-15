package com.noobventure.menkerud

import android.content.Context
import android.os.Build
import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.HttpCallApi
import com.noobventure.menkerud.call.AndroidRingNotifier
import com.noobventure.menkerud.call.CallController
import com.noobventure.menkerud.call.LiveKitCallSession
import com.noobventure.menkerud.call.RingNotifier
import com.noobventure.menkerud.call.SessionFactory
import com.noobventure.menkerud.pairing.PairPolicy
import com.noobventure.menkerud.pairing.Pairing
import com.noobventure.menkerud.push.FirebasePushAddress
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.setup.AndroidPhoneSetup
import com.noobventure.menkerud.store.AppPrefs
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.KeystoreSecretBox
import java.io.File
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

    val pushSync = PushAddressSync(api, store) { FirebasePushAddress(appContext).get() }

    val prefs = AppPrefs(appContext)

    val notifier: RingNotifier = AndroidRingNotifier(appContext)

    val sessions = SessionFactory { LiveKitCallSession(appContext) }

    /** The one call there can be. */
    val calls = CallController(
        api, sessions, notifier,
        unpair = store::clear,
        remember = { call -> scope.launch { prefs.setLastCall(call) } },
        now = System::currentTimeMillis,
        scope = scope
    )

    val pairing = Pairing(
        api, store, pushSync,
        PairPolicy(BuildConfig.DEFAULT_API, setOf(BuildConfig.ALLOWED_API_HOSTS), BuildConfig.ALLOW_ANY_API),
        phoneName = Build.MODEL
    )

    val phoneSetup = AndroidPhoneSetup(appContext)

    init {
        // The app has no «unpair» of its own: a pairing only ever goes away because the server no longer knows the
        // phone, or because the stored secret can no longer be read. The pairing screen then says why it asks again.
        scope.launch {
            if (store.unreadable) prefs.setUnpairedNotice(true)
            var paired = store.current.value != null
            store.current.collect { now ->
                if (paired && now == null) prefs.setUnpairedNotice(true)
                if (now != null) prefs.setUnpairedNotice(false)
                paired = now != null
            }
        }
    }
}

/** The container, from anywhere that has a Context. */
val Context.container: AppContainer get() = (applicationContext as App).container
