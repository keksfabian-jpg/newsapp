package de.fabian.newstrader

import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Alle Trades als CSV (Semikolon) plus Lauf-Protokoll über das Teilen-Menü. */
object Export {
    private val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun t(sec: Long) = if (sec <= 0) "" else dt.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    private fun n(v: Double) = if (v == 0.0) "" else String.format(Locale.ROOT, "%.6g", v)
    private fun f(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
    private fun q(s: String) = "\"" + s.replace("\"", "'").replace("\n", " | ") + "\""

    fun csv(signals: List<Signal>, st: Settings, log: List<String>): String {
        val sb = StringBuilder()
        sb.append("# Profil: Hebel ${Sizing.label(st.lev)}, Einsatz ${f(st.stake, 0)} EUR, Startkapital ${f(st.startCapital, 0)} EUR\n")
        sb.append("strategie;markt;richtung;zeit;einstieg;stop;ziel;geplanter_exit;status;ausstieg;ausstiegszeit;bewegung_pct;r;max_gegenbewegung_pct;hebel;einsatz_eur;ko;pnl_eur;tags;begruendung\n")
        for (s in signals.sortedBy { it.ts }) {
            val p = Sizing.position(s, st)
            sb.append(Strategies.short(s.category)).append(';')
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
                .append(if (s.isOpen) "" else f(s.pct * 100, 4)).append(';')
                .append(if (s.isOpen) "" else f(p.r, 2)).append(';')
                .append(if (s.isOpen || s.mae < 0) "" else f(s.mae * 100, 4)).append(';')
                .append(f(p.lev, 0)).append(';')
                .append(f(p.stake, 0)).append(';')
                .append(if (s.isOpen) "" else if (p.ko) "ja" else "nein").append(';')
                .append(if (s.isOpen) "" else f(p.pnl, 2)).append(';')
                .append(q(s.tags.joinToString(", "))).append(';')
                .append(q(s.reason)).append('\n')
        }
        sb.append("\n# Protokoll (neueste zuerst)\n")
        log.take(120).forEach { sb.append("# ").append(it).append('\n') }
        return sb.toString()
    }

    fun share(ctx: Context, state: AppState) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "NewsTrader – Trades")
            .putExtra(Intent.EXTRA_TEXT, csv(state.signals.take(600), state.settings, state.log))
        ctx.startActivity(Intent.createChooser(send, "Trades exportieren").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
