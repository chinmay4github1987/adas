package com.drivesentinel.ui.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drivesentinel.data.events.DriveEvent
import com.drivesentinel.data.events.EventRepository
import com.drivesentinel.data.events.EventTrigger
import com.drivesentinel.ui.common.appViewModel
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

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Events (${events.size})", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No events yet.\nHard brakes, critical collision warnings and the EVENT button save a ±10 s clip and 3 photos.",
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(events, key = { it.id }) { e -> EventRow(e, onDelete = { pendingDelete = e }) }
            }
        }
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete event?") },
            text = { Text("Deletes the clip, photos and metadata of this event from the device.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
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
        Modifier.fillMaxWidth().background(Color(0xFF11161D), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.trigger.label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(DateFormat.getDateTimeInstance().format(Date(e.wallTimeMs)), color = Color.White, fontSize = 13.sp)
            val details = buildList {
                e.speedKmh?.let { add("${it.toInt()} km/h") }
                e.peakDecelG?.let { add("%.2f g".format(it)) }
                e.speedDropKmh?.let { add("−${it.toInt()} km/h") }
                e.ttcSeconds?.let { add("TTC %.1f s".format(it)) }
                e.leadLabel?.let { add(it) }
                if (e.latitude != null && e.longitude != null) add("%.5f, %.5f".format(e.latitude, e.longitude))
                add("%.1f MB".format(e.sizeBytes / 1_048_576f))
            }
            Text(details.joinToString(" · "), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            if (e.videoUri == null) Text("Photos only (video unavailable)", color = Neon.Amber, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            e.videoUri?.let { uri -> TextButton(onClick = { open(context, uri, "video/mp4") }) { Text("Play") } }
            e.photoUris.firstOrNull()?.let { uri -> TextButton(onClick = { open(context, uri, "image/jpeg") }) { Text("Photo") } }
            TextButton(onClick = onDelete) { Text("Delete", color = Neon.Red) }
        }
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
