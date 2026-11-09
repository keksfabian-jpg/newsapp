package de.fabian.newstrader

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notify {
    private const val CH_SIGNAL = "signals"
    private const val CH_RESULT = "results"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SIGNAL, "Trade-Vorschläge", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Ergebnisse", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun signal(ctx: Context, s: Signal, daytrade: Boolean = false) {
        val side = if (s.dir == 1) "LONG" else "SHORT"
        val prefix = if (daytrade) "Daytrade: " else ""
        val title = "$prefix$side ${assetName(s.assetId)} @ ${Fmt.price(s.entry)}"
        val text = "SL ${Fmt.price(s.sl)} · TP ${Fmt.price(s.tp)}\n${s.reason}"
        show(ctx, CH_SIGNAL, s.id.hashCode(), title, text)
    }

    fun result(ctx: Context, s: Signal) {
        val how = when (s.status) {
            "TP" -> "Take-Profit erreicht"
            "SL" -> "Stop-Loss erreicht"
            "EOD" -> "zum Handelsschluss geschlossen"
            else -> "nach 24 h geschlossen"
        }
        show(ctx, CH_RESULT, s.id.hashCode() + 1, "${assetName(s.assetId)}: ${Fmt.eur(s.pnlEur)}", "$how (${Fmt.pct(s.pct)} im Basiswert)")
    }

    @SuppressLint("MissingPermission")
    private fun show(ctx: Context, channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF3B82F6.toInt())
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
