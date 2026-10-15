package com.drivesentinel.di

import android.content.Context
import com.drivesentinel.alerts.VoiceAlerter
import com.drivesentinel.camera.CameraPipeline
import com.drivesentinel.camera.SegmentRecorder
import com.drivesentinel.data.events.EventRepository
import com.drivesentinel.data.settings.SettingsRepository
import com.drivesentinel.events.EventCaptureManager
import com.drivesentinel.events.MediaStoreWriter
import com.drivesentinel.perception.PerceptionEngine
import com.drivesentinel.sensors.location.LocationSource
import com.drivesentinel.sensors.motion.MotionSource
import com.drivesentinel.session.DriveSession
import com.drivesentinel.thermal.ThermalMonitor

/** Manual DI: process-wide singletons, created lazily. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository by lazy { SettingsRepository(appContext) }
    val mediaStoreWriter by lazy { MediaStoreWriter(appContext) }
    val eventRepository by lazy { EventRepository(appContext, mediaStoreWriter) }

    val cameraPipeline by lazy { CameraPipeline(appContext) }
    private val segmentRecorder by lazy { SegmentRecorder(appContext) }
    private val perceptionEngine by lazy { PerceptionEngine(appContext) }
    private val voiceAlerter by lazy { VoiceAlerter(appContext) }

    private val eventCaptureManager by lazy {
        EventCaptureManager(
            context = appContext,
            camera = cameraPipeline,
            segments = segmentRecorder,
            latestFrame = perceptionEngine::latestFrame,
            media = mediaStoreWriter,
            repository = eventRepository,
        )
    }

    val driveSession by lazy {
        DriveSession(
            context = appContext,
            settingsRepository = settingsRepository,
            camera = cameraPipeline,
            segments = segmentRecorder,
            perception = perceptionEngine,
            capture = eventCaptureManager,
            locationSource = LocationSource(appContext),
            motionSource = MotionSource(appContext),
            thermalMonitor = ThermalMonitor(appContext),
            voice = voiceAlerter,
        )
    }
}
