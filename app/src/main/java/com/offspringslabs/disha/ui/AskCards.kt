package com.offspringslabs.disha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offspringslabs.disha.UiCard
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Interactive components the intake agent summons. Each answers with one JSON object. */
@Composable
fun AskCard(card: UiCard, say: String, onAnswer: (JSONObject) -> Unit) {
    var shown by remember(card) { mutableStateOf(false) }
    LaunchedEffect(card) { shown = true }
    AnimatedVisibility(visible = shown, enter = fadeIn(tween(380)) + expandVertically(tween(380)) + slideInVertically(tween(380)) { it / 6 }) { AskShell(say) {
        when (card) {
            is UiCard.AskDestination -> AskDestinationBody(card, onAnswer)
            is UiCard.AskDays -> AskDaysBody(card, onAnswer)
            is UiCard.AskBudget -> AskBudgetBody(card, onAnswer)
            is UiCard.AskTravellers -> AskTravellersBody(card, onAnswer)
            is UiCard.Confirm -> ConfirmBody(card, onAnswer)
            is UiCard.Progress -> ProgressBody(card)
            else -> Unit
        }
    } }
}

@Composable
private fun AskShell(say: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color.White, RoundedCornerShape(18.dp)).border(1.5.dp, Saffron, RoundedCornerShape(18.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Saffron, CircleShape)); Spacer(Modifier.width(8.dp))
            Text("TRAVELFREAK ASKS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A6B12), letterSpacing = 1.sp)
        }
        if (say.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(say, fontSize = 15.sp, color = Ink, lineHeight = 21.sp) }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun AskDestinationBody(c: UiCard.AskDestination, onAnswer: (JSONObject) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text(c.prompt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(c.suggestions) { s -> FilterChip(selected = false, onClick = { onAnswer(JSONObject().put("destination", s)) }, label = { Text(s) }) }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("or type any place in India") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAnswer(JSONObject().put("destination", text.trim())) }),
        trailingIcon = { TextButton(onClick = { onAnswer(JSONObject().put("destination", text.trim())) }, enabled = text.isNotBlank()) { Text("Go") } },
    )
}

@Composable
private fun AskDaysBody(c: UiCard.AskDays, onAnswer: (JSONObject) -> Unit) {
    var days by remember { mutableIntStateOf(c.suggested.coerceIn(c.min, c.max)) }
    Text(c.prompt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    if (c.reason.isNotBlank()) Text(c.reason, fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        OutlinedButton(onClick = { if (days > c.min) days-- }) { Text("−", fontSize = 20.sp) }
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$days", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Forest)
            Text(if (days == 1) "day" else "days", fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.width(18.dp))
        OutlinedButton(onClick = { if (days < c.max) days++ }) { Text("+", fontSize = 20.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onAnswer(JSONObject().put("days", days)) }, modifier = Modifier.fillMaxWidth()) { Text("That's right") }
}

@Composable
private fun AskBudgetBody(c: UiCard.AskBudget, onAnswer: (JSONObject) -> Unit) {
    val lo = c.minInr.toFloat(); val hi = maxOf(c.maxInr, c.minInr + 1000).toFloat()
    var v by remember { mutableFloatStateOf(c.suggestedInr.toFloat().coerceIn(lo, hi)) }
    val inr = (v / 500f).roundToInt() * 500
    Text(c.prompt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    if (c.perDayHint.isNotBlank()) Text(c.perDayHint, fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(6.dp))
    Text("₹${"%,d".format(inr)}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Forest)
    Slider(value = v, onValueChange = { v = it }, valueRange = lo..hi)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Tight" to 0.25f, "Comfortable" to 0.5f, "Premium" to 0.85f).forEach { (l, f) -> FilterChip(selected = false, onClick = { v = lo + (hi - lo) * f }, label = { Text(l, fontSize = 12.sp) }) }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onAnswer(JSONObject().put("budgetInr", inr)) }, modifier = Modifier.fillMaxWidth()) { Text("Set budget ₹${"%,d".format(inr)}") }
}

@Composable
private fun AskTravellersBody(c: UiCard.AskTravellers, onAnswer: (JSONObject) -> Unit) {
    var people by remember { mutableIntStateOf(2) }
    var city by remember { mutableStateOf("Hyderabad") }
    var pace by remember { mutableStateOf("balanced") }
    var diet by remember { mutableStateOf("any") }
    val interests = remember { mutableStateOf(setOf("temples", "food")) }
    Text(c.prompt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("People", fontSize = 13.sp, color = Muted, modifier = Modifier.width(70.dp))
        OutlinedButton(onClick = { if (people > 1) people-- }) { Text("−") }
        Text("  $people  ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        OutlinedButton(onClick = { if (people < 6) people++ }) { Text("+") }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("Starting from") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Text("Pace", fontSize = 12.sp, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("relaxed", "balanced", "packed").forEach { p -> FilterChip(selected = pace == p, onClick = { pace = p }, label = { Text(p, fontSize = 12.sp) }) } }
    Text("Interests", fontSize = 12.sp, color = Muted)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf("temples", "nature", "food", "history", "trekking", "beaches", "shopping", "photography")) { i ->
            val on = i in interests.value
            FilterChip(selected = on, onClick = { interests.value = if (on) interests.value - i else interests.value + i }, label = { Text(i, fontSize = 12.sp) })
        }
    }
    Text("Diet", fontSize = 12.sp, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("any", "veg", "jain", "halal", "vegan").forEach { d -> FilterChip(selected = diet == d, onClick = { diet = d }, label = { Text(d, fontSize = 12.sp) }) } }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onAnswer(JSONObject().put("people", people).put("startCity", city.trim().ifBlank { "Hyderabad" }).put("pace", pace).put("interests", JSONArray(interests.value.toList())).put("diet", diet)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Done") }
}

@Composable
private fun ConfirmBody(c: UiCard.Confirm, onAnswer: (JSONObject) -> Unit) {
    Text(c.prompt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().background(Color(0xFFEDE6D3), RoundedCornerShape(12.dp)).padding(12.dp)) { Text(c.summary, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium) }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onAnswer(JSONObject().put("edit", "budget")) }, modifier = Modifier.weight(1f)) { Text("Change budget") }
        OutlinedButton(onClick = { onAnswer(JSONObject().put("edit", "days")) }, modifier = Modifier.weight(1f)) { Text("Change days") }
    }
    Spacer(Modifier.height(6.dp))
    Button(onClick = { onAnswer(JSONObject().put("confirmed", true)) }, modifier = Modifier.fillMaxWidth()) { Text("Build my trip") }
}

@Composable
private fun ProgressBody(c: UiCard.Progress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Forest); Spacer(Modifier.width(10.dp))
        Text(c.stage, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { (c.done + 0.5f) / c.stages.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    c.stages.forEachIndexed { i, s ->
        Text((if (i < c.done) "✓ " else if (i == c.done) "▸ " else "· ") + s, fontSize = 12.sp, color = if (i <= c.done) Ink else Muted)
    }
}
