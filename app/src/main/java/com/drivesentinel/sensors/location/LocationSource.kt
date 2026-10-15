package com.drivesentinel.sensors.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 1 Hz high-accuracy fused location as a cold Flow. Must run inside the location FGS. */
class LocationSource(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked via hasPermission()
    fun updates(): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("ACCESS_FINE_LOCATION not granted"))
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(0L) // no batching: alerts need fresh fixes
            .setWaitForAccurateLocation(false)
            .build()
        val listener = LocationListener { location -> trySend(location) }
        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                .addOnFailureListener { close(it) }
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose { client.removeLocationUpdates(listener) }
    }
}

/** Location → [SpeedSample] on the elapsedRealtime clock. */
fun Location.toSpeedSample(): SpeedSample = SpeedSample(
    elapsedMs = elapsedRealtimeNanos / 1_000_000,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    horizontalAccuracyM = if (hasAccuracy()) accuracy else null,
)
