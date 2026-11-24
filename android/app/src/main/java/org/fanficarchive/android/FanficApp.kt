package org.fanficarchive.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.OfflineStore
import org.fanficarchive.android.data.ServerPreferences
import org.fanficarchive.android.data.SessionCookieJar
import org.fanficarchive.android.notify.ChapterUpdateNotifier
import org.fanficarchive.android.notify.UpdatePollScheduler

class FanficApp : Application(), ImageLoaderFactory {
    lateinit var repository: ArchiveRepository
        private set

    lateinit var cookieJar: SessionCookieJar
        private set

    lateinit var imageLoader: ImageLoader
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        cookieJar = SessionCookieJar(this)
        repository = ArchiveRepository(
            prefs = ServerPreferences(this),
            cookieJar = cookieJar,
            offline = OfflineStore(this),
        )
        imageLoader = newImageLoader()
        ChapterUpdateNotifier.ensureChannel(this)
        appScope.launch {
            val profile = repository.profile.first()
            val enabled = repository.preferences.chapterUpdateNotificationsEnabled()
            if (profile.loggedIn && enabled) {
                UpdatePollScheduler.schedule(this@FanficApp)
            }
        }
    }

    fun syncChapterUpdatePolling() {
        appScope.launch {
            val profile = repository.profile.first()
            val enabled = repository.preferences.chapterUpdateNotificationsEnabled()
            if (profile.loggedIn && enabled) {
                UpdatePollScheduler.schedule(this@FanficApp)
            } else {
                UpdatePollScheduler.cancel(this@FanficApp)
            }
        }
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
