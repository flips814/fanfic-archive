package org.fanficarchive.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.OfflineStore
import org.fanficarchive.android.data.ServerPreferences
import org.fanficarchive.android.data.SessionCookieJar

class FanficApp : Application(), ImageLoaderFactory {
    lateinit var repository: ArchiveRepository
        private set

    lateinit var cookieJar: SessionCookieJar
        private set

    lateinit var imageLoader: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        cookieJar = SessionCookieJar(this)
        repository = ArchiveRepository(
            prefs = ServerPreferences(this),
            cookieJar = cookieJar,
            offline = OfflineStore(this),
        )
        imageLoader = newImageLoader()
    }

    /** Share the API session cookie jar so cover requests are authenticated. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .build()
            }
            .crossfade(true)
            .build()
}
