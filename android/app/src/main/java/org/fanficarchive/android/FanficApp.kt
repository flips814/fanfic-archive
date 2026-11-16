package org.fanficarchive.android

import android.app.Application
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.OfflineStore
import org.fanficarchive.android.data.ServerPreferences
import org.fanficarchive.android.data.SessionCookieJar

class FanficApp : Application() {
    lateinit var repository: ArchiveRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ArchiveRepository(
            prefs = ServerPreferences(this),
            cookieJar = SessionCookieJar(this),
            offline = OfflineStore(this),
        )
    }
}
