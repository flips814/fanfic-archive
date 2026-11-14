package org.fanficarchive.android

import android.app.Application
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerPreferences

class FanficApp : Application() {
    lateinit var repository: ArchiveRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ArchiveRepository(ServerPreferences(this))
    }
}
