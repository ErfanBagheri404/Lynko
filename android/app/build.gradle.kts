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

    // Release signing comes from the persistent keystore injected by CI.
    // A release task without it fails at configuration time: a runner's
    // generated debug key would make future upgrades impossible.
    // Debug builds never read these variables.
    val ksPath: String? = System.getenv("KEYSTORE_FILE")
    val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    if (wantsRelease) {
        val missing = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release build requires the persistent signing keystore; missing: " +
                    missing.joinToString() +
                    ". Inject it via ANDROID_KEYSTORE_BASE64 (CI) or run a debug build.",
            )
        }
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Never debug-sign a release; a changed key breaks in-place upgrades.
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
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
