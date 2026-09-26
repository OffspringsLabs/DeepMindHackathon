package com.offspringslabs.disha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.offspringslabs.disha.DishaViewModel
import com.offspringslabs.disha.LocalBrain

@Composable
fun DishaScreen(vm: DishaViewModel, onMic: () -> Unit) {
    val ctx = LocalContext.current
    val region by vm.region.collectAsState()
    val pack by vm.pack.collectAsState()
    val online by vm.online.collectAsState()
    val forceOffline by vm.forceOffline.collectAsState()
    val brainState by vm.brainState.collectAsState()
    val brainMode by vm.brainMode.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val answer by vm.answer.collectAsState()
    val steps by vm.steps.collectAsState()
    val cards by vm.cards.collectAsState()
    val notes by vm.notes.collectAsState()
    val route by vm.route.collectAsState()
    val latency by vm.latencyMs.collectAsState()
    val busy by vm.busy.collectAsState()
    val listening by vm.listening.collectAsState()
    val recording by vm.recording.collectAsState()
    val status by vm.status.collectAsState()
    val showPack by vm.showPack.collectAsState()
    val refreshed by vm.packRefreshed.collectAsState()
    val replyLang by vm.replyLang.collectAsState()
    val ttsVoice by vm.ttsVoice.collectAsState()
    val speaking by vm.speaking.collectAsState()

    val cloud = online && !forceOffline && vm.hasKey
    var draft by remember { mutableStateOf("") }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Disha", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Forest)
                    Text("the guide that never leaves you lost", color = Muted, fontSize = 13.sp)
                }
                Badge(
                    text = if (online) "ONLINE" else "OFFLINE",
                    color = if (online) Ok else Warn,
                )
            }
            Spacer(Modifier.height(12.dp))

            // Regions
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.regions) { r ->
                    FilterChip(selected = r.id == region.id, onClick = { vm.selectRegion(r) }, label = { Text(r.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            // Reply language + Gemini voice
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Reply", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.width(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    items(com.offspringslabs.disha.ReplyLang.entries) { l ->
                        FilterChip(selected = l == replyLang, onClick = { vm.setReplyLang(l) }, label = { Text(l.label, fontSize = 12.sp) })
                    }
                }
                if (cloud) {
                    Spacer(Modifier.width(6.dp))
                    Text("🔊 $ttsVoice", fontSize = 12.sp, color = Color(0xFF3B6FD9), fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.nextVoice() })
                }
            }
            Spacer(Modifier.height(6.dp))

            // Engine + pack status
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(
                            when (brainState) {
                                is LocalBrain.State.Ready -> Ok
                                is LocalBrain.State.Loading -> Saffron
                                else -> Warn
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (val s = brainState) {
                                is LocalBrain.State.Ready -> "Gemma 3n E2B ready on ${s.backend} (${s.loadMs / 1000}s) · ${modeLabel(brainMode)}"
                                is LocalBrain.State.Loading -> "Loading Gemma 3n on-device…"
                                is LocalBrain.State.Failed -> "On-device model: ${s.message}"
                                else -> "On-device model idle"
                            },
                            fontSize = 12.sp, color = Forest, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (brainState is LocalBrain.State.Loading) {
                        Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Trip pack: ${pack.places.size} places · ${pack.hotels.size} stays · ${pack.routes.size} routes · ${pack.days.size} days" +
                            (if (refreshed) " · refreshed via Gemini" else " · bundled"),
                        fontSize = 12.sp, color = Forest,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::togglePack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(if (showPack) "Hide pack" else "View pack", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Answer offline", fontSize = 12.sp, color = Forest)
                        Switch(checked = forceOffline, onCheckedChange = { vm.toggleForceOffline() }, modifier = Modifier.padding(start = 4.dp))
                    }
                    if (showPack) {
                        HorizontalDivider()
                        Text(pack.compactText(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Ink, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Conversation
            if (transcript.isNotBlank()) {
                Text("You", fontSize = 11.sp, color = Muted)
                Text(transcript, fontSize = 16.sp, color = Ink)
                Spacer(Modifier.height(8.dp))
            }
            if (route.isNotBlank() || busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Disha", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.width(8.dp))
                    if (route.isNotBlank()) Badge(route, if (route.contains("cloud")) Color(0xFF3B6FD9) else Forest)
                    if (!busy && latency > 0) { Spacer(Modifier.width(8.dp)); Text("${latency / 1000.0}s", fontSize = 11.sp, color = Muted) }
                }
                Spacer(Modifier.height(4.dp))
            }
            // Precompiled components chosen by the agent's tool calls
            cards.forEach { c -> AgentCard(c) { lat, lon, label -> vm.openMaps(ctx, lat, lon, label) } }
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    steps.joinToString("   ") { "🔧 ${it.label}" },
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (busy && answer.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (cloud) "Asking Gemini Flash…" else "Gemma is reading your pack…", color = Muted, fontSize = 13.sp)
                }
            }
            if (answer.isNotBlank()) {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Text(answer, Modifier.padding(14.dp), fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                }
            }
            if (speaking.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF3B6FD9))
                    Spacer(Modifier.width(6.dp))
                    Text(if (speaking == "gemini") "Gemini voice · $ttsVoice · ${replyLang.label}" else "Device voice", fontSize = 11.sp, color = Muted)
                }
            }
            notes.forEach { Text("· $it", fontSize = 11.sp, color = Muted) }
            if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, fontSize = 12.sp, color = Warn) }

            Spacer(Modifier.height(16.dp))

            // Mic
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(84.dp).background(
                            when {
                                recording || listening -> Warn
                                busy -> Muted
                                else -> if (cloud) Color(0xFF3B6FD9) else Forest
                            }, CircleShape,
                        ).clickable(enabled = !busy || recording || listening) { onMic() },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (recording || listening) "■" else "🎤", fontSize = 34.sp, color = Color.White) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            recording -> "Recording for Gemini Flash Audio · tap to send"
                            listening -> "Listening on-device · tap to stop"
                            cloud -> "Tap to ask · Gemini Flash Audio · replies in ${replyLang.label}"
                            else -> "Tap to ask · on-device speech → Gemma"
                        }, fontSize = 12.sp, color = Muted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // Text fallback
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("or type: how far is the next stop, is it open at 6pm…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.ask(draft); draft = "" }),
                trailingIcon = { TextButton(onClick = { vm.ask(draft); draft = "" }, enabled = draft.isNotBlank() && !busy) { Text("Ask") } },
            )
            Spacer(Modifier.height(10.dp))

            // Quick asks
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickAsks(pack.places.firstOrNull()?.name ?: "the main temple")) { q ->
                    OutlinedButton(onClick = { vm.ask(q) }, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(q, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refreshPack, enabled = !busy && online, modifier = Modifier.weight(1f)) { Text("Refresh pack online", fontSize = 13.sp) }
                OutlinedButton(onClick = { vm.openMaps(ctx) }, modifier = Modifier.weight(1f)) { Text("Open in Maps", fontSize = 13.sp) }
            }
            if (!vm.hasKey) Text("No Gemini key: online features off. Add GEMINI_API_KEY to local.properties.", fontSize = 11.sp, color = Warn, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun quickAsks(place: String) = listOf(
    "How far is $place from base and how long?",
    "Is $place open right now?",
    "What's next on my day 1 plan?",
    "Budget for 2 people, 2 nights at the cheapest hotel?",
    "Nearest hospital and where is there no signal?",
)

private fun modeLabel(m: LocalBrain.Mode) = when (m) {
    LocalBrain.Mode.NATIVE_TOOLS -> "native tools"
    LocalBrain.Mode.MANUAL_TOOLS -> "tool protocol"
    LocalBrain.Mode.CONTEXT -> "context mode"
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.background(color, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Dot(color: Color) { Box(Modifier.size(10.dp).background(color, CircleShape)) }
