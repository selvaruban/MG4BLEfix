package com.example.mg4blefix.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_preferences")

class DevicePreferencesRepository(private val context: Context) {

    companion object {
        val KEY_TARGET_DEVICE_NAME = stringPreferencesKey("target_device_name")
        val KEY_LAST_PAIRED_TIMESTAMP = longPreferencesKey("last_paired_timestamp")
        val KEY_ACTION_COUNT = intPreferencesKey("action_count")
        const val DEFAULT_DEVICE_NAME = "MG"
    }

    val targetDeviceName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_TARGET_DEVICE_NAME] ?: DEFAULT_DEVICE_NAME
    }

    val lastPairedTimestamp: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_PAIRED_TIMESTAMP] ?: 0L
    }

    val actionCount: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_ACTION_COUNT] ?: 0
    }

    suspend fun updateTargetDeviceName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TARGET_DEVICE_NAME] = name.ifBlank { DEFAULT_DEVICE_NAME }
        }
    }

    suspend fun recordAutomatedAction(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_PAIRED_TIMESTAMP] = timestamp
            val currentCount = preferences[KEY_ACTION_COUNT] ?: 0
            preferences[KEY_ACTION_COUNT] = currentCount + 1
        }
    }

    suspend fun resetStats() {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_PAIRED_TIMESTAMP] = 0L
            preferences[KEY_ACTION_COUNT] = 0
        }
    }
}
