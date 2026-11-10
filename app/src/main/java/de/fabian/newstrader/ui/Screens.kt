@file:OptIn(ExperimentalMaterial3Api::class)

package de.fabian.newstrader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.fabian.newstrader.AppState
import de.fabian.newstrader.Asset
import de.fabian.newstrader.AssetType
import de.fabian.newstrader.Assets
import de.fabian.newstrader.CAT_PROPOSAL
import de.fabian.newstrader.CAT_SWEEP_ONLY
import de.fabian.newstrader.CalEvent
import de.fabian.newstrader.Earning
import de.fabian.newstrader.Fmt
import de.fabian.newstrader.KeyCheck
import de.fabian.newstrader.Daytrade
import de.fabian.newstrader.Export
import de.fabian.newstrader.Alarms
import de.fabian.newstrader.Position
import de.fabian.newstrader.Settings as AppSettings
import de.fabian.newstrader.Sizing
import de.fabian.newstrader.Strategies
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import de.fabian.newstrader.assetName
import androidx.compose.material.icons.filled.PlayArrow
import de.fabian.newstrader.cleanKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.VisualTransformation
import de.fabian.newstrader.NewsItem
import de.fabian.newstrader.RefreshWorker
import de.fabian.newstrader.Sentiment
import de.fabian.newstrader.Signal
import de.fabian.newstrader.Store
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun nowSec() = System.currentTimeMillis() / 1000

// ---------------------------------------------------------------- Gerüst

@Composable
fun AppRoot() {
    val state by Store.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Signale" to Icons.Filled.Home,
        "Strategien" to Icons.Filled.PlayArrow,
        "Termine" to Icons.Filled.DateRange,
        "News" to Icons.Filled.Info,
        "Setup" to Icons.Filled.Settings,
    )
    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Card, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Text,
                            selectedTextColor = C.Text,
                            unselectedIconColor = C.Muted,
                            unselectedTextColor = C.Muted,
                            indicatorColor = C.Accent.copy(alpha = 0.28f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> SignalsScreen(state)
                1 -> DaytradeScreen(state)
                2 -> CalendarScreen(state)
                3 -> NewsScreen(state)
                else -> SettingsScreen(state)
            }
        }
    }
}

@Composable
private fun Screen(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Title(text: String, sub: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = C.Muted, fontSize = 13.sp)
        }
        trailing()
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = C.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(top = 10.dp),
    )
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, border: Color = C.Line, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, border, shape).padding(14.dp),
        content = content,
    )
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun RefreshButton(state: AppState) {
    val ctx = LocalContext.current
    if (state.running) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = C.Accent)
    } else {
        IconButton(onClick = { RefreshWorker.runNow(ctx) }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren", tint = C.AccentSoft)
        }
    }
}

// ---------------------------------------------------------------- Signale

private typealias PosMap = Map<String, Position>

@Composable
private fun positions(state: AppState): PosMap = remember(state.signals, state.settings) {
    state.signals.associate { it.id to Sizing.position(it, state.settings) }
}

private fun PosMap.pnl(s: Signal) = this[s.id]?.pnl ?: 0.0

private fun hitRate(l: List<Signal>, pos: PosMap) =
    if (l.isEmpty()) "–" else "${l.count { pos.pnl(it) > 0 } * 100 / l.size} %"

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = C.Card,
    labelColor = C.Muted,
    selectedContainerColor = C.Accent.copy(alpha = 0.25f),
    selectedLabelColor = C.Text,
)

