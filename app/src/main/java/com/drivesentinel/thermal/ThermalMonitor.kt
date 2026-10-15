package com.drivesentinel.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class ThermalReading(val headroom: Float, val status: Int)

/**
 * Polls `PowerManager.getThermalHeadroom(10)` every 10 s (more often returns NaN) and listens
 * for thermal status changes. Some devices always report status NONE, so headroom is the
 * primary signal; NaN means unsupported.
 */
class ThermalMonitor(private val context: Context) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun readings(pollMs: Long = 10_000): Flow<ThermalReading> = callbackFlow {
        var status = currentStatus()
        var headroom = Float.NaN

        val listener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { s ->
                status = s
                trySend(ThermalReading(headroom, s))
            }.also { power.addThermalStatusListener(ContextCompat.getMainExecutor(context), it) }
        } else {
            null
        }

        val poller = launch {
            while (true) {
                headroom = readHeadroom()
                trySend(ThermalReading(headroom, status))
                delay(pollMs)
            }
        }

        awaitClose {
            poller.cancel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
                power.removeThermalStatusListener(listener as PowerManager.OnThermalStatusChangedListener)
            }
        }
    }

    private fun currentStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else 0

    private fun readHeadroom(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(10) else Float.NaN
}
