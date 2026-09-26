package com.offspringslabs.disha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import com.offspringslabs.disha.UiCard
import kotlin.math.roundToInt

/** Renders whichever precompiled component the agent's tool produced. */
@Composable
fun AgentCard(card: UiCard, onOpenMap: (Double?, Double?, String) -> Unit) {
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
        when (card) {
            is UiCard.Route -> RouteCard(card)
            is UiCard.OpenStatus -> OpenStatusCard(card)
            is UiCard.Budget -> BudgetCard(card)
            is UiCard.Plan -> PlanCard(card)
            is UiCard.Emergency -> EmergencyCard(card)
            is UiCard.PlaceInfo -> PlaceCard(card, onOpenMap)
            is UiCard.Inventory -> InventoryCard(card)
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
    Text(c.note, fontSize = 11.sp, color = Muted)
}

@Composable
fun PlanCard(c: UiCard.Plan) = Shell(Forest, "DAY ${c.day} PLAN · now ${c.nowLabel}") {
    c.stops.forEachIndexed { i, s ->
        val isNext = i == c.nextIndex
        val past = c.nextIndex >= 0 && i < c.nextIndex
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (isNext) 14.dp else 10.dp).background(if (isNext) Saffron else if (past) Color(0xFFCFC8B5) else Forest, CircleShape))
                if (i < c.stops.lastIndex) Box(Modifier.width(2.dp).height(18.dp).background(Color(0xFFE6DEC9)))
            }
            Spacer(Modifier.width(10.dp))
            Text(s.time, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (past) Muted else Ink, modifier = Modifier.width(48.dp))
            Column {
                Text(s.stop, fontSize = 13.sp, color = if (past) Muted else Ink, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                if (isNext) Text("NEXT", fontSize = 10.sp, color = Warn, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
    if (c.nextIndex < 0) Text("No more stops today", fontSize = 12.sp, color = Muted)
}

@Composable
fun EmergencyCard(c: UiCard.Emergency) = Shell(Warn, "SAFETY & PRACTICAL") {
    InfoRow("🏥", "Hospital", c.hospital)
    InfoRow("🚔", "Police", c.police)
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
