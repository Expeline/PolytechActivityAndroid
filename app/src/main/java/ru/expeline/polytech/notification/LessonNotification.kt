package ru.expeline.polytech.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.expeline.polytech.MainActivity
import ru.expeline.polytech.R
import ru.expeline.polytech.data.Lesson
import ru.expeline.polytech.data.PersistedState
import ru.expeline.polytech.data.StudyClock

object LessonNotification {
    private const val channel = "lesson_activity"
    private const val id = 40102
    fun sync(context: Context, lesson: Lesson?, state: PersistedState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channel, "Текущая пара", NotificationManager.IMPORTANCE_LOW).apply { description = "Таймер текущей и следующей пары" })
        }
        if (!state.preferences.liveEnabled || lesson == null || state.preferences.isFinished(StudyClock.today(), state.group.id)) { manager.cancel(id); return }
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val upcoming = lesson.start > StudyClock.now()
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lesson.subject)
            .setContentText("${if (upcoming) "Следующая" else "Сейчас"} · ${lesson.timeRange} · ${lesson.location}")
            .setContentIntent(intent).setOngoing(!upcoming).setOnlyAlertOnce(true).setSilent(true)
            .setWhen(lesson.end.toEpochMilli()).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT).build()
        manager.notify(id, notification)
    }
}

