package ru.expeline.polytech

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.expeline.polytech.data.ScheduleRepository
import ru.expeline.polytech.work.RefreshWorker
import java.util.concurrent.TimeUnit

class PolytechApplication : Application() {
    val repository by lazy { ScheduleRepository(this) }
    override fun onCreate() {
        super.onCreate()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("schedule-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

