package com.example.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * 🌐 NetworkUtils — Utility to check active network & internet connectivity
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Check if the device has an active internet connection (Wi-Fi, Cellular, or Ethernet)
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } else {
                @Suppress("DEPRECATION")
                val netInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                netInfo != null && netInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network: ${e.message}")
            false
        }
    }
}
