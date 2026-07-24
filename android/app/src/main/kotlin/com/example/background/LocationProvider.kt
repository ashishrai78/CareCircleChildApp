package com.example.background

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 📍 PRODUCTION LocationProvider v2 — multi-source fallback
 *
 * Critical fixes vs v1:
 *  1. Multi-source fallback: FusedLocation → LocationManager(GPS) → LocationManager(NETWORK) → cached
 *  2. Adaptive priority based on interval (battery-friendly)
 *  3. Async Geocoder with 3s timeout (was blocking)
 *  4. Background location permission check (Android 10+)
 *  5. Location service OFF detection — tries all fallbacks before giving up
 *  6. Cached last-known location in SharedPreferences (survives service restart)
 *
 * Why multi-source:
 *  - Realme/Xiaomi FusedLocation returns null when GPS toggle is OFF
 *  - LocationManager.NETWORK_PROVIDER still works (uses cell towers)
 *  - Last cached location is better than no location
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val PREFS_NAME = "carecircle_prefs"
        private const val KEY_LAST_KNOWN_LAT = "last_known_lat"
        private const val KEY_LAST_KNOWN_LNG = "last_known_lng"
        private const val KEY_LAST_KNOWN_TIME = "last_known_time"
        private const val KEY_LAST_KNOWN_ACCURACY = "last_known_accuracy"
        private const val KEY_LAST_KNOWN_PROVIDER = "last_known_provider"
        private const val KEY_LAST_KNOWN_ADDRESS = "last_known_address"
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationCallback: LocationCallback? = null
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Get current location with multi-source fallback.
     *
     * Returns map: { lat, lng, accuracy, altitude, speed, bearing, timestamp, address,
     *               isMock, provider, isCached, locationServiceOn }
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(
        highAccuracy: Boolean,
        timeoutMs: Int,
        callback: (Map<String, Any?>?, String?) -> Unit
    ) {
        if (!hasLocationPermission()) {
            callback(null, "Location permission not granted")
            return
        }

        scope.launch {
            var result: Map<String, Any?>? = null
            var error: String? = null
            val locationServiceOn = isLocationServiceEnabled()

            // 🔥 Strategy: try multiple sources in order
            // 1. FusedLocation (best — fuses GPS+WiFi+Cell+Bluetooth)
            result = tryFusedLocation(highAccuracy, timeoutMs.toLong())

            // 2. LocationManager.GPS_PROVIDER (if location service on)
            if (result == null && locationServiceOn) {
                Log.w(TAG, "FusedLocation failed — trying GPS_PROVIDER")
                result = tryLocationManager(LocationManager.GPS_PROVIDER, timeoutMs.toLong())
            }

            // 3. LocationManager.NETWORK_PROVIDER (works even if GPS off, uses cell towers)
            if (result == null) {
                Log.w(TAG, "GPS failed — trying NETWORK_PROVIDER")
                result = tryLocationManager(LocationManager.NETWORK_PROVIDER, timeoutMs.toLong())
            }

            // 4. FusedLocation last known (cached by Google Play Services)
            if (result == null) {
                Log.w(TAG, "All live sources failed — trying FusedLocation last known")
                result = tryFusedLastKnown()
            }

            // 5. LocationManager last known (cached by Android system)
            if (result == null) {
                Log.w(TAG, "Fused last known failed — trying LocationManager last known")
                result = tryLocationManagerLastKnown()
            }

            // 6. App's own cached location (from SharedPreferences)
            if (result == null) {
                Log.w(TAG, "All system sources failed — using app cached location")
                result = tryCachedLocation()
            }

            // Add metadata
            result = result?.let {
                it.toMutableMap().apply {
                    put("locationServiceOn", locationServiceOn)
                    if (get("isCached") == null) put("isCached", false)
                }
            }

            if (result == null) {
                error = if (!locationServiceOn) {
                    "Location service is OFF and no cached location available"
                } else {
                    "All location sources failed"
                }
                Log.e(TAG, "❌ $error")
            } else {
                // 🔥 Cache successful location for future use
                cacheLocation(result!!)
                Log.d(TAG, "✅ Location obtained from: ${result!!["provider"]}")
            }

            withContext(Dispatchers.Main) {
                callback(result, error)
            }
        }
    }

    /**
     * Get last known location (fast, no GPS wait) — multi-source
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(callback: (Map<String, Any?>?, String?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null, "Location permission not granted")
            return
        }

        scope.launch {
            var result: Map<String, Any?>? = tryFusedLastKnown()

            if (result == null) {
                result = tryLocationManagerLastKnown()
            }

            if (result == null) {
                result = tryCachedLocation()
            }

            result = result?.let {
                it.toMutableMap().apply {
                    put("locationServiceOn", isLocationServiceEnabled())
                    if (get("isCached") == null) put("isCached", false)
                }
            }

            val error = if (result == null) "No last known location available" else null

            withContext(Dispatchers.Main) {
                callback(result, error)
            }
        }
    }

    /**
     * Start continuous location updates — adaptive priority
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(intervalMs: Long, onUpdate: (Map<String, Any?>) -> Unit) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "No location permission")
            return
        }

        stopLocationUpdates()

        // 🔥 Adaptive: high accuracy for short intervals, balanced for long
        val priority = if (intervalMs < 30_000) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, intervalMs).apply {
            setMinUpdateIntervalMillis(intervalMs)
            setMaxUpdateDelayMillis(intervalMs * 2)
            setMinUpdateDistanceMeters(50f)  // 🔥 Skip updates if not moved 50m
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    scope.launch {
                        val data = buildLocationMap(loc)
                        cacheLocation(data)  // 🔥 Cache for fallback
                        withContext(Dispatchers.Main) {
                            onUpdate(data)
                        }
                    }
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Location updates started (interval=${intervalMs}ms, priority=$priority)")
        } catch (e: SecurityException) {
            Log.e(TAG, "requestLocationUpdates: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let { cb ->
            try {
                fusedClient.removeLocationUpdates(cb)
            } catch (e: Exception) {
                Log.e(TAG, "removeLocationUpdates failed: ${e.message}")
            }
            locationCallback = null
        }
    }

    // ============ Source strategies ============

    @SuppressLint("MissingPermission")
    private suspend fun tryFusedLocation(highAccuracy: Boolean, timeoutMs: Long): Map<String, Any?>? {
        return try {
            val priority = if (highAccuracy) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }

            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Map<String, Any?>?> { cont ->
                    fusedClient.getCurrentLocation(priority, null)
                        .addOnSuccessListener { location ->
                            if (cont.isActive) {
                                if (location == null) {
                                    cont.resume(null)
                                } else {
                                    scope.launch {
                                        val data = buildLocationMap(location)
                                        if (cont.isActive) cont.resume(data)
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "FusedLocation getCurrentLocation failed: ${e.message}")
                            if (cont.isActive) cont.resume(null)
                        }
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "tryFusedLocation exception: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryFusedLastKnown(): Map<String, Any?>? {
        return try {
            withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine<Map<String, Any?>?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { location ->
                            if (cont.isActive) {
                                if (location == null) {
                                    cont.resume(null)
                                } else {
                                    scope.launch {
                                        val data = buildLocationMap(location)
                                        if (cont.isActive) cont.resume(data)
                                    }
                                }
                            }
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryLocationManager(provider: String, timeoutMs: Long): Map<String, Any?>? {
        return try {
            if (!locationManager.isProviderEnabledSafe(provider)) return null

            // Single update request
            val location = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) {
                                cont.resume(location)
                            }
                            try {
                                locationManager.removeUpdates(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "removeUpdates failed: ${e.message}")
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}

                        override fun onProviderEnabled(p: String) {}

                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    try {
                        locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestSingleUpdate failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }

            location?.let { buildLocationMap(it) }
        } catch (e: Exception) {
            Log.w(TAG, "tryLocationManager($provider) exception: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryLocationManagerLastKnown(): Map<String, Any?>? {
        return try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                if (!locationManager.isProviderEnabledSafe(provider)) continue

                val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    locationManager.getLastKnownLocation(provider)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.getLastKnownLocation(provider)
                }

                if (location != null) {
                    return buildLocationMap(location)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "tryLocationManagerLastKnown exception: ${e.message}")
            null
        }
    }

    private fun tryCachedLocation(): Map<String, Any?>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lat = prefs.getString(KEY_LAST_KNOWN_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString(KEY_LAST_KNOWN_LNG, null)?.toDoubleOrNull() ?: return null
            val time = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
            val accuracy = prefs.getFloat(KEY_LAST_KNOWN_ACCURACY, 0f)
            val provider = prefs.getString(KEY_LAST_KNOWN_PROVIDER, "cached") ?: "cached"
            val address = prefs.getString(KEY_LAST_KNOWN_ADDRESS, null)

            val ageMs = System.currentTimeMillis() - time
            if (ageMs > CACHE_MAX_AGE_MS) {
                Log.w(TAG, "Cached location too old (${ageMs / 3600000}h) — discarding")
                return null
            }

            mapOf(
                "lat" to lat,
                "lng" to lng,
                "accuracy" to accuracy,
                "altitude" to 0.0,
                "speed" to 0f,
                "bearing" to 0f,
                "timestamp" to time,
                "isMock" to false,
                "address" to address,
                "provider" to "cached_$provider",
                "isCached" to true,
                "cacheAgeMs" to ageMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "tryCachedLocation exception: ${e.message}")
            null
        }
    }

    // ============ Helpers ============

    private fun cacheLocation(data: Map<String, Any?>) {
        try {
            val lat = data["lat"] as? Double ?: return
            val lng = data["lng"] as? Double ?: return
            val accuracy = (data["accuracy"] as? Float) ?: 0f
            val provider = data["provider"] as? String ?: "unknown"
            val address = data["address"] as? String

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_KNOWN_LAT, lat.toString())
                .putString(KEY_LAST_KNOWN_LNG, lng.toString())
                .putLong(KEY_LAST_KNOWN_TIME, System.currentTimeMillis())
                .putFloat(KEY_LAST_KNOWN_ACCURACY, accuracy)
                .putString(KEY_LAST_KNOWN_PROVIDER, provider)
                .apply()

            address?.let {
                prefs.edit().putString(KEY_LAST_KNOWN_ADDRESS, it).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cacheLocation failed: ${e.message}")
        }
    }

    private suspend fun buildLocationMap(location: Location): Map<String, Any?> {
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }

        // 🔥 Async Geocoder with 3s timeout
        var address: String? = null
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())

                address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ async API
                    withTimeoutOrNull(3_000) {
                        suspendCancellableCoroutine { cont ->
                            geocoder.getFromLocation(
                                location.latitude, location.longitude, 1
                            ) { addresses ->
                                if (cont.isActive) {
                                    cont.resume(addresses?.firstOrNull()?.getAddressLine(0))
                                }
                            }
                        }
                    }
                } else {
                    // Legacy sync API with timeout
                    withTimeoutOrNull(3_000) {
                        withContext(Dispatchers.IO) {
                            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            addresses?.firstOrNull()?.getAddressLine(0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
        }

        return mapOf(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "accuracy" to location.accuracy,
            "altitude" to location.altitude,
            "speed" to location.speed,
            "bearing" to location.bearing,
            "timestamp" to location.time,
            "isMock" to isMock,
            "address" to address,
            "provider" to (location.provider ?: "fused"),
            "isCached" to false
        )
    }

    private fun hasLocationPermission(): Boolean {
        val hasFine = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // 🔥 Background location check (Android 10+)
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return (hasFine || hasCoarse) && hasBackground
    }

    /**
     * 🔥 Check if any location service (GPS or Network) is enabled
     */
    private fun isLocationServiceEnabled(): Boolean {
        return try {
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gpsEnabled || networkEnabled
        } catch (e: Exception) {
            // Fallback: check Settings
            try {
                val mode = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
                )
                mode != Settings.Secure.LOCATION_MODE_OFF
            } catch (e2: Exception) {
                true  // Assume enabled if can't determine
            }
        }
    }

    /**
     * Safe extension to check if provider is enabled (catches SecurityException)
     */
    private fun LocationManager.isProviderEnabledSafe(provider: String): Boolean {
        return try {
            isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }
    }
}