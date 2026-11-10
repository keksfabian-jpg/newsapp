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
    val cur: List<String> = listOf("USD"),
)

object DayAssets {
    const val NY = "America/New_York"
    const val FRA = "Europe/Berlin"

    private fun us(sym: String, name: String, sentiment: String? = null) =
        DayAsset("ORB_$sym", name, sym, NY, 9, 30, 16, 0, true, sentiment, sym)

    private fun fut(id: String, name: String, yahoo: String, sentiment: String, cur: List<String> = listOf("USD")) =
        DayAsset(id, name, yahoo, NY, 9, 30, 16, 0, false, sentiment, null, cur)

    /** Märkte für den Opening Range Breakout. */
    val orb = listOf(
        DayAsset("ORB_DAX", "DAX", "^GDAXI", FRA, 9, 0, 17, 30, false, "DAX", null, listOf("EUR")),
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

    /** Asien-Session. */
    val asia = listOf(
        DayAsset("A_N225", "Nikkei 225", "^N225", "Asia/Tokyo", 9, 0, 15, 30, false, null, null, listOf("JPY")),
        DayAsset("A_HSI", "Hang Seng", "^HSI", "Asia/Hong_Kong", 9, 30, 16, 0, false, null, null, listOf("CNY")),
    )

    val nq = fut("F_NQ", "Nasdaq 100", "NQ=F", "NQ")
    val es = fut("F_ES", "S&P 500", "ES=F", "ES")
    val gold = fut("F_GC", "Gold", "GC=F", "GOLD")
    val oil = fut("F_CL", "Öl (WTI)", "CL=F", "OIL")
    val eurusd = fut("F_EU", "EUR/USD", "EURUSD=X", "EURUSD", listOf("EUR", "USD"))
    val gbpusd = fut("F_GU", "GBP/USD", "GBPUSD=X", "GBPUSD", listOf("GBP", "USD"))
    val usdjpy = fut("F_UJ", "USD/JPY", "JPY=X", "USDJPY", listOf("USD", "JPY"))

    val all = orb + asia + listOf(nq, es, gold, oil, eurusd, gbpusd, usdjpy)

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Entscheidung einer Strategie für einen Markt und Tag. */
sealed class Dec {
    class Trade(val sig: Signal) : Dec()
    class No(val why: String) : Dec()
    class Wait(val why: String) : Dec()
}

class Ctx(
    val now: Long,
    val sentiments: Map<String, Sentiment>,
    val earnings: List<Earning>,
    val calendar: List<CalEvent>,
    val candles: Map<String, List<Candle>>,
)

class DayResult(
    val signals: List<Signal>,
    val created: List<Signal>,
    val closed: List<Signal>,
    val decided: Set<String>,
    val log: List<String>,
    val checked: Int,
    val waiting: Int,
)

/** Hängt Warnungen an, wenn während der Haltedauer wichtige Wirtschaftstermine anstehen. */
object Events {
    fun during(assetId: String, from: Long, to: Long, cal: List<CalEvent>): List<CalEvent> {
        val cur = assetCurrencies(assetId)
        return cal.filter { it.impact == "High" && it.country in cur && it.ts >= from && it.ts <= to }.sortedBy { it.ts }
    }

    fun annotate(sig: Signal, cal: List<CalEvent>): Signal {
        val to = if (sig.exitAt > 0) sig.exitAt else sig.ts + 24 * 3600
        val ev = during(sig.assetId, sig.ts, to, cal)
        if (ev.isEmpty()) return sig
        val first = ev.first()
        val more = if (ev.size > 1) " +${ev.size - 1}" else ""
        val tag = "⚠ ${first.country} ${first.title} ${Fmt.dayTime(first.ts)}$more"
        val line = "⚠ Wichtige Termine während der Haltedauer: " +
            ev.take(4).joinToString(", ") { "${it.country} ${it.title} (${Fmt.dayTime(it.ts)})" } +
            ". Rund um solche Termine sind starke Ausschläge üblich."
        return sig.copy(tags = sig.tags + tag, reason = sig.reason + "\n" + line)
    }
}

object Daytrade {
    const val TARGET_R = 10.0
    const val MIN_RVOL = 1.5
    const val BAR = 300L
    private const val GRACE = 6 * 3600L

    /** Ab dieser Verspätung gilt ein Trade als nachgetragen (kein Push mehr). */
    const val LATE = 45 * 60L
    private val NYZ: ZoneId = ZoneId.of(DayAssets.NY)
    private val LON: ZoneId = ZoneId.of("Europe/London")

    // ---------------------------------------------------------------- Zeit-Helfer

    private fun zone(a: DayAsset): ZoneId = ZoneId.of(a.zone)
    private fun localDate(z: ZoneId, ts: Long): LocalDate = Instant.ofEpochSecond(ts).atZone(z).toLocalDate()
    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
    private fun at(z: ZoneId, d: LocalDate, h: Int, m: Int): Long = d.atTime(h, m).atZone(z).toEpochSecond()
    private fun et(d: LocalDate, h: Int, m: Int) = at(NYZ, d, h, m)
    private fun etMinute(ts: Long): Int = Instant.ofEpochSecond(ts).atZone(NYZ).let { it.hour * 60 + it.minute }

    private fun isOpenBar(a: DayAsset, ts: Long): Boolean {
        val t = Instant.ofEpochSecond(ts).atZone(zone(a))
        return t.hour == a.openH && t.minute == a.openM
    }

    private fun bar(cs: List<Candle>, ts: Long) = cs.firstOrNull { it.ts == ts }
    private fun dataPast(cs: List<Candle>, ts: Long) = cs.any { it.ts > ts }
    private fun done(cs: List<Candle>, from: Long, to: Long) = cs.filter { it.ts >= from && it.ts < to && !it.partial }

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

    /** Nächste Eröffnungen für die Anzeige. */
    fun nextOpens(now: Long): List<Pair<String, Long>> = listOf(
        "Tokio" to nextAt(ZoneId.of("Asia/Tokyo"), 9, 0, now - BAR),
        "DAX" to nextAt(ZoneId.of(DayAssets.FRA), 9, 0, now - BAR),
        "USA" to nextAt(NYZ, 9, 30, now - BAR),
    )

    /**
     * Nächster Weckzeitpunkt. Die Zeiten liegen kurz nach den Momenten, in denen die (teils 10–15 min
     * verzögerten) Kurse für eine Strategie vollständig sind. Nachts übernimmt der normale 15-min-Takt.
     */
    fun nextAlarm(now: Long): Long {
        val fra = ZoneId.of(DayAssets.FRA)
        val ny = listOf(
            3 to 27, 3 to 42, 3 to 57, 4 to 12, // Silver Bullet London
            9 to 37, 9 to 47, // ORB USA
            10 to 27, 10 to 42, 10 to 57, 11 to 12, // Silver Bullet NY, VWAP, Trendtag
            11 to 42, 12 to 12, 12 to 42, 13 to 12, // Trendtag
            14 to 27, 14 to 42, 14 to 57, 15 to 12, // Silver Bullet Nachmittag
            15 to 42, 16 to 2, 16 to 12, // Intraday-Momentum, Overnight, Tagesabschluss
        )
        val berlin = listOf(8 to 22, 9 to 22, 9 to 37, 10 to 7, 10 to 52) // London Breakout, DAX-ORB
        return (berlin.map { (h, m) -> nextAt(fra, h, m, now) } + ny.map { (h, m) -> nextAt(NYZ, h, m, now) }).min()
    }

    // ---------------------------------------------------------------- Strategie-Tabelle

    private class Spec(
        val code: String,
        val assets: List<DayAsset>,
        val zoneOf: (DayAsset) -> ZoneId,
        val from: (DayAsset, LocalDate) -> Long,
        val until: (DayAsset, LocalDate) -> Long,
        val detect: (DayAsset, List<Candle>, LocalDate, Ctx) -> Dec,
        val days: (LocalDate) -> Boolean = { true },
    )

    private fun nySpec(
        code: String, assets: List<DayAsset>, fh: Int, fm: Int, uh: Int, um: Int,
        days: (LocalDate) -> Boolean = { true },
        detect: (DayAsset, List<Candle>, LocalDate, Ctx) -> Dec,
    ) = Spec(code, assets, { NYZ }, { _, d -> et(d, fh, fm) }, { _, d -> et(d, uh, um) }, detect, days)

    private val specs: List<Spec> by lazy {
        val a = DayAssets
        listOf(
            Spec(
                CAT_ORB, a.orb, { zone(it) },
                { x, d -> at(zone(x), d, x.openH, x.openM) + BAR },
                { x, d -> at(zone(x), d, x.openH, x.openM) + 2 * 3600 },
                { x, cs, d, c -> orb(CAT_ORB, x, cs, d, c) },
            ),
            Spec(
                CAT_AORB, a.asia, { zone(it) },
                { x, d -> at(zone(x), d, x.openH, x.openM) + BAR },
                { x, d -> at(zone(x), d, x.closeH, x.closeM) + 3 * 3600 },
                { x, cs, d, c -> orb(CAT_AORB, x, cs, d, c) },
            ),
            nySpec(CAT_SBL, listOf(a.nq, a.es, a.gold, a.eurusd, a.gbpusd), 3, 15, 4, 45) { x, cs, d, _ ->
                silver(CAT_SBL, x, cs, d, et(d.minusDays(1), 18, 0), et(d, 3, 0), et(d, 3, 0), et(d, 4, 0), et(d, 8, 0), "Nacht-Range")
            },
            nySpec(CAT_SB, listOf(a.nq, a.es, a.gold), 10, 15, 11, 45) { x, cs, d, _ ->
                silver(CAT_SB, x, cs, d, et(d, 9, 30), et(d, 10, 0), et(d, 10, 0), et(d, 11, 0), et(d, 16, 0), "ersten 30 min")
            },
            nySpec(CAT_SBP, listOf(a.nq, a.es), 14, 15, 15, 45) { x, cs, d, _ ->
                silver(CAT_SBP, x, cs, d, et(d, 12, 0), et(d, 14, 0), et(d, 14, 0), et(d, 15, 0), et(d, 16, 0), "Mittags-Range")
            },
            Spec(
                CAT_LBO, listOf(a.eurusd, a.gbpusd, a.usdjpy, a.gold), { LON },
                { _, d -> at(LON, d, 7, 5) }, { _, d -> at(LON, d, 10, 45) },
                { x, cs, d, _ -> londonBreakout(x, cs, d) },
            ),
            nySpec(CAT_VWAP, listOf(a.nq, a.es, a.gold, a.oil), 10, 30, 11, 15) { x, cs, d, _ -> vwap(x, cs, d) },
            nySpec(CAT_FTD, listOf(a.nq, a.es), 10, 30, 13, 45) { x, cs, d, c -> trendDay(x, cs, d, c) },
            nySpec(CAT_IM, listOf(a.nq, a.es), 15, 30, 16, 15) { x, cs, d, _ -> intradayMomentum(x, cs, d) },
            nySpec(CAT_ON, listOf(a.nq, a.es), 15, 50, 16, 45, { it.dayOfWeek != DayOfWeek.FRIDAY }) { x, cs, d, _ ->
                overnight(x, cs, d)
            },
        )
    }

    /** Für Tests: Entscheidung einer Strategie auf vorgegebenen Kerzen. */
    internal fun decide(code: String, a: DayAsset, cs: List<Candle>, d: LocalDate, ctx: Ctx): Dec =
        specs.first { it.code == code }.detect(a, cs, d, ctx)

    // ---------------------------------------------------------------- Ablauf

    private class Slot(val spec: Spec, val asset: DayAsset, val day: LocalDate, val key: String)

    fun step(
        current: List<Signal>,
        decided: Set<String>,
        now: Long,
        sentiments: Map<String, Sentiment>,
        earnings: List<Earning>,
        calendar: List<CalEvent>,
        errors: MutableList<String>,
    ): DayResult {
        val log = ArrayList<String>()
        val newDecided = HashSet<String>()
        val ids = current.map { it.id }.toSet()
        val slots = ArrayList<Slot>()
        for (spec in specs) for (a in spec.assets) {
            val d = localDate(spec.zoneOf(a), now)
            if (weekend(d) || !spec.days(d)) continue
            val key = "${spec.code}-${a.id}-$d"
            if (key in decided || key in ids) continue
            val from = spec.from(a, d)
            val until = spec.until(a, d)
            // Einstiege hängen nur von den Kerzen ab, deshalb wird ein verpasstes Fenster bis zu 6 h nachgetragen
            val grace = until + GRACE
            if (now in from..grace) {
                slots += Slot(spec, a, d, key)
            } else if (now > grace && now < grace + 3 * 3600) {
                newDecided += key
                log += "– ${Strategies.short(spec.code)} ${a.name}: Zeitfenster verpasst (keine vollständigen Kurse erhalten)"
            }
        }

        val load = LinkedHashSet<DayAsset>()
        slots.forEach { load += it.asset }
        if (slots.any { it.spec.code == CAT_FTD }) {
            load += DayAssets.nq
            load += DayAssets.es
        }
        current.filter { it.isOpen }.forEach { s -> DayAssets.byId(s.assetId)?.let { load += it } }

        val bySymbol = HashMap<String, List<Candle>?>()
        val candles = HashMap<String, List<Candle>>()
        for (a in load) {
            val cs = if (bySymbol.containsKey(a.yahoo)) bySymbol[a.yahoo] else {
                val fetched = try {
                    Sources.candles(a.yahoo, "5m")
                } catch (e: Exception) {
                    errors += "${a.name}: ${friendly(e)}"
                    null
                }
                bySymbol[a.yahoo] = fetched
                fetched
            }
            if (cs != null) candles[a.id] = cs
        }

        val evaluated = current.map { sig ->
            candles[sig.assetId]?.let { Engine.backfillMae(evaluateTimed(sig, it, now, BAR), it, BAR, 0) } ?: sig
        }
        val closed = evaluated.filter { e -> !e.isOpen && current.any { it.id == e.id && it.isOpen } }

        val ctx = Ctx(now, sentiments, earnings, calendar, candles)
        val all = evaluated.toMutableList()
        val created = ArrayList<Signal>()
        var waiting = 0
        for (slot in slots) {
            val cs = candles[slot.asset.id]
            val label = "${Strategies.short(slot.spec.code)} ${slot.asset.name}"
            if (cs == null) {
                waiting++
                continue
            }
            val dec = try {
                slot.spec.detect(slot.asset, cs, slot.day, ctx)
            } catch (e: Exception) {
                Dec.Wait("Fehler: ${friendly(e)}")
            }
            when (dec) {
                is Dec.Trade -> {
                    val late = now - dec.sig.ts > LATE
                    val sig = dec.sig.copy(id = slot.key, tags = if (late) dec.sig.tags + "nachgetragen" else dec.sig.tags)
                    all += sig
                    created += sig
                    newDecided += slot.key
                    log += "✓ $label: ${if (sig.dir == 1) "LONG" else "SHORT"} @ ${Fmt.price(sig.entry)}"
                }
                is Dec.No -> {
                    newDecided += slot.key
                    log += "– $label: ${dec.why}"
                }
                is Dec.Wait -> waiting++
            }
        }
        return DayResult(all, created, closed, newDecided, log, slots.size, waiting)
    }

    /**
     * Auswertung mit Stop/Ziel, optionalem VWAP-Nachziehstop (Trendtag) und geplantem Zeitausstieg
     * (Kurs zur Eröffnung der Ausstiegskerze).
     */
    fun evaluateTimed(sig: Signal, candles: List<Candle>, now: Long, bar: Long): Signal {
        if (!sig.isOpen) return sig
        val end = if (sig.exitAt > 0) sig.exitAt else sig.ts + 24 * 3600
        val during = candles.filter { it.ts >= sig.ts && it.ts < end }
        val trail = sig.category == CAT_FTD
        var pv = 0.0
        var vol = 0.0
        if (trail) {
            val open = et(localDate(NYZ, sig.ts), 9, 30)
            for (c in candles) if (c.ts >= open && c.ts < sig.ts) {
                pv += (c.h + c.l + c.c) / 3 * c.v
                vol += c.v
            }
        }
        for (c in during) {
            if (sig.dir == 1) {
                if (sig.sl > 0 && c.l <= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", during)
                if (sig.tp > 0 && c.h >= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", during)
            } else {
                if (sig.sl > 0 && c.h >= sig.sl) return Engine.close(sig, sig.sl, c.ts, "SL", during)
                if (sig.tp > 0 && c.l <= sig.tp) return Engine.close(sig, sig.tp, c.ts, "TP", during)
            }
            if (trail && !c.partial) {
                pv += (c.h + c.l + c.c) / 3 * c.v
                vol += c.v
                if (vol > 0 && (c.c - pv / vol) * sig.dir < 0) return Engine.close(sig, c.c, c.ts, "TRAIL", during)
            }
        }
        if (now >= end + bar) {
            val atEnd = candles.firstOrNull { it.ts >= end && it.ts < end + 2 * bar }
            if (atEnd != null) return Engine.close(sig, atEnd.o, atEnd.ts, "EOD", during)
            // keine Kerze zum Ausstiegszeitpunkt (Markt zu oder Daten fehlen) → nach 30 min letzten Kurs nehmen
            val last = during.lastOrNull()
            if (last != null && now >= end + 1800) return Engine.close(sig, last.c, last.ts + bar, "EOD", during)
            if (now - sig.ts > 48 * 3600) return Engine.close(sig, sig.entry, now, "EOD", during)
        }
        return sig
    }

    private fun mk(
        code: String, a: DayAsset, ts: Long, dir: Int, entry: Double, sl: Double, tp: Double,
        exitAt: Long, reasons: List<String>, tags: List<String>,
    ) = Signal(
        id = "",
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
    private fun f2(v: Double) = String.format(Locale.GERMANY, "%.2f", v)

    // ---------------------------------------------------------------- 1) Opening Range Breakout (auch Asien)

    private fun orb(code: String, a: DayAsset, cs: List<Candle>, d: LocalDate, ctx: Ctx): Dec {
        val z = zone(a)
        val open = at(z, d, a.openH, a.openM)
        val c1 = bar(cs, open)
        val c2 = bar(cs, open + BAR)
        if (c1 == null) {
            return if (dataPast(cs, open + BAR)) Dec.No("Eröffnungskerze fehlt in den Kursdaten") else Dec.Wait("Eröffnungskerze noch nicht da")
        }
        if (c1.partial) return Dec.Wait("Eröffnungskerze läuft noch")
        if (c2 == null) {
            return if (dataPast(cs, open + 2 * BAR)) Dec.No("zweite Kerze fehlt in den Kursdaten") else Dec.Wait("zweite Kerze noch nicht da")
        }
        val dir = when {
            c1.c > c1.o -> 1
            c1.c < c1.o -> -1
            else -> return Dec.No("erste Kerze ohne Richtung")
        }
        val tags = ArrayList<String>()
        val reasons = ArrayList<String>()
        reasons += if (dir == 1) "Erste 5-min-Kerze grün (${Fmt.price(c1.o)} → ${Fmt.price(c1.c)}) → Long"
        else "Erste 5-min-Kerze rot (${Fmt.price(c1.o)} → ${Fmt.price(c1.c)}) → Short"

        val today = d.toString()
        val earningsToday = a.finnhub != null && ctx.earnings.any { it.symbol == a.finnhub && it.date == today }
        if (a.rvolFilter) {
            val prev = cs.filter { it.ts < open && isOpenBar(a, it.ts) && it.v > 0 }.map { it.v }
            if (c1.v <= 0 || prev.isEmpty()) {
                if (!earningsToday) return Dec.No("kein Volumen zum Vergleich")
            } else {
                val rvol = c1.v / prev.average()
                if (rvol < MIN_RVOL && !earningsToday) return Dec.No("Volumen nur ${f1(rvol)}× (nötig 1,5×)")
                tags += "RVOL ${f1(rvol)}×"
                reasons += "Aktie „in play“: Eröffnungsvolumen ${f1(rvol)}× so hoch wie an den Vortagen"
            }
        }
        if (earningsToday) tags += "Earnings heute"

        val entry = c2.o
        val rawStop = if (dir == 1) c1.l else c1.h
        if ((entry - rawStop) * dir <= 0) return Dec.No("zweite Kerze eröffnet schon jenseits des Stops")
        val risk = max((entry - rawStop) * dir, entry * 0.0005)
        val stop = entry - dir * risk
        val closeTs = at(z, d, a.closeH, a.closeM)
        reasons += "Einstieg zur Eröffnung der zweiten Kerze, Stop am anderen Ende der ersten (${Fmt.price(stop)}), Ziel 10R, sonst Exit zum Handelsschluss"

        val sent = a.sentimentId?.let { ctx.sentiments[it] }?.takeIf { ctx.now - it.ts < 6 * 3600 }
        if (sent != null) {
            tags += "News ${Fmt.score(sent.score)}" + when {
                sent.score * dir > 0.15 -> " ✓"
                sent.score * dir < -0.15 -> " ✗"
                else -> ""
            }
        }
        return Dec.Trade(mk(code, a, open + BAR, dir, entry, stop, entry + dir * TARGET_R * risk, closeTs, reasons, tags))
    }

    // ---------------------------------------------------------------- 2) Intraday-Momentum

    private fun intradayMomentum(a: DayAsset, cs: List<Candle>, d: LocalDate): Dec {
        val t = et(d, 15, 30)
        val prevClose = cs.lastOrNull {
            localDate(NYZ, it.ts) < d && etMinute(it.ts) in (15 * 60 + 30)..(15 * 60 + 55)
        }?.c ?: return Dec.No("Vortagesschluss fehlt")
        val at10 = bar(cs, et(d, 9, 55))?.takeIf { !it.partial } ?: return Dec.No("Kerze um 16:00 Uhr (dt.) fehlt")
        val r1 = at10.c / prevClose - 1
        if (abs(r1) < 0.0025) return Dec.No("erste halbe Stunde nur ${Fmt.pct(r1)} (nötig ±0,25 %)")
        val entryBar = bar(cs, t) ?: return Dec.Wait("Kerze um 21:30 (dt.) noch nicht da")
        val dir = if (r1 > 0) 1 else -1
        val entry = entryBar.o
        return Dec.Trade(
            mk(
                CAT_IM, a, t, dir, entry, entry * (1 - dir * 0.006), 0.0, et(d, 16, 0),
                listOf(
                    "Vortagesschluss → 16:00 Uhr (dt.): ${Fmt.pct(r1)}",
                    "Die letzte halbe Stunde folgt statistisch oft der ersten → ${if (dir == 1) "Long" else "Short"} bis Handelsschluss",
                    "Notstop 0,6 %",
                ),
                listOf("Erste 30 min ${Fmt.pct(r1)}"),
            ),
        )
    }

    // ---------------------------------------------------------------- 3) Overnight-Effekt

    private fun overnight(a: DayAsset, cs: List<Candle>, d: LocalDate): Dec {
        val t = et(d, 15, 50)
        val entryBar = bar(cs, t) ?: return Dec.Wait("Kerze um 21:50 (dt.) noch nicht da")
        val entry = entryBar.o
        return Dec.Trade(
            mk(
                CAT_ON, a, t, 1, entry, entry * 0.99, 0.0, et(d.plusDays(1), 9, 30),
                listOf(
                    "Long kurz vor US-Schluss, Verkauf zur US-Eröffnung morgen",
                    "Ein großer Teil der Aktienrendite entsteht historisch über Nacht",
                    "Notstop 1 %",
                ),
                listOf("Über Nacht"),
            ),
        )
    }

    // ---------------------------------------------------------------- 4) ICT Silver Bullet (London, New York, Nachmittag)

    private fun silver(
        code: String, a: DayAsset, cs: List<Candle>, d: LocalDate,
        refFrom: Long, refTo: Long, ws: Long, we: Long, exitAt: Long, refLabel: String,
    ): Dec {
        val ref = done(cs, refFrom, refTo)
        val win = done(cs, ws, we)
        val windowOver = cs.any { it.ts >= we }
        if (ref.size < 5) {
            return if (windowOver) Dec.No("zu wenig Kurse für die Referenz-Range") else Dec.Wait("Referenz-Range noch nicht fertig")
        }
        val rh = ref.maxOf { it.h }
        val rl = ref.minOf { it.l }

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
            if (b.ts - b1.ts != 2 * BAR) continue // Lücke in den Daten, keine echte FVG
            val fvg = if (sweepDir == -1) b1.l > b.h else b1.h < b.l
            if (!fvg) continue
            val entry = b.c
            val risk = max((entry - ext) * sweepDir * 1.02, entry * 0.0005)
            if ((entry - ext) * sweepDir <= 0) continue
            val sl = entry - sweepDir * risk
            val gapLo = if (sweepDir == -1) b.h else b1.h
            val gapHi = if (sweepDir == -1) b1.l else b.l
            return Dec.Trade(
                mk(
                    code, a, b.ts + BAR, sweepDir, entry, sl, entry + sweepDir * 2 * risk, exitAt,
                    listOf(
                        if (sweepDir == -1) "Hoch der $refLabel (${Fmt.price(rh)}) gesweept" else "Tief der $refLabel (${Fmt.price(rl)}) gesweept",
                        "Fair Value Gap ${Fmt.price(gapLo)}–${Fmt.price(gapHi)} in Gegenrichtung entstanden",
                        "Stop hinter dem Sweep-Extrem, Ziel 2R",
                    ),
                    listOf("Sweep + FVG"),
                ),
            )
        }
        return when {
            !windowOver -> Dec.Wait(if (sweepDir == 0) "noch kein Sweep" else "Sweep da, noch keine FVG")
            sweepDir == 0 -> Dec.No("kein Sweep der $refLabel im Fenster")
            else -> Dec.No("Sweep, aber keine Fair Value Gap danach")
        }
    }

    // ---------------------------------------------------------------- 5) London Breakout

    private fun londonBreakout(a: DayAsset, cs: List<Candle>, d: LocalDate): Dec {
        val ref = done(cs, at(LON, d, 0, 0), at(LON, d, 7, 0))
        val ws = at(LON, d, 7, 0)
        val we = at(LON, d, 10, 0)
        val windowOver = cs.any { it.ts >= we }
        if (ref.size < 30) return if (windowOver) Dec.No("zu wenig Kurse für die Asia-Range") else Dec.Wait("Asia-Range unvollständig")
        val rh = ref.maxOf { it.h }
        val rl = ref.minOf { it.l }
        val height = rh - rl
        if (height <= 0) return Dec.No("Asia-Range ohne Spanne")
        val mid = (rh + rl) / 2
        for (b in done(cs, ws, we)) {
            val dir = when {
                b.c > rh + 0.1 * height -> 1
                b.c < rl - 0.1 * height -> -1
                else -> continue
            }
            val entry = b.c
            val risk = (entry - mid) * dir
            return Dec.Trade(
                mk(
                    CAT_LBO, a, b.ts + BAR, dir, entry, mid, entry + dir * 1.5 * risk, at(LON, d, 16, 0),
                    listOf(
                        "5-min-Kerze schließt klar ${if (dir == 1) "über" else "unter"} der Asia-Range (${Fmt.price(rl)}–${Fmt.price(rh)})",
                        "Mit dem Ausbruch handeln, Stop in der Range-Mitte (${Fmt.price(mid)}), Ziel 1,5R",
                    ),
                    listOf("Ausbruch Asia-Range"),
                ),
            )
        }
        return if (windowOver) Dec.No("kein Ausbruch aus der Asia-Range") else Dec.Wait("noch kein Ausbruch")
    }

    // ---------------------------------------------------------------- 6) Orderflow-Näherung (VWAP + Volumen-Delta)

    private fun vwap(a: DayAsset, cs: List<Candle>, d: LocalDate): Dec {
        val t = et(d, 10, 30)
        val entryBar = bar(cs, t) ?: return Dec.Wait("Kerze um 16:30 (dt.) noch nicht da")
        val bars = done(cs, et(d, 9, 30), t).filter { it.v > 0 }
        if (bars.size < 8) return Dec.No("zu wenig Kerzen mit Volumen")
        val vol = bars.sumOf { it.v }
        val vw = bars.sumOf { (it.h + it.l + it.c) / 3 * it.v } / vol
        val delta = bars.sumOf { it.v * (it.c - it.o) / max(it.h - it.l, 1e-9) }
        val price = entryBar.o
        val dist = (price - vw) / vw
        val pressure = delta / vol
        val dir = when {
            dist > 0.001 && pressure > 0.1 -> 1
            dist < -0.001 && pressure < -0.1 -> -1
            else -> return Dec.No("kein klares Bild (VWAP-Abstand ${Fmt.pct(dist)}, Kaufdruck ${Fmt.pct(pressure)})")
        }
        val risk = max(abs(price - vw) * 1.05, price * 0.0015)
        return Dec.Trade(
            mk(
                CAT_VWAP, a, t, dir, price, price - dir * risk, price + dir * 2 * risk, et(d, 16, 0),
                listOf(
                    "Kurs ${Fmt.pct(dist)} ${if (dir == 1) "über" else "unter"} dem VWAP seit Eröffnung (${Fmt.price(vw)})",
                    "Kaufdruck-Näherung aus Volumen: ${Fmt.pct(pressure)} → Trend in dieselbe Richtung",
                    "Stop hinter dem VWAP, Ziel 2R",
                ),
                listOf("VWAP ${Fmt.pct(dist)}", "Delta ${Fmt.pct(pressure)}"),
            ),
        )
    }

    // ---------------------------------------------------------------- 7) Fable-Trendtag (Eigenentwicklung)

    /** Bewegung seit Eröffnung und Effizienz (Nettoweg / zurückgelegter Weg) bis einschließlich Kerze uptoTs. */
    private fun moveAndEfficiency(bars: List<Candle>, uptoTs: Long): Pair<Double, Double>? {
        val part = bars.filter { it.ts <= uptoTs }
        if (part.size < 6) return null
        val o = part.first().o
        var path = abs(part.first().c - o)
        for (i in 1 until part.size) path += abs(part[i].c - part[i - 1].c)
        val net = part.last().c - o
        return Pair(net / o, if (path > 0) abs(net) / path else 0.0)
    }

    /** Übliche Tagesspanne (Hoch−Tief der Kassa-Sitzung) der Vortage als Anteil vom Kurs. */
    private fun typicalRange(cs: List<Candle>, d: LocalDate): Double? {
        val ranges = cs.filter { !it.partial && localDate(NYZ, it.ts) < d && etMinute(it.ts) in 570 until 960 }
            .groupBy { localDate(NYZ, it.ts) }
            .values.filter { it.size >= 60 }
            .map { day -> (day.maxOf { it.h } - day.minOf { it.l }) / day.first().o }
        return if (ranges.isEmpty()) null else ranges.average()
    }

    private fun trendDay(a: DayAsset, cs: List<Candle>, d: LocalDate, ctx: Ctx): Dec {
        val open = et(d, 9, 30)
        val scanFrom = et(d, 10, 25)
        val scanTo = et(d, 13, 0)
        val windowOver = cs.any { it.ts >= scanTo }
        val bars = done(cs, open, scanTo)
        if (bars.size < 12 || bars.first().ts != open) {
            return if (windowOver) Dec.No("zu wenig Kurse seit Eröffnung") else Dec.Wait("erste Stunde läuft noch")
        }
        val typical = typicalRange(cs, d) ?: return Dec.No("keine Vortage für die übliche Tagesspanne")
        val need = typical / 3
        val sibId = if (a.id == "F_NQ") "F_ES" else "F_NQ"
        val sibBars = ctx.candles[sibId]?.let { done(it, open, scanTo) }
            ?: return Dec.Wait("Kurse des zweiten Index fehlen")
        val orHigh = bars.take(6).maxOf { it.h }
        val orLow = bars.take(6).minOf { it.l }
        val o0 = bars.first().o

        var pv = 0.0
        var vol = 0.0
        var above = 0
        var below = 0
        var path = 0.0
        var best = 0.0
        for (i in bars.indices) {
            val b = bars[i]
            val w = if (b.v > 0) b.v else 1.0
            pv += (b.h + b.l + b.c) / 3 * w
            vol += w
            val vw = pv / vol
            if (b.c > vw) above++ else if (b.c < vw) below++
            path += abs(b.c - if (i == 0) o0 else bars[i - 1].c)
            if (b.ts < scanFrom) continue

            val move = b.c / o0 - 1
            if (abs(move) > abs(best)) best = move
            val dir = if (move > 0) 1 else -1
            val eff = if (path > 0) abs(b.c - o0) / path else 0.0
            val side = (if (dir == 1) above else below).toDouble() / (i + 1)
            val outside = if (dir == 1) b.c > orHigh else b.c < orLow
            if (abs(move) < need || eff < 0.35 || side < 0.8 || !outside) continue
            val sib = moveAndEfficiency(sibBars, b.ts) ?: continue
            if (sib.first * dir <= 0 || sib.second < 0.25) continue
            val t = b.ts + BAR
            if (ctx.calendar.any { it.impact == "High" && it.country == "USD" && it.ts > t && it.ts <= t + 45 * 60 }) continue

            val entry = b.c
            val risk = max((entry - vw) * dir * 1.15, entry * 0.0015)
            return Dec.Trade(
                mk(
                    CAT_FTD, a, t, dir, entry, entry - dir * risk, 0.0, et(d, 15, 55),
                    listOf(
                        "Trendtag-Muster: ${Fmt.pct(move)} seit Eröffnung (üblich sind ${Fmt.pct(typical).removePrefix("+")} Tagesspanne)",
                        "Bewegung sauber: Effizienz ${f2(eff)}, ${(side * 100).toInt()} % der Kerzen ${if (dir == 1) "über" else "unter"} dem VWAP",
                        "Kurs außerhalb der ersten 30 Minuten, ${if (sibId == "F_ES") "S&P 500" else "Nasdaq 100"} bestätigt (${Fmt.pct(sib.first)})",
                        "Stop knapp hinter dem VWAP (${Fmt.price(vw)}). Ausstieg, sobald eine 5-min-Kerze den VWAP zurückkreuzt, spätestens 21:55",
                    ),
                    listOf("Trendtag ${Fmt.pct(move)}", "Effizienz ${f2(eff)}"),
                ),
            )
        }
        return if (windowOver) Dec.No("kein Trendtag-Muster (größte Bewegung ${Fmt.pct(best)}, nötig ±${f2(need * 100)} % plus saubere Struktur)")
        else Dec.Wait("noch kein Trendtag-Muster")
    }

    // ---------------------------------------------------------------- 8) News pur (läuft auf den 15-min-Kerzen im Runner)

    fun detectNews(a: Asset, cs: List<Candle>, sent: Sentiment, now: Long, existing: List<Signal>): Signal? {
        if (abs(sent.score) < 0.5) return null
        val last = cs.lastOrNull { !it.partial } ?: return null
        if (now - (last.ts + 900) > 30 * 60) return null // Markt gerade zu
        val dir = if (sent.score > 0) 1 else -1
        val day = Engine.dayStart(now)
        val id = "$CAT_NEWS-${a.id}-$day-$dir"
        if (existing.any { it.id == id }) return null
        val recent = cs.filter { !it.partial }.takeLast(14)
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
