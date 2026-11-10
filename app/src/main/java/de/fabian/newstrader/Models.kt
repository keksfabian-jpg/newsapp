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

/** partial = Kerze läuft laut Datenstand noch (Kurse kommen teils 10–15 min verzögert). */
data class Candle(
    val ts: Long, val o: Double, val h: Double, val l: Double, val c: Double,
    val v: Double = 0.0, val partial: Boolean = false,
)

/** Währungen, deren Wirtschaftstermine einen Markt bewegen. */
fun assetCurrencies(id: String): List<String> = Assets.byId(id)?.currencies ?: DayAssets.byId(id)?.cur ?: emptyList()

/** Name eines Markts aus beiden Listen (Sweep-Assets und Daytrade-Assets). */
fun assetName(id: String): String = Assets.byId(id)?.name ?: DayAssets.byId(id)?.name ?: id

const val CAT_PROPOSAL = "VORSCHLAG"
const val CAT_SWEEP_ONLY = "NUR_SWEEP"
const val CAT_ORB = "ORB"
const val CAT_AORB = "AORB"
const val CAT_IM = "IM"
const val CAT_ON = "ON"
const val CAT_SB = "SB"
const val CAT_SBL = "SBL"
const val CAT_SBP = "SBP"
const val CAT_LBO = "LBO"
const val CAT_VWAP = "VWAP"
const val CAT_FTD = "FTD"
const val CAT_NEWS = "NEWS"

data class StratInfo(val code: String, val name: String, val desc: String)

