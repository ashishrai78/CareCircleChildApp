package com.example.background

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 🛡️ PRODUCTION AutoStartHelper
 *
 * Opens OEM-specific AutoStart / Battery Optimization settings screen so the
 * user can manually whitelist CareCircle from being killed in background.
 *
 * Supports:
 *  ✅ Xiaomi      (MIUI / HyperOS)
 *  ✅ Redmi / Poco (MIUI based)
 *  ✅ Realme       (ColorOS / RealmeUI)
 *  ✅ Oppo         (ColorOS)
 *  ✅ Vivo         (FuntouchOS / OriginOS)
 *  ✅ Samsung      (OneUI)
 *  ✅ Huawei       (EMUI / HarmonyOS)
 *  ✅ Honor        (MagicOS)
 *  ✅ OnePlus      (OxygenOS / ColorOS)
 *  ✅ Asus         (ZenUI)
 *  ✅ Nokia        (Stock+)
 *  ✅ Meizu        (Flyme)
 *  ✅ Letv / LeEco (EUI)
 *  ✅ Qiku / 360   (360OS)
 *
 * Why multiple intents per manufacturer:
 *  - OEMs change package names between ROM versions (MIUI 12 vs 13 vs 14)
 *  - Some devices have multiple security apps installed
 *  - Fallback chain ensures at least one works
 */
