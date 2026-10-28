package ru.expeline.polytech.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.expeline.polytech.PolytechApplication
import ru.expeline.polytech.data.StudyClock
import ru.expeline.polytech.notification.LessonNotification
import ru.expeline.polytech.widget.ScheduleWidget

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PolytechApplication
        return runCatching {
            val errors = app.repository.refresh(StudyClock.today())
            val data = app.repository.state.value
            LessonNotification.sync(app, app.repository.activeOrNext(value = data), data)
            ScheduleWidget.updateAll(app)
            if (errors.isEmpty()) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }
}

