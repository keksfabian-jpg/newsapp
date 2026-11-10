package de.fabian.newstrader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/** Ein kompletter Update-Durchlauf (läuft im Hintergrund per WorkManager, ausgelöst durch Takt oder Wecker). */
object Runner {
    private val lock = Mutex()

    suspend fun run(ctx: Context, src: String = "Takt") = withContext(Dispatchers.IO) {
        Store.init(ctx)
        lock.withLock {
            Store.update { it.copy(running = true) }
            try {
                runInner(ctx, src)
            } finally {
                Store.update { it.copy(running = false) }
                Alarms.scheduleNext(ctx)
            }
        }
    }

    private fun runInner(ctx: Context, src: String) {
        val st = Store.state.value
        val s = st.settings
        val now = System.currentTimeMillis() / 1000
        val errors = ArrayList<String>()
        val assets = Assets.all.filter { it.id !in s.disabled }
        val todayUtc = LocalDate.now(ZoneOffset.UTC)

        val finnKey = cleanKey(s.finnhubKey)
        val claudeKey = cleanKey(s.claudeKey)

        // 1) Wirtschaftskalender + Earnings (alle 3 h; nach Fehler Pause 1 h, damit der Feed nicht sperrt)
        var calendar = st.calendar
        var earnings = st.earnings
        var calTs = st.calendarTs
        if (now - calTs > 3 * 3600) {
            calTs = now
            try {
                calendar = Sources.calendar()
            } catch (e: Exception) {
                errors += "Kalender: ${friendly(e)}"
                calTs = now - 2 * 3600
            }
            if (finnKey.isNotBlank()) {
                try {
                    val syms = (Assets.all.mapNotNull { it.finnhub } + DayAssets.all.mapNotNull { it.finnhub }).toSet()
                    earnings = Sources.earnings(finnKey, todayUtc.toString(), todayUtc.plusDays(14).toString(), syms)
                } catch (e: Exception) {
                    errors += "Earnings (Finnhub): ${friendly(e)}"
                }
            }
        }

        // 2) Kerzen (15 min) – auch für abgeschaltete Märkte mit noch offenem Trade
        val openIds = st.signals.filter { it.isOpen }.map { it.assetId }.toSet()
        val candles = HashMap<String, List<Candle>>()
        for (a in Assets.all) {
            if (a !in assets && a.id !in openIds) continue
            try {
                candles[a.id] = Sources.candles(a)
            } catch (e: Exception) {
                errors += "${a.name}: ${friendly(e)}"
            }
        }

        // 3) Offene Signale auswerten
        val evaluated = st.signals.map { sig ->
            val cs = candles[sig.assetId]
            when {
                cs == null -> sig
                sig.category == CAT_NEWS -> Engine.backfillMae(Daytrade.evaluateTimed(sig, cs, now, 900), cs, 900, 0)
                sig.category in Strategies.sweepCodes -> Engine.backfillMae(Engine.evaluate(sig, cs, now), cs, 900, 900)
                else -> sig
            }
        }
        val newlyClosed = evaluated.filter { e -> !e.isOpen && st.signals.any { it.id == e.id && it.isOpen } }

        // 4) News + KI-Sentiment (alle 30 min)
        var sentiments = st.sentiments
        var sentimentsFresh = false
        var sentTs = st.sentimentTs
        var news = st.news
        if (finnKey.isNotBlank() && now - sentTs > 30 * 60) {
            sentTs = now
            var newsOk = false
            try {
                val items = ArrayList<NewsItem>()
                items += Sources.news(finnKey, "general")
                items += Sources.news(finnKey, "forex")
                val from = todayUtc.minusDays(1).toString()
                for (a in assets) {
                    val sym = a.finnhub ?: continue
                    try {
                        items += Sources.companyNews(finnKey, sym, from, todayUtc.toString()).take(8)
                    } catch (_: Exception) {
                    }
                }
                news = items.filter { now - it.ts < 12 * 3600 && it.headline.isNotBlank() }
                    .distinctBy { it.headline }
                    .sortedByDescending { it.ts }
                    .take(150)
                newsOk = true
            } catch (e: Exception) {
                errors += "News (Finnhub): ${friendly(e)}"
                sentTs = now - 20 * 60 // in ~10 min erneut versuchen
            }
            if (newsOk && claudeKey.isNotBlank() && news.isNotEmpty()) {
                try {
                    val ev = calendar.filter { it.impact == "High" && it.ts > now - 6 * 3600 && it.ts < now + 24 * 3600 }
                    sentiments = ClaudeApi.sentiment(claudeKey, assets, news, ev, now).associateBy { it.assetId }
                    sentimentsFresh = true
                } catch (e: Exception) {
                    errors += "KI (Claude): ${friendly(e)}"
                    sentTs = now - 20 * 60
                }
            }
        }

        val log = ArrayList<String>()

        // 5) Neue Sweeps → Signale
        val all = evaluated.toMutableList()
        val created = ArrayList<Signal>()
        for (a in assets) {
            val cs = candles[a.id] ?: continue
            val sw = Engine.detect(a, cs, now) ?: continue
            val day = Engine.dayStart(sw.ts)
            if (all.any {
                    it.category in Strategies.sweepCodes && it.assetId == a.id && it.dir == sw.dir &&
                        Engine.dayStart(it.ts) == day
                }
            ) continue
            val sent = sentiments[a.id]?.takeIf { now - it.ts < 6 * 3600 }
            val sig = Events.annotate(Engine.build(a, sw, sent, s, calendar, earnings, now), calendar)
            all += sig
            created += sig
            log += "✓ ${Strategies.short(sig.category)} ${a.name}: ${if (sig.dir == 1) "LONG" else "SHORT"} @ ${Fmt.price(sig.entry)}"
        }

        // 6) News pur: direkt nach frischer KI-Bewertung
        val newsCreated = ArrayList<Signal>()
        if (sentimentsFresh) {
            for (a in assets) {
                val cs = candles[a.id] ?: continue
                val sent = sentiments[a.id] ?: continue
                val sig = Daytrade.detectNews(a, cs, sent, now, all)?.let { Events.annotate(it, calendar) } ?: continue
                all += sig
                newsCreated += sig
                log += "✓ News pur ${a.name}: ${if (sig.dir == 1) "LONG" else "SHORT"} @ ${Fmt.price(sig.entry)}"
            }
        }

        // 7) Daytrade-Strategien auf 5-min-Kerzen
        val dayCodes = Strategies.dayCodes - CAT_NEWS
        val day = Daytrade.step(all.filter { it.category in dayCodes }, st.decided, now, sentiments, earnings, calendar, errors)
        val dayCreated = day.created.map { Events.annotate(it, calendar) }
        val annotated = dayCreated.associateBy { it.id }
        all.removeAll { it.category in dayCodes }
        all += day.signals.map { annotated[it.id] ?: it }
        log += day.log

        val closedCount = newlyClosed.size + day.closed.size
        val summary = "${Fmt.log(now)} $src · geprüft ${day.checked}" +
            (if (day.waiting > 0) " · wartet ${day.waiting}" else "") +
            (if (created.size + newsCreated.size + dayCreated.size > 0) " · neu ${created.size + newsCreated.size + dayCreated.size}" else "") +
            (if (closedCount > 0) " · geschlossen $closedCount" else "") +
            (if (errors.isNotEmpty()) " · Fehler ${errors.size}" else "")
        val newLog = (listOf(summary) + log.map { "   $it" } + errors.take(3).map { "   ! $it" })

        val cutoff = todayUtc.minusDays(4).toString()
        val finalNews = news
        val finalSent = sentiments
        val finalCal = calendar
        val finalEarn = earnings
        val finalSentTs = sentTs
        val finalCalTs = calTs
        Store.update {
            it.copy(
                signals = all.sortedByDescending { x -> x.ts }.take(1500),
                sentiments = finalSent,
                calendar = finalCal,
                earnings = finalEarn,
                news = finalNews,
                sentimentTs = finalSentTs,
                calendarTs = finalCalTs,
                lastRun = now,
                lastError = errors.take(4).joinToString("\n"),
                log = (newLog + it.log).take(250),
                decided = (it.decided + day.decided).filter { k -> k.takeLast(10) >= cutoff }.toSet(),
            )
        }

        created.filter { it.category == CAT_PROPOSAL }.forEach { Notify.signal(ctx, it) }
        newlyClosed.filter { it.category == CAT_PROPOSAL }.forEach { Notify.result(ctx, it) }
        if (s.daytradePush) {
            (dayCreated + newsCreated).filter { now - it.ts <= Daytrade.LATE }.forEach { Notify.signal(ctx, it, daytrade = true) }
            (day.closed + newlyClosed.filter { it.category == CAT_NEWS }).forEach { Notify.result(ctx, it) }
        }
    }
}
