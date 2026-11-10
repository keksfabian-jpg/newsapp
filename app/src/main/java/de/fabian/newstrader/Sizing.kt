package de.fabian.newstrader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Ergebnis eines Paper-Trades unter dem gewählten Hebel-Profil. */
data class Position(
    val lev: Double,
    val stake: Double,
    /** Kurs, bei dem der KO-Schein wertlos verfällt. */
    val koPrice: Double,
    val ko: Boolean,
    val pnl: Double,
    /** Ergebnis in Vielfachen des Stop-Risikos (hebelunabhängig). */
    val r: Double,
    /** Angenommene Kosten: Spread + Gebühren. */
    val cost: Double,
)

/**
 * Rechenmodell für KO-Scheine:
 * - Gewinn/Verlust = Einsatz × Hebel × Kursbewegung − Spread − 2 € Gebühren.
 * - KO: Läuft der Kurs um 1/Hebel gegen die Position, ist der Einsatz weg – auch wenn der Stop weiter entfernt liegt.
 * - Spread ist eine Annahme je Marktart und wächst mit dem Hebel.
 * Das Ergebnis wird immer aus den gespeicherten Kursen berechnet, lässt sich also für jedes Profil neu rechnen.
 */
object Sizing {
    val levels = listOf(0, 10, 30, 100, 300)
    const val MAX_AUTO = 300.0
    const val FEES = 2.0

    fun label(l: Int) = if (l == 0) "Auto" else "$l×"

    /** Angenommener Spread als Anteil vom Kurs des Basiswerts. */
    fun spread(assetId: String): Double = when (assetId) {
        "EURUSD", "GBPUSD", "USDJPY", "F_EU", "F_GU", "F_UJ" -> 0.00010
        "NQ", "ES", "DAX", "ORB_NQ", "ORB_ES", "ORB_DAX", "F_NQ", "F_ES" -> 0.00010
        "A_N225", "A_HSI" -> 0.00030
        "GOLD", "F_GC" -> 0.00020
        "OIL", "F_CL" -> 0.00040
        "SILVER" -> 0.00050
        else -> 0.00050 // Einzelaktien
    }

    fun stopDist(s: Signal): Double = if (s.sl > 0 && s.entry > 0) abs(s.entry - s.sl) / s.entry else 0.0

    fun leverage(sig: Signal, st: Settings): Double {
        if (st.lev > 0) return st.lev.toDouble()
        val d = stopDist(sig)
        if (d <= 0) return 10.0
        // höchster Hebel, bei dem der KO noch knapp hinter dem Stop liegt
        return floor(min(MAX_AUTO, 0.9 / d)).coerceAtLeast(1.0)
    }

    fun position(sig: Signal, st: Settings): Position {
        val lev = leverage(sig, st)
        val stake = st.stake
        val d = stopDist(sig)
        val koPrice = sig.entry * (1 - sig.dir / lev)
        val spreadCost = stake * lev * spread(sig.assetId)
        if (sig.isOpen) return Position(lev, stake, koPrice, false, 0.0, 0.0, spreadCost + FEES)
        val mae = when {
            sig.mae >= 0 -> sig.mae
            sig.status == "SL" -> d
            else -> max(0.0, -sig.pct)
        }
        val ko = mae >= 1.0 / lev || sig.pct * lev <= -1.0
        val pnl = if (ko) -stake - FEES / 2 else max(stake * lev * sig.pct - spreadCost, -stake) - FEES
        val r = if (d > 0) sig.pct / d else 0.0
        return Position(lev, stake, koPrice, ko, pnl, r, spreadCost + FEES)
    }
}