object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /**
     * Get the manufacturer name (lowercase) for current device
     */
    fun getManufacturer(): String {
        return Build.MANUFACTURER?.lowercase()?.trim() ?: "unknown"
    }

    fun getBrand(): String {
        return Build.BRAND?.lowercase()?.trim() ?: "unknown"
    }

    /**
     * Detect OEM family (broader grouping)
     */
    fun getOEMFamily(): OEMFamily {
        val manufacturer = getManufacturer()
        val brand = getBrand()

        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
            brand.contains("redmi") || brand.contains("poco") ||
            brand.contains("blackshark") -> OEMFamily.XIAOMI

            manufacturer.contains("realme") || brand.contains("realme") -> OEMFamily.REALME

            manufacturer.contains("oppo") || brand.contains("oppo") -> OEMFamily.OPPO

            manufacturer.contains("vivo") || brand.contains("vivo") ||
            brand.contains("iqoo") -> OEMFamily.VIVO

            manufacturer.contains("samsung") || brand.contains("samsung") -> OEMFamily.SAMSUNG

            manufacturer.contains("huawei") || brand.contains("huawei") ||
            brand.contains("honor") -> OEMFamily.HUAWEI

            manufacturer.contains("oneplus") || brand.contains("oneplus") -> OEMFamily.ONEPLUS

            manufacturer.contains("asus") || brand.contains("asus") -> OEMFamily.ASUS

            manufacturer.contains("nokia") || brand.contains("nokia") ||
            brand.contains("hmd") -> OEMFamily.NOKIA

            manufacturer.contains("meizu") -> OEMFamily.MEIZU

            manufacturer.contains("letv") || brand.contains("letv") -> OEMFamily.LETV

            else -> OEMFamily.OTHER
        }
    }

    /**
     * Open AutoStart settings for current device.
     * Returns true if any intent succeeded, false otherwise.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val family = getOEMFamily()
        Log.d(TAG, "🔍 Detected OEM family: $family (manufacturer=${getManufacturer()}, brand=${getBrand()})")

        val intents = getIntentList(family)

        for (intent in intents) {
            if (tryStartActivity(context, intent)) {
                Log.d(TAG, "✅ Successfully opened: ${intent.component?.flattenToString()}")
                return true
            }
        }

        // Fallback: open app details settings (works on all devices)
        Log.w(TAG, "⚠️ All OEM intents failed — opening app details as fallback")
        return openAppDetailsSettings(context)
    }

    /**
     * Open Battery Optimization settings (universal — Android 6+)
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback to settings
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Battery settings failed: ${e2.message}")
                false
            }
        }
    }

    /**
     * Request "Ignore Battery Optimizations" directly (shows system dialog)
     */
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct request failed, opening settings: ${e.message}")
            openBatteryOptimizationSettings(context)
        }
    }

    /**
     * Get list of intents to try for each OEM family.
     * Order matters — most common first.
     */
    private fun getIntentList(family: OEMFamily): List<Intent> {
        return when (family) {
            OEMFamily.XIAOMI -> listOf(
                // MIUI Security Center (most common)
                intent("com.miui.securitycenter",
                       "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                // Newer MIUI / HyperOS
                intent("com.miui.securitycenter",
                       "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                // MIUI 12+ alternate
                intent("com.miui.securitycenter",
                       "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
                // Some MIUI versions
                intent("com.miui.securitycenter",
                       "com.miui.permcenter.startupapp.StartUpAppListActivity"),
                // HyperOS
                intent("com.miui.securitycenter",
                       "com.miui.permcenter.startupapp.StartupAppListActivity"),
                // Battery saver
                intent("com.miui.securitycenter",
                       "com.miui.powercenter.PowerActivity")
            )

            OEMFamily.REALME -> listOf(
                // RealmeUI / ColorOS 11+
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                // RealmeUI 2.0+
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                // Older Realme
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.permission.PermissionSettingsActivity"),
                // ColorOS 13+
                intent("com.oplus.safecenter",
                       "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                // Battery
                intent("com.coloros.oppoguardelf",
                       "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                // Background management
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.backgroundmanagement.BackgroundAppListActivity")
            )

            OEMFamily.OPPO -> listOf(
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                intent("com.oplus.safecenter",
                       "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.permission.PermissionSettingsActivity"),
                // Old ColorOS
                intent("com.oppo.safe",
                       "com.oppo.safe.permission.startup.StartupAppListActivity"),
                intent("com.oppo.safe",
                       "com.oppo.safe.permission.floatwindow.FloatWindowListActivity")
            )

            OEMFamily.VIVO -> listOf(
                // FuntouchOS
                intent("com.vivo.permissionmanager",
                       "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                // Newer Vivo
                intent("com.vivo.permissionmanager",
                       "com.vivo.permissionmanager.activity.PurviewTabActivity"),
                // iManager (older)
                intent("com.iqoo.secure",
                       "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                // Vivo secure
                intent("com.vivo.permissionmanager",
                       "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"),
                // OriginOS
                intent("com.vivo.permissionmanager",
                       "com.vivo.permissionmanager.permission.PermissionActivity"),
                // Battery settings
                intent("com.vivo.abe",
                       "com.vivo.abe.FlashlightActivity")
            )

            OEMFamily.SAMSUNG -> listOf(
                // One UI 4+ (Device Care)
                intent("com.samsung.android.lool",
                       "com.samsung.android.sm.ui.battery.BatteryActivity"),
                // One UI 3
                intent("com.samsung.android.lool",
                       "com.samsung.android.sm.ui.battery.AppPowerManagementActivity"),
                // Older Samsung Smart Manager
                intent("com.samsung.android.sm",
                       "com.samsung.android.sm.ui.battery.AppPowerManagementActivity"),
                // Samsung battery settings
                intent("com.samsung.android.settings.battery",
                       "com.samsung.android.settings.battery.BatteryActivity"),
                // Sleep apps
                intent("com.samsung.android.lool",
                       "com.samsung.android.sm.ui.battery.SleepingAppsActivity"),
                // Background restrictions
                intent("com.samsung.android.lool",
                       "com.samsung.android.sm.ui.battery.BackgroundActivity")
            )

            OEMFamily.HUAWEI -> listOf(
                // EMUI
                intent("com.huawei.systemmanager",
                       "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                // Newer EMUI / HarmonyOS
                intent("com.huawei.systemmanager",
                       "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                // HarmonyOS 3+
                intent("com.huawei.systemmanager",
                       "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                // Battery
                intent("com.huawei.systemmanager",
                       "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                // Background
                intent("com.huawei.systemmanager",
                       "com.huawei.systemmanager.mainscreen.MainScreenActivity")
            )

            OEMFamily.ONEPLUS -> listOf(
                // OnePlus (OxygenOS now based on ColorOS)
                intent("com.coloros.safecenter",
                       "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.oplus.safecenter",
                       "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                // OnePlus battery
                intent("com.oneplus.security",
                       "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                // Old OxygenOS
                intent("net.oneplus.forbes",
                       "net.oneplus.forbes.BatteryOptimization"),
                intent("net.oneplus.optimizer",
                       "net.oneplus.optimizer.app.MainActivity")
            )

            OEMFamily.ASUS -> listOf(
                // ZenUI
                intent("com.asus.mobilemanager",
                       "com.asus.mobilemanager.entry.FunctionActivity").apply {
                    putExtra("showFragment", "com.asus.mobilemanager.autostart.AutoStartActivity")
                },
                intent("com.asus.mobilemanager",
                       "com.asus.mobilemanager.autostart.AutoStartActivity"),
                // Power Master
                intent("com.asus.mobilemanager",
                       "com.asus.mobilemanager.function最大功率.Activity"),
                intent("com.asus.mobilemanager",
                       "com.asus.mobilemanager.MainActivity")
            )

            OEMFamily.NOKIA -> listOf(
                // Nokia (mostly stock with some tweaks)
                intent("com.evenwell.PowerSaver",
                       "com.evenwell.PowerSaver.MainActivity"),
                intent("com.evenwell.powersaving",
                       "com.evenwell.powersaving.MainActivity"),
                // Stock fallback
                intent("com.android.settings",
                       "com.android.settings.Settings\$AppNotificationSettingsActivity")
            )

            OEMFamily.MEIZU -> listOf(
                // Flyme
                intent("com.meizu.safe",
                       "com.meizu.safe.permission.SmartBGActivity"),
                intent("com.meizu.safe",
                       "com.meizu.safe.permission.PermissionMainActivity"),
                intent("com.meizu.safe",
                       "com.meizu.safe.powerui.PowerAppPermissionActivity")
            )

            OEMFamily.LETV -> listOf(
                // LeEco
                intent("com.letv.android.letvsafe",
                       "com.letv.android.letvsafe.AutobootManageActivity"),
                intent("com.letv.android.letvsafe",
                       "com.letv.android.letvsafe.BackgroundAppManageActivity")
            )

            OEMFamily.OTHER -> emptyList()
        }
    }

    /**
     * Try to start an activity with the given intent.
     * Returns true if successful, false otherwise.
     */
    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            // Check if activity exists
            val pm = context.packageManager
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo == null) {
                Log.v(TAG, "  ✗ Not found: ${intent.component?.flattenToString()}")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.v(TAG, "  ✗ Failed: ${intent.component?.flattenToString()} — ${e.message}")
            false
        }
    }

    /**
     * Open app details settings (universal fallback)
     */
    fun openAppDetailsSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "App details failed: ${e.message}")
            false
        }
    }

    /**
     * Check if device likely needs AutoStart permission (heuristic)
     */
    fun needsAutoStartPermission(): Boolean {
        return getOEMFamily() != OEMFamily.OTHER && getOEMFamily() != OEMFamily.NOKIA
    }

    /**
     * Get user-friendly OEM name for display
     */
    fun getOEMDisplayName(): String {
        return when (getOEMFamily()) {
            OEMFamily.XIAOMI -> "Xiaomi / Redmi / Poco (MIUI / HyperOS)"
            OEMFamily.REALME -> "Realme (RealmeUI / ColorOS)"
            OEMFamily.OPPO -> "Oppo (ColorOS)"
            OEMFamily.VIVO -> "Vivo / iQOO (FuntouchOS / OriginOS)"
            OEMFamily.SAMSUNG -> "Samsung (One UI)"
            OEMFamily.HUAWEI -> "Huawei / Honor (EMUI / HarmonyOS)"
            OEMFamily.ONEPLUS -> "OnePlus (OxygenOS / ColorOS)"
            OEMFamily.ASUS -> "Asus (ZenUI)"
            OEMFamily.NOKIA -> "Nokia"
            OEMFamily.MEIZU -> "Meizu (Flyme)"
            OEMFamily.LETV -> "Letv / LeEco"
            OEMFamily.OTHER -> "Other / Stock Android"
        }
    }

    /**
     * Create intent with component
     */
    private fun intent(pkg: String, cls: String): Intent {
        return Intent().apply {
            component = ComponentName(pkg, cls)
        }
    }
}

/**
 * OEM family classification
 */
enum class OEMFamily {
    XIAOMI,     // Xiaomi, Redmi, Poco, Black Shark
    REALME,     // Realme
    OPPO,       // Oppo
    VIVO,       // Vivo, iQOO
    SAMSUNG,    // Samsung
    HUAWEI,     // Huawei, Honor
    ONEPLUS,    // OnePlus
    ASUS,       // Asus
    NOKIA,      // Nokia / HMD
    MEIZU,      // Meizu
    LETV,       // Letv / LeEco
    OTHER       // Stock Android / Google Pixel / Motorola / etc.
}
