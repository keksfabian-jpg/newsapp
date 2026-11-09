package de.fabian.newstrader

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Markt für die Daytrade-Strategien, mit Börsenzeiten in der Zeitzone der Börse. */
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
    const val NY = "America/New_York"
    const val FRA = "Europe/Berlin"

    private fun us(sym: String, name: String, sentiment: String? = null) =
        DayAsset("ORB_$sym", name, sym, NY, 9, 30, 16, 0, true, sentiment, sym)

    private fun fut(id: String, name: String, yahoo: String, sentiment: String) =
        DayAsset(id, name, yahoo, NY, 9, 30, 16, 0, false, sentiment)

    /** Märkte für den Opening Range Breakout. */
    val orb = listOf(
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

    /** Futures für die zeitbasierten Strategien. */
    val futures = listOf(
        fut("F_NQ", "Nasdaq 100", "NQ=F", "NQ"),
        fut("F_ES", "S&P 500", "ES=F", "ES"),
        fut("F_GC", "Gold", "GC=F", "GOLD"),
        fut("F_CL", "Öl (WTI)", "CL=F", "OIL"),
    )

    val all = orb + futures

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class DayResult(val signals: List<Signal>, val created: List<Signal>, val closed: List<Signal>)

object Daytrade {
    const val TARGET_R = 10.0
    const val MIN_RVOL = 1.5
    private const val BAR = 300L
    private val NYZ: ZoneId = ZoneId.of(DayAssets.NY)

    private val IM_IDS = setOf("F_NQ", "F_ES")
    private val ON_IDS = setOf("F_NQ", "F_ES")
    private val SB_IDS = setOf("F_NQ", "F_ES", "F_GC")
    private val VWAP_IDS = setOf("F_NQ", "F_ES", "F_GC", "F_CL")

    // ---------------------------------------------------------------- Zeit-Helfer

    private fun zone(a: DayAsset): ZoneId = ZoneId.of(a.zone)
    private fun localDate(z: ZoneId, ts: Long): LocalDate = Instant.ofEpochSecond(ts).atZone(z).toLocalDate()
    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
    private fun at(z: ZoneId, d: LocalDate, h: Int, m: Int): Long = d.atTime(h, m).atZone(z).toEpochSecond()
    private fun et(d: LocalDate, h: Int, m: Int) = at(NYZ, d, h, m)
    private fun etToday(now: Long): LocalDate? = localDate(NYZ, now).takeIf { !weekend(it) }
    private fun etMinute(ts: Long): Int = Instant.ofEpochSecond(ts).atZone(NYZ).let { it.hour * 60 + it.minute }

    fun sessionOpen(a: DayAsset, now: Long): Long? {
        val d = localDate(zone(a), now)
        return if (weekend(d)) null else at(zone(a), d, a.openH, a.openM)
    }

    private fun sessionClose(a: DayAsset, ts: Long): Long = at(zone(a), localDate(zone(a), ts), a.closeH, a.closeM)

    private fun isOpenBar(a: DayAsset, ts: Long): Boolean {
        val t = Instant.ofEpochSecond(ts).atZone(zone(a))
        return t.hour == a.openH && t.minute == a.openM
    }

    private fun lastDone(cs: List<Candle>, now: Long) = cs.lastOrNull { it.ts + BAR <= now + 30 }

    private fun nextAt(z: ZoneId, h: Int, m: Int, now: Long): Long {
        var d = localDate(z, now)
        repeat(8) {
            if (!weekend(d)) {
                val t = at(z, d, h, m)
                if (t > now) return t
            }
            d = d.plusDays(1)
        }
        return at(z, d, h, m)
    }

    /** Nächste Eröffnungen (DAX und US) für die Anzeige. */
    fun nextOpens(now: Long): List<Pair<String, Long>> = listOf(
        "DAX-Eröffnung" to nextAt(ZoneId.of(DayAssets.FRA), 9, 0, now - BAR),
        "US-Eröffnung" to nextAt(NYZ, 9, 30, now - BAR),
    )

    /** Feste Zeitpunkte für gezielte Läufe in den nächsten Tagen. */
    fun nextRunTargets(now: Long): List<Long> {
        val fra = ZoneId.of(DayAssets.FRA)
        val ny = listOf(9 to 36, 10 to 16, 10 to 31, 10 to 46, 11 to 1, 15 to 31, 15 to 41, 15 to 56, 16 to 11)
        return (listOf(nextAt(fra, 9, 6, now)) + ny.map { (h, m) -> nextAt(NYZ, h, m, now) })
            .filter { it - now < 26 * 3600 }
            .distinct()
    }

    // ---------------------------------------------------------------- Ablauf

    /** Wann braucht ein Markt Kerzen? Nur in den Strategie-Fenstern oder bei offenem Trade (spart Akku). */
    private fun needed(a: DayAsset, now: Long): Boolean {
        if (a.id.startsWith("ORB_")) {
            val o = sessionOpen(a, now) ?: return false
            return now >= o + BAR && now <= o + 45 * 60
        }
        val d = etToday(now) ?: return false
        fun inWin(h1: Int, m1: Int, h2: Int, m2: Int) = now >= et(d, h1, m1) && now <= et(d, h2, m2)
        return (a.id in IM_IDS && inWin(15, 30, 15, 50)) ||
            (a.id in ON_IDS && inWin(15, 55, 16, 20)) ||
            (a.id in SB_IDS && inWin(10, 10, 11, 10)) ||
            (a.id in VWAP_IDS && inWin(10, 30, 10, 55))
    }

    fun step(
        current: List<Signal>,
        now: Long,
        s: Settings,
        sentiments: Map<String, Sentiment>,
        earnings: List<Earning>,
        errors: MutableList<String>,
    ): DayResult {
        val openAssets = current.filter { it.isOpen }.map { it.assetId }.toSet()
        val bySymbol = HashMap<String, List<Candle>>()
        val candles = HashMap<String, List<Candle>>()
        for (a in DayAssets.all) {
            if (a.id !in openAssets && !needed(a, now)) continue
            val cs = bySymbol[a.yahoo] ?: try {
                Sources.candles(a.yahoo, "5m").also { bySymbol[a.yahoo] = it }
            } catch (e: Exception) {
                errors += "${a.name}: ${friendly(e)}"
                null
            }
            if (cs != null) candles[a.id] = cs
        }

        val evaluated = current.map { sig -> candles[sig.assetId]?.let { evaluateTimed(sig, it, now, s, BAR) } ?: sig }
        val closed = evaluated.filter { e -> !e.isOpen && current.any { it.id == e.id && it.isOpen } }

        val all = evaluated.toMutableList()
        val created = ArrayList<Signal>()
        fun add(sig: Signal?) {
            if (sig == null || all.any { it.id == sig.id }) return
            all += sig
            created += sig
        }
        for (a in DayAssets.all) {
            val cs = candles[a.id] ?: continue
            try {
                if (a.id.startsWith("ORB_")) add(detectOrb(a, cs, now, sentiments, earnings))
                if (a.id in IM_IDS) add(detectIM(a, cs, now))
                if (a.id in ON_IDS) add(detectON(a, cs, now))
                if (a.id in SB_IDS) add(detectSB(a, cs, now))
                if (a.id in VWAP_IDS) add(detectVWAP(a, cs, now))
            } catch (e: Exception) {
                errors += "Strategie ${a.name}: ${friendly(e)}"
            }
        }
        return DayResult(all, created, closed)
    }

    /** Auswertung mit Stop/Ziel und geplantem Zeitausstieg (Kurs zur Eröffnung der Ausstiegskerze). */
    fun evaluateTimed(sig: Signal, candles: List<Candle>, now: Long, s: Settings, bar: Long): Signal {
        if (!sig.isOpen) return sig
        val end = if (sig.exitAt > 0) sig.exitAt else sig.ts + 24 * 3600
        val during = candles.filter { it.ts >= sig.ts && it.ts < end }
        for (c in during) {
            if (sig.dir == 1) {
                if (sig.sl > 0 && c.l <= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", s)
                if (sig.tp > 0 && c.h >= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", s)
            } else {
                if (sig.sl > 0 && c.h >= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", s)
                if (sig.tp > 0 && c.l <= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", s)
            }
        }
        if (now >= end + bar) {
            val atEnd = candles.firstOrNull { it.ts >= end && it.ts < end + 2 * bar }
            if (atEnd != null) return Engine.close(sig, atEnd.o, atEnd.ts, "EOD", s)
            // keine Kerze zum Ausstiegszeitpunkt (Daten verzögert oder Markt zu) → nach 30 min letzten Kurs nehmen
            val last = during.lastOrNull()
            if (last != null && now >= end + 1800) return Engine.close(sig, last.c, last.ts, "EOD", s)
            if (now - sig.ts > 48 * 3600) return Engine.close(sig, sig.entry, now, "EOD", s)
        }
        return sig
    }

    private fun mkSignal(
        code: String, a: DayAsset, key: String, ts: Long, dir: Int, entry: Double, sl: Double, tp: Double,
        exitAt: Long, reasons: List<String>, tags: List<String>,
    ) = Signal(
        id = "$code-${a.id}-$key",
        assetId = a.id,
        ts = ts,
        dir = dir,
        entry = entry,
        sl = sl,
        tp = tp,
        category = code,
        reason = reasons.joinToString("\n"),
        tags = tags + "Exit bis ${Fmt.time(exitAt)}",
        exitAt = exitAt,
    )

    private fun f1(v: Double) = String.format(Locale.GERMANY, "%.1f", v)

    // ---------------------------------------------------------------- 1) Opening Range Breakout

    private fun detectOrb(
        a: DayAsset, candles: List<Candle>, now: Long,
        sentiments: Map<String, Sentiment>, earnings: List<Earning>,
    ): Signal? {
        val open = sessionOpen(a, now) ?: return null
        if (now < open + BAR || now > open + 45 * 60) return null
        val first = candles.firstOrNull { it.ts == open } ?: return null
        val dir = when {
            first.c > first.o -> 1
            first.c < first.o -> -1
            else -> return null
        }
        val tags = ArrayList<String>()
        val reasons = ArrayList<String>()
        reasons += if (dir == 1) "Erste 5-min-Kerze grün (${Fmt.price(first.o)} → ${Fmt.price(first.c)}) → Long"
        else "Erste 5-min-Kerze rot (${Fmt.price(first.o)} → ${Fmt.price(first.c)}) → Short"

        val today = localDate(zone(a), now).toString()
        val earningsToday = a.finnhub != null && earnings.any { it.symbol == a.finnhub && it.date == today }
        if (a.rvolFilter) {
            val prev = candles.filter { it.ts < open && isOpenBar(a, it.ts) && it.v > 0 }.map { it.v }
            if (first.v <= 0 || prev.isEmpty()) {
                if (!earningsToday) return null
            } else {
                val rvol = first.v / prev.average()
                if (rvol < MIN_RVOL && !earningsToday) return null
                tags += "RVOL ${f1(rvol)}×"
                reasons += "Aktie „in play“: Eröffnungsvolumen ${f1(rvol)}× so hoch wie an den Vortagen"
            }
        }
        if (earningsToday) tags += "Earnings heute"

        val last = candles.lastOrNull { it.ts + BAR <= now + 30 && it.ts >= open } ?: return null
        val entry = last.c
        val stop = if (dir == 1) first.l else first.h
        val risk = (entry - stop) * dir
        val range = first.h - first.l
        if (range <= 0 || risk <= 0 || risk > range * 2.5) return null
        val closeTs = sessionClose(a, open)
        reasons += "Stop am anderen Ende der Kerze (${Fmt.price(stop)}), Ziel 10R, sonst Exit zum Handelsschluss"

        val sent = a.sentimentId?.let { sentiments[it] }?.takeIf { now - it.ts < 6 * 3600 }
        if (sent != null) {
            tags += "News ${Fmt.score(sent.score)}" + when {
                sent.score * dir > 0.15 -> " ✓"
                sent.score * dir < -0.15 -> " ✗"
                else -> ""
            }
        }
        return mkSignal(CAT_ORB, a, "$open", last.ts + BAR, dir, entry, stop, entry + dir * TARGET_R * risk, closeTs, reasons, tags)
    }

    // ---------------------------------------------------------------- 2) Intraday-Momentum

    private fun detectIM(a: DayAsset, cs: List<Candle>, now: Long): Signal? {
        val d = etToday(now) ?: return null
        val t = et(d, 15, 30)
        if (now < t || now > t + 20 * 60) return null
        val prevClose = cs.lastOrNull { localDate(NYZ, it.ts) < d && etMinute(it.ts) in (15 * 60 + 30)..(15 * 60 + 55) }?.c ?: return null
        val at10 = cs.firstOrNull { it.ts == et(d, 9, 55) } ?: return null
        val r1 = at10.c / prevClose - 1
        if (abs(r1) < 0.0025) return null
        val dir = if (r1 > 0) 1 else -1
        val last = lastDone(cs, now) ?: return null
        if (last.ts < t - 25 * 60) return null
        val entry = last.c
        return mkSignal(
            CAT_IM, a, d.toString(), last.ts + BAR, dir, entry, entry * (1 - dir * 0.006), 0.0, et(d, 16, 0),
            listOf(
                "Vortagesschluss → 16:00 Uhr (dt.): ${Fmt.pct(r1)}",
                "Die letzte halbe Stunde folgt statistisch oft der ersten → ${if (dir == 1) "Long" else "Short"} bis Handelsschluss",
                "Notstop 0,6 %",
            ),
            listOf("Erste 30 min ${Fmt.pct(r1)}"),
        )
    }

    // ---------------------------------------------------------------- 3) Overnight-Effekt

    private fun detectON(a: DayAsset, cs: List<Candle>, now: Long): Signal? {
        val d = etToday(now) ?: return null
        if (d.dayOfWeek == DayOfWeek.FRIDAY) return null // Wochenende wäre > 24 h
        val t = et(d, 15, 50)
        if (now < t || now > et(d, 16, 20)) return null
        val last = lastDone(cs, now) ?: return null
        if (last.ts < t - 20 * 60) return null
        val entry = last.c
        val exit = et(d.plusDays(1), 9, 30)
        return mkSignal(
            CAT_ON, a, d.toString(), last.ts + BAR, 1, entry, entry * 0.99, 0.0, exit,
            listOf(
                "Long kurz vor US-Schluss, Verkauf zur US-Eröffnung morgen",
                "Ein großer Teil der Aktienrendite entsteht historisch über Nacht",
                "Notstop 1 %",
            ),
            listOf("Über Nacht"),
        )
    }

    // ---------------------------------------------------------------- 4) ICT Silver Bullet (NY AM)

    private fun detectSB(a: DayAsset, cs: List<Candle>, now: Long): Signal? {
        val d = etToday(now) ?: return null
        val ws = et(d, 10, 0)
        val we = et(d, 11, 0)
        if (now < ws + 15 * 60 || now > we + 10 * 60) return null
        val orBars = cs.filter { it.ts >= et(d, 9, 30) && it.ts < ws }
        if (orBars.size < 5) return null
        val rh = orBars.maxOf { it.h }
        val rl = orBars.minOf { it.l }
        val win = cs.filter { it.ts >= ws && it.ts < we && it.ts + BAR <= now + 30 }
        if (win.size < 3) return null

        var sweepDir = 0
        var sweepIdx = -1
        var ext = 0.0
        for (i in win.indices) {
            val b = win[i]
            if (sweepDir == 0) {
                if (b.h > rh) {
                    sweepDir = -1; sweepIdx = i; ext = b.h
                } else if (b.l < rl) {
                    sweepDir = 1; sweepIdx = i; ext = b.l
                }
                continue
            }
            ext = if (sweepDir == -1) max(ext, b.h) else min(ext, b.l)
            if (i - 2 < sweepIdx) continue
            val b1 = win[i - 2]
            val fvg = if (sweepDir == -1) b1.l > b.h else b1.h < b.l
            if (!fvg) continue
            // FVG gefunden – nur handeln, wenn sie frisch ist
            val last = lastDone(cs, now) ?: return null
            if (last.ts - b.ts > 15 * 60) return null
            val entry = last.c
            val sl = ext * (1 - sweepDir * 0.0002)
            val risk = (entry - sl) * sweepDir
            if (risk <= 0) return null
            val gapLo = if (sweepDir == -1) b.h else b1.h
            val gapHi = if (sweepDir == -1) b1.l else b.l
            return mkSignal(
                CAT_SB, a, d.toString(), last.ts + BAR, sweepDir, entry, sl, entry + sweepDir * 2 * risk, et(d, 16, 0),
                listOf(
                    if (sweepDir == -1) "Hoch der ersten 30 min (${Fmt.price(rh)}) gesweept" else "Tief der ersten 30 min (${Fmt.price(rl)}) gesweept",
                    "Fair Value Gap ${Fmt.price(gapLo)}–${Fmt.price(gapHi)} in Gegenrichtung entstanden",
                    "Stop hinter dem Sweep-Extrem, Ziel 2R",
                ),
                listOf("Sweep + FVG"),
            )
        }
        return null
    }

    // ---------------------------------------------------------------- 5) Orderflow-Näherung (VWAP + Volumen-Delta)

    private fun detectVWAP(a: DayAsset, cs: List<Candle>, now: Long): Signal? {
        val d = etToday(now) ?: return null
        val t = et(d, 10, 30)
        if (now < t || now > t + 25 * 60) return null
        val bars = cs.filter { it.ts >= et(d, 9, 30) && it.ts < t && it.v > 0 }
        if (bars.size < 8) return null
        val vol = bars.sumOf { it.v }
        val vwap = bars.sumOf { (it.h + it.l + it.c) / 3 * it.v } / vol
        val delta = bars.sumOf { it.v * (it.c - it.o) / max(it.h - it.l, 1e-9) }
        val last = lastDone(cs, now) ?: return null
        if (last.ts < t - 25 * 60) return null
        val price = last.c
        val dist = (price - vwap) / vwap
        val pressure = delta / vol
        val dir = when {
            dist > 0.001 && pressure > 0.1 -> 1
            dist < -0.001 && pressure < -0.1 -> -1
            else -> return null
        }
        val risk = max(abs(price - vwap) * 1.05, price * 0.0015)
        val sl = price - dir * risk
        return mkSignal(
            CAT_VWAP, a, d.toString(), last.ts + BAR, dir, price, sl, price + dir * 2 * risk, et(d, 16, 0),
            listOf(
                "Kurs ${Fmt.pct(dist)} ${if (dir == 1) "über" else "unter"} dem VWAP seit Eröffnung (${Fmt.price(vwap)})",
                "Kaufdruck-Näherung aus Volumen: ${Fmt.pct(pressure)} → Trend in dieselbe Richtung",
                "Stop hinter dem VWAP, Ziel 2R",
            ),
            listOf("VWAP ${Fmt.pct(dist)}", "Delta ${Fmt.pct(pressure)}"),
        )
    }

    // ---------------------------------------------------------------- 6) News pur (läuft auf den 15-min-Kerzen im Runner)

    fun detectNews(a: Asset, cs: List<Candle>, sent: Sentiment, now: Long, existing: List<Signal>): Signal? {
        if (abs(sent.score) < 0.6) return null
        val last = cs.lastOrNull { it.ts + 900 <= now + 30 } ?: return null
        if (now - (last.ts + 900) > 30 * 60) return null // Markt gerade zu
        val dir = if (sent.score > 0) 1 else -1
        val day = Engine.dayStart(now)
        val id = "$CAT_NEWS-${a.id}-$day-$dir"
        if (existing.any { it.id == id }) return null
        val recent = cs.takeLast(14)
        if (recent.size < 5) return null
        val atr = recent.sumOf { it.h - it.l } / recent.size
        if (atr <= 0) return null
        val entry = last.c
        val exitAt = last.ts + 900 + 4 * 3600
        return Signal(
            id = id,
            assetId = a.id,
            ts = last.ts + 900,
            dir = dir,
            entry = entry,
            sl = entry - dir * 1.5 * atr,
            tp = entry + dir * 3 * atr,
            category = CAT_NEWS,
            reason = "KI-Sentiment ${Fmt.score(sent.score)}: ${sent.reason}\nSofort in News-Richtung, Stop 1,5× ATR, Ziel 3× ATR, Exit nach 4 h",
            tags = listOf("KI ${Fmt.score(sent.score)}", "Exit bis ${Fmt.time(exitAt)}"),
            exitAt = exitAt,
        )
    }
}
