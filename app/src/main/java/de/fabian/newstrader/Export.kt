package de.fabian.newstrader

import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Alle Trades als CSV (Semikolon, deutsches Excel-freundlich) über das Teilen-Menü. */
object Export {
    private val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun t(sec: Long) = if (sec <= 0) "" else dt.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    private fun n(v: Double) = if (v == 0.0) "" else String.format(java.util.Locale.ROOT, "%.6g", v)
    private fun q(s: String) = "\"" + s.replace("\"", "'").replace("\n", " | ") + "\""

    fun csv(signals: List<Signal>): String {
        val sb = StringBuilder()
        sb.append("strategie;markt;richtung;zeit;einstieg;stop;ziel;geplanter_exit;status;ausstieg;ausstiegszeit;bewegung_pct;pnl_eur;tags;begruendung\n")
        for (s in signals.sortedBy { it.ts }) {
            sb.append(Strategies.name(s.category)).append(';')
                .append(assetName(s.assetId)).append(';')
                .append(if (s.dir == 1) "LONG" else "SHORT").append(';')
                .append(t(s.ts)).append(';')
                .append(n(s.entry)).append(';')
                .append(n(s.sl)).append(';')
                .append(n(s.tp)).append(';')
                .append(t(s.exitAt)).append(';')
                .append(s.status).append(';')
                .append(n(s.exitPrice)).append(';')
                .append(t(s.exitTs)).append(';')
                .append(if (s.isOpen) "" else String.format(java.util.Locale.ROOT, "%.4f", s.pct * 100)).append(';')
                .append(if (s.isOpen) "" else String.format(java.util.Locale.ROOT, "%.2f", s.pnlEur)).append(';')
                .append(q(s.tags.joinToString(", "))).append(';')
                .append(q(s.reason)).append('\n')
        }
        return sb.toString()
    }

    fun share(ctx: Context, signals: List<Signal>) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "NewsTrader – Trades")
            .putExtra(Intent.EXTRA_TEXT, csv(signals))
        ctx.startActivity(Intent.createChooser(send, "Trades exportieren").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
