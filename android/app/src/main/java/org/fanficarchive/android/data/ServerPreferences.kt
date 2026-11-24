package org.fanficarchive.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("fanfic_archive")

data class ServerProfile(
    val baseUrl: String = "",
    val allowHttp: Boolean = false,
    val siteName: String = "",
    val loggedIn: Boolean = false,
    val username: String = "",
    /** Archive user id from /api/me (OIDC or local); scopes reading-progress cache. */
    val userId: Int = 0,
)

data class ReaderPrefs(
    val fontScale: Float = 1.1f,
    val theme: String = "sepia",
    val translateTo: String = "",
)

/** Local bookmark: last chapter and scroll position for a work on a given server. */
@Serializable
data class ReadingProgress(
    val chapterNumber: Int,
    val scrollOffset: Int = 0,
    /** 0..1 of the chapter page scroll; preferred for restore when content height changes. */
    val scrollFraction: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
)

class ServerPreferences(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val baseUrlKey = stringPreferencesKey("base_url")
    private val allowHttpKey = booleanPreferencesKey("allow_http")
    private val siteNameKey = stringPreferencesKey("site_name")
    private val loggedInKey = booleanPreferencesKey("logged_in")
    private val usernameKey = stringPreferencesKey("username")
    private val userIdKey = intPreferencesKey("user_id")
    private val fontScaleKey = floatPreferencesKey("font_scale")
    private val readerThemeKey = stringPreferencesKey("reader_theme")
    private val translateToKey = stringPreferencesKey("translate_to")
    private val readingProgressKey = stringPreferencesKey("reading_progress")
    private val chapterUpdateNotifyKey = booleanPreferencesKey("chapter_update_notify")
    private val chapterUpdateSeededKey = booleanPreferencesKey("chapter_update_seeded")
    private val seenUpdateNotificationIdsKey = stringPreferencesKey("seen_update_notification_ids")

    val profile: Flow<ServerProfile> = context.dataStore.data.map { prefs ->
        ServerProfile(
            baseUrl = prefs[baseUrlKey].orEmpty(),
            allowHttp = prefs[allowHttpKey] ?: false,
            siteName = prefs[siteNameKey].orEmpty(),
            loggedIn = prefs[loggedInKey] ?: false,
            username = prefs[usernameKey].orEmpty(),
            userId = prefs[userIdKey] ?: 0,
        )
    }

    /** Local device alerts when followed/owned works gain chapters. Default on. */
    val chapterUpdateNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[chapterUpdateNotifyKey] ?: true
    }

    val readerPrefs: Flow<ReaderPrefs> = context.dataStore.data.map { prefs ->
        ReaderPrefs(
            fontScale = prefs[fontScaleKey] ?: 1.1f,
            theme = prefs[readerThemeKey] ?: "sepia",
            translateTo = prefs[translateToKey].orEmpty(),
        )
    }

    /** All bookmarks keyed by "baseUrl|workId". */
    val readingProgressAll: Flow<Map<String, ReadingProgress>> = context.dataStore.data.map { prefs ->
        decodeProgressMap(prefs[readingProgressKey])
    }

    suspend fun saveServer(baseUrl: String, allowHttp: Boolean, siteName: String) {
        context.dataStore.edit { prefs ->
            prefs[baseUrlKey] = baseUrl.trimEnd('/')
            prefs[allowHttpKey] = allowHttp
            prefs[siteNameKey] = siteName
        }
    }

    suspend fun setLoggedIn(loggedIn: Boolean, username: String = "", userId: Int = 0) {
        context.dataStore.edit { prefs ->
            prefs[loggedInKey] = loggedIn
            prefs[usernameKey] = username
            if (loggedIn && userId > 0) prefs[userIdKey] = userId
            else prefs.remove(userIdKey)
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit { prefs ->
            prefs.remove(baseUrlKey)
            prefs.remove(allowHttpKey)
            prefs.remove(siteNameKey)
            prefs.remove(loggedInKey)
            prefs.remove(usernameKey)
            prefs.remove(userIdKey)
            prefs.remove(chapterUpdateSeededKey)
            prefs.remove(seenUpdateNotificationIdsKey)
        }
    }

    suspend fun setChapterUpdateNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[chapterUpdateNotifyKey] = enabled
            if (!enabled) {
                prefs.remove(chapterUpdateSeededKey)
                prefs.remove(seenUpdateNotificationIdsKey)
            }
        }
    }

    suspend fun chapterUpdateNotificationsEnabled(): Boolean =
        chapterUpdateNotificationsEnabled.first()

    suspend fun chapterUpdateFeedSeeded(): Boolean =
        context.dataStore.data.first()[chapterUpdateSeededKey] ?: false

    suspend fun markChapterUpdateFeedSeeded() {
        context.dataStore.edit { prefs -> prefs[chapterUpdateSeededKey] = true }
    }

    suspend fun seenUpdateNotificationIds(): Set<String> {
        val raw = context.dataStore.data.first()[seenUpdateNotificationIdsKey].orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }

    suspend fun rememberUpdateNotificationIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val merged = (
                runCatching {
                    json.decodeFromString<List<String>>(prefs[seenUpdateNotificationIdsKey].orEmpty())
                }.getOrDefault(emptyList()) + ids
                )
                .distinct()
                .takeLast(250)
            prefs[seenUpdateNotificationIdsKey] = json.encodeToString(merged)
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

    /** Local cache key scoped to archive account (OIDC/local user id). */
    fun progressKey(baseUrl: String, userId: Int, workId: Int): String =
        "${baseUrl.trimEnd('/')}|u$userId|$workId"

    fun readingProgressForUser(baseUrl: String, userId: Int): Flow<Map<Int, ReadingProgress>> =
        readingProgressAll.map { all ->
            if (userId <= 0) return@map emptyMap()
            val prefix = "${baseUrl.trimEnd('/')}|u$userId|"
            all.mapNotNull { (key, value) ->
                if (!key.startsWith(prefix)) return@mapNotNull null
                val id = key.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
                id to value
            }.toMap()
        }

    suspend fun getReadingProgress(baseUrl: String, userId: Int, workId: Int): ReadingProgress? {
        if (userId <= 0) return null
        val all = readingProgressAll.first()
        return all[progressKey(baseUrl, userId, workId)]
    }

    suspend fun saveReadingProgress(
        baseUrl: String,
        userId: Int,
        workId: Int,
        chapterNumber: Int,
        scrollOffset: Int,
        scrollFraction: Float = 0f,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        if (userId <= 0) return
        val key = progressKey(baseUrl, userId, workId)
        context.dataStore.edit { prefs ->
            val map = decodeProgressMap(prefs[readingProgressKey]).toMutableMap()
            map[key] = ReadingProgress(
                chapterNumber = chapterNumber,
                scrollOffset = scrollOffset.coerceAtLeast(0),
                scrollFraction = scrollFraction.coerceIn(0f, 1f),
                updatedAt = updatedAt,
            )
            prefs[readingProgressKey] = json.encodeToString(map)
        }
    }

    suspend fun clearReadingProgress(baseUrl: String, userId: Int, workId: Int) {
        if (userId <= 0) return
        val key = progressKey(baseUrl, userId, workId)
        context.dataStore.edit { prefs ->
            val map = decodeProgressMap(prefs[readingProgressKey]).toMutableMap()
            if (map.remove(key) != null) {
                prefs[readingProgressKey] = json.encodeToString(map)
            }
        }
    }

    private fun decodeProgressMap(raw: String?): Map<String, ReadingProgress> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ReadingProgress>>(raw) }.getOrDefault(emptyMap())
    }
}
