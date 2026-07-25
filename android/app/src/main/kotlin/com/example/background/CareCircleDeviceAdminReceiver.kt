package com.example.background

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 🛡️ CareCircle Device Admin Receiver
 *
 * Makes CareCircle a Device Administrator — provides:
 *  ✅ Protection against uninstall (child can't easily remove app)
 *  ✅ Protection against force-stop
 *  ✅ Protection against clear-data
 *  ✅ Remote lock capability (parent can lock child's device)
 *  ✅ Disable camera capability
 *  ✅ Anti-uninstall: even if accessibility is revoked, app stays protected
 *
 * OEM behavior (Realme/Xiaomi/OPPO):
 *  - Device Admin apps are treated as "system-critical"
 *  - Accessibility services of Device Admin apps are LESS likely to be auto-revoked
 *  - Battery optimization is less aggressive
 */
class CareCircleDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DEVICE_ADMIN"

        /**
         * Get ComponentName for this receiver (used to check admin status)
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, CareCircleDeviceAdminReceiver::class.java)
        }

        /**
         * Check if Device Admin is currently enabled
         */
        fun isEnabled(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as android.app.admin.DevicePolicyManager
                dpm.isAdminActive(getComponentName(context))
            } catch (e: Exception) {
                Log.e(TAG, "isEnabled check failed: ${e.message}")
                false
            }
        }

        /**
         * Open system Device Admin settings to enable this app
         * Returns true if intent launched successfully
         */
        fun openEnableScreen(context: Context): Boolean {
            return try {
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        getComponentName(context)
                    )
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "CareCircle requires Device Admin to protect your child's device from being tampered with. " +
                                "This prevents the app from being uninstalled, force-stopped, or having its data cleared."
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Device Admin enable screen: ${e.message}")
                false
            }
        }

        /**
         * Disable Device Admin (used during unpair/reset)
         */
        fun disable(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as android.app.admin.DevicePolicyManager
                dpm.removeActiveAdmin(getComponentName(context))
                Log.d(TAG, "Device Admin disabled")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable Device Admin: ${e.message}")
                false
            }
        }
    }

    /**
     * Called when Device Admin is enabled by user
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "✅ Device Admin ENABLED")
        Toast.makeText(context, "CareCircle protection activated", Toast.LENGTH_SHORT).show()

        // 🔥 Broadcast to Flutter app — refresh UI
        try {
            val broadcastIntent = Intent("com.example.background.DEVICE_ADMIN_STATE_CHANGED")
            broadcastIntent.putExtra("enabled", true)
            context.sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }
    }

    /**
     * 🔥 Called when user tries to disable Device Admin
     * Return a warning message — system shows it in dialog
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "⚠️ User attempting to disable Device Admin")
        return "⚠️ WARNING: Disabling CareCircle Device Admin will:\n\n" +
                "• Stop child monitoring and location tracking\n" +
                "• Allow the app to be uninstalled\n" +
                "• Disable remote lock and protection features\n\n" +
                "Are you sure you want to disable child protection?"
    }

    /**
     * Called when Device Admin is actually disabled
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "❌ Device Admin DISABLED")
        Toast.makeText(context, "CareCircle protection disabled", Toast.LENGTH_SHORT).show()

        // 🔥 Broadcast to Flutter app — refresh UI
        try {
            val broadcastIntent = Intent("com.example.background.DEVICE_ADMIN_STATE_CHANGED")
            broadcastIntent.putExtra("enabled", false)
            context.sendBroadcast(broadcastIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d(TAG, "Password changed on device")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Password attempt failed")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.d(TAG, "Password attempt succeeded")
    }
}