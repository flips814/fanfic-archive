package org.fanficarchive.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fanficarchive.android.notify.ChapterUpdateNotifier
import org.fanficarchive.android.notify.UpdatePollScheduler
import org.fanficarchive.android.screens.FanficNavHost
import org.fanficarchive.android.screens.theme.FanficTheme

class MainActivity : ComponentActivity() {
    private var openWorkId by mutableStateOf<Int?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denial is fine; posts are skipped without permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openWorkId = intentWorkId(intent)
        val app = application as FanficApp
        setContent {
            FanficTheme {
                FanficNavHost(
                    repository = app.repository,
                    openWorkId = openWorkId,
                    onOpenWorkConsumed = { openWorkId = null },
                )
            }
        }
        lifecycleScope.launch {
            if (app.repository.preferences.chapterUpdateNotificationsEnabled()) {
                maybeRequestNotificationPermission()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openWorkId = intentWorkId(intent)
    }

    override fun onResume() {
        super.onResume()
        val app = application as FanficApp
        app.syncChapterUpdatePolling()
        lifecycleScope.launch {
            val profile = app.repository.profile.first()
            val enabled = app.repository.preferences.chapterUpdateNotificationsEnabled()
            if (profile.loggedIn && enabled) UpdatePollScheduler.runNow(this@MainActivity)
        }
    }

    private fun intentWorkId(intent: Intent?): Int? {
        if (intent == null || !intent.hasExtra(ChapterUpdateNotifier.EXTRA_WORK_ID)) return null
        val id = intent.getIntExtra(ChapterUpdateNotifier.EXTRA_WORK_ID, 0)
        return id.takeIf { it > 0 }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
