package org.fanficarchive.android.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class ArchiveRepository(
    private val prefs: ServerPreferences,
    private val cookieJar: SessionCookieJar = SessionCookieJar(),
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

    suspend fun logout() {
        runCatching { api().logout() }
        cookieJar.clear()
        prefs.setLoggedIn(false)
    }

    suspend fun disconnect() {
        runCatching { api().logout() }
        cookieJar.clear()
        api = null
        boundBaseUrl = null
        prefs.clearServer()
    }

    suspend fun me(): UserMe = api().me()

    suspend fun listWorks(query: String = "", followedOnly: Boolean = false): WorkListResponse =
        api().listWorks(
            q = query.ifBlank { null },
            followed = if (followedOnly) "1" else null,
        )

    suspend fun getWork(id: Int): WorkDetail = api().getWork(id)

    suspend fun getChapter(workId: Int, number: Int): ChapterContent = api().getChapter(workId, number)

    suspend fun setFollowing(workId: Int, follow: Boolean): FollowResponse =
        if (follow) api().follow(workId) else api().unfollow(workId)

    suspend fun checkUpdates(workId: Int): Job = api().check(workId)

    suspend fun importUrls(urls: List<String>): ImportResponse = api().imports(ImportRequest(urls))

    suspend fun saveReaderPrefs(fontScale: Float, theme: String) {
        prefs.saveReaderPrefs(fontScale, theme)
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
