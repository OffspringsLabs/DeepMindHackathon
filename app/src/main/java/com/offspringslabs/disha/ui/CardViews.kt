package com.offspringslabs.disha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.offspringslabs.disha.UiCard
import com.offspringslabs.disha.MapPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/** Renders whichever precompiled component the agent's tool produced. */
@Composable
fun AgentCard(card: UiCard, onOpenMap: (Double?, Double?, String) -> Unit) {
    var shown by remember(card) { mutableStateOf(false) }
    LaunchedEffect(card) { shown = true }
    AnimatedVisibility(visible = shown, enter = fadeIn(tween(380)) + expandVertically(tween(380)) + slideInVertically(tween(380)) { it / 6 }) {
        when (card) {
            is UiCard.Route -> RouteCard(card)
            is UiCard.OpenStatus -> OpenStatusCard(card)
            is UiCard.Budget -> BudgetCard(card)
            is UiCard.Plan -> PlanCard(card)
            is UiCard.Emergency -> EmergencyCard(card)
            is UiCard.PlaceInfo -> PlaceCard(card, onOpenMap)
            is UiCard.Inventory -> InventoryCard(card)
            is UiCard.PhraseCard -> PhraseView(card)
            is UiCard.FareCard -> FareView(card)
            is UiCard.Checklist -> ChecklistView(card)
            is UiCard.Alert -> AlertView(card)
            is UiCard.FoodList -> FoodListView(card)
            is UiCard.Tradeoff -> TradeoffView(card)
            is UiCard.MapCard -> MapView(card)
            is UiCard.Decision -> DecisionView(card)
            else -> Unit // Ask* cards render through AskCard
        }
    }
}

@Composable
private fun Shell(accent: Color, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFE6DEC9), RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.12f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape)); Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.sp)
        }
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun Big(value: String, unit: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 36.sp)
        Spacer(Modifier.width(4.dp))
        Text(unit, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
    }
}

