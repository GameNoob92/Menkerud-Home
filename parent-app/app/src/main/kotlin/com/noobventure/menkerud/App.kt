package com.noobventure.menkerud

import android.app.Application
import kotlinx.coroutines.launch

/** The app's process. Everything that has to outlive a screen hangs off its [container]. */
class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // At every start, changed or not: see PushAddressSync.
        container.scope.launch { container.pushSync.run() }
    }
}
