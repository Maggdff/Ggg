package com.duoprog.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "duoprog_settings")

class SettingsDataStore(private val context: Context) {
    private val SOUND_KEY = booleanPreferencesKey("sound_enabled")

    val soundEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[SOUND_KEY] ?: true }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SOUND_KEY] = enabled }
    }
}
