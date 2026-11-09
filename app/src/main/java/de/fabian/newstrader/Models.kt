package de.fabian.newstrader

import org.json.JSONArray
import org.json.JSONObject

enum class RefType { ASIA, PREVDAY }

enum class AssetType(val label: String) {
    FOREX("Forex"), COMMODITY("Rohstoffe"), INDEX("Indizes"), STOCK("Aktien")
}

/**
 * Ein beobachteter Markt.
 * windowStartMin/windowEndMin: Handelsfenster in UTC-Minuten, in dem Sweeps gesucht werden.
 */
data class Asset(
    val id: String,
    val name: String,
    val yahoo: String,
    val type: AssetType,
    val ref: RefType,
    val currencies: List<String>,
    val windowStartMin: Int,
    val windowEndMin: Int,
    val finnhub: String? = null,
)

object Assets {
    private const val FX_START = 7 * 60
    private const val FX_END = 17 * 60
    private const val US_START = 14 * 60 + 30
    private const val US_END = 21 * 60

    val all = listOf(
        Asset("EURUSD", "EUR/USD", "EURUSD=X", AssetType.FOREX, RefType.ASIA, listOf("EUR", "USD"), FX_START, FX_END),
        Asset("GBPUSD", "GBP/USD", "GBPUSD=X", AssetType.FOREX, RefType.ASIA, listOf("GBP", "USD"), FX_START, FX_END),
        Asset("USDJPY", "USD/JPY", "JPY=X", AssetType.FOREX, RefType.ASIA, listOf("USD", "JPY"), FX_START, FX_END),
        Asset("GOLD", "Gold", "GC=F", AssetType.COMMODITY, RefType.ASIA, listOf("USD"), FX_START, FX_END),
        Asset("SILVER", "Silber", "SI=F", AssetType.COMMODITY, RefType.ASIA, listOf("USD"), FX_START, FX_END),
        Asset("OIL", "Öl (WTI)", "CL=F", AssetType.COMMODITY, RefType.ASIA, listOf("USD"), FX_START, FX_END),
        Asset("NQ", "Nasdaq 100", "NQ=F", AssetType.INDEX, RefType.ASIA, listOf("USD"), FX_START, US_END),
        Asset("ES", "S&P 500", "ES=F", AssetType.INDEX, RefType.ASIA, listOf("USD"), FX_START, US_END),
        Asset("DAX", "DAX", "^GDAXI", AssetType.INDEX, RefType.PREVDAY, listOf("EUR"), 8 * 60, 16 * 60 + 30),
        Asset("AAPL", "Apple", "AAPL", AssetType.STOCK, RefType.PREVDAY, listOf("USD"), US_START, US_END, "AAPL"),
        Asset("NVDA", "Nvidia", "NVDA", AssetType.STOCK, RefType.PREVDAY, listOf("USD"), US_START, US_END, "NVDA"),
        Asset("PFE", "Pfizer", "PFE", AssetType.STOCK, RefType.PREVDAY, listOf("USD"), US_START, US_END, "PFE"),
        Asset("WMT", "Walmart", "WMT", AssetType.STOCK, RefType.PREVDAY, listOf("USD"), US_START, US_END, "WMT"),
        Asset("KO", "Coca-Cola", "KO", AssetType.STOCK, RefType.PREVDAY, listOf("USD"), US_START, US_END, "KO"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class Candle(val ts: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0)

/** Name eines Markts aus beiden Listen (Sweep-Assets und Daytrade-Assets). */
fun assetName(id: String): String = Assets.byId(id)?.name ?: DayAssets.byId(id)?.name ?: id

const val CAT_PROPOSAL = "VORSCHLAG"
const val CAT_SWEEP_ONLY = "NUR_SWEEP"

data class Signal(
    val id: String,
    val assetId: String,
    val ts: Long,
    val dir: Int,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val category: String,
    val reason: String,
    val tags: List<String>,
    val status: String = "OPEN",
    val exitPrice: Double = 0.0,
    val exitTs: Long = 0,
    val pct: Double = 0.0,
    val pnlEur: Double = 0.0,
) {
    val isOpen get() = status == "OPEN"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("assetId", assetId).put("ts", ts).put("dir", dir)
        .put("entry", entry).put("sl", sl).put("tp", tp)
        .put("category", category).put("reason", reason).put("tags", JSONArray(tags))
        .put("status", status).put("exitPrice", exitPrice).put("exitTs", exitTs)
        .put("pct", pct).put("pnlEur", pnlEur)

    companion object {
        fun fromJson(o: JSONObject) = Signal(
            id = o.getString("id"),
            assetId = o.getString("assetId"),
            ts = o.getLong("ts"),
            dir = o.getInt("dir"),
            entry = o.getDouble("entry"),
            sl = o.getDouble("sl"),
            tp = o.getDouble("tp"),
            category = o.optString("category", CAT_SWEEP_ONLY),
            reason = o.optString("reason"),
            tags = o.optJSONArray("tags").strings(),
            status = o.optString("status", "OPEN"),
            exitPrice = o.optDouble("exitPrice", 0.0),
            exitTs = o.optLong("exitTs", 0),
            pct = o.optDouble("pct", 0.0),
            pnlEur = o.optDouble("pnlEur", 0.0),
        )
    }
}

data class Sentiment(val assetId: String, val score: Double, val reason: String, val ts: Long) {
    fun toJson(): JSONObject = JSONObject().put("assetId", assetId).put("score", score).put("reason", reason).put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject) = Sentiment(o.getString("assetId"), o.optDouble("score", 0.0), o.optString("reason"), o.optLong("ts"))
    }
}

data class CalEvent(
    val title: String,
    val country: String,
    val ts: Long,
    val impact: String,
    val forecast: String,
    val previous: String,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("country", country).put("ts", ts)
        .put("impact", impact).put("forecast", forecast).put("previous", previous)

    companion object {
        fun fromJson(o: JSONObject) = CalEvent(
            o.optString("title"), o.optString("country"), o.optLong("ts"),
            o.optString("impact"), o.optString("forecast"), o.optString("previous"),
        )
    }
}

data class NewsItem(val headline: String, val source: String, val ts: Long, val related: String, val url: String) {
    fun toJson(): JSONObject = JSONObject().put("headline", headline).put("source", source).put("ts", ts)
        .put("related", related).put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = NewsItem(
            o.optString("headline"), o.optString("source"), o.optLong("ts"), o.optString("related"), o.optString("url"),
        )
    }
}

