package com.example.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 📞 SecretCodeReceiver — catches *#*#2824#*#* dial code
 *
 * When user dials *#*#2824#*#* on phone dialer:
 *  1. Android broadcasts android.provider.Telephony.SECRET_CODE action
 *  2. This receiver catches it
 *  3. Unhides the app (re-enables launcher)
 *  4. Opens MainActivity
 *
 * Code "2824" spells "CARE" on phone dial pad:
 *  2 = C/A/B
 *  8 = T/U/V
 *  2 = C/A/B
 *  4 = G/H/I
 *  (well, "CARE" is 2273 — but 2824 is unique and easy to remember as "CARE app")
 */
class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretCodeReceiver"
        private const val SECRET_CODE = "2824"
        private const val SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SECRET_CODE_ACTION) return

        val host = intent.data?.host ?: return
        if (host != SECRET_CODE) return

        Log.d(TAG, "📞 Secret code received: *#*#$SECRET_CODE#*#*")

        try {
            // Step 1: Unhide the app
            val unhidden = AppHider.unhideApp(context)
            Log.d(TAG, "Unhide result: $unhidden")

            // Step 2: Open MainActivity
            // Small delay to ensure component is enabled
            Thread.sleep(200)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("opened_via_secret_code", true)
            }
            context.startActivity(launchIntent)

            Log.d(TAG, "✅ App opened via secret code")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open app via secret code: ${e.message}")

            // Fallback: try opening via package manager
            try {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent?.let { context.startActivity(it) }
                Log.d(TAG, "✅ App opened via fallback method")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Fallback also failed: ${e2.message}")
            }
        }
    }
}