object Strategies {
    val all = listOf(
        StratInfo(
            CAT_PROPOSAL, "Sweep + News",
            "Asia-/Vortages-Range wird gesweept und zurückerobert, KI-Sentiment bestätigt die Richtung. Stop hinter dem Sweep, Ziel 2R, max. 24 h. (Reiter Signale)",
        ),
        StratInfo(
            CAT_SWEEP_ONLY, "Nur Sweep (ICT)",
            "Gleicher Liquidity Sweep wie oben, aber ohne News-Bestätigung. Zeigt, ob die News überhaupt etwas bringen.",
        ),
        StratInfo(
            CAT_FTD, "Fable-Trendtag (Eigenentwicklung)",
            "Sucht Trendtage in Nasdaq und S&P zwischen 16:30 und 19:00 (dt.). Einstieg nur, wenn alles zusammenpasst: Kurs außerhalb der ersten 30 Minuten, mind. ein Drittel der üblichen Tagesspanne gelaufen, Bewegung sauber statt zackig (Effizienz ≥ 0,35), ≥ 80 % der Kerzen auf derselben VWAP-Seite und der andere Index bestätigt. Stop knapp hinter dem VWAP. Kein festes Ziel: Ausstieg, sobald eine Kerze den VWAP zurückkreuzt, spätestens 21:55. Ungetestet – die Paper-Daten entscheiden.",
        ),
        StratInfo(
            CAT_ORB, "Opening Range Breakout",
            "Erste 5-min-Kerze nach Eröffnung (DAX 9:00, USA 15:30) gibt die Richtung vor, Einstieg zur Eröffnung der zweiten Kerze. Aktien nur bei 1,5× Volumen oder Earnings. Stop am anderen Kerzenende, Ziel 10R, Exit zum Handelsschluss.",
        ),
        StratInfo(
            CAT_AORB, "Asien-ORB (Nikkei, Hang Seng)",
            "Gleiche Regel wie der Opening Range Breakout, aber zur Eröffnung in Tokio (1:00 dt.) und Hongkong (2:30 dt.). Achtung: Zu diesen Zeiten sind KO-Scheine bei Trade Republic nicht handelbar – reine Beobachtung.",
        ),
        StratInfo(
            CAT_SBL, "ICT Silver Bullet London",
            "9–10 Uhr (dt.): Hoch/Tief der Nacht (ab 0 Uhr dt.) wird gesweept, danach Fair Value Gap in Gegenrichtung → Einstieg. Stop hinter dem Sweep, Ziel 2R, Exit 14:00. Nasdaq, S&P, Gold, EUR/USD, GBP/USD.",
        ),
        StratInfo(
            CAT_SB, "ICT Silver Bullet New York",
            "16–17 Uhr (dt.): Hoch/Tief der ersten 30 min wird gesweept, danach Fair Value Gap in Gegenrichtung → Einstieg. Stop hinter dem Sweep, Ziel 2R, Exit 22:00. Nasdaq, S&P, Gold.",
        ),
        StratInfo(
            CAT_SBP, "ICT Silver Bullet Nachmittag",
            "20–21 Uhr (dt.): Hoch/Tief der Mittagsrange (18–20 Uhr) wird gesweept, danach Fair Value Gap in Gegenrichtung → Einstieg. Stop hinter dem Sweep, Ziel 2R, Exit 22:00. Nasdaq, S&P.",
        ),
        StratInfo(
            CAT_LBO, "London Breakout",
            "Gegenstück zum Sweep: Schließt eine 5-min-Kerze zwischen 8 und 11 Uhr (dt.) klar außerhalb der Asia-Range, wird MIT dem Ausbruch gehandelt. Stop in der Range-Mitte, Ziel 1,5R, Exit 17:00. EUR/USD, GBP/USD, USD/JPY, Gold.",
        ),
        StratInfo(
            CAT_VWAP, "Orderflow-Näherung (VWAP)",
            "Um 16:30 (dt.): Kurs klar über/unter dem VWAP seit Eröffnung UND Kaufdruck-Näherung aus dem Volumen in dieselbe Richtung → mit dem Trend handeln. Stop am VWAP, Ziel 2R, Exit 22:00. Echte Orderbuchdaten sind nicht frei verfügbar.",
        ),
        StratInfo(
            CAT_IM, "Intraday-Momentum",
            "S&P/Nasdaq: Bewegt sich der Markt vom Vortagesschluss bis 16:00 Uhr (dt.) um mind. 0,25 %, wird um 21:30 in diese Richtung gehandelt und um 22:00 geschlossen. Grund: Absicherungsflüsse vor Handelsschluss.",
        ),
        StratInfo(
            CAT_ON, "Overnight-Effekt",
            "S&P/Nasdaq Long um 21:50, Verkauf zur US-Eröffnung am nächsten Tag (15:30). Nur Mo–Do. Ein großer Teil der Aktienrendite entsteht über Nacht. Steht ein wichtiger Termin an, gibt es eine Warnung.",
        ),
        StratInfo(
            CAT_NEWS, "News pur (KI)",
            "Sobald die KI für einen Markt ein starkes Sentiment (≥ 0,5) meldet, wird sofort in diese Richtung gehandelt – ohne Chart-Bestätigung. Stop 1,5× ATR, Ziel 3× ATR, Exit nach 4 h.",
        ),
    )
    val sweepCodes = setOf(CAT_PROPOSAL, CAT_SWEEP_ONLY)
    val dayCodes = all.map { it.code }.toSet() - sweepCodes
    fun name(code: String) = all.firstOrNull { it.code == code }?.name ?: code
    fun short(code: String) = name(code).substringBefore(" (")
}

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
    /** Geplanter Zeitausstieg (0 = Standard 24 h). */
    val exitAt: Long = 0,
    /** Größte Gegenbewegung bis zum Ausstieg als Anteil vom Einstieg (−1 = unbekannt). Für die KO-Simulation. */
    val mae: Double = -1.0,
) {
    val isOpen get() = status == "OPEN"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("assetId", assetId).put("ts", ts).put("dir", dir)
        .put("entry", entry).put("sl", sl).put("tp", tp)
        .put("category", category).put("reason", reason).put("tags", JSONArray(tags))
        .put("status", status).put("exitPrice", exitPrice).put("exitTs", exitTs)
        .put("pct", pct).put("exitAt", exitAt).put("mae", mae)

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
            exitAt = o.optLong("exitAt", 0),
            mae = o.optDouble("mae", -1.0),
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
    /** Einsatz pro Trade in €. */
    val stake: Double = 150.0,
    /** Hebel-Profil: 0 = Auto (KO knapp hinter dem Stop), sonst fester Hebel. */
    val lev: Int = 0,
    val startCapital: Double = 250.0,
    val minScore: Double = 0.3,
    val disabled: Set<String> = emptySet(),
    val daytradePush: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("finnhubKey", finnhubKey).put("claudeKey", claudeKey)
        .put("stake", stake).put("lev", lev).put("startCapital", startCapital).put("minScore", minScore)
        .put("disabled", JSONArray(disabled.toList()))
        .put("daytradePush", daytradePush)

    companion object {
        fun fromJson(o: JSONObject) = Settings(
            finnhubKey = o.optString("finnhubKey"),
            claudeKey = o.optString("claudeKey"),
            stake = o.optDouble("stake", 150.0),
            lev = o.optInt("lev", 0),
            startCapital = o.optDouble("startCapital", 250.0),
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
    /** Lauf-Protokoll, neueste Zeile zuerst. */
    val log: List<String> = emptyList(),
    /** Bereits entschiedene Strategie-Slots (Strategie-Markt-Tag), damit nichts doppelt geprüft wird. */
    val decided: Set<String> = emptySet(),
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
