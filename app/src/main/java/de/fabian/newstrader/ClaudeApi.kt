package de.fabian.newstrader

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Bewertet Schlagzeilen pro Asset mit der Claude API (Score -1 … +1). */
object ClaudeApi {
    private const val MODEL = "claude-haiku-5-5"

    private fun headers(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
        "content-type" to "application/json",
    )

    /** Minimale Anfrage zum Prüfen des Keys. */
    fun ping(key: String) {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 5)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")))
        Net.post("https://api.anthropic.com/v1/messages", body.toString(), headers(key))
    }

    fun sentiment(
        key: String,
        assets: List<Asset>,
        news: List<NewsItem>,
        events: List<CalEvent>,
        now: Long,
    ): List<Sentiment> {
        val sb = StringBuilder()
        sb.append("ASSETS:\n")
        assets.forEach { sb.append("- ${it.id}: ${it.name} (${it.type.label})\n") }
        sb.append("\nWICHTIGE WIRTSCHAFTSTERMINE (UTC):\n")
        if (events.isEmpty()) sb.append("- keine\n")
        events.forEach {
            sb.append("- ${Fmt.utc(it.ts)} ${it.country} ${it.title} (Prognose ${it.forecast.ifBlank { "-" }}, vorher ${it.previous.ifBlank { "-" }})\n")
        }
        sb.append("\nSCHLAGZEILEN (neueste zuerst, UTC):\n")
        news.take(90).forEach {
            val rel = if (it.related.isNotBlank()) " [${it.related}]" else ""
            sb.append("- ${Fmt.utc(it.ts)}$rel ${it.headline}\n")
        }

        val prompt = """
Du bist ein nüchterner Markt-Analyst. Bewerte für jedes Asset unten, in welche Richtung die aktuelle Nachrichtenlage den Kurs in den nächsten Stunden eher drückt.

Regeln:
- score von -1.0 (klar bärisch) bis +1.0 (klar bullisch). 0 = neutral oder keine relevanten News.
- Nur echte, konkrete Informationen zählen (Zahlen, Entscheidungen, Überraschungen ggü. Prognose). Meinungsartikel und Allgemeinplätze kaum gewichten.
- Forex-Paare: Richtung des Paares (z. B. EUR/USD steigt, wenn USD schwächer wird).
- Sei zurückhaltend: Werte über 0.6 nur bei sehr klarer Lage.
- reason: ein kurzer deutscher Satz (max. 20 Wörter).

Antworte NUR mit einem JSON-Array, ohne weiteren Text:
[{"id":"EURUSD","score":0.0,"reason":"..."}]

$sb
""".trimIndent()

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val resp = Net.post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            headers(key),
        )
        val content = JSONObject(resp).getJSONArray("content")
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") text.append(b.optString("text"))
        }
        val t = text.toString()
        val s = t.indexOf('[')
        val e = t.lastIndexOf(']')
        if (s < 0 || e <= s) throw IOException("Unerwartete KI-Antwort")
        val ids = assets.map { it.id }.toSet()
        return JSONArray(t.substring(s, e + 1)).objects { o ->
            Sentiment(
                assetId = o.getString("id"),
                score = o.optDouble("score", 0.0).coerceIn(-1.0, 1.0),
                reason = o.optString("reason"),
                ts = now,
            )
        }.filter { it.assetId in ids }
    }
}
