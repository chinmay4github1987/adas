package com.drivesentinel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.ui.common.OrientationLock
import com.drivesentinel.ui.drive.DriveScreen
import com.drivesentinel.ui.events.EventsScreen
import com.drivesentinel.ui.onboarding.DisclaimerScreen
import com.drivesentinel.ui.settings.SettingsScreen
import com.drivesentinel.ui.theme.DriveSentinelTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DriveSentinelTheme { AppRoot() }
        }
    }
}

private enum class Screen { DRIVE, SETTINGS, EVENTS }

@Composable
private fun AppRoot() {
    val container = LocalContext.current.appContainer
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
    val scope = rememberCoroutineScope()
    val current = settings

    if (current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (!current.disclaimerAccepted) {
        DisclaimerScreen(onAccept = {
            scope.launch { container.settingsRepository.update { it.copy(disclaimerAccepted = true) } }
        })
        return
    }

    val driving by remember { container.driveSession.state.map { it.active }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    OrientationLock(locked = driving)

    var screen by rememberSaveable { mutableStateOf(Screen.DRIVE) }
    BackHandler(enabled = screen != Screen.DRIVE) { screen = Screen.DRIVE }
    when (screen) {
        Screen.DRIVE -> DriveScreen(onOpenSettings = { screen = Screen.SETTINGS }, onOpenEvents = { screen = Screen.EVENTS })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.DRIVE })
        Screen.EVENTS -> EventsScreen(onBack = { screen = Screen.DRIVE })
    }
}
