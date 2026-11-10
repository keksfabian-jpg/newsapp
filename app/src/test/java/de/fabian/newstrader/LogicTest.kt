package de.fabian.newstrader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LogicTest {
    private val ny = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 11, 10) // Dienstag
    private fun et(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(ny).toEpochSecond()
    private fun ctx(candles: Map<String, List<Candle>> = emptyMap()) =
        Ctx(et(day, 13, 30), emptyMap(), emptyList(), emptyList(), candles)

    private fun trade(dec: Dec): Signal {
        assertTrue("erwartet Trade, war ${(dec as? Dec.No)?.why ?: (dec as? Dec.Wait)?.why}", dec is Dec.Trade)
        return (dec as Dec.Trade).sig
    }

    @Test
    fun liveRowIsMergedAndFormingBarMarked() {
        val t = 1794327000L // durch 300 teilbar
        val raw = listOf(
            Candle(t, 10.0, 11.0, 9.5, 10.5, 100.0),
            Candle(t + 300, 10.5, 10.8, 10.2, 10.6, 100.0),
            Candle(t + 600, 10.6, 10.7, 10.4, 10.5, 20.0),
            Candle(t + 689, 10.9, 10.9, 10.9, 10.9, 0.0), // Live-Zeile
        )
        val out = Sources.normalize(raw, 300, t + 689)
        assertEquals(3, out.size)
        assertFalse(out[1].partial)
        assertTrue(out[2].partial)
        assertEquals(10.9, out[2].c, 1e-9)
        assertEquals(10.9, out[2].h, 1e-9)
    }

    @Test
    fun closedMarketHasNoPartialBars() {
        val t = 1794327000L
        val raw = listOf(Candle(t, 1.0, 1.0, 1.0, 1.0), Candle(t + 300, 1.0, 1.0, 1.0, 1.0))
        assertTrue(Sources.normalize(raw, 300, t + 900).none { it.partial })
    }

    @Test
    fun sizingAutoAndKnockout() {
        val sl = Signal("a", "USDJPY", 0, 1, 158.154, 158.027, 158.407, CAT_SWEEP_ONLY, "", emptyList(),
            status = "SL", exitPrice = 158.027, exitTs = 900, pct = (158.027 - 158.154) / 158.154)
        val auto = Sizing.position(sl, Settings(stake = 150.0, lev = 0))
        assertEquals(300.0, auto.lev, 0.0)
        assertFalse(auto.ko)
        assertEquals(-1.0, auto.r, 1e-6)
        assertEquals(-42.6, auto.pnl, 0.5) // 150 × 300 × 0,0803 % + 4,50 Spread + 2 Gebühren

        // Stop 0,35 % entfernt, fester Hebel 300 → KO bei 0,33 % vor dem Stop
        val wide = Signal("b", "NQ", 0, 1, 28000.0, 27902.0, 28196.0, CAT_SWEEP_ONLY, "", emptyList(),
            status = "TP", exitPrice = 28196.0, exitTs = 900, pct = 0.007, mae = 0.0034)
        val p300 = Sizing.position(wide, Settings(stake = 150.0, lev = 300))
        assertTrue(p300.ko)
        assertEquals(-151.0, p300.pnl, 1e-6)
        val pAuto = Sizing.position(wide, Settings(stake = 150.0, lev = 0))
        assertFalse(pAuto.ko)
        assertEquals(257.0, pAuto.lev, 0.0)
        assertEquals(2.0, pAuto.r, 1e-6)
    }

    @Test
    fun orbEntersAtSecondBarOpen() {
        val o = et(day, 9, 30)
        val cs = listOf(
            Candle(o - 300, 99.9, 100.0, 99.8, 100.0, 5.0),
            Candle(o, 100.0, 100.6, 99.8, 100.5, 10.0),
            Candle(o + 300, 100.55, 100.9, 100.4, 100.8, 10.0, partial = true),
        )
        val a = DayAssets.orb.first { it.id == "ORB_NQ" }
        val s = trade(Daytrade.decide(CAT_ORB, a, cs, day, ctx()))
        assertEquals(1, s.dir)
        assertEquals(100.55, s.entry, 1e-9)
        assertEquals(99.8, s.sl, 1e-9)
        assertEquals(100.55 + 10 * 0.75, s.tp, 1e-9)
        assertEquals(o + 300, s.ts)
        // erste Kerze noch unfertig → warten
        val waiting = listOf(Candle(o, 100.0, 100.6, 99.8, 100.5, 10.0, partial = true))
        assertTrue(Daytrade.decide(CAT_ORB, a, waiting, day, ctx()) is Dec.Wait)
    }

    @Test
    fun silverBulletNeedsSweepAndGap() {
        val o = et(day, 9, 30)
        val cs = ArrayList<Candle>()
        for (i in 0 until 6) cs += Candle(o + i * 300L, 100.5, 101.0, 100.0, 100.5, 1.0)
        val w = et(day, 10, 0)
        cs += Candle(w, 101.0, 101.5, 100.9, 101.2, 1.0) // Sweep über 101,0
        cs += Candle(w + 300, 101.2, 101.25, 100.5, 100.55, 1.0)
        cs += Candle(w + 600, 100.55, 100.7, 100.3, 100.4, 1.0) // FVG: 100,9 > 100,7
        cs += Candle(w + 900, 100.4, 100.45, 100.3, 100.35, 1.0, partial = true)
        val s = trade(Daytrade.decide(CAT_SB, DayAssets.nq, cs, day, ctx()))
        assertEquals(-1, s.dir)
        assertEquals(100.4, s.entry, 1e-9)
        assertEquals(100.4 + 1.1 * 1.02, s.sl, 1e-9)
        assertEquals(w + 900, s.ts)
        // ohne FVG und Fenster noch offen → warten
        assertTrue(Daytrade.decide(CAT_SB, DayAssets.nq, cs.take(8), day, ctx()) is Dec.Wait)
    }

    @Test
    fun timedExitAndTrailingStop() {
        val t = et(day, 10, 30)
        val sig = Signal("x", "F_NQ", t, 1, 100.0, 99.0, 102.0, CAT_SB, "", emptyList(), exitAt = t + 3600)
        val up = listOf(Candle(t, 100.0, 100.5, 99.6, 100.4), Candle(t + 300, 100.4, 102.1, 100.3, 102.0))
        val tp = Daytrade.evaluateTimed(sig, up, t + 700, 300)
        assertEquals("TP", tp.status)
        assertEquals(0.004, tp.mae, 1e-9)
        val flat = listOf(Candle(t, 100.0, 100.5, 99.6, 100.4), Candle(t + 3600, 100.7, 100.8, 100.6, 100.7))
        val eod = Daytrade.evaluateTimed(sig.copy(tp = 0.0), flat, t + 3600 + 400, 300)
        assertEquals("EOD", eod.status)
        assertEquals(100.7, eod.exitPrice, 1e-9)
    }

    @Test
    fun trendDayFiresOnCleanTrend() {
        fun series(start: Double, id: String): List<Candle> {
            val cs = ArrayList<Candle>()
            val prev = et(day.minusDays(1), 9, 30)
            for (i in 0 until 78) { // Vortag: Spanne 1 %
                val base = start * (1 + 0.005 * (if (i % 2 == 0) 1 else -1))
                cs += Candle(prev + i * 300L, start, maxOf(start, base), minOf(start, base), start, 100.0)
            }
            val o = et(day, 9, 30)
            var p = start
            for (i in 0 until 16) {
                val n = p * 1.0006
                cs += Candle(o + i * 300L, p, n, p, n, 100.0)
                p = n
            }
            return cs
        }
        val nq = series(28000.0, "F_NQ")
        val es = series(7500.0, "F_ES")
        val s = trade(Daytrade.decide(CAT_FTD, DayAssets.nq, nq, day, ctx(mapOf("F_NQ" to nq, "F_ES" to es))))
        assertEquals(1, s.dir)
        assertEquals(et(day, 10, 30), s.ts)
        assertTrue(s.sl < s.entry)
        assertEquals(0.0, s.tp, 0.0)

        // Zickzack ohne Richtung → kein Trade
        val chop = ArrayList(nq.take(78))
        val o = et(day, 9, 30)
        for (i in 0 until 50) {
            val c = if (i % 2 == 0) 28050.0 else 27950.0
            chop += Candle(o + i * 300L, 28000.0, 28060.0, 27940.0, c, 100.0)
        }
        assertTrue(Daytrade.decide(CAT_FTD, DayAssets.nq, chop, day, ctx(mapOf("F_NQ" to chop, "F_ES" to chop))) is Dec.No)
    }
}
