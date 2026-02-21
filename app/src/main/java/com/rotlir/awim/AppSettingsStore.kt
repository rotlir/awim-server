package com.rotlir.awim

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "awim")

data class AppSettings(
    val port: String,
    val tcpMode: Boolean
)

class AppSettingsStore(private val context: Context) {
    private val portPreferenceKey = stringPreferencesKey("port")
    private val tcpModePreferenceKey = booleanPreferencesKey("tcpMode")

    suspend fun loadSettings(): AppSettings {
        val preferences = context.dataStore.data.first()
        return AppSettings(
            preferences[portPreferenceKey] ?: "",
            preferences[tcpModePreferenceKey] ?: false
        )
    }

    suspend fun savePort(port: String) {
        context.dataStore.edit { preferences ->
            preferences[portPreferenceKey] = port
        }
    }

    suspend fun saveTcpMode(tcpMode: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[tcpModePreferenceKey] = tcpMode
        }
    }
}
