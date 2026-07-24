package com.example.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives ACCESSIBILITY_REVOKED broadcast from native services.
 * Declared statically in AndroidManifest — survives app process death.
 */
class AccessibilityRevokedReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.example.background.ACCESSIBILITY_REVOKED"

        // Static reference to current Flutter event sink (set from MainActivity)
        @Volatile
        var sinkCallback: ((String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.w("ACCESS_REVOKED_RX", "⚠️ Accessibility revoked broadcast received")
        sinkCallback?.invoke("ACCESSIBILITY_REVOKED")
    }
}