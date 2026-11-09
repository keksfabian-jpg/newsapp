package de.fabian.newstrader

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