/** Hebel-Profil: rechnet alle Paper-Trades sofort neu. */
@Composable
private fun LeverageBar(s: AppSettings) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hebel-Profil", color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("Einsatz ${Fmt.eurPlain(s.stake).removePrefix("+")} pro Trade", color = C.Muted, fontSize = 12.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sizing.levels.forEach { l ->
                FilterChip(
                    selected = s.lev == l,
                    onClick = { Store.update { st -> st.copy(settings = st.settings.copy(lev = l)) } },
                    label = { Text(Sizing.label(l)) },
                    colors = chipColors(),
                )
            }
        }
        Text(
            if (s.lev == 0) "Auto: höchster Hebel bis 300×, bei dem der KO noch knapp hinter dem Stop liegt."
            else "Fester Hebel ${s.lev}×: KO, sobald der Kurs ${String.format(Locale.GERMANY, "%.2f", 100.0 / s.lev)} % gegen dich läuft – auch vor dem Stop.",
            color = C.Muted, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
fun SignalsScreen(state: AppState) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val pos = positions(state)
    val proposals = state.signals.filter { it.category == CAT_PROPOSAL }
    val closedP = proposals.filter { !it.isOpen }.sortedBy { it.exitTs }
    val closedS = state.signals.filter { it.category == CAT_SWEEP_ONLY && !it.isOpen }
    val list = if (showAll) state.signals.filter { it.category in Strategies.sweepCodes } else proposals

    Screen {
        item {
            Title("NewsTrader", "Aktualisiert ${Fmt.ago(state.lastRun, nowSec())}") { RefreshButton(state) }
        }
        item { PnlCard(closedP.map { pos.pnl(it) }, "Paper-Ergebnis der Vorschläge", state.settings) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Vorschläge", hitRate(closedP, pos), "${closedP.size} abgeschl.", Modifier.weight(1f))
                StatTile("Nur Sweep", hitRate(closedS, pos), "${closedS.size} abgeschl.", Modifier.weight(1f))
                StatTile("Offen", "${list.count { it.isOpen }}", "Paper-Trades", Modifier.weight(1f))
            }
        }
        if (state.settings.finnhubKey.isBlank() || state.settings.claudeKey.isBlank()) {
            item {
                CardBox(border = C.Amber.copy(alpha = 0.5f)) {
                    Text("API-Keys fehlen", color = C.Amber, fontWeight = FontWeight.Bold)
                    Text(
                        "Trage unter Setup deinen Finnhub- und Claude-Key ein. Ohne Keys gibt es kein News-Sentiment und damit nur „Nur Sweep“-Signale.",
                        color = C.Text, fontSize = 13.sp,
                    )
                }
            }
        }
        if (state.lastError.isNotBlank()) {
            item { Text(state.lastError, color = C.Amber, fontSize = 12.sp) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(if (showAll) "Alle Signale" else "Vorschläge")
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = !showAll },
                    label = { Text("inkl. Nur Sweep") },
                    colors = chipColors(),
                )
            }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    "Noch keine Signale. Die App prüft alle 15 Minuten im Hintergrund – auch wenn sie geschlossen ist.",
                    color = C.Muted, fontSize = 14.sp,
                )
            }
        }
        items(list.take(150), key = { it.id }) { sig -> pos[sig.id]?.let { SignalCard(sig, it) } }
    }
}

