package com.drivesentinel.ui.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drivesentinel.data.events.DriveEvent
import com.drivesentinel.data.events.EventRepository
import com.drivesentinel.data.events.EventTrigger
import com.drivesentinel.ui.common.AppIcons
import com.drivesentinel.ui.common.GlowDot
import com.drivesentinel.ui.common.ScreenHeader
import com.drivesentinel.ui.common.TechLabel
import com.drivesentinel.ui.common.appViewModel
import com.drivesentinel.ui.common.cockpitBackground
import com.drivesentinel.ui.common.glass
import com.drivesentinel.ui.common.techShape
import com.drivesentinel.ui.theme.Cockpit
import com.drivesentinel.ui.theme.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class EventsViewModel(private val repo: EventRepository) : ViewModel() {
    val events: StateFlow<List<DriveEvent>> = repo.events

    fun refresh() = viewModelScope.launch { repo.refresh() }
    fun delete(e: DriveEvent) = viewModelScope.launch { repo.delete(e) }
}

@Composable
fun EventsScreen(onBack: () -> Unit) {
    val vm = appViewModel { EventsViewModel(it.eventRepository) }
    val events by vm.events.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<DriveEvent?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().cockpitBackground().safeDrawingPadding()) {
        val totalMb = events.sumOf { it.sizeBytes } / 1_048_576f
        ScreenHeader(
            title = "Event log",
            subtitle = "${events.size} recorded · ${"%.1f".format(totalMb)} MB on device",
            onBack = onBack,
        )
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 460.dp).glass(techShape(18.dp)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(AppIcons.Clips, contentDescription = null, tint = Neon.Cyan, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No events recorded", color = Cockpit.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Hard brakes, critical collision warnings and the Mark event button save a ±10 s clip and 3 photos.",
                        color = Cockpit.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(events, key = { it.id }) { e -> EventRow(e, onDelete = { pendingDelete = e }) }
            }
        }
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color(0xFF0A1420),
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Neon.Red) },
            title = { Text("Delete this event?", color = Cockpit.Text) },
            text = { Text("The clip, photos and metadata will be permanently removed from this device.", color = Cockpit.TextMuted) },
            confirmButton = {
                TextButton(onClick = { vm.delete(e); pendingDelete = null }) {
                    Text("DELETE", color = Neon.Red, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("CANCEL", color = Neon.Cyan, letterSpacing = 1.sp) }
            },
        )
    }
}

@Composable
private fun EventRow(e: DriveEvent, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, e.thumbnailPath) {
        value = withContext(Dispatchers.IO) {
            e.thumbnailPath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    val color = when (e.trigger) {
        EventTrigger.HARD_BRAKE -> Neon.Amber
        EventTrigger.FCW_CRITICAL -> Neon.Red
        EventTrigger.MANUAL -> Neon.Cyan
    }
    Row(
        Modifier.fillMaxWidth().glass(techShape(14.dp), edge = color.copy(alpha = 0.5f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(168.dp).height(94.dp).clip(techShape(10.dp)).background(Color(0xFF0F1C2A))
                .then(if (e.videoUri != null) Modifier.clickable(role = Role.Button) { open(context, e.videoUri, "video/mp4") } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            if (e.videoUri != null) {
                Icon(AppIcons.Play, contentDescription = "Play clip", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlowDot(color, size = 8.dp)
                Spacer(Modifier.width(8.dp))
                TechLabel(e.trigger.label, color = color, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                DateFormat.getDateTimeInstance().format(Date(e.wallTimeMs)),
                color = Cockpit.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val details = buildList {
                e.speedKmh?.let { add("${it.toInt()} km/h") }
                e.peakDecelG?.let { add("%.2f g".format(it)) }
                e.speedDropKmh?.let { add("−${it.toInt()} km/h") }
                e.ttcSeconds?.let { add("TTC %.1f s".format(it)) }
                e.leadLabel?.let { add(it) }
                if (e.latitude != null && e.longitude != null) add("%.5f, %.5f".format(e.latitude, e.longitude))
                add("%.1f MB".format(e.sizeBytes / 1_048_576f))
            }
            Text(details.joinToString("  ·  "), color = Cockpit.TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (e.videoUri == null) TechLabel("Photos only · video unavailable", color = Neon.Amber, fontSize = 10.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.videoUri?.let { uri -> RowAction(AppIcons.Play, "Play", Neon.Cyan) { open(context, uri, "video/mp4") } }
            e.photoUris.firstOrNull()?.let { uri -> RowAction(AppIcons.Photo, "Photo", Neon.Cyan) { open(context, uri, "image/jpeg") } }
            RowAction(Icons.Filled.Delete, "Delete", Neon.Red, onDelete)
        }
    }
}

/** Compact labelled action with a 48 dp touch height. */
@Composable
private fun RowAction(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .widthIn(min = 108.dp)
            .glass(techShape(8.dp), edge = accent.copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        TechLabel(label, color = accent, fontSize = 11.sp)
    }
}

private fun open(context: android.content.Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app to open $mime", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
    }
}
