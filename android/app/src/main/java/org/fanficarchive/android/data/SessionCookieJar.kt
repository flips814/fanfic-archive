package org.fanficarchive.android.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** In-memory cookie jar that keeps the archive session cookie across Retrofit calls. */
class SessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = hostKey(url)
        val existing = store[key].orEmpty().associateBy { it.name }.toMutableMap()
        cookies.forEach { existing[it.name] = it }
        store[key] = existing.values.toList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val cookies = store[hostKey(url)].orEmpty().filter { it.expiresAt >= now && it.matches(url) }
        store[hostKey(url)] = cookies
        return cookies
    }

    fun clear() {
        store.clear()
    }

    fun hasSession(baseUrl: String): Boolean {
        val url = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull() ?: return false
        return loadForRequest(url).any { it.name == "session" }
    }

    private fun hostKey(url: HttpUrl): String = "${url.host}:${url.port}"
}
