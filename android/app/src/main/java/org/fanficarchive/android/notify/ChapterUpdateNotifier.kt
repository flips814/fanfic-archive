package org.fanficarchive.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fanficarchive.android.MainActivity
import org.fanficarchive.android.R
import org.fanficarchive.android.data.StoryNotification

object ChapterUpdateNotifier {
    const val CHANNEL_ID = "chapter_updates"
    const val EXTRA_WORK_ID = "work_id"
    private const val GROUP_KEY = "chapter_updates"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chapter updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when a story you follow gains a new chapter"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, note: StoryNotification) {
        ensureChannel(context)
        val workId = note.workId ?: return
        val chapters = note.chaptersAdded.coerceAtLeast(1)
        val title = note.workTitle?.takeIf { it.isNotBlank() } ?: "Story updated"
        val body = if (chapters == 1) {
            "New chapter available"
        } else {
            "$chapters new chapters available"
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_WORK_ID, workId)
        }
        val pending = PendingIntent.getActivity(
            context,
            workId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notifyId = (note.id.hashCode() and Int.MAX_VALUE).let { if (it == 0) workId else it }
        runCatching {
            NotificationManagerCompat.from(context).notify(notifyId, notification)
        }
    }
}
