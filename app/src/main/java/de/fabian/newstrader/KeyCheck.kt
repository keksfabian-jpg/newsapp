package de.fabian.newstrader

import org.json.JSONObject

/** Prüft die eingetragenen API-Keys einzeln mit einer kleinen Testanfrage. */
object KeyCheck {
    fun finnhub(raw: String): String {
        val key = cleanKey(raw)
        if (key.isBlank()) return "Finnhub: kein Key eingetragen"
        if (key.startsWith("sk-ant")) return "✗ Finnhub: Das ist ein Claude-Key – Felder vertauscht?"
        return try {
            val t = Net.get("https://finnhub.io/api/v1/quote?symbol=AAPL&token=$key")
            if (JSONObject(t).has("c")) "✓ Finnhub-Key funktioniert" else "Finnhub: unerwartete Antwort"
        } catch (e: Exception) {
            "✗ Finnhub: ${friendly(e)} – im Dashboard „API key“ kopieren, nicht „Webhook secret“"
        }
    }

    fun claude(raw: String): String {
        val key = cleanKey(raw)
        if (key.isBlank()) return "Claude: kein Key eingetragen"
        if (!key.startsWith("sk-ant")) return "✗ Claude: Key muss mit „sk-ant-“ beginnen"
        return try {
            ClaudeApi.ping(key)
            "✓ Claude-Key funktioniert"
        } catch (e: Exception) {
            "✗ Claude: ${friendly(e)}"
        }
    }
}
