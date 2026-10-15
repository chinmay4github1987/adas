package com.drivesentinel.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.drivesentinel.appContainer

/**
 * Foreground service (`camera|location`) that owns the [com.drivesentinel.session.DriveSession]:
 * camera + AI, GPS, sensors, alert engine and loop recording keep running when the activity
 * is backgrounded or the screen turns off.
 *
 * Android 14/15 rules followed:
 *  * started only from the visible activity ("Start drive"), never from boot/background —
 *    camera is a while-in-use permission and an FGS of type camera can't start in background;
 *  * `startForeground` is called immediately in `onStartCommand` with explicit types, and only
 *    with the types whose runtime permission is granted (otherwise SecurityException);
 *  * no `microphone` type: DriveSentinel never records audio (privacy + simpler Play review);
 *  * no ACCESS_BACKGROUND_LOCATION: location FGS started while visible keeps access.
 */
class DriveService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_NOT_STICKY // don't resurrect from the background (would violate FGS rules)
    }

    private fun startSession() {
        val types = foregroundTypes()
        if (types == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.w(TAG, "No camera/location permission; not starting")
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(this, DriveNotifications.NOTIFICATION_ID, DriveNotifications.build(this), types)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) / SecurityException.
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        appContainer.driveSession.start(this)
    }

    private fun stopSession() {
        appContainer.driveSession.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        appContainer.driveSession.stop()
        super.onDestroy()
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "DriveService"
        const val ACTION_START = "com.drivesentinel.action.START"
        const val ACTION_STOP = "com.drivesentinel.action.STOP"

        /** Call only while the activity is visible. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, DriveService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DriveService::class.java).setAction(ACTION_STOP))
        }
    }
}
