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
 * 📍 PRODUCTION LocationProvider v4 — Multi-Source Fallback (GPS → Fused → Network → Last Known → Cached)
 *
 * Cell tower resolution has been completely removed as requested.
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

            // ⚡ Step 0: Try background location enable via Settings.Secure (if location OFF)
            var locationServiceOn = isLocationServiceEnabled()
            if (!locationServiceOn) {
                Log.d(TAG, "Location is OFF — attempting background enable via Settings.Secure")
                tryEnableLocationProgrammatically()
                locationServiceOn = isLocationServiceEnabled()
            }

            // 🔥 Strategy: try multiple sources in order
            // 1. FusedLocation (best — fuses GPS+WiFi+Cell+Bluetooth)
            result = tryFusedLocation(highAccuracy, timeoutMs.toLong())

            // 2. LocationManager.GPS_PROVIDER (if location service on)
            if (result == null && locationServiceOn) {
                Log.w(TAG, "FusedLocation failed — trying GPS_PROVIDER")
                result = tryLocationManager(LocationManager.GPS_PROVIDER, timeoutMs.toLong())
            }

            // 3. LocationManager.NETWORK_PROVIDER
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
                Log.w(TAG, "All sources failed — using app cached location")
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
                cacheLocation(result!!)
                val provider = result!!["provider"] as? String ?: ""
                Log.d(TAG, "✅ Location obtained from: $provider")
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

        val priority = if (intervalMs < 30_000) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, intervalMs).apply {
            setMinUpdateIntervalMillis(intervalMs)
            setMaxUpdateDelayMillis(intervalMs * 2)
            setMinUpdateDistanceMeters(50f)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    scope.launch {
                        val data = buildLocationMap(loc)
                        cacheLocation(data)
                        withContext(Dispatchers.Main) {
                            onUpdate(data)
                        }
                    }
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "Started location updates (interval: ${intervalMs}ms, priority: $priority)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d(TAG, "Stopped location updates")
        }
    }

    // ============ Private Location Sources ============

    @SuppressLint("MissingPermission")
    private suspend fun tryFusedLocation(highAccuracy: Boolean, timeoutMs: Long): Map<String, Any?>? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val priority = if (highAccuracy) {
                    Priority.PRIORITY_HIGH_ACCURACY
                } else {
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY
                }

                val token = com.google.android.gms.tasks.CancellationTokenSource()
                fusedClient.getCurrentLocation(priority, token.token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            scope.launch {
                                val map = buildLocationMap(loc)
                                if (cont.isActive) cont.resume(map)
                            }
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "FusedLocation error: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }

                cont.invokeOnCancellation { token.cancel() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryLocationManager(provider: String, timeoutMs: Long): Map<String, Any?>? {
        if (!locationManager.isProviderEnabled(provider)) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        locationManager.removeUpdates(this)
                        scope.launch {
                            val map = buildLocationMap(loc)
                            if (cont.isActive) cont.resume(map)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        locationManager.removeUpdates(this)
                        if (cont.isActive) cont.resume(null)
                    }
                }

                try {
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Log.w(TAG, "LocationManager $provider error: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }

                cont.invokeOnCancellation {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryFusedLastKnown(): Map<String, Any?>? {
        return suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null && isFreshLocation(loc)) {
                        scope.launch {
                            val map = buildLocationMap(loc)
                            if (cont.isActive) cont.resume(map)
                        }
                    } else {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryLocationManagerLastKnown(): Map<String, Any?>? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var bestLoc: Location? = null

        for (p in providers) {
            try {
                if (locationManager.isProviderEnabled(p)) {
                    val loc = locationManager.getLastKnownLocation(p)
                    if (loc != null && isFreshLocation(loc)) {
                        if (bestLoc == null || loc.time > bestLoc.time) {
                            bestLoc = loc
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last known $p error: ${e.message}")
            }
        }

        return bestLoc?.let { buildLocationMap(it) }
    }

    private fun tryCachedLocation(): Map<String, Any?>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var lat = prefs.getString(KEY_LAST_KNOWN_LAT, null)?.toDoubleOrNull()
            var lng = prefs.getString(KEY_LAST_KNOWN_LNG, null)?.toDoubleOrNull()
            var time = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
            var accuracy = prefs.getFloat(KEY_LAST_KNOWN_ACCURACY, 0f)
            var provider = prefs.getString(KEY_LAST_KNOWN_PROVIDER, "cached") ?: "cached"
            var address = prefs.getString(KEY_LAST_KNOWN_ADDRESS, null)

            if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) {
                Log.w(TAG, "SharedPreferences cache empty — querying system PASSIVE_PROVIDER for last known location")
                for (p in listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                    try {
                        @Suppress("DEPRECATION")
                        val loc = locationManager.getLastKnownLocation(p)
                        if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                            lat = loc.latitude
                            lng = loc.longitude
                            accuracy = loc.accuracy
                            time = loc.time
                            provider = "system_$p"
                            Log.d(TAG, "Found system last known location from $p: $lat, $lng")
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) {
                return null
            }

            val ageMs = System.currentTimeMillis() - time
            Log.d(TAG, "💾 Using last known location (${ageMs / 60000} min old): $lat, $lng")

            val resultMap = mapOf(
                "lat" to lat,
                "lng" to lng,
                "accuracy" to accuracy,
                "altitude" to 0.0,
                "speed" to 0f,
                "bearing" to 0f,
                "timestamp" to if (time > 0) time else System.currentTimeMillis(),
                "isMock" to false,
                "address" to address,
                "provider" to if (provider.startsWith("cached_")) provider else "cached_$provider",
                "isCached" to true,
                "cacheAgeMs" to ageMs,
                "locationServiceOn" to false
            )

            cacheLocation(resultMap)
            resultMap
        } catch (e: Exception) {
            Log.w(TAG, "tryCachedLocation exception: ${e.message}")
            null
        }
    }

    // ============ Helpers ============

    private fun cacheLocation(data: Map<String, Any?>) {
        try {
            val lat = (data["lat"] as? Number)?.toDouble() ?: (data["lat"] as? String)?.toDoubleOrNull() ?: return
            val lng = (data["lng"] as? Number)?.toDouble() ?: (data["lng"] as? String)?.toDoubleOrNull() ?: return
            val accuracy = (data["accuracy"] as? Number)?.toFloat() ?: 0f
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

        var address: String? = null
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = withTimeoutOrNull(3_000) {
                    withContext(Dispatchers.IO) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            suspendCancellableCoroutine { cont ->
                                geocoder.getFromLocation(
                                    location.latitude,
                                    location.longitude,
                                    1
                                ) { list ->
                                    cont.resume(list)
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        }
                    }
                }

                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    address = (0..addr.maxAddressLineIndex)
                        .map { addr.getAddressLine(it) }
                        .joinToString(", ")
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
            "provider" to location.provider,
            "isCached" to false,
            "locationServiceOn" to isLocationServiceEnabled()
        )
    }

    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationServiceEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            val mode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            mode != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    fun tryEnableLocationProgrammatically(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (!locationManager.isLocationEnabled) {
                    Log.d(TAG, "⚡ Attempting background location enable via Settings.Secure")
                    try {
                        Settings.Secure.putInt(
                            context.contentResolver,
                            Settings.Secure.LOCATION_MODE,
                            Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                        )
                    } catch (e: Exception) {
                        try {
                            @Suppress("DEPRECATION")
                            Settings.Secure.putString(
                                context.contentResolver,
                                Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                                "+gps,+network"
                            )
                        } catch (_: Exception) {}
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val mode = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
                )
                if (mode == Settings.Secure.LOCATION_MODE_OFF) {
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                    )
                }
            }
            isLocationServiceEnabled()
        } catch (e: Exception) {
            Log.w(TAG, "Background location enable attempt: ${e.message}")
            isLocationServiceEnabled()
        }
    }

    private fun isFreshLocation(loc: Location): Boolean {
        return (System.currentTimeMillis() - loc.time) < CACHE_MAX_AGE_MS
    }
}
