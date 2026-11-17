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
    private val offline: OfflineStore,
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
        visibility: String? = null,
        followedOnly: Boolean = false,
        addedBy: Int? = null,
        offset: Int = 0,
        limit: Int = 30,
    ): WorkListResponse = api().listWorks(
        q = query.ifBlank { null },
        site = site?.ifBlank { null },
        status = status?.ifBlank { null },
        visibility = visibility?.ifBlank { null },
        followed = if (followedOnly) "1" else null,
        addedBy = addedBy,
        limit = limit,
        offset = offset,
    )

    private suspend fun currentBaseUrl(): String {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) throw IllegalStateException("No server configured")
        return base.trimEnd('/')
    }

    suspend fun getWork(id: Int): WorkDetail {
        val base = runCatching { currentBaseUrl() }.getOrNull()
        return runCatching { api().getWork(id) }
            .getOrElse { err ->
                val cached = base?.let { url ->
                    offline.loadWork(url, id)?.toWorkDetail(offline.localCoverUri(url, id))
                }
                cached ?: throw err
            }
    }

    suspend fun getChapter(workId: Int, number: Int): ChapterContent {
        val base = runCatching { currentBaseUrl() }.getOrNull()
        return runCatching { api().getChapter(workId, number) }
            .getOrElse { err ->
                val cached = base?.let { offline.loadWork(it, workId)?.toChapterContent(number) }
                cached ?: throw err
            }
    }

    suspend fun isWorkDownloaded(workId: Int): Boolean {
        val base = prefs.profile.first().baseUrl.trimEnd('/')
        if (base.isBlank()) return false
        return offline.isDownloaded(base, workId)
    }

    suspend fun downloadedWorkIds(): Set<Int> {
        val base = prefs.profile.first().baseUrl.trimEnd('/')
        if (base.isBlank()) return emptySet()
        return offline.downloadedWorkIds(base)
    }

    suspend fun listOfflineWorks(): List<WorkSummary> {
        val base = prefs.profile.first().baseUrl.trimEnd('/')
        if (base.isBlank()) return emptyList()
        return offline.listDownloaded(base).map { snap ->
            snap.toWorkSummary(offline.localCoverUri(snap.baseUrl, snap.workId))
        }
    }

    suspend fun removeOfflineWork(workId: Int) {
        val base = currentBaseUrl()
        offline.deleteWork(base, workId)
    }

    /**
     * Download the full work (metadata + every chapter) for offline reading.
     * [onProgress] receives completed chapter count and total chapters.
     */
    suspend fun downloadWorkOffline(
        workId: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OfflineWorkSnapshot {
        val base = currentBaseUrl()
        val detail = api().getWork(workId)
        val chapterRefs = detail.chapterList.ifEmpty {
            (1..detail.chapters.coerceAtLeast(0)).map { ChapterSummary(number = it, title = "Chapter $it") }
        }
        val total = chapterRefs.size
        if (total == 0) throw IllegalStateException("This work has no chapters to download.")

        val chapters = mutableListOf<OfflineChapter>()
        var lastError: Throwable? = null
        for ((index, ref) in chapterRefs.withIndex()) {
            try {
                val content = api().getChapter(workId, ref.number)
                chapters += OfflineChapter(
                    number = content.number,
                    title = content.title,
                    contentHtml = content.contentHtml,
                    wordCount = content.wordCount,
                    sourceUrl = content.sourceUrl,
                )
                onProgress(chapters.size, total)
            } catch (err: Throwable) {
                lastError = err
                break
            }
            // Keep UI responsive between chapter fetches.
            if (index < chapterRefs.lastIndex) kotlinx.coroutines.yield()
        }

        if (chapters.isEmpty()) {
            throw lastError ?: IllegalStateException("Download failed")
        }

        val snapshot = OfflineWorkSnapshot(
            workId = detail.id,
            baseUrl = base,
            title = detail.title,
            author = detail.author,
            site = detail.site,
            sourceUrl = detail.sourceUrl,
            status = detail.status,
            rating = detail.rating,
            language = detail.language,
            languageCode = detail.languageCode,
            wordCount = detail.wordCount,
            visibility = detail.visibility,
            summaryHtml = detail.summaryHtml,
            fandoms = detail.fandoms,
            relationships = detail.relationships,
            characters = detail.characters,
            tags = detail.tags,
            warnings = detail.warnings,
            chapterList = detail.chapterList,
            chapters = chapters,
            coverUrl = detail.coverUrl,
        )
        offline.saveWork(snapshot)
        downloadCoverForOffline(base, detail.id, detail.coverUrl)
        if (lastError != null && chapters.size < total) {
            throw IllegalStateException(
                "Downloaded ${chapters.size} of $total chapters, then failed: ${errorMessage(lastError)}",
            )
        }
        return snapshot
    }

    private suspend fun downloadCoverForOffline(baseUrl: String, workId: Int, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        val absolute = if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            coverUrl
        } else {
            baseUrl.trimEnd('/') + (if (coverUrl.startsWith("/")) coverUrl else "/$coverUrl")
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(absolute).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        } ?: return
        offline.saveCover(baseUrl, workId, bytes)
    }

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

    /** Pull server bookmarks and merge with local by newest updatedAt; push newer locals. */
    suspend fun syncReadingProgress() {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return
        val remote = runCatching { api().listReadingProgress().progress }.getOrElse { return }
        val local = prefs.readingProgressForServer(base).first()
        val remoteByWork = remote.associateBy { it.workId }

        for (dto in remote) {
            val remoteAt = parseServerTime(dto.updatedAt) ?: continue
            val existing = local[dto.workId]
            if (existing == null || remoteAt > existing.updatedAt) {
                prefs.saveReadingProgress(
                    baseUrl = base,
                    workId = dto.workId,
                    chapterNumber = dto.chapterNumber,
                    scrollOffset = if (existing?.chapterNumber == dto.chapterNumber) existing.scrollOffset else 0,
                    scrollFraction = dto.scrollFraction.toFloat(),
                    updatedAt = remoteAt,
                )
            }
        }

        for ((workId, progress) in local) {
            val remoteDto = remoteByWork[workId]
            val remoteAt = parseServerTime(remoteDto?.updatedAt) ?: 0L
            if (remoteDto == null || progress.updatedAt > remoteAt) {
                runCatching {
                    api().putReadingProgress(
                        workId,
                        ReadingProgressUpdateRequest(
                            chapterNumber = progress.chapterNumber,
                            scrollFraction = progress.scrollFraction,
                        ),
                    )
                }
            }
        }
    }

    suspend fun saveReadingProgress(
        workId: Int,
        chapterNumber: Int,
        scrollOffset: Int,
        scrollFraction: Float = 0f,
    ) {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return
        val updatedAt = System.currentTimeMillis()
        prefs.saveReadingProgress(base, workId, chapterNumber, scrollOffset, scrollFraction, updatedAt)
        runCatching {
            api().putReadingProgress(
                workId,
                ReadingProgressUpdateRequest(
                    chapterNumber = chapterNumber,
                    scrollFraction = scrollFraction,
                ),
            )
        }
    }

    suspend fun clearReadingProgress(workId: Int) {
        val base = prefs.profile.first().baseUrl
        if (base.isBlank()) return
        prefs.clearReadingProgress(base, workId)
        runCatching { api().deleteReadingProgress(workId) }
    }

    private fun parseServerTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.recoverCatching {
            // FastAPI may emit "+00:00" instead of Z.
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        }.getOrNull()
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
