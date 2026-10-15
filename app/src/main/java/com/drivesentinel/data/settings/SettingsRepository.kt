package com.drivesentinel.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drivesentinel.perception.fcw.FcwSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val SPEED_LIMIT = intPreferencesKey("speed_limit_kmh")
        val VOICE = booleanPreferencesKey("voice_enabled")
        val HUD_MIRROR = booleanPreferencesKey("hud_mirror")
        val LANE_ASSIST = booleanPreferencesKey("lane_assist")
        val FCW_ENABLED = booleanPreferencesKey("fcw_enabled")
        val SIGN_ALERTS = booleanPreferencesKey("sign_alerts")
        val FCW_SENSITIVITY = stringPreferencesKey("fcw_sensitivity")
        val BRAKE_SENSITIVITY = stringPreferencesKey("brake_sensitivity")
        val BRAKE_ACCEL_ONLY = booleanPreferencesKey("brake_accel_only")
        val QUOTA_MB = intPreferencesKey("storage_quota_mb")
        val CAMERA_OFFSET = floatPreferencesKey("camera_offset")
        val SHOW_DETECTIONS = booleanPreferencesKey("show_detections")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted_v1")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.SPEED_LIMIT] = next.speedLimitKmh.coerceIn(20, 200)
            prefs[Keys.VOICE] = next.voiceEnabled
            prefs[Keys.HUD_MIRROR] = next.hudMirror
            prefs[Keys.LANE_ASSIST] = next.laneAssistEnabled
            prefs[Keys.FCW_ENABLED] = next.fcwEnabled
            prefs[Keys.SIGN_ALERTS] = next.signAlertsEnabled
            prefs[Keys.FCW_SENSITIVITY] = next.fcwSensitivity.name
            prefs[Keys.BRAKE_SENSITIVITY] = next.brakeSensitivity.name
            prefs[Keys.BRAKE_ACCEL_ONLY] = next.brakeAccelOnly
            prefs[Keys.QUOTA_MB] = next.storageQuotaMb.coerceIn(256, 32_768)
            prefs[Keys.CAMERA_OFFSET] = next.cameraOffset.coerceIn(-0.3f, 0.3f)
            prefs[Keys.SHOW_DETECTIONS] = next.showDetections
            prefs[Keys.DISCLAIMER] = next.disclaimerAccepted
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            speedLimitKmh = this[Keys.SPEED_LIMIT] ?: d.speedLimitKmh,
            voiceEnabled = this[Keys.VOICE] ?: d.voiceEnabled,
            hudMirror = this[Keys.HUD_MIRROR] ?: d.hudMirror,
            laneAssistEnabled = this[Keys.LANE_ASSIST] ?: d.laneAssistEnabled,
            fcwEnabled = this[Keys.FCW_ENABLED] ?: d.fcwEnabled,
            signAlertsEnabled = this[Keys.SIGN_ALERTS] ?: d.signAlertsEnabled,
            fcwSensitivity = enumOr(this[Keys.FCW_SENSITIVITY], d.fcwSensitivity),
            brakeSensitivity = enumOr(this[Keys.BRAKE_SENSITIVITY], d.brakeSensitivity),
            brakeAccelOnly = this[Keys.BRAKE_ACCEL_ONLY] ?: d.brakeAccelOnly,
            storageQuotaMb = this[Keys.QUOTA_MB] ?: d.storageQuotaMb,
            cameraOffset = this[Keys.CAMERA_OFFSET] ?: d.cameraOffset,
            showDetections = this[Keys.SHOW_DETECTIONS] ?: d.showDetections,
            disclaimerAccepted = this[Keys.DISCLAIMER] ?: d.disclaimerAccepted,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
