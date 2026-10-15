package com.drivesentinel.data.events

import android.content.Context
import android.util.Log
import com.drivesentinel.events.MediaStoreWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Index of captured events. The source of truth is one JSON file per event in app-private
 * storage (`files/events/<id>.json`) plus a small thumbnail; the media itself lives in
 * MediaStore. Footage never leaves the device (DPDP "personal or domestic purpose").
 */
class EventRepository(
    context: Context,
    private val media: MediaStoreWriter,
) {
    private val dir = File(context.filesDir, "events").apply { mkdirs() }
    private val mutex = Mutex()
    private val _events = MutableStateFlow<List<DriveEvent>>(emptyList())
    val events: StateFlow<List<DriveEvent>> = _events.asStateFlow()

    fun thumbnailFile(id: String) = File(dir, "${id}_thumb.jpg")

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { _events.value = loadAll() }
    }

    suspend fun add(event: DriveEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(dir, "${event.id}.json").writeText(event.toJson().toString(2))
            _events.value = loadAll()
        }
    }

    suspend fun delete(event: DriveEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            deleteLocked(event)
            _events.value = loadAll()
        }
    }

    /** Deletes the oldest events until the total size is within [quotaBytes]. Returns # deleted. */
    suspend fun enforceQuota(quotaBytes: Long): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = loadAll().sortedBy { it.wallTimeMs }.toMutableList()
            var total = all.sumOf { it.sizeBytes }
            var deleted = 0
            while (total > quotaBytes && all.size > 1) { // never delete the event just saved
                val oldest = all.removeAt(0)
                deleteLocked(oldest)
                total -= oldest.sizeBytes
                deleted++
            }
            if (deleted > 0) _events.value = loadAll()
            deleted
        }
    }

    private suspend fun deleteLocked(event: DriveEvent) {
        event.videoUri?.let { media.delete(it) }
        event.photoUris.forEach { media.delete(it) }
        event.sidecarUri?.let { media.delete(it) }
        event.thumbnailPath?.let { File(it).delete() }
        File(dir, "${event.id}.json").delete()
    }

    private fun loadAll(): List<DriveEvent> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching { DriveEvent.fromJson(JSONObject(f.readText())) }
                    .onFailure { Log.w("EventRepository", "Bad event file ${f.name}", it) }
                    .getOrNull()
            }
            .sortedByDescending { it.wallTimeMs }
}