private fun fmtKm(d: Double) = if (d >= 10) d.roundToInt().toString() else "%.1f".format(d).removeSuffix(".0")
private fun fmtMin(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min"

@Composable
fun RouteCard(c: UiCard.Route) = Shell(Color(0xFF3B6FD9), "ROUTE") {
    Text("${c.from}  →  ${c.to}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Big(fmtKm(c.km), "km", Modifier.weight(1f))
        Big(fmtMin(c.minutes).substringBefore(" "), fmtMin(c.minutes).substringAfter(" ", ""), Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Text(c.mode, fontSize = 13.sp, color = Muted)
    if (c.estimated) Text("estimate · not a measured route", fontSize = 11.sp, color = Warn)
}

@Composable
fun OpenStatusCard(c: UiCard.OpenStatus) {
    val col = if (c.isOpen) Ok else Warn
    Shell(col, if (c.isOpen) "OPEN NOW" else "CLOSED") {
        Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(c.type, fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(col, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (c.isOpen) "OPEN" else "CLOSED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(c.untilLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("hours ${c.hours} · checked at ${c.atLabel}", fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable
fun BudgetCard(c: UiCard.Budget) = Shell(Saffron.copy(red = 0.75f), "BUDGET") {
    Text(c.title, fontSize = 13.sp, color = Muted)
    Spacer(Modifier.height(6.dp))
    c.lines.forEach { (label, amt) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp, color = Ink)
            Text("₹${"%,d".format(amt)}", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text("Total", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("₹${"%,d".format(c.total)}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Forest)
    }
    if (c.limit > 0) {
        Spacer(Modifier.height(6.dp))
        val frac = (c.total.toFloat() / c.limit).coerceIn(0f, 1.5f)
        Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFFEDE6D3), RoundedCornerShape(999.dp))) {
            Box(Modifier.fillMaxWidth(frac.coerceAtMost(1f)).height(10.dp).background(if (c.total > c.limit) Warn else Ok, RoundedCornerShape(999.dp)))
        }
        Text("of ₹${"%,d".format(c.limit)} budget" + (if (c.perPerson > 0) " · ₹${"%,d".format(c.perPerson)} per person" else ""), fontSize = 11.sp, color = Muted)
    }
    Text(c.note, fontSize = 12.sp, color = if (c.note.startsWith("OVER")) Warn else Muted, fontWeight = FontWeight.Medium)
}

@Composable
fun PlanCard(c: UiCard.Plan) = Shell(Forest, "DAY ${c.day}${if (c.theme.isNotBlank()) " · ${c.theme.uppercase()}" else ""} · now ${c.nowLabel}") {
    c.stops.forEachIndexed { i, s ->
        val leg = c.legs.getOrNull(i).orEmpty()
        if (leg.isNotBlank() && i > 0) Text("      ↓ $leg", fontSize = 11.sp, color = Muted)
        val isNext = i == c.nextIndex
        val past = c.nextIndex >= 0 && i < c.nextIndex
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (isNext) 14.dp else 10.dp).background(if (isNext) Saffron else if (past) Color(0xFFCFC8B5) else Forest, CircleShape))
                if (i < c.stops.lastIndex) Box(Modifier.width(2.dp).height(18.dp).background(Color(0xFFE6DEC9)))
            }
            Spacer(Modifier.width(10.dp))
            Text(s.time, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (past) Muted else Ink, modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(s.stop, fontSize = 13.sp, color = if (past) Muted else Ink, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                if (isNext) Text("NEXT", fontSize = 10.sp, color = Warn, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            c.costs.getOrNull(i)?.let { cost -> if (cost > 0) Text("₹$cost", fontSize = 12.sp, color = Muted) }
        }
    }
    if (c.nextIndex < 0) Text("No more stops today", fontSize = 12.sp, color = Muted)
}

@Composable
fun EmergencyCard(c: UiCard.Emergency) = Shell(Warn, "SAFETY & PRACTICAL") {
    val ctx = LocalContext.current
    InfoRow("🏥", "Hospital", c.hospital)
    if (c.pharmacy.isNotBlank()) InfoRow("💊", "24h pharmacy", c.pharmacy)
    InfoRow("🚔", "Police", c.police)
    if (c.numbers.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.numbers.take(3).forEach { n ->
                Box(Modifier.weight(1f).background(Warn, RoundedCornerShape(10.dp)).clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${n.phone.filter { it.isDigit() || it == '+' }}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 8.dp, horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("📞 ${n.phone}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(n.label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    c.phrase?.let { ph ->
        Box(Modifier.fillMaxWidth().background(Color(0xFFFBEFE6), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Column { Text(ph.native, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink); Text("${ph.roman} · ${ph.english}", fontSize = 12.sp, color = Muted) }
        }
    }
    InfoRow("🏧", "Cash", c.atm)
    InfoRow("📵", "No signal", c.noSignal)
    if (c.arrival.isNotEmpty()) { HorizontalDivider(Modifier.padding(vertical = 6.dp)); c.arrival.forEach { (k, v) -> InfoRow("🚉", k.replaceFirstChar { it.uppercase() }, v) } }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 16.sp); Spacer(Modifier.width(8.dp))
        Column { Text(label, fontSize = 11.sp, color = Muted); Text(value, fontSize = 13.sp, color = Ink) }
    }
}

@Composable
fun PlaceCard(c: UiCard.PlaceInfo, onOpenMap: (Double?, Double?, String) -> Unit) = Shell(Forest, c.type.uppercase()) {
    Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        c.hours?.let { Pill("🕒 $it") }
        Pill("📍 ${c.distance}")
        c.price?.let { Pill(it) }
    }
    if (c.tip.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text("💡 ${c.tip}", fontSize = 13.sp, color = Ink) }
    if (c.lat != null && c.lon != null) {
        Spacer(Modifier.height(8.dp))
        Text("Open in Maps →", fontSize = 13.sp, color = Color(0xFF3B6FD9), fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onOpenMap(c.lat, c.lon, c.name) })
    }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.background(Color(0xFFEDE6D3), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, fontSize = 12.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InventoryCard(c: UiCard.Inventory) = Shell(Forest, "IN YOUR PACK") {
    Section("Places", c.places); Section("Stays", c.hotels); Section("Food", c.food)
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Text(title, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    rows.forEach { (a, b) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(a, Modifier.weight(1f), fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(b, fontSize = 12.sp, color = Muted)
        }
    }
}


@Composable
fun PhraseView(c: UiCard.PhraseCard) = Shell(Color(0xFF7A3E9D), "SAY IT · ${c.language.uppercase()}") {
    Text(c.native, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 38.sp)
    Spacer(Modifier.height(4.dp))
    Text(c.roman, fontSize = 16.sp, color = Color(0xFF7A3E9D), fontWeight = FontWeight.Medium)
    Text(c.english, fontSize = 13.sp, color = Muted)
}

@Composable
fun FareView(c: UiCard.FareCard) = Shell(Color(0xFF3B6FD9), "FAIR FARE · ${c.mode.uppercase()}") {
    Text("${c.from}  →  ${c.to}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text("Fair", fontSize = 11.sp, color = Muted); Text("₹${c.fairInr}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ok) }
        Column(Modifier.weight(1f)) { Text("Tourists get quoted", fontSize = 11.sp, color = Muted); Text("₹${c.touristQuoteInr}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Warn) }
    }
    if (c.rule.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Rule: ${c.rule}", fontSize = 12.sp, color = Ink) }
}

@Composable
fun ChecklistView(c: UiCard.Checklist) = Shell(if (c.accent == "warn") Warn else Forest, c.title.uppercase()) {
    c.items.forEach { (label, detail) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Text(if (c.accent == "warn") "▲" else "✓", fontSize = 13.sp, color = if (c.accent == "warn") Warn else Ok, modifier = Modifier.width(18.dp))
            Column { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink); if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = Muted) }
        }
    }
}

@Composable
fun AlertView(c: UiCard.Alert) = Shell(Warn, "⚠ ${c.title.uppercase()}") {
    c.items.forEach { (pattern, counter) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFFFBEFE6), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(pattern, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("→ $counter", fontSize = 13.sp, color = Forest)
        }
    }
}

@Composable
fun FoodListView(c: UiCard.FoodList) = Shell(Saffron.copy(red = 0.75f), c.title.uppercase()) {
    c.items.forEach { f ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Text(if (f.veg == true) "🟢" else if (f.veg == false) "🔴" else "⚪", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(f.item + (if (f.localName.isNotBlank()) "  ·  ${f.localName}" else ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(listOf(f.where, if (f.spice > 0) "spice " + "🌶".repeat(f.spice) else "", f.mealWindow, f.allergens.takeIf { it.isNotBlank() }?.let { "allergens: $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Muted)
            }
            Text("₹${f.price}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
        }
    }
}

@Composable
fun TradeoffView(c: UiCard.Tradeoff) = Shell(Forest, "WHAT IF") {
    Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text("Saves", fontSize = 11.sp, color = Muted); Text("₹${"%,d".format(c.savesInr)}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ok) }
        Column(Modifier.weight(1f)) { Text("Frees", fontSize = 11.sp, color = Muted); Text(if (c.freesMin >= 60) "${c.freesMin / 60}h ${c.freesMin % 60}m" else "${c.freesMin} min", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Forest) }
    }
    Text(c.detail, fontSize = 12.sp, color = Muted)
    if (c.alternatives.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Other ways to fit the budget", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold)
        c.alternatives.forEach { a -> Text("• ${a.title} — saves ₹${a.savesInr}. ${a.tradeoff}", fontSize = 12.sp, color = Ink) }
    }
}

@Composable
fun DecisionView(c: UiCard.Decision) = Shell(Saffron.copy(red = 0.75f), "TRAVELFREAK'S CALL") {
    Text(c.recommendation, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 24.sp)
    Spacer(Modifier.height(6.dp))
    c.why.forEach { w -> Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) { Text("✓", color = Ok, fontSize = 13.sp, modifier = Modifier.width(18.dp)); Text(w, fontSize = 13.sp, color = Ink) } }
    if (c.alternative.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text("Otherwise: ${c.alternative}", fontSize = 12.sp, color = Muted) }
    if (c.delta.isNotBlank()) Text(c.delta, fontSize = 12.sp, color = Forest, fontWeight = FontWeight.Medium)
}

/** Offline schematic map drawn from stored coordinates: places, stay, day routes, base star. */
@Composable
fun MapView(c: UiCard.MapCard) = Shell(Color(0xFF3B6FD9), "OFFLINE MAP · ${c.base.uppercase()}") {
    val ctx = LocalContext.current
    val pts = c.points
    if (pts.isEmpty()) { Text("No coordinates in this pack yet.", fontSize = 13.sp, color = Muted); return@Shell }
    val lats = pts.map { it.lat } + c.baseLat; val lons = pts.map { it.lon } + c.baseLon
    val minLat = lats.min(); val maxLat = lats.max(); val minLon = lons.min(); val maxLon = lons.max()
    val kx = cos(Math.toRadians((minLat + maxLat) / 2)).toFloat()
    val dayColors = listOf(Color(0xFF3B6FD9), Color(0xFFB8541F), Color(0xFF1B8A5A), Color(0xFF7A3E9D), Color(0xFFD9A43B))
    Canvas(Modifier.fillMaxWidth().height(230.dp).background(Color(0xFFF3EFE4), RoundedCornerShape(12.dp))) {
        val pad = 28f
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        val spanLon = max((maxLon - minLon) * kx, 0.005); val spanLat = max(maxLat - minLat, 0.005)
        val scale = minOf(w / spanLon.toFloat(), h / spanLat.toFloat())
        fun px(lat: Double, lon: Double) = Offset(pad + (((lon - minLon) * kx).toFloat() * scale) + (w - spanLon.toFloat() * scale) / 2, pad + ((maxLat - lat).toFloat() * scale) + (h - spanLat.toFloat() * scale) / 2)
        // day routes
        c.routeDays.forEachIndexed { di, idxs ->
            val col = dayColors[di % dayColors.size]
            idxs.zipWithNext().forEach { (a, b) -> drawLine(col, px(pts[a].lat, pts[a].lon), px(pts[b].lat, pts[b].lon), strokeWidth = 4f, cap = StrokeCap.Round) }
        }
        // points
        pts.forEach { p -> val o = px(p.lat, p.lon); drawCircle(if (p.kind == "stay") Saffron else Forest, if (p.kind == "stay") 9f else 7f, o); drawCircle(Color.White, 3f, o) }
        // base star
        val b = px(c.baseLat, c.baseLon); drawCircle(Warn, 11f, b); drawCircle(Color.White, 5f, b)
        // scale ring ~5 km
        val kmPx = (scale / 111f) * 5f; if (kmPx > 20 && kmPx < w) drawCircle(Muted, kmPx, b, style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
    }
    Spacer(Modifier.height(6.dp))
    Text(pts.take(8).mapIndexed { i, p -> "${if (p.kind == "stay") "🏨" else "•"} ${p.name}" }.joinToString("   "), fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("● base   ─ day routes   ◌ 5 km ring", fontSize = 10.sp, color = Muted)
    if (c.deadZones.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text("📵 No signal: ${c.deadZones.joinToString("; ")}", fontSize = 12.sp, color = Warn) }
    Spacer(Modifier.height(8.dp))
    Text("Open area in Google Maps → tap 'Download offline map'", fontSize = 13.sp, color = Color(0xFF3B6FD9), fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.mapsAreaUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
}
