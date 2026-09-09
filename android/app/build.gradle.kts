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
        create("bazaar") {
            dimension = "store"
            applicationId = "com.lynko.app.bazaar"
        }
        create("myket") {
            dimension = "store"
            applicationId = "com.lynko.app.myket"
        }
        create("play") {
            dimension = "store"
            applicationId = "com.lynko.app.play"
        }
    }

    // Release signing from env (CI). Without a keystore we fall back to the
    // debug key so CI artifacts remain installable for sideloading.
    val ksPath: String? = System.getenv("KEYSTORE_FILE")

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
