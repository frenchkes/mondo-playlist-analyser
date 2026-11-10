plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mondo.dj"
    compileSdk = 35
    defaultConfig {
        // Use a fresh package id so Android cannot confuse this install with
        // any earlier MONDO package/signature left in another profile.
        applicationId = "com.kesmond.mondo"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("mondo-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
