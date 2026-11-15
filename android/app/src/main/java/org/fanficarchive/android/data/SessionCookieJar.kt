package org.fanficarchive.android.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** Persists archive session cookies so login survives process death. */
class SessionCookieJar(context: Context) : CookieJar {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = hostKey(url)
        val existing = store[key].orEmpty().associateBy { it.name }.toMutableMap()
        cookies.forEach { existing[it.name] = it }
        store[key] = existing.values.toList()
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val key = hostKey(url)
        val cookies = store[key].orEmpty().filter { it.expiresAt >= now && it.matches(url) }
        if (cookies.size != store[key].orEmpty().size) {
            store[key] = cookies
            persist()
        }
        return cookies
    }

    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
    }

    fun hasSession(baseUrl: String): Boolean {
        val url = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull() ?: return false
        return loadForRequest(url).any { it.name == "session" }
    }

    /**
     * Copy WebView cookies (after browser SSO) into the OkHttp jar.
     * CookieManager only returns name=value, so give the session cookie a 30-day lifetime
     * to match the server SessionMiddleware max_age.
     */
    fun importFromCookieManager(baseUrl: String): Boolean {
        val url = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull() ?: return false
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return false
        val cookies = header.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { Cookie.parse(url, it) }
            .map { cookie ->
                if (cookie.name == "session") cookie.withExpiresAt(System.currentTimeMillis() + SESSION_MAX_AGE_MS)
                else cookie
            }
        if (cookies.isEmpty()) return false
        saveFromResponse(url, cookies)
        return cookies.any { it.name == "session" }
    }

    private fun loadFromDisk() {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return
        runCatching {
            val map = json.decodeFromString<Map<String, List<StoredCookie>>>(raw)
            val now = System.currentTimeMillis()
            map.forEach { (host, stored) ->
                val cookies = stored.mapNotNull { it.toCookie() }.filter { it.expiresAt >= now }
                if (cookies.isNotEmpty()) store[host] = cookies
            }
        }
    }

    private fun persist() {
        val payload = store.mapValues { (_, cookies) -> cookies.map { StoredCookie.from(it) } }
        prefs.edit().putString(KEY_COOKIES, json.encodeToString(payload)).apply()
    }

    private fun hostKey(url: HttpUrl): String = "${url.host}:${url.port}"

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        fun toCookie(): Cookie? = runCatching {
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)
            if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            builder.build()
        }.getOrNull()

        companion object {
            fun from(cookie: Cookie) = StoredCookie(
                name = cookie.name,
                value = cookie.value,
                expiresAt = cookie.expiresAt,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly,
            )
        }
    }

    private fun Cookie.withExpiresAt(expiresAt: Long): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        private const val PREFS_NAME = "fanfic_session_cookies"
        private const val KEY_COOKIES = "cookies"
        /** Match server SessionMiddleware max_age (30 days). */
        private const val SESSION_MAX_AGE_MS = 60L * 60L * 24L * 30L * 1000L
    }
}
