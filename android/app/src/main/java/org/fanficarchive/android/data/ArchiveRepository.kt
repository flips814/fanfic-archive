package org.fanficarchive.android.data

import android.webkit.CookieManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class ArchiveRepository(
    private val prefs: ServerPreferences,
    private val cookieJar: SessionCookieJar,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    @Volatile
    private var api: ApiService? = null

    @Volatile
    private var boundBaseUrl: String? = null

    val profile: Flow<ServerProfile> = prefs.profile
    val readerPrefs: Flow<ReaderPrefs> = prefs.readerPrefs

    private fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    fun validateUrl(raw: String, allowHttp: Boolean): String {
        val url = normalizeBaseUrl(raw)
        if (url.startsWith("http://") && !allowHttp) {
            throw IllegalArgumentException("Enable “Allow HTTP (LAN)” to use a non-HTTPS address.")
        }
        return url
    }

    private fun apiFor(baseUrl: String): ApiService {
        val normalized = baseUrl.trimEnd('/') + "/"
        if (api != null && boundBaseUrl == normalized) return api!!
        api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
        boundBaseUrl = normalized
        return api!!
    }

    private suspend fun api(): ApiService {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) throw IllegalStateException("No server configured")
        return apiFor(base)
    }

    suspend fun probeServer(rawUrl: String, allowHttp: Boolean): ServerInfo {
        val base = validateUrl(rawUrl, allowHttp)
        cookieJar.clear()
        val service = apiFor(base)
        val health = service.health()
        if (!health.ok) throw IllegalStateException("Server health check failed")
        val info = service.info()
        prefs.saveServer(base, allowHttp, info.siteName)
        prefs.setLoggedIn(false)
        return info
    }

    suspend fun refreshInfo(): ServerInfo = api().info()

    suspend fun login(username: String, password: String): UserMe {
        val user = api().login(LoginRequest(username.trim(), password))
        prefs.setLoggedIn(true, user.name)
        return user
    }

    /** URL for in-app WebView SSO (or the general web login page). */
    suspend fun browserLoginUrl(preferSso: Boolean): String {
        val base = prefs.profile.first().baseUrl.trimEnd('/')
        if (base.isBlank()) throw IllegalStateException("No server configured")
        return if (preferSso) "$base/login/sso?next=/" else "$base/login?next=/"
    }

    /**
     * After the WebView finishes OIDC (or web login), copy its cookies and confirm `/api/me`.
     */
    suspend fun completeBrowserLogin(): UserMe {
        val base = prefs.profile.first().baseUrl
        if (!cookieJar.importFromCookieManager(base)) {
            throw IllegalStateException("Sign-in did not set a session cookie. Try again.")
        }
        val user = api().me()
        prefs.setLoggedIn(true, user.name)
        return user
    }

    private fun clearWebViewCookies() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    suspend fun logout() {
        runCatching { api().logout() }
        cookieJar.clear()
        clearWebViewCookies()
        prefs.setLoggedIn(false)
    }

    suspend fun disconnect() {
        runCatching { api().logout() }
        cookieJar.clear()
        clearWebViewCookies()
        api = null
        boundBaseUrl = null
        prefs.clearServer()
    }

    suspend fun me(): UserMe = api().me()

    /**
     * Re-validate a persisted session after process death.
     * Returns true if still authenticated; clears local login on a hard 401.
     */
    suspend fun restoreSession(): Boolean {
        val profile = prefs.profile.first()
        if (!profile.loggedIn || profile.baseUrl.isBlank()) return false
        if (!cookieJar.hasSession(profile.baseUrl)) {
            // SSO may still have the cookie in WebView storage.
            cookieJar.importFromCookieManager(profile.baseUrl)
        }
        if (!cookieJar.hasSession(profile.baseUrl)) {
            prefs.setLoggedIn(false)
            return false
        }
        return runCatching {
            val user = api().me()
            prefs.setLoggedIn(true, user.name)
            true
        }.getOrElse { err ->
            if (err is HttpException && err.code() == 401) {
                cookieJar.clear()
                clearWebViewCookies()
                prefs.setLoggedIn(false)
            }
            false
        }
    }

    suspend fun listWorks(
        query: String = "",
        site: String? = null,
        status: String? = null,
        followedOnly: Boolean = false,
        addedBy: Int? = null,
        offset: Int = 0,
        limit: Int = 30,
    ): WorkListResponse = api().listWorks(
        q = query.ifBlank { null },
        site = site?.ifBlank { null },
        status = status?.ifBlank { null },
        followed = if (followedOnly) "1" else null,
        addedBy = addedBy,
        limit = limit,
        offset = offset,
    )

    suspend fun getWork(id: Int): WorkDetail = api().getWork(id)

    suspend fun getChapter(workId: Int, number: Int): ChapterContent = api().getChapter(workId, number)

    suspend fun setFollowing(workId: Int, follow: Boolean): FollowResponse =
        if (follow) api().follow(workId) else api().unfollow(workId)

    suspend fun checkUpdates(workId: Int): Job = api().check(workId)

    suspend fun toggleAutoUpdate(workId: Int): AutoUpdateResponse = api().toggleAutoUpdate(workId)

    suspend fun deleteWork(workId: Int): OkResponse = api().deleteWork(workId)

    suspend fun setVisibility(workId: Int, visibility: String): VisibilityUpdateResponse =
        api().setVisibility(workId, VisibilityUpdateRequest(visibility))

    suspend fun importUrls(
        urls: List<String>,
        visibility: String = VISIBILITY_PUBLIC,
    ): ImportResponse = api().imports(ImportRequest(urls = urls, visibility = visibility))

    suspend fun listJobs(
        kind: String? = null,
        status: String? = null,
        userId: Int? = null,
        offset: Int = 0,
    ): JobListResponse = api().jobs(kind = kind, status = status, userId = userId, offset = offset)

    suspend fun listUsers(): UsersResponse = api().users()

    suspend fun listComments(workId: Int, chapterId: Int? = null): CommentListResponse =
        api().comments(workId, chapterId)

    suspend fun postComment(
        workId: Int,
        body: String,
        chapterId: Int? = null,
        parentId: Int? = null,
        spoiler: Boolean = false,
    ): CommentItem = api().createComment(
        workId,
        CommentCreateRequest(body = body, chapterId = chapterId, parentId = parentId, spoiler = spoiler),
    )

    suspend fun removeComment(commentId: Int): OkResponse = api().deleteComment(commentId)

    suspend fun listNotifications(limit: Int = 50): StoryNotificationList =
        api().notifications(limit = limit)

    suspend fun getSettings(): NotificationSettings = api().getSettings()

    suspend fun saveSettings(settings: NotificationSettings): NotificationSettings =
        api().updateSettings(settings)

    suspend fun epubUrl(workId: Int): String {
        val base = prefs.profile.first().baseUrl.trimEnd('/')
        return "$base/works/$workId/epub"
    }

    /** Download EPUB bytes using the authenticated cookie jar. */
    suspend fun downloadEpub(workId: Int): Pair<String, ByteArray> {
        val url = epubUrl(workId)
        val request = Request.Builder().url(url).get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("EPUB download failed (${response.code})")
                val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty EPUB")
                val name = response.header("Content-Disposition")
                    ?.substringAfter("filename=", "")
                    ?.trim('"', ' ')
                    ?.ifBlank { null }
                    ?: "work-$workId.epub"
                name to bytes
            }
        }
    }

    suspend fun saveReaderPrefs(fontScale: Float, theme: String) {
        prefs.saveReaderPrefs(fontScale, theme)
    }

    suspend fun saveTranslateTo(code: String?) {
        prefs.saveTranslateTo(code)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun readingProgressForCurrentServer(): Flow<Map<Int, ReadingProgress>> =
        prefs.profile.map { it.baseUrl }.flatMapLatest { base ->
            prefs.readingProgressForServer(base)
        }

    suspend fun getReadingProgress(workId: Int): ReadingProgress? {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return null
        return prefs.getReadingProgress(base, workId)
    }

    suspend fun saveReadingProgress(
        workId: Int,
        chapterNumber: Int,
        scrollOffset: Int,
        scrollFraction: Float = 0f,
    ) {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return
        prefs.saveReadingProgress(base, workId, chapterNumber, scrollOffset, scrollFraction)
    }

    suspend fun clearReadingProgress(workId: Int) {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return
        prefs.clearReadingProgress(base, workId)
    }

    suspend fun translateLanguages(sourceLanguageCode: String? = null): TranslateLanguagesResponse =
        api().translateLanguages(sourceLanguageCode?.ifBlank { null })

    suspend fun getChapterTranslation(workId: Int, number: Int, to: String): TranslationStatus =
        api().getChapterTranslation(workId, number, to)

    suspend fun requestChapterTranslation(workId: Int, number: Int, to: String): TranslationStatus =
        api().requestChapterTranslation(workId, number, to)

    suspend fun getWorkTranslation(workId: Int, to: String): TranslationStatus =
        api().getWorkTranslation(workId, to)

    suspend fun requestWorkTranslation(workId: Int, to: String): TranslationStatus =
        api().requestWorkTranslation(workId, to)

    /** Poll until a chapter translation finishes (or fails). Prefetches the next chapter like the site. */
    suspend fun awaitChapterTranslation(
        workId: Int,
        number: Int,
        to: String,
        nextNumber: Int? = null,
        onProgress: (TranslationStatus) -> Unit = {},
    ): TranslationStatus {
        var status = requestChapterTranslation(workId, number, to)
        onProgress(status)
        while (status.status == "running") {
            kotlinx.coroutines.delay(1_200)
            status = getChapterTranslation(workId, number, to)
            onProgress(status)
        }
        if (status.status == "done" && nextNumber != null) {
            runCatching { requestChapterTranslation(workId, nextNumber, to) }
        }
        return status
    }

    fun errorMessage(error: Throwable): String = when (error) {
        is HttpException -> {
            val body = error.response()?.errorBody()?.string().orEmpty()
            val detail = runCatching { json.decodeFromString<ApiError>(body).detail }.getOrNull()
            detail ?: "HTTP ${error.code()}"
        }
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "Something went wrong"
        else -> error.message ?: "Network error"
    }
}
