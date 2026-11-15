// Top-level build file: the plugins the app module uses. Kotlin itself is the one built into the Android Gradle
// plugin (AGP 9), so there is no org.jetbrains.kotlin.android here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
