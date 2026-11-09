package de.fabian.newstrader

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

data class Sweep(
    val dir: Int,
    val ts: Long,
    val close: Double,
    val extreme: Double,
    val refHigh: Double,
    val refLow: Double,
    val refLabel: String,
)

/**
 * Signallogik:
 * 1. Referenz-Range: Asia-Session (00–07 UTC) bzw. Vortag bei Aktien/DAX.
 * 2. Sweep: Kerze sticht über/unter die Range und schließt wieder innerhalb → Gegenrichtung.
 * 3. Vorschlag nur, wenn KI-Sentiment in dieselbe Richtung zeigt und kein Top-Termin in < 30 min ansteht.
 *    Alle anderen Sweeps werden still als "Nur Sweep" mitprotokolliert (Vergleich).
 */
object Engine {
    private const val BAR = 900L

    fun dayStart(ts: Long) = ts - Math.floorMod(ts, 86_400L)

    fun detect(a: Asset, candles: List<Candle>, now: Long): Sweep? {
        if (candles.size < 20) return null
        val today = dayStart(now)
        val minute = ((now - today) / 60).toInt()
        if (minute < a.windowStartMin || minute > a.windowEndMin) return null

        val refHigh: Double
        val refLow: Double
        val label: String
        when (a.ref) {
            RefType.ASIA -> {
                val asia = candles.filter { it.ts >= today && it.ts < today + 7 * 3600 }
                if (asia.size < 12) return null
                refHigh = asia.maxOf { it.h }
                refLow = asia.minOf { it.l }
                label = "Asia-Range"
            }
            RefType.PREVDAY -> {
                val prevDay = candles.map { dayStart(it.ts) }.filter { it < today }.maxOrNull() ?: return null
                val prev = candles.filter { dayStart(it.ts) == prevDay }
                if (prev.size < 8) return null
                refHigh = prev.maxOf { it.h }
                refLow = prev.minOf { it.l }
                label = "Vortages-Range"
            }
        }

        val windowStart = today + a.windowStartMin * 60L
        val done = candles.filter { it.ts >= windowStart && it.ts + BAR <= now + 30 }
        for (c in done.takeLast(2).reversed()) {
            if (c.h > refHigh && c.c < refHigh) return Sweep(-1, c.ts, c.c, c.h, refHigh, refLow, label)
            if (c.l < refLow && c.c > refLow) return Sweep(1, c.ts, c.c, c.l, refHigh, refLow, label)
        }
        return null
    }

    fun build(
        a: Asset,
        sw: Sweep,
        sent: Sentiment?,
        settings: Settings,
        cal: List<CalEvent>,
        earnings: List<Earning>,
        now: Long,
    ): Signal {
        val risk = max(abs(sw.close - sw.extreme) * 1.15, sw.close * 0.0008)
        val sl = sw.close - sw.dir * risk
        val tp = sw.close + sw.dir * 2 * risk

        val tags = ArrayList<String>()
        val reasons = ArrayList<String>()
        tags += "Sweep ${sw.refLabel}"
        reasons += if (sw.dir == 1) {
            "Tief der ${sw.refLabel} (${Fmt.price(sw.refLow)}) unterschritten und zurückerobert"
        } else {
            "Hoch der ${sw.refLabel} (${Fmt.price(sw.refHigh)}) überschritten und darunter geschlossen"
        }

        var aligned = false
        if (sent != null) {
            tags += "KI ${Fmt.score(sent.score)}"
            aligned = sent.score * sw.dir >= settings.minScore
            reasons += "KI-Sentiment: ${sent.reason}"
            if (!aligned) reasons += "Sentiment bestätigt die Richtung nicht ausreichend"
        } else {
            reasons += "Kein aktuelles KI-Sentiment (API-Keys in den Einstellungen?)"
        }

        val soon = cal.filter { it.impact == "High" && it.country in a.currencies && it.ts > now && it.ts - now < 30 * 60 }
        if (soon.isNotEmpty()) {
            tags += "Termin < 30 min"
            reasons += "Gesperrt: ${soon.first().country} ${soon.first().title} um ${Fmt.time(soon.first().ts)}"
            aligned = false
        }
        val recent = cal.filter { it.impact == "High" && it.country in a.currencies && it.ts <= now && now - it.ts < 90 * 60 }
        if (recent.isNotEmpty()) tags += "Nach ${recent.first().country} ${recent.first().title}"

        if (a.finnhub != null) {
            val today = LocalDate.ofEpochDay(now / 86_400).toString()
            if (earnings.any { it.symbol == a.finnhub && it.date == today }) tags += "Earnings heute"
        }

        return Signal(
            id = "${a.id}-${sw.ts}-${sw.dir}",
            assetId = a.id,
            ts = sw.ts,
            dir = sw.dir,
            entry = sw.close,
            sl = sl,
            tp = tp,
            category = if (aligned) CAT_PROPOSAL else CAT_SWEEP_ONLY,
            reason = reasons.joinToString("\n"),
            tags = tags,
        )
    }

    /** Paper-Auswertung: SL zuerst prüfen (konservativ), nach 24 h zum letzten Kurs schließen. */
    fun evaluate(sig: Signal, candles: List<Candle>, now: Long, s: Settings): Signal {
        if (!sig.isOpen) return sig
        val after = candles.filter { it.ts >= sig.ts + BAR }
        for (c in after) {
            if (sig.dir == 1) {
                if (c.l <= sig.sl) return close(sig, sig.sl, c.ts, "SL", s)
                if (c.h >= sig.tp) return close(sig, sig.tp, c.ts, "TP", s)
            } else {
                if (c.h >= sig.sl) return close(sig, sig.sl, c.ts, "SL", s)
                if (c.l <= sig.tp) return close(sig, sig.tp, c.ts, "TP", s)
            }
        }
        if (now - sig.ts > 24 * 3600 && after.isNotEmpty()) {
            val last = after.last()
            return close(sig, last.c, last.ts, "ZEIT", s)
        }
        return sig
    }

    fun close(sig: Signal, exit: Double, ts: Long, status: String, s: Settings): Signal {
        val pct = (exit - sig.entry) / sig.entry * sig.dir
        val pnl = max(s.stake * s.leverage * pct, -s.stake) - 2.0
        return sig.copy(status = status, exitPrice = exit, exitTs = ts, pct = pct, pnlEur = pnl)
    }
}
