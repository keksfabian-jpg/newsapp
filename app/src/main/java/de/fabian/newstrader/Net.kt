package de.fabian.newstrader

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

/** Entfernt Leerzeichen, Zeilenumbrüche und unsichtbare Zeichen aus einem kopierten API-Key. */
fun cleanKey(s: String): String = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

/** Kurze, verständliche Fehlermeldung statt HTML-Dump. */
fun friendly(e: Exception): String = when (e) {
    is HttpException -> when {
        e.body.contains("credit balance", true) -> "kein Guthaben auf dem API-Konto"
        e.code == 401 || e.code == 403 -> "API-Key ungültig (HTTP ${e.code})"
        e.code == 429 -> "zu viele Anfragen – neuer Versuch später"
        e.code == 404 -> "nicht gefunden (HTTP 404)"
        e.code >= 500 -> "Server-Fehler (HTTP ${e.code})"
        else -> "HTTP ${e.code}"
    }
    is java.net.UnknownHostException -> "keine Internetverbindung"
    is java.net.SocketTimeoutException -> "Zeitüberschreitung"
    else -> (e.message ?: e.javaClass.simpleName).take(80)
}

object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android 15) NewsTrader/1.0"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, null, headers)

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, body, headers)

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text.take(500))
            return text
        } finally {
            c.disconnect()
        }
    }
}
