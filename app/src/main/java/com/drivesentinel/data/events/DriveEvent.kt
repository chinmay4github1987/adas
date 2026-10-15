package com.drivesentinel.data.events

import org.json.JSONArray
import org.json.JSONObject

enum class EventTrigger(val label: String) {
    HARD_BRAKE("Hard brake"),
    FCW_CRITICAL("Collision warning"),
    MANUAL("Manual"),
}

/** A captured event. Serialized as the JSON sidecar (also exported next to the media). */
data class DriveEvent(
    val id: String,
    val trigger: EventTrigger,
    val wallTimeMs: Long,
    val speedKmh: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyM: Float?,
    val headingDeg: Float?,
    val peakDecelG: Float?,
    val speedDropKmh: Float?,
    val ttcSeconds: Float?,
    val leadLabel: String?,
    val preRollMs: Long,
    val postRollMs: Long,
    val videoUri: String?,
    val photoUris: List<String>,
    val sidecarUri: String?,
    val thumbnailPath: String?,
    val sizeBytes: Long,
    val appVersion: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("id", id)
        put("trigger", trigger.name)
        put("wallTimeMs", wallTimeMs)
        put("timeIso", java.time.Instant.ofEpochMilli(wallTimeMs).toString())
        putOpt("speedKmh", speedKmh?.toDouble())
        putOpt("latitude", latitude)
        putOpt("longitude", longitude)
        putOpt("locationAccuracyM", locationAccuracyM?.toDouble())
        putOpt("headingDeg", headingDeg?.toDouble())
        putOpt("peakDecelG", peakDecelG?.toDouble())
        putOpt("speedDropKmh", speedDropKmh?.toDouble())
        putOpt("ttcSeconds", ttcSeconds?.takeIf { it.isFinite() }?.toDouble())
        putOpt("leadLabel", leadLabel)
        put("preRollMs", preRollMs)
        put("postRollMs", postRollMs)
        putOpt("videoUri", videoUri)
        put("photoUris", JSONArray(photoUris))
        putOpt("sidecarUri", sidecarUri)
        putOpt("thumbnailPath", thumbnailPath)
        put("sizeBytes", sizeBytes)
        put("appVersion", appVersion)
    }

    companion object {
        fun fromJson(o: JSONObject): DriveEvent = DriveEvent(
            id = o.getString("id"),
            trigger = runCatching { EventTrigger.valueOf(o.getString("trigger")) }.getOrDefault(EventTrigger.MANUAL),
            wallTimeMs = o.getLong("wallTimeMs"),
            speedKmh = o.optDoubleOrNull("speedKmh")?.toFloat(),
            latitude = o.optDoubleOrNull("latitude"),
            longitude = o.optDoubleOrNull("longitude"),
            locationAccuracyM = o.optDoubleOrNull("locationAccuracyM")?.toFloat(),
            headingDeg = o.optDoubleOrNull("headingDeg")?.toFloat(),
            peakDecelG = o.optDoubleOrNull("peakDecelG")?.toFloat(),
            speedDropKmh = o.optDoubleOrNull("speedDropKmh")?.toFloat(),
            ttcSeconds = o.optDoubleOrNull("ttcSeconds")?.toFloat(),
            leadLabel = o.optStringOrNull("leadLabel"),
            preRollMs = o.optLong("preRollMs"),
            postRollMs = o.optLong("postRollMs"),
            videoUri = o.optStringOrNull("videoUri"),
            photoUris = o.optJSONArray("photoUris")?.let { arr -> List(arr.length()) { arr.getString(it) } }.orEmpty(),
            sidecarUri = o.optStringOrNull("sidecarUri"),
            thumbnailPath = o.optStringOrNull("thumbnailPath"),
            sizeBytes = o.optLong("sizeBytes"),
            appVersion = o.optString("appVersion"),
        )

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key) else null
    }
}
