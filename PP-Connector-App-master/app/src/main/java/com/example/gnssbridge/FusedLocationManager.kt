package com.example.gnssbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class FusedLocationManager(
    context: Context,
    private val onLocation: (Location) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private val request =
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(onLocation)
        }
    }

    @Volatile
    private var running = false

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasLocationPermission()) {
            onStatus("Android Location permission is not granted")
            return false
        }

        if (running) {
            return true
        }

        client.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
        ).addOnSuccessListener {
            running = true
            onStatus("🟢 Android Location active")
        }.addOnFailureListener { error ->
            running = false
            onStatus("🔴 Android Location error: ${error.message ?: "unknown error"}")
        }

        return true
    }

    fun stop() {
        if (!running) {
            return
        }

        client.removeLocationUpdates(callback)
        running = false
        onStatus("🔴 Android Location disabled")
    }

    fun isRunning(): Boolean = running
}
