package com.drivesentinel.ui.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesentinel.camera.CameraPipeline
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.data.settings.SettingsRepository
import com.drivesentinel.session.DriveSession
import com.drivesentinel.session.DriveState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DriveViewModel(
    private val session: DriveSession,
    private val settingsRepository: SettingsRepository,
    val camera: CameraPipeline,
) : ViewModel() {

    val state: StateFlow<DriveState> = session.state

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun manualEvent(): Boolean = session.manualEvent()

    /** One-tap "I'm centred in my lane now" mount calibration (only meaningful while moving). */
    fun calibrateCenter() {
        val offset = state.value.perception.laneStatus.offset
        if (offset.isNaN()) return
        viewModelScope.launch {
            settingsRepository.update { s ->
                // offset = (xCam − centre) / width; shift xCam so the current position reads 0.
                s.copy(cameraOffset = (s.cameraOffset - offset * 0.75f).coerceIn(-0.3f, 0.3f))
            }
        }
    }
}
