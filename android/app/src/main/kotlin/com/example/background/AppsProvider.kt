package com.example.background

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 📱 PRODUCTION AppsProvider — uses native PackageManager
 *
 * Why this is better than installed_apps package:
 *  - Native control over icon size & compression
 *  - Better filtering (system apps, launchable only, etc.)
 *  - Returns version, install source, category — all in one call
 *  - Compressed base64 (smaller, fits in Firestore)
 */
class AppsProvider(private val context: Context) {

    companion object {
        private const val TAG = "AppsProvider"
        private const val ICON_PX = 96  // 96x96px — small but visible, ~3KB base64
    }

    fun getInstalledApps(withIcons: Boolean, excludeSystem: Boolean): List<Map<String, Any?>> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val result = mutableListOf<Map<String, Any?>>()

        for (appInfo in packages) {
            try {
                // Skip system apps if requested
                if (excludeSystem && isSystemApp(appInfo)) continue

                // Skip apps without launcher intent (no UI)
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null && !isSystemApp(appInfo)) continue

                val pkgInfo = pm.getPackageInfo(appInfo.packageName, 0)

                val appData = mutableMapOf<String, Any?>(
                    "packageName" to appInfo.packageName,
                    "name" to pm.getApplicationLabel(appInfo).toString(),
                    "versionName" to (pkgInfo.versionName ?: ""),
                    "versionCode" to if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pkgInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkgInfo.versionCode.toLong()
                    },
                    "category" to categoryToString(appInfo.category),
                    "systemApp" to isSystemApp(appInfo),
                    "installedAt" to pkgInfo.firstInstallTime,
                    "updatedAt" to pkgInfo.lastUpdateTime,
                    "enabled" to appInfo.enabled,
                    "minSdk" to appInfo.minSdkVersion,
                    "targetSdk" to appInfo.targetSdkVersion
                )

                if (withIcons) {
                    appData["icon"] = getAppIconBase64(appInfo.packageName)
                }

                result.add(appData)

            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${appInfo.packageName}: ${e.message}")
            }
        }

        Log.d(TAG, "Returning ${result.size} apps (icons=$withIcons, excludeSystem=$excludeSystem)")
        return result
    }

    fun getAppIconBase64(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, ICON_PX)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Icon fetch failed for $packageName: ${e.message}")
            null
        }
    }

    // ============ Private helpers ============

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun categoryToString(category: Int): String {
        return when (category) {
            ApplicationInfo.CATEGORY_GAME -> "GAME"
            ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
            ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
            ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
            ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
            ApplicationInfo.CATEGORY_NEWS -> "NEWS"
            ApplicationInfo.CATEGORY_MAPS -> "MAPS"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> "ACCESSIBILITY"
            // CATEGORY_FINANCE removed — not available in all SDK versions
            else -> "OTHER"
        }
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        return if (drawable is BitmapDrawable) {
            Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        } else {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }
}
