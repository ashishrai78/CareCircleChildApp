package com.example.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * 📡 NetworkStateMonitor — Listens for network/internet connectivity changes in real-time.
 * As soon as the device connects to Mobile Data or Wi-Fi, it triggers OfflineSyncManager
 * to push all locally cached notifications and call logs to Firebase Firestore.
 */
object NetworkStateMonitor {

    private const val TAG = "NetworkStateMonitor"

    @Volatile
    private var isRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network connectivity changes
     */
    fun start(context: Context) {
        if (isRegistered) return

        synchronized(this) {
            if (isRegistered) return

            try {
                val appContext = context.applicationContext
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "🌐 Internet connection available (onAvailable)!")
                        OfflineSyncManager.triggerSync(appContext, reason = "network_available")
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            Log.d(TAG, "🌐 Internet capability validated (onCapabilitiesChanged)!")
                            OfflineSyncManager.triggerSync(appContext, reason = "network_capabilities_validated")
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.d(TAG, "📵 Network connection lost")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(networkCallback!!)
                } else {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(request, networkCallback!!)
                }

                isRegistered = true
                Log.d(TAG, "✅ NetworkStateMonitor registered successfully")

                // Initial check: if already online, trigger sync for any existing offline data
                if (NetworkUtils.isNetworkAvailable(appContext)) {
                    OfflineSyncManager.triggerSync(appContext, reason = "initial_online_check")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to register NetworkStateMonitor: ${e.message}")
            }
        }
    }

    /**
     * Stop monitoring network connectivity changes
     */
    fun stop(context: Context) {
        if (!isRegistered) return

        synchronized(this) {
            if (!isRegistered) return

            try {
                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                networkCallback?.let { cm?.unregisterNetworkCallback(it) }
                networkCallback = null
                isRegistered = false
                Log.d(TAG, "NetworkStateMonitor stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister NetworkStateMonitor: ${e.message}")
            }
        }
    }
}
