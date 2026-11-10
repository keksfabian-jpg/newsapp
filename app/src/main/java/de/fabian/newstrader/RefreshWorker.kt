package de.fabian.newstrader

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        Runner.run(applicationContext, inputData.getString("src") ?: "Takt")
        Result.success()
    } catch (e: Exception) {
        Store.update { it.copy(lastError = "Update fehlgeschlagen: ${e.message}") }
        Result.retry()
    }

    /** Nur auf älteren Android-Versionen nötig, wenn die Arbeit als „dringend“ läuft. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notify.channels(applicationContext)
        val n: Notification = NotificationCompat.Builder(applicationContext, "results")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NewsTrader aktualisiert")
            .build()
        return ForegroundInfo(7, n)
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
            Alarms.scheduleNext(ctx)
        }

        /** Sofortiger Lauf; „dringend“, damit Android ihn auch im Stromsparmodus zeitnah startet. */
        fun runNow(ctx: Context, src: String = "Manuell") {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(net)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf("src" to src))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("refresh-now", ExistingWorkPolicy.KEEP, req)
        }
    }
}
