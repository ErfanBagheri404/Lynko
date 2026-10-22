import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lynko.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lynko.app"
        minSdk = 26
        targetSdk = 35
        // CI passes -PandroidVersionCode / -PandroidVersionName; local defaults below.
        versionCode = (project.findProperty("androidVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("androidVersionName") as String?) ?: "0.1.0"
    }

    // Per-ABI APKs (plus a fat universal one) for smaller sideload downloads.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildToolsVersion = "35.0.0"

    flavorDimensions += "store"
    productFlavors {
        create("base") {
            dimension = "store"
            isDefault = true
        }
    }

    // Release signing: read android/keystore/keystore.properties (gitignored)
    // first, with env vars taking precedence so CI keeps working unchanged.
    // Without either we fall back to the debug key so CI artifacts remain
    // installable for sideloading.
    val signingProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    fun signing(envName: String, propName: String): String? =
        System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: signingProps.getProperty(propName)?.takeIf { it.isNotBlank() }

    val ksPath: String? = signing("KEYSTORE_FILE", "storeFile")?.let { raw ->
        val f = File(raw)
        if (f.isAbsolute) f.absolutePath else rootProject.file(raw).absolutePath
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = signing("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("KEY_ALIAS", "keyAlias")
                keyPassword = signing("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (ksPath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pure-Java, zero transitive deps
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
