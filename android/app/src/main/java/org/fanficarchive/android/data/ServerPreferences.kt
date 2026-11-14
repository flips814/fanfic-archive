package org.fanficarchive.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("fanfic_archive")

data class ServerProfile(
    val baseUrl: String = "",
    val allowHttp: Boolean = false,
    val siteName: String = "",
    val loggedIn: Boolean = false,
    val username: String = "",
)

data class ReaderPrefs(
    val fontScale: Float = 1.1f,
    val theme: String = "sepia",
    val translateTo: String = "",
)

class ServerPreferences(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val allowHttpKey = booleanPreferencesKey("allow_http")
    private val siteNameKey = stringPreferencesKey("site_name")
    private val loggedInKey = booleanPreferencesKey("logged_in")
    private val usernameKey = stringPreferencesKey("username")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val readerThemeKey = stringPreferencesKey("reader_theme")
    private val translateToKey = stringPreferencesKey("translate_to")

    val profile: Flow<ServerProfile> = context.dataStore.data.map { prefs ->
        ServerProfile(
            baseUrl = prefs[baseUrlKey].orEmpty(),
            allowHttp = prefs[allowHttpKey] ?: false,
            siteName = prefs[siteNameKey].orEmpty(),
            loggedIn = prefs[loggedInKey] ?: false,
            username = prefs[usernameKey].orEmpty(),
        )
    }

    val readerPrefs: Flow<ReaderPrefs> = context.dataStore.data.map { prefs ->
        ReaderPrefs(
            fontScale = prefs[fontScaleKey] ?: 1.1f,
            theme = prefs[readerThemeKey] ?: "sepia",
            translateTo = prefs[translateToKey].orEmpty(),
        )
    }

    suspend fun saveServer(baseUrl: String, allowHttp: Boolean, siteName: String) {
        context.dataStore.edit { prefs ->
            prefs[baseUrlKey] = baseUrl.trimEnd('/')
            prefs[allowHttpKey] = allowHttp
            prefs[siteNameKey] = siteName
        }
    }

    suspend fun setLoggedIn(loggedIn: Boolean, username: String = "") {
        context.dataStore.edit { prefs ->
            prefs[loggedInKey] = loggedIn
            prefs[usernameKey] = username
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { prefs ->
            prefs.remove(baseUrlKey)
            prefs.remove(allowHttpKey)
            prefs.remove(siteNameKey)
            prefs.remove(loggedInKey)
            prefs.remove(usernameKey)
        }
    }

    suspend fun saveReaderPrefs(fontScale: Float, theme: String) {
        context.dataStore.edit { prefs ->
            prefs[fontScaleKey] = fontScale
            prefs[readerThemeKey] = theme
        }
    }

    suspend fun saveTranslateTo(code: String?) {
        context.dataStore.edit { prefs ->
            if (code.isNullOrBlank()) prefs.remove(translateToKey)
            else prefs[translateToKey] = code
        }
    }
}
