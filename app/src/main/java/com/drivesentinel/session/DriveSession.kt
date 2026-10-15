package com.drivesentinel.session

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.drivesentinel.alerts.BeepSynth
import com.drivesentinel.alerts.OverspeedMonitor
import com.drivesentinel.alerts.VoiceAlert
import com.drivesentinel.alerts.VoiceAlerter
import com.drivesentinel.camera.CameraPipeline
import com.drivesentinel.camera.SegmentRecorder
import com.drivesentinel.data.events.EventTrigger
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.data.settings.SettingsRepository
import com.drivesentinel.events.EventCaptureManager
import com.drivesentinel.events.EventContext
import com.drivesentinel.perception.PerceptionConfig
import com.drivesentinel.perception.PerceptionEngine
import com.drivesentinel.perception.PerceptionFrame
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.perception.lane.LaneState
import com.drivesentinel.sensors.location.LocationSource
import com.drivesentinel.sensors.location.SpeedSmoother
import com.drivesentinel.sensors.location.toSpeedSample
import com.drivesentinel.sensors.motion.BrakeDetector
import com.drivesentinel.sensors.motion.MotionSource
import com.drivesentinel.thermal.ThermalLevel
import com.drivesentinel.thermal.ThermalMonitor
import com.drivesentinel.thermal.ThermalPolicy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Orchestrates one drive: camera + AI, GPS speed, motion sensors, thermal ladder, alert logic
 * and event capture. Owned by the [com.drivesentinel.service.DriveService] (started/stopped by
 * it) so everything keeps running while the activity is in the background; the UI only
 * observes [state].
 *
 * Threading: camera binding, segment ring and event capture on Main; GPS + motion + brake
 * logic serialized on one "logic" thread; perception on the camera analysis thread.
 */
