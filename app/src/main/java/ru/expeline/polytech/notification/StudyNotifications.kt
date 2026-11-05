package ru.expeline.polytech.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ru.expeline.polytech.MainActivity
import ru.expeline.polytech.R
import ru.expeline.polytech.data.AssignmentDTO
import ru.expeline.polytech.data.ScheduleChange
import ru.expeline.polytech.data.ScheduleChangeKind
import ru.expeline.polytech.data.StudyClock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Schedule-change alerts and deadline reminders. All local: no push server is needed. */
object StudyNotifications {
    private const val CHANGES = "schedule_changes"
    private const val DEADLINES = "deadlines"
    private const val DEADLINE_TAG = "deadline"
    private val dayFormat = DateTimeFormatter.ofPattern("EE, d MMM", Locale.forLanguageTag("ru")).withZone(ru.expeline.polytech.data.MoscowZone)

    private fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANGES, "Изменения расписания", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Переносы, отмены и новые пары на ближайшую неделю" })
        manager.createNotificationChannel(NotificationChannel(DEADLINES, "Сроки заданий", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Напоминания за день и за 3 часа до срока" })
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!allowed(context)) return
        channels(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setGroup(channel).build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    fun describe(change: ScheduleChange): String {
        fun whenText(start: Instant, range: String) = "${dayFormat.format(start)} $range"
        val old = change.old; val new = change.new
        return when (change.kind) {
            ScheduleChangeKind.MOVED -> if (old != null && new != null) "${whenText(old.start, old.timeRange)} → ${whenText(new.start, new.timeRange)}, ${new.location}" else ""
            ScheduleChangeKind.CANCELLED -> old?.let { "Не будет: ${whenText(it.start, it.timeRange)}" }.orEmpty()
            ScheduleChangeKind.ADDED -> new?.let { "${whenText(it.start, it.timeRange)}, ${it.location}" }.orEmpty()
            ScheduleChangeKind.ROOM_CHANGED -> new?.let { "${whenText(it.start, it.timeRange)}: ${old?.location.orEmpty()} → ${it.location}" }.orEmpty()
            ScheduleChangeKind.TEACHER_CHANGED -> new?.let { "${whenText(it.start, it.timeRange)}: ${it.teachers.joinToString()}" }.orEmpty()
        }
    }

    /** Announces changes to lessons in the coming week. */
    fun announce(context: Context, changes: List<ScheduleChange>) {
        val now = Instant.now()
        val soon = changes.filter { it.relevantStart > now && it.relevantStart < now.plus(Duration.ofDays(7)) }
        soon.take(5).forEach { post(context, CHANGES, it.fingerprint.hashCode(), "${it.kind.title}: ${it.subject}", describe(it)) }
        if (soon.size > 5) post(context, CHANGES, 7_000, "Расписание изменилось", "Ещё ${soon.size - 5} изменений. Откройте «Изменения» в Политехе.")
    }

    /** Replaces all deadline reminders: a day and three hours before each open assignment. */
    fun scheduleDeadlines(context: Context, assignments: List<AssignmentDTO>, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(DEADLINE_TAG)
        if (!enabled) return
        val now = Instant.now()
        assignments.filter { !it.completedByMe && it.deletedAt == null && it.dueAt > now }.sortedBy { it.dueAt }.take(30).forEach { item ->
            listOf(Duration.ofDays(1) to "Завтра", Duration.ofHours(3) to "Через 3 часа").forEach { (offset, label) ->
                val fire = item.dueAt.minus(offset)
                if (fire <= now) return@forEach
                val text = "${item.subject} · до ${dayFormat.format(item.dueAt)} ${StudyClock.time(item.dueAt)}"
                work.enqueue(OneTimeWorkRequestBuilder<DeadlineWorker>()
                    .setInitialDelay(Duration.between(now, fire).toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("title" to "$label срок: ${item.title}", "text" to text, "id" to "${item.id}$label".hashCode()))
                    .addTag(DEADLINE_TAG).build())
            }
        }
    }

    class DeadlineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            post(applicationContext, DEADLINES, inputData.getInt("id", 0), inputData.getString("title").orEmpty(), inputData.getString("text").orEmpty())
            return Result.success()
        }
    }
}
