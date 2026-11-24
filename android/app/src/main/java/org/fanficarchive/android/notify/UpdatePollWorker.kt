package org.fanficarchive.android.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.fanficarchive.android.FanficApp

/**
 * Polls `/api/notifications` for finished update jobs that added chapters, then
 * posts Android notifications for any the device has not already shown.
 */
class UpdatePollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? FanficApp ?: return Result.success()
        val prefs = app.repository.preferences
        val profile = prefs.profile.first()
        if (!profile.loggedIn || profile.baseUrl.isBlank()) return Result.success()
        if (!prefs.chapterUpdateNotificationsEnabled()) return Result.success()

        val feed = runCatching { app.repository.listNotifications(limit = 50) }
            .getOrElse { return Result.retry() }

        val updates = feed.notifications.filter {
            it.kind == "story_updated" && it.chaptersAdded > 0 && it.workId != null
        }
        if (updates.isEmpty()) return Result.success()

        val seen = prefs.seenUpdateNotificationIds()
        if (!prefs.chapterUpdateFeedSeeded()) {
            // First run: remember existing updates so we don't dump history as alerts.
            prefs.rememberUpdateNotificationIds(updates.map { it.id })
            prefs.markChapterUpdateFeedSeeded()
            return Result.success()
        }

        val fresh = updates.filter { it.id !in seen }
        if (fresh.isEmpty()) return Result.success()

        ChapterUpdateNotifier.ensureChannel(applicationContext)
        // Oldest first so the newest lands on top of the shade.
        for (note in fresh.asReversed()) {
            ChapterUpdateNotifier.show(applicationContext, note)
        }
        prefs.rememberUpdateNotificationIds(fresh.map { it.id })
        return Result.success()
    }
}
