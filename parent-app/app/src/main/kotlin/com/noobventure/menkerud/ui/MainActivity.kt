package com.noobventure.menkerud.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.noobventure.menkerud.BuildConfig
import com.noobventure.menkerud.api.Sent
import com.noobventure.menkerud.call.CallState
import com.noobventure.menkerud.container
import com.noobventure.menkerud.setup.PhoneSetup
import com.noobventure.menkerud.setup.SetupItem
import com.noobventure.menkerud.ui.screens.HomeScreen
import com.noobventure.menkerud.ui.screens.PairingScreen
import com.noobventure.menkerud.ui.screens.QuietButton
import com.noobventure.menkerud.ui.theme.MenkerudColors
import com.noobventure.menkerud.ui.theme.MenkerudTheme
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** The app's front door: the pairing screen for a phone that is not paired, the home screen for one that is. */
class MainActivity : ComponentActivity() {
    private val key = mutableStateOf("")                    // what is in the key field
    private val pairMessage = mutableStateOf<String?>(null) // what was wrong with the last attempt to pair
    private val pairing = mutableStateOf(false)             // an attempt is under way
    private val setup = mutableStateOf<PhoneSetup?>(null)
    private val homeNotice = mutableStateOf<String?>(null)  // one line on the home screen: no server, or camera and microphone needed
    private val probe = mutableStateOf<String?>(null)       // test builds: what the two servers answered

