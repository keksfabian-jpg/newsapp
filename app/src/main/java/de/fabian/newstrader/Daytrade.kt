package de.fabian.newstrader

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** Markt für die Daytrade-Strategie, mit Börsenzeiten in der lokalen Zeitzone der Börse. */
data class DayAsset(
    val id: String,
    val name: String,
    val yahoo: String,
    val zone: String,
    val openH: Int,
    val openM: Int,
    val closeH: Int,
    val closeM: Int,
    val rvolFilter: Boolean,
    val sentimentId: String? = null,
    val finnhub: String? = null,
)

object DayAssets {
    private const val NY = "America/New_York"
    private const val FRA = "Europe/Berlin"

    private fun us(sym: String, name: String, sentiment: String? = null) =
        DayAsset("ORB_$sym", name, sym, NY, 9, 30, 16, 0, true, sentiment, sym)

    val all = listOf(
        DayAsset("ORB_DAX", "DAX", "^GDAXI", FRA, 9, 0, 17, 30, false, "DAX"),
        DayAsset("ORB_NQ", "Nasdaq 100", "NQ=F", NY, 9, 30, 16, 0, false, "NQ"),
        DayAsset("ORB_ES", "S&P 500", "ES=F", NY, 9, 30, 16, 0, false, "ES"),
        us("TSLA", "Tesla"),
        us("NVDA", "Nvidia", "NVDA"),
        us("AMD", "AMD"),
        us("META", "Meta"),
        us("AAPL", "Apple", "AAPL"),
        us("AMZN", "Amazon"),
        us("MSFT", "Microsoft"),
        us("NFLX", "Netflix"),
        us("PLTR", "Palantir"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class DayResult(val signals: List<Signal>, val created: List<Signal>, val closed: List<Signal>)

/**
 * Opening Range Breakout (5 min):
 * - Erste 5-min-Kerze nach Börseneröffnung bestimmt die Richtung (grün = Long, rot = Short).
 * - Aktien nur "in play": Volumen der ersten Kerze ≥ 1,5× Durchschnitt der Vortage, oder Earnings-Tag.
 * - Stop am anderen Ende der ersten Kerze, Ziel 10R, sonst Ausstieg zum Handelsschluss.
 */
object Daytrade {
    const val CAT = "ORB"
    const val TARGET_R = 10.0
    const val MIN_RVOL = 1.5
    private const val BAR = 300L
    private const val ENTRY_WINDOW = 45 * 60L

    private fun zone(a: DayAsset): ZoneId = ZoneId.of(a.zone)

    private fun localDate(a: DayAsset, ts: Long): LocalDate = Instant.ofEpochSecond(ts).atZone(zone(a)).toLocalDate()

    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    fun openOn(a: DayAsset, d: LocalDate): Long = d.atTime(a.openH, a.openM).atZone(zone(a)).toEpochSecond()

    fun sessionOpen(a: DayAsset, now: Long): Long? {
        val d = localDate(a, now)
        return if (weekend(d)) null else openOn(a, d)
    }

    fun sessionClose(a: DayAsset, ts: Long): Long {
        val d = localDate(a, ts)
        return d.atTime(a.closeH, a.closeM).atZone(zone(a)).toEpochSecond()
    }

    private fun isOpenBar(a: DayAsset, ts: Long): Boolean {
        val t = Instant.ofEpochSecond(ts).atZone(zone(a))
        return t.hour == a.openH && t.minute == a.openM
    }

    /** Kerzen nur laden, wenn gerade Eröffnung ist oder ein offener Trade läuft (spart Akku). */
    private fun relevant(a: DayAsset, now: Long): Boolean {
        val o = sessionOpen(a, now) ?: return false
        return now >= o + BAR && now <= o + ENTRY_WINDOW
    }

    /** Nächste Eröffnungen (DAX und US) für die Anzeige. */
    fun nextOpens(now: Long): List<Pair<String, Long>> {
        val dax = DayAssets.all.first { it.id == "ORB_DAX" }
        val us = DayAssets.all.first { it.id == "ORB_NQ" }
        return listOf("DAX-Eröffnung" to nextOpen(dax, now), "US-Eröffnung" to nextOpen(us, now))
    }

    private fun nextOpen(a: DayAsset, now: Long): Long {
        var d = localDate(a, now)
        repeat(8) {
            if (!weekend(d)) {
                val o = openOn(a, d)
                if (o + BAR >= now) return o
            }
            d = d.plusDays(1)
        }
        return openOn(a, d)
    }

    /** Zeitpunkt für einen gezielten Lauf kurz nach der nächsten Eröffnung. */
    fun nextRunTarget(now: Long): Long = nextOpens(now).minOf { it.second } + 6 * 60

    fun step(
        current: List<Signal>,
        now: Long,
        s: Settings,
        sentiments: Map<String, Sentiment>,
        earnings: List<Earning>,
        errors: MutableList<String>,
    ): DayResult {
        val openIds = current.filter { it.isOpen }.map { it.assetId }.toSet()
        val candles = HashMap<String, List<Candle>>()
        for (a in DayAssets.all) {
            if (a.id !in openIds && !relevant(a, now)) continue
            try {
                candles[a.id] = Sources.candles(a.yahoo, "5m")
            } catch (e: Exception) {
                errors += "Daytrade ${a.name}: ${friendly(e)}"
            }
        }

        val evaluated = current.map { sig ->
            val a = DayAssets.byId(sig.assetId)
            val cs = candles[sig.assetId]
            if (a == null || cs == null) sig else evaluate(a, sig, cs, now, s)
        }
        val closed = evaluated.filter { e -> !e.isOpen && current.any { it.id == e.id && it.isOpen } }

        val all = evaluated.toMutableList()
        val created = ArrayList<Signal>()
        for (a in DayAssets.all) {
            val cs = candles[a.id] ?: continue
            val sig = detect(a, cs, now, sentiments, earnings) ?: continue
            if (all.any { it.id == sig.id }) continue
            all += sig
            created += sig
        }
        return DayResult(all, created, closed)
    }

    private fun detect(
        a: DayAsset,
        candles: List<Candle>,
        now: Long,
        sentiments: Map<String, Sentiment>,
        earnings: List<Earning>,
    ): Signal? {
        val open = sessionOpen(a, now) ?: return null
        if (now < open + BAR || now > open + ENTRY_WINDOW) return null
        val first = candles.firstOrNull { it.ts == open } ?: return null
        val dir = when {
            first.c > first.o -> 1
            first.c < first.o -> -1
            else -> return null
        }

        val tags = ArrayList<String>()
        val reasons = ArrayList<String>()
        val today = localDate(a, now).toString()
        val earningsToday = a.finnhub != null && earnings.any { it.symbol == a.finnhub && it.date == today }

        if (a.rvolFilter) {
            val prev = candles.filter { it.ts < open && isOpenBar(a, it.ts) && it.v > 0 }.map { it.v }
            if (first.v <= 0 || prev.isEmpty()) {
                if (!earningsToday) return null
            } else {
                val rvol = first.v / prev.average()
                if (rvol < MIN_RVOL && !earningsToday) return null
                tags += "RVOL ${String.format(java.util.Locale.GERMANY, "%.1f", rvol)}×"
                reasons += "Aktie ist „in play“: Eröffnungsvolumen ${String.format(java.util.Locale.GERMANY, "%.1f", rvol)}× so hoch wie an den Vortagen"
            }
        }
        if (earningsToday) tags += "Earnings heute"

        val last = candles.lastOrNull { it.ts + BAR <= now + 30 && it.ts >= open } ?: return null
        val entry = last.c
        val stop = if (dir == 1) first.l else first.h
        val risk = (entry - stop) * dir
        val range = first.h - first.l
        if (range <= 0 || risk <= 0) return null // Stop schon gerissen
        if (risk > range * 2.5) return null // Kurs schon zu weit gelaufen
        val tp = entry + dir * TARGET_R * risk
        val closeTs = sessionClose(a, open)

        reasons.add(
            0,
            if (dir == 1) "Erste 5-min-Kerze grün (${Fmt.price(first.o)} → ${Fmt.price(first.c)}) → Long"
            else "Erste 5-min-Kerze rot (${Fmt.price(first.o)} → ${Fmt.price(first.c)}) → Short",
        )
        reasons += "Stop am anderen Ende der Kerze (${Fmt.price(stop)}), Ziel 10R. Spätestens um ${Fmt.time(closeTs)} schließen."

        val sentId = a.sentimentId
        val sent = if (sentId != null) sentiments[sentId]?.takeIf { now - it.ts < 6 * 3600 } else null
        if (sent != null) {
            val agree = sent.score * dir > 0.15
            val against = sent.score * dir < -0.15
            tags += "News ${Fmt.score(sent.score)}" + when {
                agree -> " ✓"
                against -> " ✗"
                else -> ""
            }
        }
        tags += "Exit bis ${Fmt.time(closeTs)}"

        return Signal(
            id = "${a.id}-$open",
            assetId = a.id,
            ts = last.ts + BAR,
            dir = dir,
            entry = entry,
            sl = stop,
            tp = tp,
            category = CAT,
            reason = reasons.joinToString("\n"),
            tags = tags,
        )
    }

    private fun evaluate(a: DayAsset, sig: Signal, candles: List<Candle>, now: Long, s: Settings): Signal {
        if (!sig.isOpen) return sig
        val closeTs = sessionClose(a, sig.ts - BAR)
        val during = candles.filter { it.ts >= sig.ts && it.ts < closeTs }
        for (c in during) {
            if (sig.dir == 1) {
                if (c.l <= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", s)
                if (c.h >= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", s)
            } else {
                if (c.h >= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", s)
                if (c.l <= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", s)
            }
        }
        if (now >= closeTs + 60 || now - sig.ts > 24 * 3600) {
            val last = during.lastOrNull() ?: candles.lastOrNull { it.ts < max(closeTs, sig.ts + BAR) }
            if (last != null) return Engine.close(sig, last.c, last.ts, "EOD", s)
        }
        return sig
    }
}
