package com.noobventure.menkerud

import android.content.Context
import com.noobventure.menkerud.api.CallApi
import com.noobventure.menkerud.api.HttpCallApi
import com.noobventure.menkerud.push.FirebasePushAddress
import com.noobventure.menkerud.push.PushAddressSync
import com.noobventure.menkerud.store.CredentialStore
import com.noobventure.menkerud.store.KeystoreSecretBox
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The one place the app's objects are made and handed to each other. One per process, made by [App]. */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** For work that must outlive a screen. Lives as long as the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The pairing, in a file Android never backs up. */
    val store = CredentialStore(File(appContext.noBackupFilesDir, "device.bin"), KeystoreSecretBox())

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val api: CallApi = HttpCallApi(http, { store.current.value }, BuildConfig.VERSION_NAME)

    val pushSync = PushAddressSync(api, store) { FirebasePushAddress(appContext).get() }
}

/** The container, from anywhere that has a Context. */
val Context.container: AppContainer get() = (applicationContext as App).container