    private var asking: SetupItem? = null                   // the piece whose permission question is up
    private var ringWhenAllowed = false                     // «Ring hjem» is waiting for that question
    private var askedAt = 0L

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val item = asking
        val ring = ringWhenAllowed
        asking = null
        ringWhenAllowed = false
        val phone = container.phoneSetup
        setup.value = phone.read()
        val refused = item != null && phone.permissions(item).any { !phone.granted(it) }
        if (ring) {
            if (refused) homeNotice.value = Texts.needCamMic else container.calls.ringHome()
        }
        // Android answered without asking: it was refused for good earlier, and only the settings can change that.
        val withoutAsking = SystemClock.elapsedRealtime() - askedAt < ANSWERED_WITHOUT_ASKING_MS
        if (refused && withoutAsking) item?.let(phone::settings)?.let(::open)
    }

    private val settingsScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setup.value = container.phoneSetup.read()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeLink(intent)
        setContent { MenkerudTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    /** `menkerudhome://pair?…` opened from outside only fills in the field: the parent still presses «Koble til». */
    private fun takeLink(intent: Intent?) {
        val link = intent?.dataString ?: return
        if (intent.action == Intent.ACTION_VIEW && link.startsWith("menkerudhome://pair", ignoreCase = true)) {
            key.value = link
            pairMessage.value = null
        }
    }

    @Composable
    private fun Screen() {
        val app = container
        val me by app.store.current.collectAsStateWithLifecycle()
        val call by app.calls.state.collectAsStateWithLifecycle()
        val wasUnpaired by app.prefs.unpairedNotice.collectAsStateWithLifecycle(initialValue = false)
        val lastCall by app.prefs.lastCall.collectAsStateWithLifecycle(initialValue = null)
        val callOn = call != CallState.Idle

        // A call that begins while this screen shows – or was on when the app was opened – has its own screen.
        LaunchedEffect(callOn) { if (callOn) showCall() }

        // Every time this screen comes to the front: is the phone still paired, and still ready to ring?
        LifecycleResumeEffect(me?.deviceId) {
            val check = lifecycleScope.launch { cameToFront() }
            onPauseOrDispose { check.cancel() }
        }

        val paired = me
        if (paired == null) {
            PairingScreen(
                key = key.value, onKey = { key.value = it; pairMessage.value = null },
                notice = if (wasUnpaired) Texts.unpaired else null,
                message = pairMessage.value, busy = pairing.value,
                onGo = ::pair, onScan = ::scan
            )
        } else {
            HomeScreen(
                who = paired.who, label = paired.label,
                setup = setup.value ?: app.phoneSetup.read(),
                samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
                lastCall = lastCall?.let { lastCallText(it, System.currentTimeMillis(), ZoneId.systemDefault()) },
                notice = homeNotice.value,
                version = "Menkerud Home " + BuildConfig.VERSION_NAME,
                callOn = callOn,
                onRingHome = { if (callOn) showCall() else ringHome() },
                onFix = ::fix,
                extra = { if (BuildConfig.ALLOW_ANY_API) ConnectionTest() }
            )
        }
    }

    private suspend fun cameToFront() {
        setup.value = container.phoneSetup.read()
        if (container.store.current.value == null) return
        when (container.api.whoAmI()) {
            Sent.UNPAIRED -> container.store.clear()                    // the pairing screen says why
            Sent.FAILED -> homeNotice.value = Texts.noServer
            Sent.OK -> if (homeNotice.value == Texts.noServer) homeNotice.value = null
        }
    }

    private fun pair() {
        if (pairing.value || key.value.isBlank()) return
        pairing.value = true
        pairMessage.value = null
        val text = key.value
        // On the app's own scope: a key is good for one try, so this must finish even if the screen goes away.
        container.scope.launch {
            pairMessage.value = container.pairing.submit(text)
            pairing.value = false
            if (container.store.current.value != null) key.value = ""
        }
    }

    private fun scan() {
        pairMessage.value = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val text = code.rawValue
                if (text.isNullOrBlank()) pairMessage.value = Texts.scanFailed else { key.value = text; pair() }
            }
            .addOnCanceledListener { pairMessage.value = Texts.scanFailed }
            .addOnFailureListener { pairMessage.value = Texts.scanFailed }     // the scanner could not be fetched, or broke
    }

    private fun ringHome() {
        homeNotice.value = null
        val phone = container.phoneSetup
        val wanted = phone.permissions(SetupItem.CAMERA_MIC).filterNot(phone::granted)
        if (wanted.isEmpty()) {
            container.calls.ringHome()                                  // the call's own screen opens when the state changes
            return
        }
        asking = SetupItem.CAMERA_MIC
        ringWhenAllowed = true
        // A headset may be asked for at the same time; whether that is allowed changes nothing.
        ask(if (Build.VERSION.SDK_INT >= 31) wanted + Manifest.permission.BLUETOOTH_CONNECT else wanted)
    }

    /** A missing piece was tapped: ask Android for it, or open the setting that decides it. */
    private fun fix(item: SetupItem) {
        val phone = container.phoneSetup
        val wanted = phone.permissions(item).filterNot(phone::granted)
        if (wanted.isNotEmpty()) {
            asking = item
            ask(wanted)
        } else {
            phone.settings(item)?.let(::open)
        }
    }

    private fun ask(permissions: List<String>) {
        askedAt = SystemClock.elapsedRealtime()
        askPermissions.launch(permissions.toTypedArray())
    }

    private fun open(settings: Intent) {
        try {
            settingsScreen.launch(settings)
        } catch (e: ActivityNotFoundException) {                        // a phone without that screen: the app's own page has it all
            try { settingsScreen.launch(container.phoneSetup.appSettings()) } catch (e: ActivityNotFoundException) { }
        }
    }

    private fun showCall() {
        startActivity(Intent(this, CallActivity::class.java).setAction(CallActivity.ACTION_SHOW))
    }

    /** Test builds only: does an app get through to the two servers from where the phone is now? 200 and 401 are the good answers. */
    @Composable
    private fun ConnectionTest() {
        QuietButton(Texts.testConnection, onClick = {
            probe.value = "…"
            lifecycleScope.launch {
                probe.value = withContext(Dispatchers.IO) {
                    listOf("call" to "https://call.noobventure.com/healthz", "rtc" to "https://rtc.noobventure.com/rtc/validate")
                        .joinToString(" · ") { (name, url) ->
                            name + " " + try {
                                container.http.newCall(Request.Builder().url(url).build()).execute().use { it.code.toString() }
                            } catch (e: IOException) {
                                "–"
                            }
                        }
                }
            }
        })
        probe.value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MenkerudColors.TextSoft, textAlign = TextAlign.Center)
        }
    }

    private companion object {
        const val ANSWERED_WITHOUT_ASKING_MS = 500L
    }
}