@Composable
private fun PnlCard(pnls: List<Double>, title: String, s: AppSettings) {
    val total = pnls.sum()
    CardBox {
        Text(title, color = C.Muted, fontSize = 13.sp)
        Text(
            Fmt.eur(total),
            color = if (total >= 0) C.Green else C.Red,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Konto: ${Fmt.eurPlain(s.startCapital).removePrefix("+")} → ${Fmt.eurPlain(s.startCapital + total).removePrefix("+")} · Hebel ${Sizing.label(s.lev)}",
            color = C.Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        val pts = remember(pnls) {
            val l = ArrayList<Double>()
            var acc = 0.0
            l.add(0.0)
            pnls.forEach { acc += it; l.add(acc) }
            l
        }
        if (pts.size < 2) {
            Text("Die Kurve erscheint nach dem ersten abgeschlossenen Trade.", color = C.Muted, fontSize = 13.sp)
        } else {
            LineChart(pts, Modifier.fillMaxWidth().height(130.dp))
        }
    }
}

@Composable
private fun LineChart(values: List<Double>, modifier: Modifier) {
    val color = if (values.last() >= 0) C.Green else C.Red
    Canvas(modifier) {
        val lo = min(values.min(), 0.0)
        val hi = max(values.max(), 0.0)
        val range = if (hi - lo > 0) hi - lo else 1.0
        fun x(i: Int) = size.width * i / (values.size - 1).toFloat()
        fun y(v: Double) = (size.height * (1 - (v - lo) / range)).toFloat()

        drawLine(C.Line, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        val fill = Path()
        fill.addPath(line)
        fill.lineTo(size.width, size.height)
        fill.lineTo(0f, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(C.Card).border(1.dp, C.Line, shape).padding(12.dp)) {
        Text(title, color = C.Muted, fontSize = 12.sp)
        Text(value, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = C.Muted, fontSize = 11.sp)
    }
}

private fun statusLabel(s: String) = when (s) {
    "TP" -> "TP erreicht"
    "SL" -> "SL erreicht"
    "ZEIT" -> "nach 24 h"
    "EOD" -> "Zeitausstieg"
    "TRAIL" -> "VWAP-Ausstieg"
    else -> s
}

@Composable
private fun SignalCard(s: Signal, p: Position) {
    var open by remember { mutableStateOf(false) }
    val dirColor = if (s.dir == 1) C.Green else C.Red
    val highlight = s.isOpen && (s.category == CAT_PROPOSAL || s.category in Strategies.dayCodes)
    val warn = s.tags.filter { it.startsWith("⚠") }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.Card)
            .border(1.dp, if (highlight) C.Accent.copy(alpha = 0.7f) else C.Line, shape)
            .clickable { open = !open }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                assetName(s.assetId), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Chip(if (s.dir == 1) "LONG" else "SHORT", dirColor)
            if (s.category == CAT_SWEEP_ONLY) {
                Spacer(Modifier.width(6.dp))
                Chip("NUR SWEEP", C.Muted)
            }
            if (p.ko) {
                Spacer(Modifier.width(6.dp))
                Chip("KO", C.Red)
            }
            Spacer(Modifier.weight(1f))
            if (s.isOpen) {
                Text("offen", color = C.AccentSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(
                    Fmt.eur(p.pnl),
                    color = if (p.pnl >= 0) C.Green else C.Red,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val strat = if (s.category in Strategies.dayCodes) "${Strategies.short(s.category)} · " else ""
        val sub = strat + Fmt.dateTime(s.ts) +
            if (!s.isOpen) " · ${statusLabel(s.status)} · ${Fmt.r(p.r)} · ${Fmt.pct(s.pct)}" else ""
        Text(sub, color = C.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Level("Einstieg", s.entry, C.Text, Modifier.weight(1f))
            Level("Stop-Loss", s.sl, C.Red, Modifier.weight(1f))
            if (s.tp > 0) {
                Level("Take-Profit", s.tp, C.Green, Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Ausstieg", color = C.Muted, fontSize = 11.sp)
                    Text(Fmt.time(s.exitAt), color = C.AccentSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Hebel", color = C.Muted, fontSize = 11.sp)
                Text("${p.lev.toInt()}×", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("Einsatz", color = C.Muted, fontSize = 11.sp)
                Text(Fmt.eurPlain(p.stake).removePrefix("+"), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Level("KO-Schwelle", p.koPrice, C.Amber, Modifier.weight(1f))
        }
        warn.forEach {
            Text(it, color = C.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
        val tags = s.tags.filter { !it.startsWith("⚠") }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tags.joinToString(" · "), color = C.AccentSoft, fontSize = 12.sp)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(s.reason, color = C.Text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
            Text(
                "Angenommene Kosten: ${Fmt.eur(-p.cost)} (Spread + 2 € Gebühren)",
                color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text("Tippen für Begründung", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Level(label: String, v: Double, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = C.Muted, fontSize = 11.sp)
        Text(Fmt.price(v), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Strategien

@Composable
fun DaytradeScreen(state: AppState) {
    val ctx = LocalContext.current
    val now = nowSec()
    var filter by rememberSaveable { mutableStateOf("ALLE") }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val pos = positions(state)
    val shown = if (filter == "ALLE") state.signals else state.signals.filter { it.category == filter }
    val closedShown = shown.filter { !it.isOpen }.sortedBy { it.exitTs }

    Screen {
        item { Title("Strategien", "Paper-Vergleich · jede Position max. 24 h") { RefreshButton(state) } }
        item { LeverageBar(state.settings) }
        item { ComparisonCard(state.signals, pos) { filter = if (filter == it) "ALLE" else it } }
        item {
            PnlCard(
                closedShown.map { pos.pnl(it) },
                if (filter == "ALLE") "Paper-Ergebnis aller Strategien" else "Paper-Ergebnis: ${Strategies.short(filter)}",
                state.settings,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("ALLE") + Strategies.all.map { it.code }).forEach { code ->
                    FilterChip(
                        selected = filter == code,
                        onClick = { filter = code },
                        label = { Text(if (code == "ALLE") "Alle" else Strategies.short(code)) },
                        colors = chipColors(),
                    )
                }
            }
        }
        item {
            CardBox {
                Text("Nächste Eröffnungen (deine Zeit)", color = C.Muted, fontSize = 13.sp)
                Daytrade.nextOpens(now).forEach { (label, ts) ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(label, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.dateTime(ts), color = C.AccentSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    "Kurse kommen bei Futures 10 min, beim DAX 15 min verzögert. Die Einstiege werden trotzdem exakt nach Regel berechnet, nur der Push kommt entsprechend später.",
                    color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { SectionTitle("Trades") }
        if (shown.isEmpty()) {
            item { Text("Noch keine Trades für diese Auswahl.", color = C.Muted, fontSize = 14.sp) }
        }
        items(shown.take(150), key = { it.id }) { sig -> pos[sig.id]?.let { SignalCard(sig, it) } }
        item {
            OutlinedButton(
                onClick = { Export.share(ctx, state) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Alle Trades als CSV teilen (${state.signals.size})", color = C.AccentSoft) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("So funktionieren die Strategien")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showInfo = !showInfo }) {
                    Text(if (showInfo) "ausblenden" else "anzeigen", color = C.AccentSoft)
                }
            }
        }
        if (showInfo) {
            items(Strategies.all) { info ->
                CardBox {
                    Text(info.name, color = C.Text, fontWeight = FontWeight.Bold)
                    Text(info.desc, color = C.Text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

private class StratRow(
    val code: String, val name: String, val closed: Int, val open: Int,
    val hit: String, val pnl: Double, val avgR: Double, val kos: Int,
)

@Composable
private fun ComparisonCard(signals: List<Signal>, pos: PosMap, onPick: (String) -> Unit) {
    val rows = remember(signals, pos) {
        Strategies.all.map { info ->
            val mine = signals.filter { it.category == info.code }
            val closed = mine.filter { !it.isOpen }
            val ps = closed.mapNotNull { pos[it.id] }
            StratRow(
                info.code, Strategies.short(info.code), closed.size, mine.count { it.isOpen },
                hitRate(closed, pos), ps.sumOf { it.pnl },
                if (ps.isEmpty()) 0.0 else ps.sumOf { it.r } / ps.size, ps.count { it.ko },
            )
        }.sortedWith(compareByDescending<StratRow> { it.closed > 0 }.thenByDescending { it.pnl })
    }

    CardBox {
        Text("Vergleich aller Strategien", color = C.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row {
            Text("Strategie", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(2.3f))
            Text("Trades", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
            Text("Treffer", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
            Text("Ø R", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
            Text("Summe", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
        }
        rows.forEach { r ->
            val pnlColor = when {
                r.closed == 0 -> C.Muted
                r.pnl >= 0 -> C.Green
                else -> C.Red
            }
            Row(
                Modifier.fillMaxWidth().clickable { onPick(r.code) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.name, color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(2.3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (r.open > 0) "${r.closed}+${r.open}" else "${r.closed}", color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
                Text(r.hit, color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
                Text(if (r.closed == 0) "–" else Fmt.r(r.avgR), color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
                Text(
                    if (r.closed == 0) "–" else Fmt.eurPlain(r.pnl) + if (r.kos > 0) " ·${r.kos}KO" else "",
                    color = pnlColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, modifier = Modifier.weight(1.2f),
                )
            }
        }
        Text(
            "Trades: abgeschlossen + offen. Ø R = Ergebnis in Vielfachen des Stop-Risikos, unabhängig vom Hebel. Tippen filtert die Liste. Aussagekräftig erst ab ca. 30 Trades je Strategie.",
            color = C.Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---------------------------------------------------------------- Termine

private val earnDay = DateTimeFormatter.ofPattern("EEE, dd.MM.", Locale.GERMANY)

@Composable
fun CalendarScreen(state: AppState) {
    val now = nowSec()
    val active = Assets.all.filter { it.id !in state.settings.disabled }
    val currencies = active.flatMap { it.currencies }.toSet()
    val events = state.calendar.filter { (it.impact == "High" || it.impact == "Medium") && it.ts > now - 12 * 3600 }
    val grouped = events.groupBy { Fmt.day(it.ts) }

    Screen {
        item { Title("Termine", "Wirtschaftskalender und Earnings") { RefreshButton(state) } }
        item { SectionTitle("Earnings deiner Aktien") }
        if (state.earnings.isEmpty()) {
            item {
                Text(
                    if (state.settings.finnhubKey.isBlank()) "Finnhub-Key fehlt (Setup)." else "Keine Earnings in den nächsten 14 Tagen.",
                    color = C.Muted, fontSize = 14.sp,
                )
            }
        }
        items(state.earnings) { EarningRow(it) }
        item { SectionTitle("Wirtschaftskalender (mittel & hoch)") }
        if (events.isEmpty()) item { Text("Noch keine Termine geladen.", color = C.Muted, fontSize = 14.sp) }
        grouped.forEach { (day, evs) ->
            item { Text(day, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
            items(evs) { EventRow(it, it.country in currencies, now) }
        }
    }
}

@Composable
private fun EarningRow(e: Earning) {
    val asset = Assets.all.firstOrNull { it.finnhub == e.symbol }
    val day = try {
        LocalDate.parse(e.date).format(earnDay)
    } catch (_: Exception) {
        e.date
    }
    val hour = when (e.hour) {
        "bmo" -> "vor Börsenstart"
        "amc" -> "nach Börsenschluss"
        else -> "Zeit offen"
    }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(asset?.name ?: e.symbol, color = C.Text, fontWeight = FontWeight.Bold)
                Text("$day · $hour", color = C.Muted, fontSize = 12.sp)
            }
            if (e.epsEstimate.isNotBlank()) Text("EPS-Prognose ${e.epsEstimate}", color = C.AccentSoft, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EventRow(e: CalEvent, relevant: Boolean, now: Long) {
    val past = e.ts < now
    val dot = if (e.impact == "High") C.Red else C.Amber
    val alpha = if (past) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Fmt.time(e.ts), color = C.Text.copy(alpha = alpha), fontSize = 13.sp, modifier = Modifier.width(46.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Chip(e.country, if (relevant) C.AccentSoft else C.Muted)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = C.Text.copy(alpha = alpha), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.forecast.isNotBlank() || e.previous.isNotBlank()) {
                Text(
                    "Prognose ${e.forecast.ifBlank { "–" }} · vorher ${e.previous.ifBlank { "–" }}",
                    color = C.Muted.copy(alpha = alpha), fontSize = 11.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- News

@Composable
fun NewsScreen(state: AppState) {
    val ctx = LocalContext.current
    val now = nowSec()
    val sents: List<Pair<Asset, Sentiment>> = Assets.all
        .filter { it.id !in state.settings.disabled }
        .mapNotNull { a -> state.sentiments[a.id]?.let { a to it } }
        .sortedByDescending { abs(it.second.score) }

    Screen {
        item { Title("News", "KI-Bewertung ${Fmt.ago(state.sentimentTs, now)}") { RefreshButton(state) } }
        item { SectionTitle("KI-Sentiment je Markt") }
        if (sents.isEmpty()) {
            item {
                Text(
                    "Noch keine KI-Bewertung. Finnhub- und Claude-Key unter Setup eintragen.",
                    color = C.Muted, fontSize = 14.sp,
                )
            }
        }
        items(sents) { (a, s) -> SentimentRow(a, s) }
        item { SectionTitle("Schlagzeilen") }
        if (state.news.isEmpty()) item { Text("Noch keine News geladen.", color = C.Muted, fontSize = 14.sp) }
        items(state.news.take(80)) { n ->
            NewsRow(n, now) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)))
                } catch (_: Exception) {
                }
            }
        }
    }
}

@Composable
private fun SentimentRow(a: Asset, s: Sentiment) {
    val color = when {
        s.score > 0.15 -> C.Green
        s.score < -0.15 -> C.Red
        else -> C.Muted
    }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, color = C.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(Fmt.score(s.score), color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val mid = size.width / 2
            drawRect(C.Line, size = size)
            val w = (abs(s.score) * mid).toFloat()
            val left = if (s.score >= 0) mid else mid - w
            drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
            drawRect(C.Text.copy(alpha = 0.5f), topLeft = Offset(mid - 1f, 0f), size = Size(2f, size.height))
        }
        if (s.reason.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(s.reason, color = C.Text.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun NewsRow(n: NewsItem, now: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(n.headline, color = C.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val rel = if (n.related.isNotBlank()) " · ${n.related}" else ""
        Text("${n.source} · ${Fmt.ago(n.ts, now)}$rel", color = C.Muted, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- Setup

private fun trimNum(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else v.toString()

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent,
    unfocusedBorderColor = C.Line,
    focusedTextColor = C.Text,
    unfocusedTextColor = C.Text,
    focusedLabelColor = C.AccentSoft,
    unfocusedLabelColor = C.Muted,
    cursorColor = C.Accent,
)

@Composable
fun SettingsScreen(state: AppState) {
    val ctx = LocalContext.current
    val s = state.settings
    var finn by remember(s.finnhubKey) { mutableStateOf(s.finnhubKey) }
    var claude by remember(s.claudeKey) { mutableStateOf(s.claudeKey) }
    var stake by remember(s.stake) { mutableStateOf(trimNum(s.stake)) }
    var capital by remember(s.startCapital) { mutableStateOf(trimNum(s.startCapital)) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var minScore by remember(s.minScore) { mutableFloatStateOf(s.minScore.toFloat()) }
    var saved by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyTrans = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

    Screen {
        item { Title("Setup") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("API-Keys")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showKeys = !showKeys }) {
                    Text(if (showKeys) "Keys verbergen" else "Keys anzeigen", color = C.AccentSoft)
                }
            }
        }
        item {
            OutlinedTextField(
                value = finn, onValueChange = { finn = it; saved = false; checkResult = "" },
                label = { Text("Finnhub API-Key") }, singleLine = true,
                visualTransformation = keyTrans,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            Text("finnhub.io → Dashboard → Feld „API key“ (nicht „Webhook secret“).", color = C.Muted, fontSize = 12.sp)
        }
        item {
            OutlinedTextField(
                value = claude, onValueChange = { claude = it; saved = false; checkResult = "" },
                label = { Text("Claude API-Key (sk-ant-…)") }, singleLine = true,
                visualTransformation = keyTrans,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            Text("console.anthropic.com → API Keys. Höchstens eine KI-Abfrage pro 30 min.", color = C.Muted, fontSize = 12.sp)
        }
        item {
            OutlinedButton(
                onClick = {
                    checking = true
                    checkResult = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            KeyCheck.finnhub(finn) + "\n" + KeyCheck.claude(claude)
                        }
                        checkResult = r
                        checking = false
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.Accent)
                } else {
                    Text("Keys prüfen", color = C.AccentSoft)
                }
            }
            if (checkResult.isNotBlank()) {
                Text(
                    checkResult,
                    color = if (checkResult.contains("✗")) C.Amber else C.Green,
                    fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { SectionTitle("Paper-Trading") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = stake, onValueChange = { stake = it; saved = false },
                    label = { Text("Einsatz (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), colors = fieldColors(),
                )
                OutlinedTextField(
                    value = capital, onValueChange = { capital = it; saved = false },
                    label = { Text("Startkapital (€)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), colors = fieldColors(),
                )
            }
        }
        item { LeverageBar(s) }
        item {
            Text(
                "Mindest-Sentiment für einen Vorschlag: ${String.format(Locale.GERMANY, "%.1f", minScore)}",
                color = C.Text, fontSize = 14.sp,
            )
            Slider(
                value = minScore,
                onValueChange = { minScore = (it * 10).roundToInt() / 10f; saved = false },
                valueRange = 0.1f..0.8f,
                steps = 6,
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Line),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Push für Daytrades", color = C.Text, fontSize = 15.sp)
                    Text("Paper-Auswertung läuft immer mit", color = C.Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = s.daytradePush,
                    onCheckedChange = { on -> Store.update { st -> st.copy(settings = st.settings.copy(daytradePush = on)) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, uncheckedTrackColor = C.Card2),
                )
            }
        }
        item {
            Button(
                onClick = {
                    var f = cleanKey(finn)
                    var c = cleanKey(claude)
                    // vertauschte Keys automatisch korrigieren
                    if (f.startsWith("sk-ant") && !c.startsWith("sk-ant")) {
                        val t = f; f = c; c = t
                        checkResult = "Keys waren vertauscht – automatisch korrigiert."
                    }
                    finn = f
                    claude = c
                    Store.update {
                        val keyChanged = f != it.settings.finnhubKey
                        it.copy(
                            settings = it.settings.copy(
                                finnhubKey = f,
                                claudeKey = c,
                                stake = stake.replace(',', '.').toDoubleOrNull() ?: it.settings.stake,
                                startCapital = capital.replace(',', '.').toDoubleOrNull() ?: it.settings.startCapital,
                                minScore = minScore.toDouble(),
                            ),
                            sentimentTs = 0,
                            calendarTs = if (keyChanged) 0 else it.calendarTs,
                            lastError = "",
                        )
                    }
                    saved = true
                    RefreshWorker.runNow(ctx)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent),
            ) {
                Text(if (saved) "Gespeichert ✓" else "Speichern & aktualisieren", fontWeight = FontWeight.SemiBold)
            }
        }
        item { SectionTitle("Märkte") }
        AssetType.entries.forEach { t ->
            item { Text(t.label, color = C.AccentSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            items(Assets.all.filter { it.type == t }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = a.id !in s.disabled,
                        onCheckedChange = { on ->
                            Store.update { st ->
                                val d = st.settings.disabled.toMutableSet()
                                if (on) d.remove(a.id) else d.add(a.id)
                                st.copy(settings = st.settings.copy(disabled = d))
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, uncheckedTrackColor = C.Card2),
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Protokoll")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "ausblenden" else "anzeigen", color = C.AccentSoft)
                }
            }
            Text(
                if (Alarms.exactAllowed(ctx)) "Exakte Wecker aktiv: Die App prüft pünktlich zu den Strategie-Zeiten."
                else "Exakte Wecker sind nicht erlaubt – Prüfungen können sich verzögern. Die Ergebnisse stimmen trotzdem, nur der Push kommt später.",
                color = C.Muted, fontSize = 12.sp,
            )
        }
        if (showLog) {
            item {
                CardBox {
                    if (state.log.isEmpty()) Text("Noch keine Läufe protokolliert.", color = C.Muted, fontSize = 13.sp)
                    state.log.take(80).forEach {
                        Text(
                            it, fontSize = 12.sp, lineHeight = 16.sp,
                            color = when {
                                it.trimStart().startsWith("✓") -> C.Green
                                it.trimStart().startsWith("!") -> C.Amber
                                it.startsWith(" ") -> C.Text.copy(alpha = 0.8f)
                                else -> C.Muted
                            },
                        )
                    }
                }
            }
        }
        item { SectionTitle("So wird gerechnet") }
        item {
            CardBox {
                Text(
                    "Jeder Trade wird wie ein KO-Schein gerechnet: Einsatz × Hebel × Kursbewegung, minus Spread und 2 € Gebühren. Mehr als der Einsatz kann nicht verloren gehen.\n\n" +
                        "KO: Läuft der Kurs um 1/Hebel gegen die Position (bei 300× sind das 0,33 %), ist der Einsatz weg – auch wenn der Stop weiter entfernt liegt.\n\n" +
                        "Spread ist eine Annahme: 0,01 % bei Forex und großen Indizes, 0,02 % Gold, 0,03 % Asien-Indizes, 0,04 % Öl, 0,05 % Aktien und Silber. Er wächst mit dem Hebel: Bei 300× kosten 0,01 % schon 3 % des Einsatzes.\n\n" +
                        "Das Hebel-Profil lässt sich jederzeit umschalten, alle Trades werden dann neu berechnet.",
                    color = C.Text, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
        item { SectionTitle("So entstehen Vorschläge") }
        item {
            CardBox {
                Text(
                    "1. Referenz: Asia-Range (01–08 Uhr) bzw. Vortages-Range bei Aktien und DAX.\n" +
                        "2. Sweep: Eine 15-min-Kerze sticht über/unter die Range und schließt wieder innerhalb.\n" +
                        "3. Vorschlag nur, wenn das KI-Sentiment aus den News in dieselbe Richtung zeigt und kein wichtiger Termin in den nächsten 30 min ansteht.\n" +
                        "4. Stop hinter dem Sweep-Extrem, Ziel 2R. Jeder Vorschlag wird automatisch als Paper-Trade ausgewertet und nach spätestens 24 h geschlossen.\n" +
                        "5. „Nur Sweep“ protokolliert alle Sweeps ohne News-Bestätigung – so siehst du, ob die News-Bestätigung wirklich etwas bringt.",
                    color = C.Text, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
    }
}