class DriveSession(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val camera: CameraPipeline,
    private val segments: SegmentRecorder,
    private val perception: PerceptionEngine,
    private val capture: EventCaptureManager,
    private val locationSource: LocationSource,
    private val motionSource: MotionSource,
    private val thermalMonitor: ThermalMonitor,
    private val voice: VoiceAlerter,
) {
    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null

    /**
     * Long-lived single analysis thread. Never shut down, so release (old session) and rearm
     * (new session) are serialized on the thread that owns the GPU delegate.
     */
    private val analysisExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "ds-analysis") }
    }
    private var logicDispatcher: ExecutorCoroutineDispatcher? = null

    // Logic-thread state.
    private val speedSmoother = SpeedSmoother()
    private val overspeed = OverspeedMonitor()
    private val brakeDetector = BrakeDetector()
    private val thermalPolicy = ThermalPolicy()
    @Volatile private var lastLocation: Location? = null
    @Volatile private var settings = AppSettings()

    // Analysis-thread alert edge detection.
    private var lastFcwLevel = FcwLevel.NONE
    private var lastLaneState = LaneState.CENTERED
    private var lastLaneAlertMs = 0L
    private var lastFcwEventMs = 0L

    val isActive: Boolean get() = scope != null

    /** Main thread. [owner] is the foreground service's lifecycle. */
    fun start(owner: LifecycleOwner) {
        if (scope != null) return
        val errors = CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Session coroutine failed", t)
            _state.update { it.copy(lastError = t.message ?: t.javaClass.simpleName) }
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
        scope = s
        val analysis = analysisExecutor
        val logic = Executors.newSingleThreadExecutor { r -> Thread(r, "ds-logic") }.asCoroutineDispatcher()
            .also { logicDispatcher = it }

        speedSmoother.reset(); overspeed.reset(); brakeDetector.reset()
        lastFcwLevel = FcwLevel.NONE
        lastLaneState = LaneState.CENTERED
        analysis.execute { perception.rearm() }
        perception.onFrame = ::onPerceptionFrame
        _state.value = DriveState(active = true)

        // Settings → all consumers.
        s.launch {
            settingsRepository.settings.collect { st ->
                settings = st
                withContext(logic) {
                    overspeed.thresholdKmh = st.speedLimitKmh.toFloat()
                    brakeDetector.config = brakeDetector.config.copy(
                        thresholdG = st.brakeSensitivity.thresholdG,
                        accelOnly = st.brakeAccelOnly,
                    )
                }
                voice.voiceEnabled = st.voiceEnabled
                perception.config = PerceptionConfig(
                    laneAssistEnabled = st.laneAssistEnabled,
                    fcwEnabled = st.fcwEnabled,
                    fcwSensitivity = st.fcwSensitivity,
                    cameraOffset = st.cameraOffset,
                )
                _state.update { it.copy(speedLimitKmh = st.speedLimitKmh) }
            }
        }

        // Camera + loop recording.
        s.launch {
            val status = camera.bind(owner, analysis, perception)
            perception.focalNormX = status.focalNormX
            _state.update { it.copy(camera = status, lastError = status.error) }
            camera.recorder?.let { segments.start(it, s) }
        }
        s.launch { segments.recording.collect { rec -> _state.update { it.copy(recording = rec) } } }
        s.launch { capture.status.collect { c -> _state.update { it.copy(capture = c) } } }

        // GPS speed → smoothing → overspeed + brake confirmation.
        s.launch(logic) {
            locationSource.updates()
                .catch { e -> Log.e(TAG, "Location stream failed", e); _state.update { it.copy(lastError = "GPS: ${e.message}") } }
                .collect { loc -> onLocation(loc) }
        }
        s.launch(logic) {
            while (isActive) {
                val st = speedSmoother.checkStale(SystemClock.elapsedRealtime())
                _state.update { it.copy(speed = st) }
                delay(1_000)
            }
        }

        // Motion → hard-brake detection.
        if (motionSource.isAvailable) {
            s.launch(logic) {
                motionSource.samples()
                    .flowOn(Dispatchers.Default)
                    .catch { e -> Log.e(TAG, "Motion stream failed", e) }
                    .collect { m ->
                        brakeDetector.onMotion(m.timeMs, m.linear, m.gravity)?.let(::onHardBrake)
                    }
            }
            s.launch(logic) {
                while (isActive) {
                    _state.update {
                        it.copy(decelG = brakeDetector.currentDecelG, forwardAxisLearned = brakeDetector.forwardAxis != null)
                    }
                    delay(250)
                }
            }
        }

        // Thermal ladder.
        s.launch {
            thermalMonitor.readings().collect { r ->
                val previous = thermalPolicy.level
                val level = thermalPolicy.update(r.headroom, r.status, SystemClock.elapsedRealtime())
                perception.targetFps = level.aiFps
                _state.update { it.copy(thermal = level, thermalHeadroom = r.headroom) }
                if (level != previous) onThermalChanged(previous, level)
            }
        }

        // Perception → UI state.
        s.launch { perception.frames.collect { f -> _state.update { it.copy(perception = f) } } }
    }

    /** Main thread. */
    fun stop() {
        val s = scope ?: return
        capture.cancel()
        segments.stop()
        camera.unbind()
        perception.onFrame = null
        s.cancel()
        scope = null
        analysisExecutor.execute { perception.releaseOnAnalysisThread() }
        logicDispatcher?.close()
        logicDispatcher = null
        _state.value = DriveState(active = false, speedLimitKmh = settings.speedLimitKmh)
    }

    /** Manual event button (main thread). */
    fun manualEvent(): Boolean = triggerCapture(EventTrigger.MANUAL, EventContext())

    // ---- logic thread -------------------------------------------------------------------

    private fun onLocation(loc: Location) {
        lastLocation = loc
        val sample = loc.toSpeedSample()
        val st = speedSmoother.update(sample)
        perception.speedKmh = st.kmh
        val result = overspeed.update(sample.elapsedMs, st.kmh, st.reliable)
        if (result.speak) {
            voice.announce(VoiceAlert("Slow down"))
        }
        if (st.reliable) {
            brakeDetector.onSpeed(sample.elapsedMs, st.rawKmh)?.let(::onHardBrake)
        }
        _state.update { it.copy(speed = st, overspeed = result.isOverspeed) }
    }

    private fun onHardBrake(e: BrakeDetector.BrakeEvent) {
        Log.i(TAG, "Hard brake: $e")
        val ctx = EventContext(
            speedKmh = e.preSpeedKmh ?: _state.value.speed.kmh,
            location = lastLocation,
            peakDecelG = e.peakDecelG,
            speedDropKmh = e.speedDropKmh,
        )
        scope?.launch(Dispatchers.Main) {
            if (triggerCapture(EventTrigger.HARD_BRAKE, ctx)) {
                voice.announce(VoiceAlert("Hard brake recorded", tone = BeepSynth.ROBOT_PROMPT))
            }
        }
    }

    // ---- analysis thread ----------------------------------------------------------------

    private fun onPerceptionFrame(f: PerceptionFrame) {
        val now = SystemClock.elapsedRealtime()
        val level = f.fcw.level
        if (level != lastFcwLevel) {
            when (level) {
                FcwLevel.CRITICAL -> {
                    voice.announce(VoiceAlert("Brake! Brake!", tone = BeepSynth.URGENT, urgent = true))
                    if (now - lastFcwEventMs > FCW_EVENT_COOLDOWN_MS) {
                        lastFcwEventMs = now
                        val ctx = EventContext(
                            speedKmh = _state.value.speed.kmh,
                            location = lastLocation,
                            ttcSeconds = f.fcw.ttcSeconds,
                            leadLabel = f.fcw.leadLabel,
                        )
                        scope?.launch(Dispatchers.Main) { triggerCapture(EventTrigger.FCW_CRITICAL, ctx) }
                    }
                }
                FcwLevel.WARNING -> if (lastFcwLevel == FcwLevel.NONE) {
                    voice.announce(VoiceAlert(text = null, tone = BeepSynth.URGENT))
                }
                FcwLevel.NONE -> Unit
            }
            lastFcwLevel = level
        }

        val lane = f.laneStatus.state
        if (lane != lastLaneState) {
            val drifting = lane == LaneState.LEFT_DRIFT || lane == LaneState.RIGHT_DRIFT
            if (drifting && !f.laneStatus.suppressed && now - lastLaneAlertMs > LANE_ALERT_COOLDOWN_MS) {
                lastLaneAlertMs = now
                voice.announce(VoiceAlert(if (lane == LaneState.LEFT_DRIFT) "Drifting left" else "Drifting right"))
            }
            lastLaneState = lane
        }
    }

    // ---- main thread --------------------------------------------------------------------

    private fun triggerCapture(trigger: EventTrigger, ctx: EventContext): Boolean {
        val s = scope ?: return false
        val quota = settings.storageQuotaMb.toLong() * 1024 * 1024
        return capture.trigger(s, trigger, ctx, quota)
    }

    private fun onThermalChanged(previous: ThermalLevel, level: ThermalLevel) {
        Log.i(TAG, "Thermal $previous → $level")
        if (level >= ThermalLevel.SEVERE && previous < ThermalLevel.SEVERE) {
            voice.announce(VoiceAlert("Phone too hot. A I paused."))
        }
        val s = scope ?: return
        if (level.pauseRecording && !previous.pauseRecording) {
            segments.stop()
        } else if (!level.pauseRecording && previous.pauseRecording) {
            camera.recorder?.let { segments.start(it, s) }
        }
    }

    private companion object {
        const val TAG = "DriveSession"
        const val FCW_EVENT_COOLDOWN_MS = 30_000L
        const val LANE_ALERT_COOLDOWN_MS = 8_000L
    }
}
