package com.drivesentinel.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.ui.common.AppIcons
import com.drivesentinel.ui.common.PrimaryActionButton
import com.drivesentinel.ui.common.TechLabel
import com.drivesentinel.ui.common.cockpitBackground
import com.drivesentinel.ui.common.glass
import com.drivesentinel.ui.common.techShape
import com.drivesentinel.ui.theme.Cockpit
import com.drivesentinel.ui.theme.Neon

private val POINTS = listOf(
    "Driver assistance only" to "DriveSentinel is not a safety system: it may fail to warn, or warn falsely. You remain fully responsible for driving.",
    "Mount before you drive" to "Keep the phone mounted on the dashboard/windshield before you start, without blocking your view or airbags. Never hold it while driving (MV Act s.184; phones may only be used, fixed on the dashboard, for navigation-style assistance).",
    "Hands off while moving" to "Do not interact with the app while the vehicle is moving. Settings lock above 5 km/h; alerts are audio-first.",
    "Recording & privacy" to "Recordings may capture other people and vehicles. Footage stays on this device; use it lawfully and only for personal purposes.",
    "Heat" to "Phones overheat on a sunny dashboard. The app throttles and pauses AI when hot — prefer a vent mount and avoid fast-charging while recording.",
)

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().cockpitBackground().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Shield, contentDescription = null, tint = Neon.Amber, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    TechLabel("System briefing", color = Neon.Amber)
                    Text("Before you drive", color = Cockpit.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(6.dp))
            POINTS.forEachIndexed { i, (title, body) ->
                Row(Modifier.fillMaxWidth().glass(techShape(10.dp), edge = Cockpit.EdgeDim).padding(16.dp)) {
                    Text(
                        "%02d".format(i + 1),
                        color = Neon.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, color = Cockpit.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(body, color = Cockpit.TextMuted, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // The whole row is the tap target, not just the checkbox.
            Row(
                Modifier.fillMaxWidth()
                    .glass(techShape(10.dp), edge = if (checked) Neon.Cyan else Cockpit.Edge)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    colors = CheckboxDefaults.colors(checkedColor = Neon.Cyan, checkmarkColor = Color.Black, uncheckedColor = Cockpit.Edge),
                )
                Text(
                    "I understand: driver assistance only; keep the phone mounted; do not interact while driving.",
                    color = Cockpit.Text,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().alpha(if (checked) 1f else 0.35f)) {
                PrimaryActionButton(
                    icon = Icons.Filled.Check,
                    label = "Accept and continue",
                    accent = Neon.Cyan,
                    onClick = { if (checked) onAccept() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
