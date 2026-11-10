package de.fabian.newstrader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Exakter Wecker zu den Strategie-Zeitpunkten – Android verschiebt normale Hintergrundarbeit sonst im Stromsparmodus. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        RefreshWorker.runNow(ctx, "Wecker")
        Alarms.scheduleNext(ctx)
    }
}

object Alarms {
    fun scheduleNext(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val at = Daytrade.nextAlarm(System.currentTimeMillis() / 1000 + 30) * 1000
            val pi = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
        }
    }

    fun exactAllowed(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }
}