data class Earning(val symbol: String, val date: String, val hour: String, val epsEstimate: String) {
    fun toJson(): JSONObject = JSONObject().put("symbol", symbol).put("date", date).put("hour", hour).put("eps", epsEstimate)

    companion object {
        fun fromJson(o: JSONObject) = Earning(o.optString("symbol"), o.optString("date"), o.optString("hour"), o.optString("eps"))
    }
}

data class Settings(
    val finnhubKey: String = "",
    val claudeKey: String = "",
    val stake: Double = 150.0,
    val leverage: Double = 10.0,
    val minScore: Double = 0.3,
    val disabled: Set<String> = emptySet(),
    val daytradePush: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("finnhubKey", finnhubKey).put("claudeKey", claudeKey)
        .put("stake", stake).put("leverage", leverage).put("minScore", minScore)
        .put("disabled", JSONArray(disabled.toList()))
        .put("daytradePush", daytradePush)

    companion object {
        fun fromJson(o: JSONObject) = Settings(
            finnhubKey = o.optString("finnhubKey"),
            claudeKey = o.optString("claudeKey"),
            stake = o.optDouble("stake", 150.0),
            leverage = o.optDouble("leverage", 10.0),
            minScore = o.optDouble("minScore", 0.3),
            disabled = o.optJSONArray("disabled").strings().toSet(),
            daytradePush = o.optBoolean("daytradePush", true),
        )
    }
}

data class AppState(
    val signals: List<Signal> = emptyList(),
    val sentiments: Map<String, Sentiment> = emptyMap(),
    val calendar: List<CalEvent> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val earnings: List<Earning> = emptyList(),
    val settings: Settings = Settings(),
    val lastRun: Long = 0,
    val lastError: String = "",
    val sentimentTs: Long = 0,
    val calendarTs: Long = 0,
    val running: Boolean = false,
)

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

fun <T> JSONArray?.objects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        try {
            out.add(f(o))
        } catch (_: Exception) {
        }
    }
    return out
}
