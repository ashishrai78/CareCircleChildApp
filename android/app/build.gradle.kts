plugins {
    id("com.android.application")
    // START: FlutterFire Configuration
    id("com.google.gms.google-services")
    // END: FlutterFire Configuration
    id("kotlin-android")
    id("kotlin-kapt")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.background"
    compileSdk = 36   // ✅ Android 16 (required by connectivity_plus, flutter_webrtc, google_sign_in_android, path_provider_android)
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.example.background"
        minSdk = 24          // ✅ Android 7.0+ (covers 99% market)
        targetSdk = 36       // ✅ Android 16 (matches compileSdk — Play Store compliant)
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        multiDexEnabled = true

        // ✅ Native code architecture filters (smaller APK)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // TODO: Replace with your own release keystore before production launch
            signingConfig = signingConfigs.getByName("debug")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    // ✅ WebRTC has conflicting duplicates
    configurations {
        all {
            exclude(group = "com.google.android.gms", module = "play-services-places")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {

    // ============ Core Library Desugaring (java.time on old Android) ============
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // ============ MultiDex ============
    implementation("androidx.multidex:multidex:2.0.1")

    // ============ Kotlin Coroutines (for async native calls) ============
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // ============ AndroidX Lifecycle (for service lifecycle) ============
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // ============ WorkManager 2.9+ (background tasks) ============
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ============ Google Play Services - Location (FusedLocationProviderClient) ============
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ============ Firebase (auto-provided by FlutterFire plugins — DO NOT add manually) ============
    // NOTE: firebase_core, firebase_auth, cloud_firestore, firebase_storage, firebase_messaging
    // Flutter plugins automatically add their native Android dependencies.
    // Adding them here causes "Could not find com.google.firebase:firebase-firestore-ktx:" errors.

    // ============ JSON serialization ============
    implementation("com.google.code.gson:gson:2.11.0")

    // ============ AndroidX Core (for NotificationCompat, etc.) ============
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ============ Firebase (native — for WatchdogService direct Firestore access) ============
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-common")


}
