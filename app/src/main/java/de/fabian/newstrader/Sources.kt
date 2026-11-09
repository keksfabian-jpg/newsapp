package de.fabian.newstrader

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime

/** Datenquellen: Yahoo (Kerzen), ForexFactory-Feed (Wirtschaftskalender), Finnhub (News, Earnings). */
object Sources {

    fun candles(a: Asset): List<Candle> = candles(a.yahoo, "15m")

    fun candles(yahoo: String, interval: String): List<Candle> {
        val sym = URLEncoder.encode(yahoo, "UTF-8")
        val txt = Net.get("https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=$interval&range=5d")
        val result = JSONObject(txt).getJSONObject("chart").optJSONArray("result") ?: return emptyList()
        val r = result.getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val v = q.optJSONArray("volume")
        val out = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            val vol = if (v == null || v.isNull(i)) 0.0 else v.optDouble(i, 0.0)
            out.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), vol))
        }
        return out
    }

    fun calendar(): List<CalEvent> {
        val arr = JSONArray(Net.get("https://nfs.faireconomy.media/ff_calendar_thisweek.json"))
        return arr.objects { o ->
            CalEvent(
                title = o.optString("title"),
                country = o.optString("country"),
                ts = OffsetDateTime.parse(o.getString("date")).toEpochSecond(),
                impact = o.optString("impact"),
                forecast = o.optString("forecast"),
                previous = o.optString("previous"),
            )
        }.sortedBy { it.ts }
    }

    fun earnings(key: String, from: String, to: String, symbols: Set<String>): List<Earning> {
        val txt = Net.get("https://finnhub.io/api/v1/calendar/earnings?from=$from&to=$to&token=$key")
        val arr = JSONObject(txt).optJSONArray("earningsCalendar")
        return arr.objects { o ->
            Earning(
                symbol = o.optString("symbol"),
                date = o.optString("date"),
                hour = o.optString("hour"),
                epsEstimate = if (o.isNull("epsEstimate")) "" else o.optString("epsEstimate"),
            )
        }.filter { it.symbol in symbols }.sortedBy { it.date }
    }

    fun news(key: String, category: String): List<NewsItem> {
        val arr = JSONArray(Net.get("https://finnhub.io/api/v1/news?category=$category&token=$key"))
        return arr.objects { it.toNews() }
    }

    fun companyNews(key: String, symbol: String, from: String, to: String): List<NewsItem> {
        val arr = JSONArray(Net.get("https://finnhub.io/api/v1/company-news?symbol=$symbol&from=$from&to=$to&token=$key"))
        return arr.objects { it.toNews().copy(related = symbol) }
    }

    private fun JSONObject.toNews() = NewsItem(
        headline = optString("headline"),
        source = optString("source"),
        ts = optLong("datetime"),
        related = optString("related"),
        url = optString("url"),
    )
}
