package com.batya.stopsmsspam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.batya.stopsmsspam.bulk.SendPacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val delaySeconds: Int = SendPacing.DEFAULT_DELAY_SECONDS,
    val jitterPercent: Int = SendPacing.DEFAULT_JITTER_PERCENT,
    val fallbackKeyword: String = "STOP",
    val markReadAfterSend: Boolean = true,
    val deleteAfterSend: Boolean = false,
    /**
     * On by default. The first thing anyone should do with a tool that texts dozens of strangers
     * is watch it not text anyone.
     */
    val dryRun: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            delaySeconds = prefs[KEY_DELAY] ?: SendPacing.DEFAULT_DELAY_SECONDS,
            jitterPercent = prefs[KEY_JITTER] ?: SendPacing.DEFAULT_JITTER_PERCENT,
            fallbackKeyword = prefs[KEY_FALLBACK] ?: "STOP",
            markReadAfterSend = prefs[KEY_MARK_READ] ?: true,
            deleteAfterSend = prefs[KEY_DELETE] ?: false,
            dryRun = prefs[KEY_DRY_RUN] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(current())
        context.dataStore.edit { prefs ->
            // Snapped, not just clamped: the slider offers a fixed set of delays, and a value
            // stored when the range was different should land on one of them.
            prefs[KEY_DELAY] = SendPacing.nearestStep(updated.delaySeconds)
            prefs[KEY_JITTER] = updated.jitterPercent.coerceIn(0, SendPacing.MAX_JITTER_PERCENT)
            prefs[KEY_FALLBACK] = updated.fallbackKeyword.trim().uppercase().ifEmpty { "STOP" }
            prefs[KEY_MARK_READ] = updated.markReadAfterSend
            prefs[KEY_DELETE] = updated.deleteAfterSend
            prefs[KEY_DRY_RUN] = updated.dryRun
        }
    }

    private companion object {
        val KEY_DELAY = intPreferencesKey("delay_seconds")
        val KEY_JITTER = intPreferencesKey("jitter_percent")
        val KEY_FALLBACK = stringPreferencesKey("fallback_keyword")
        val KEY_MARK_READ = booleanPreferencesKey("mark_read_after_send")
        val KEY_DELETE = booleanPreferencesKey("delete_after_send")
        val KEY_DRY_RUN = booleanPreferencesKey("dry_run")
    }
}
