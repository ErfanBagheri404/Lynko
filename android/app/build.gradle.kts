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
        versionCode = 1
        versionName = "0.1.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
