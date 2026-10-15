package com.drivesentinel.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.ui.theme.Neon

private val POINTS = listOf(
    "Driver assistance only. DriveSentinel is not a safety system: it may fail to warn, or warn falsely. You remain fully responsible for driving.",
    "Keep the phone mounted on the dashboard/windshield before you start, without blocking your view or airbags. Never hold it while driving (MV Act s.184; phones may only be used, fixed on the dashboard, for navigation-style assistance).",
    "Do not interact with the app while the vehicle is moving. Settings lock above 5 km/h; alerts are audio-first.",
    "Recordings may capture other people and vehicles. Footage stays on this device; use it lawfully and only for personal purposes.",
    "Phones overheat on a sunny dashboard. The app throttles and pauses AI when hot — prefer a vent mount and avoid fast-charging while recording.",
)

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Before you drive", color = Neon.Amber, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))
        POINTS.forEach { p ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("•  ", color = Neon.Cyan, fontSize = 16.sp)
                Text(p, color = Color.White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("I understand: driver assistance only; keep the phone mounted; do not interact while driving.", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAccept, enabled = checked) { Text("Accept and continue", fontWeight = FontWeight.Bold) }
    }
}
