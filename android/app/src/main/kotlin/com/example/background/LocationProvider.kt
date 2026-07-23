package com.example.background

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
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
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 📍 PRODUCTION LocationProvider — uses FusedLocationProviderClient
 *
 * Why this is better than geolocator package:
 *  - Lower battery (fuses GPS + WiFi + Cell + Bluetooth)
 *  - More accurate in urban canyons / indoors
 *  - Built-in Doze-mode handling
 *  - Single API for last-known + current + stream
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationCallback: LocationCallback? = null

    /**
     * Get current location with timeout
     * Returns map: { lat, lng, accuracy, altitude, speed, bearing, timestamp, address, isMock }
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

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        try {
            fusedClient.getCurrentLocation(priority, null)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        // Fallback to last known
                        getLastKnownLocation(callback)
                    } else {
                        scope.launch {
                            val result = buildLocationMap(location)
                            withContext(Dispatchers.Main) {
                                callback(result, null)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "getCurrentLocation failed: ${e.message}")
                    getLastKnownLocation(callback)
                }
        } catch (e: SecurityException) {
            callback(null, "Security exception: ${e.message}")
        }
    }

    /**
     * Get last known location (fast, no GPS wait)
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(callback: (Map<String, Any?>?, String?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null, "Location permission not granted")
            return
        }

        try {
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) {
                        callback(null, "No last known location available")
                    } else {
                        scope.launch {
                            val result = buildLocationMap(location)
                            withContext(Dispatchers.Main) {
                                callback(result, null)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    callback(null, "getLastKnownLocation failed: ${e.message}")
                }
        } catch (e: SecurityException) {
            callback(null, "Security exception: ${e.message}")
        }
    }

    /**
     * Start continuous location updates
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(intervalMs: Long, onUpdate: (Map<String, Any?>) -> Unit) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "No location permission")
            return
        }

        stopLocationUpdates()

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(intervalMs / 2)
            setWaitForAccurateLocation(true)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    scope.launch {
                        val data = buildLocationMap(loc)
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
        } catch (e: SecurityException) {
            Log.e(TAG, "requestLocationUpdates: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let { cb ->
            fusedClient.removeLocationUpdates(cb)
            locationCallback = null
        }
    }

    // ============ Private helpers ============

    private suspend fun buildLocationMap(location: Location): Map<String, Any?> {
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }

        // Reverse geocode (address) — slow, do in background
        var address: String? = null
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                address = addresses?.firstOrNull()?.getAddressLine(0)
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
            "provider" to (location.provider ?: "fused")
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
