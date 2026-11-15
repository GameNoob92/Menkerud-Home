import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.ResValue
import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is read from keystore.properties (git-ignored) or from MENKERUD_* environment variables; neither
// is ever committed. With nothing configured the release build is left unsigned, so the project still builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
fun keystoreValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)

// Firebase without the google-services plugin: the four values the SDK needs are read from the operator's
// app/google-services.json (git-ignored) for the client whose package name is this build's application id.
// No file, or no such client: null – the app then builds and runs, it just cannot be rung (BuildConfig.PUSH_CONFIGURED).
fun firebaseValues(applicationId: String): Map<String, String>? {
    val json = file("google-services.json")
    if (!json.exists()) return null
    val root = JsonSlurper().parse(json) as? Map<*, *> ?: return null
    val project = root["project_info"] as? Map<*, *> ?: return null
    val client = (root["client"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().firstOrNull { c ->
        val android = (c["client_info"] as? Map<*, *>)?.get("android_client_info") as? Map<*, *>
        android?.get("package_name") == applicationId
    } ?: return null
    return mapOf(
        "google_app_id" to ((client["client_info"] as? Map<*, *>)?.get("mobilesdk_app_id") as? String ?: return null),
        "google_api_key" to (((client["api_key"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String ?: return null),
        "project_id" to (project["project_id"] as? String ?: return null),
        "gcm_defaultSenderId" to (project["project_number"] as? String ?: return null),
    )
}

android {
    namespace = "com.noobventure.menkerud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noobventure.menkerud"
        minSdk = 26
        // 36 on purpose, not 37: see parent-app/README.md («Why the app targets Android 16»).
        targetSdk = 36
        // +1 on every change that reaches a phone; MINOR for features, PATCH for fixes.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The server a typed key is redeemed at, and the only host a release build lets a QR code name.
        buildConfigField("String", "DEFAULT_API", "\"https://call.noobventure.com\"")
        buildConfigField("String", "ALLOWED_API_HOSTS", "\"call.noobventure.com\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreValue("storeFile", "MENKERUD_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreValue("storePassword", "MENKERUD_STORE_PASSWORD")
                keyAlias = keystoreValue("keyAlias", "MENKERUD_KEY_ALIAS")
                keyPassword = keystoreValue("keyPassword", "MENKERUD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { abiFilters += "arm64-v8a" }       // both phones are arm64: 15 MB instead of about 40
            buildConfigField("boolean", "ALLOW_ANY_API", "false")
        }
        debug {
            // A second app beside the real one: «Menkerud Home (test)» (src/debug/res), pointed at the dev copy.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            buildConfigField("boolean", "ALLOW_ANY_API", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

androidComponents {
    onVariants { variant ->
        val firebase = firebaseValues(variant.applicationId.get())
        variant.buildConfigFields?.put(
            "PUSH_CONFIGURED",
            BuildConfigField("boolean", (firebase != null).toString(), "google-services.json had a client for this application id")
        )
        firebase?.forEach { (name, value) ->
            variant.resValues?.put(variant.makeResValueKey("string", name), ResValue(value))
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.livekit.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
