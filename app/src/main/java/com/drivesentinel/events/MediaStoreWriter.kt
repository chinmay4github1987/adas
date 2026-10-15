package com.drivesentinel.events

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Publishes event media to shared storage:
 *  * clips → `Movies/DriveSentinel`, snapshots → `Pictures/DriveSentinel`,
 *  * JSON sidecars → `Documents/DriveSentinel` (MediaStore does not accept JSON under Movies/).
 *
 * API 29+: MediaStore with `IS_PENDING` (no permission needed for our own items).
 * API 26–28: public directories + media scan (needs WRITE_EXTERNAL_STORAGE, maxSdk 28).
 */
class MediaStoreWriter(private val context: Context) {

    private val resolver get() = context.contentResolver

    suspend fun saveVideo(source: File, displayName: String, takenMs: Long): Uri? = withContext(Dispatchers.IO) {
        publish(
            displayName = displayName,
            mime = "video/mp4",
            directory = Environment.DIRECTORY_MOVIES,
            takenMs = takenMs,
            collection = { MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) },
        ) { out -> source.inputStream().use { it.copyTo(out) } }
    }

    suspend fun saveJpeg(bitmap: Bitmap, displayName: String, takenMs: Long, quality: Int = 92): Uri? =
        withContext(Dispatchers.IO) {
            publish(
                displayName = displayName,
                mime = "image/jpeg",
                directory = Environment.DIRECTORY_PICTURES,
                takenMs = takenMs,
                collection = { MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) },
            ) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        }

    suspend fun saveJson(json: String, displayName: String, takenMs: Long): Uri? = withContext(Dispatchers.IO) {
        publish(
            displayName = displayName,
            mime = "application/json",
            directory = Environment.DIRECTORY_DOCUMENTS,
            takenMs = takenMs,
            collection = { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) },
        ) { out -> out.write(json.toByteArray()) }
    }

    /** Deletes an item we published earlier. Returns false if it was not ours / already gone. */
    suspend fun delete(uriString: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        runCatching {
            if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).delete()
            } else {
                resolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false) // SecurityException after reinstall: item no longer owned
    }

    fun sizeOf(uriString: String): Long = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") File(requireNotNull(uri.path)).length()
        else resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun publish(
        displayName: String,
        mime: String,
        directory: String,
        takenMs: Long,
        collection: () -> Uri,
        write: (OutputStream) -> Unit,
    ): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(displayName, mime, directory, takenMs, collection(), write)
        } else {
            publishLegacy(displayName, mime, directory, write)
        }
    }.onFailure { android.util.Log.w(TAG, "publish $displayName failed", it) }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScoped(
        displayName: String,
        mime: String,
        directory: String,
        takenMs: Long,
        collection: Uri,
        write: (OutputStream) -> Unit,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (mime.startsWith("image/") || mime.startsWith("video/")) {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
            }
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "MediaStore insert returned null" }
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(displayName: String, mime: String, directory: String, write: (OutputStream) -> Unit): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(directory), ALBUM).apply { mkdirs() }
        val file = File(dir, displayName)
        file.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(file)
    }

    companion object {
        const val ALBUM = "DriveSentinel"
        private const val TAG = "MediaStoreWriter"
    }
}
