package de.fabian.newstrader

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        Runner.run(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Store.update { it.copy(lastError = "Update fehlgeschlagen: ${e.message}") }
        Result.retry()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("refresh-now", ExistingWorkPolicy.KEEP, req)
        }
    }
}
