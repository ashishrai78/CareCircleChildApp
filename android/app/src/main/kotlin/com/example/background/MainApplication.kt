package com.example.background

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * ============================================================
 * MainApplication.kt — NAYI FILE
 * ============================================================
 *
 * ⭐ KAAM: har activity ke onStart()/onStop() ko MicEligibility me
 * track karna — bina iske MicGate kaam nahi karega.
 *
 * MANIFEST CHANGE (patched manifest me ho chuka):
 *   android:name="${applicationName}"  →  android:name=".MainApplication"
 *
 * FLUTTER COMPATIBILITY:
 *   Plain Application class kaafi hai (Flutter 2+ me plugin registration
 *   FlutterActivity.configureFlutterEngine se hota hai, Application se
 *   nahi). Tumhare saare MethodChannels / GeneratedPluginRegistrant
 *   waise hi chalenge.
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ============================================================
        // ⭐ MicEligibility tracking — mic-fix ka foundation
        // ============================================================
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                MicEligibility.onActivityStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                MicEligibility.onActivityStopped()
            }

            // Baaki callbacks — no-op, but interface ke liye zaroori
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // ============================================================
        // 🌐 Network & Offline Sync Monitoring
        // ============================================================
        try {
            NetworkStateMonitor.start(this)
        } catch (e: Exception) {
            Log.e("CC_APP", "Failed to start NetworkStateMonitor: ${e.message}")
        }

        Log.d("CC_APP", "MainApplication ready — MicEligibility & NetworkStateMonitor wired")
    }
}
