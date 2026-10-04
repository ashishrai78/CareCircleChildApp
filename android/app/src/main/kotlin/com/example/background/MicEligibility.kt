package com.example.background

/**
 * ============================================================
 * MicEligibility.kt — NAYI FILE (mic-fix ka core)
 * ============================================================
 *
 * ROOT PROBLEM (30-60 min wali silent-mic bug):
 *   Android 11+ ka while-in-use rule — agar app BACKGROUND me hai aur
 *   CareCircleForegroundService background se (re)start hoti hai
 *   (START_STICKY / Doze / OEM killer), to Android RECORD_AUDIO access
 *   SILENTLY deny kar deta hai:
 *     - AudioRecord START hota hai (koi exception NAHI)
 *     - WebRTC connect ho jata hai
 *     - Lekin OS har sample = 0 (pure zeros) return karta hai
 *   Isliye parent ko "connected" dikhta hai but sirf SILENCE sunti hai.
 *
 * FIX:
 *   Mic access sirf tab legal hai jab app "in use" ho = koi Activity
 *   STARTED ho. Ye class MainApplication ke ActivityLifecycleCallbacks
 *   se har onStart()/onStop() pe update hoti hai.
 *
 *   Jab parent listening request karta hai aur app background me hai,
 *   CareCircleForegroundService MicGateActivity ko 300ms ke liye launch
 *   karta hai → activity STARTED → isAppInUse() = true →
 *   startForeground(MICROPHONE) + AudioRecord ab LEGAL.
 *
 * Thread-safe hai (synchronized).
 */
object MicEligibility {

    @Volatile
    private var startedActivities = 0

    /** Har activity onStart() pe call hota hai (MainApplication se wired) */
    fun onActivityStarted() {
        synchronized(this) { startedActivities++ }
    }

    /** Har activity onStop() pe call hota hai */
    fun onActivityStopped() {
        synchronized(this) {
            if (startedActivities > 0) startedActivities--
        }
    }

    /**
     * TRUE = child app abhi "in use" hai (koi activity visible)
     *        → mic direct start kar sakte hai.
     * FALSE = app background me hai → pehle MicGateActivity chalao.
     */
    fun isAppInUse(): Boolean = synchronized(this) { startedActivities > 0 }
}
