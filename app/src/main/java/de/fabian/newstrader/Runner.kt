package de.fabian.newstrader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/** Ein kompletter Update-Durchlauf (läuft im Hintergrund per WorkManager). */
object Runner {

    suspend fun run(ctx: Context) = withContext(Dispatchers.IO) {
        Store.init(ctx)
        Store.update { it.copy(running = true) }
        try {
            runInner(ctx)
        } finally {
            Store.update { it.copy(running = false) }
        }
    }

    private fun runInner(ctx: Context) {
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

        // 2) Kerzen
        val candles = HashMap<String, List<Candle>>()
        for (a in assets) {
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
                sig.category == CAT_NEWS -> Daytrade.evaluateTimed(sig, cs, now, s, 900)
                sig.category in Strategies.sweepCodes -> Engine.evaluate(sig, cs, now, s)
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
            val sig = Engine.build(a, sw, sent, s, calendar, earnings, now)
            all += sig
            created += sig
        }

        // 6) News pur: direkt nach frischer KI-Bewertung
        val newsCreated = ArrayList<Signal>()
        if (sentimentsFresh) {
            for (a in assets) {
                val cs = candles[a.id] ?: continue
                val sent = sentiments[a.id] ?: continue
                val sig = Daytrade.detectNews(a, cs, sent, now, all) ?: continue
                all += sig
                newsCreated += sig
            }
        }

        // 7) Daytrade-Strategien (ORB, Intraday-Momentum, Overnight, Silver Bullet, VWAP)
        val dayCodes = Strategies.dayCodes - CAT_NEWS
        val day = Daytrade.step(all.filter { it.category in dayCodes }, now, s, sentiments, earnings, errors)
        all.removeAll { it.category in dayCodes }
        all += day.signals

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
            )
        }

        created.filter { it.category == CAT_PROPOSAL }.forEach { Notify.signal(ctx, it) }
        newlyClosed.filter { it.category == CAT_PROPOSAL }.forEach { Notify.result(ctx, it) }
        if (s.daytradePush) {
            (day.created + newsCreated).forEach { Notify.signal(ctx, it, daytrade = true) }
            (day.closed + newlyClosed.filter { it.category == CAT_NEWS }).forEach { Notify.result(ctx, it) }
        }

        // gezielte Läufe zu den Strategie-Zeitpunkten (Eröffnungen, Silver Bullet, VWAP, Intraday-Momentum, Overnight)
        Daytrade.nextRunTargets(now).forEach { RefreshWorker.scheduleAt(ctx, it) }
    }
}
