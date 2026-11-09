package de.fabian.newstrader

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val de = Locale.GERMANY
    private val dateTimeF = DateTimeFormatter.ofPattern("dd.MM. HH:mm", de)
    private val timeF = DateTimeFormatter.ofPattern("HH:mm", de)
    private val dayF = DateTimeFormatter.ofPattern("EEEE, dd.MM.", de)
    private val utcF = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    private fun local(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault())

    fun price(p: Double): String = when {
        p >= 1000 -> String.format(de, "%,.1f", p)
        p >= 100 -> String.format(de, "%.2f", p)
        p >= 10 -> String.format(de, "%.3f", p)
        else -> String.format(de, "%.5f", p)
    }

    fun eur(v: Double): String = String.format(de, "%+,.2f €", v)
    fun eurPlain(v: Double): String = String.format(de, "%,.0f €", v)
    fun pct(v: Double): String = String.format(de, "%+.2f %%", v * 100)
    fun score(v: Double): String = String.format(de, "%+.1f", v)
    fun dateTime(sec: Long): String = dateTimeF.format(local(sec))
    fun time(sec: Long): String = timeF.format(local(sec))
    fun day(sec: Long): String = dayF.format(local(sec))
    fun utc(sec: Long): String = utcF.format(Instant.ofEpochSecond(sec).atOffset(ZoneOffset.UTC))

    fun ago(sec: Long, now: Long): String {
        val m = (now - sec) / 60
        return when {
            sec <= 0 -> "noch nie"
            m < 1 -> "gerade eben"
            m < 60 -> "vor $m min"
            m < 1440 -> "vor ${m / 60} h"
            else -> "vor ${m / 1440} d"
        }
    }
}
