package com.offspringslabs.disha

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ToolStep(val label: String, val result: String)

class DishaViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TripPackStore(app)
    private val brain = LocalBrain(app)
    private val gemini = GeminiClient()
    private val connectivity = Connectivity(app)
    private val voice = VoiceIO(app)
    private val recorder = AudioRecorder()

    val regions = store.regions
    private val _region = MutableStateFlow(regions.first())
    val region: StateFlow<Region> = _region
    private val _pack = MutableStateFlow(store.load(regions.first().id))
    val pack: StateFlow<TripPack> = _pack
    val packRefreshed = MutableStateFlow(store.isRefreshed(regions.first().id))

    val online: StateFlow<Boolean> = connectivity.online
    val forceOffline = MutableStateFlow(false)
    val brainState: StateFlow<LocalBrain.State> = brain.state
    val brainMode: StateFlow<LocalBrain.Mode> = brain.mode
    val hasKey: Boolean = gemini.hasKey

    val transcript = MutableStateFlow("")
    val answer = MutableStateFlow("")
    val steps = MutableStateFlow<List<ToolStep>>(emptyList())
    val cards = MutableStateFlow<List<UiCard>>(emptyList())
    val notes = MutableStateFlow<List<String>>(emptyList())
    val route = MutableStateFlow("")
    val latencyMs = MutableStateFlow(0L)
    val busy = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val showPack = MutableStateFlow(false)

    private var recordTimeout: Job? = null

    init {
        viewModelScope.launch { brain.init() }
        voice.onPartial = { transcript.value = it }
        voice.onResult = { t -> transcript.value = t; ask(t) }
        voice.onListening = { listening.value = it }
        voice.onError = { status.value = it }
    }

    fun useCloud(): Boolean = online.value && !forceOffline.value && hasKey

    fun selectRegion(r: Region) {
        _region.value = r
        _pack.value = store.load(r.id)
        packRefreshed.value = store.isRefreshed(r.id)
        transcript.value = ""
        clearAnswer()
    }

    fun toggleForceOffline() { forceOffline.value = !forceOffline.value }
    fun togglePack() { showPack.value = !showPack.value }
    fun setStatus(s: String) { status.value = s }

    private fun clearAnswer() {
        answer.value = ""; steps.value = emptyList(); cards.value = emptyList(); notes.value = emptyList(); route.value = ""; status.value = ""
    }

    /** Tools shared by both brains; every tool call lands a precompiled card on screen. */
    private fun toolsForScreen(): TripTools = TripTools(pack.value).also { t -> t.onCard = { c -> cards.value = cards.value + c } }

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty() || busy.value) return
        voice.stopSpeaking()
        transcript.value = question
        clearAnswer()
        busy.value = true
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                var done = false
                if (useCloud()) {
                    route.value = "Gemini Flash · cloud agent"
                    try {
                        val r = gemini.answerWithTools(question, pack.value, toolsForScreen())
                        steps.value = r.toolCalls.map { ToolStep(it.first, it.second) }
                        answer.value = r.text
                        done = true
                    } catch (e: Exception) {
                        Log.w("Disha", "cloud failed, falling back on-device", e)
                        notes.value = notes.value + "cloud unreachable (${e.message?.take(60)}), answering on-device"
                        cards.value = emptyList()
                    }
                }
                if (!done) askOnDevice(question)
                voice.speak(answer.value)
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                latencyMs.value = System.currentTimeMillis() - t0
                busy.value = false
            }
        }
    }

    private suspend fun askOnDevice(question: String) {
        route.value = "Gemma 3n E2B · on-device agent"
        val sb = StringBuilder()
        brain.ask(pack.value, question).collect { ev ->
            when (ev) {
                is BrainEvent.Text -> { sb.append(ev.delta); answer.value = sb.toString() }
                is BrainEvent.ToolUsed -> steps.value = steps.value + ToolStep(ev.label, ev.result)
                is BrainEvent.Card -> cards.value = cards.value + ev.card
                is BrainEvent.Note -> notes.value = notes.value + ev.text
            }
        }
    }

    /** Mic: online → record for Gemini Flash Audio; offline → on-device recognizer → Gemma. Tap again to stop. */
    fun onMicTap() {
        when {
            listening.value -> voice.stopListening()
            recording.value -> stopRecordingAndAsk()
            busy.value -> Unit
            useCloud() -> {
                voice.stopSpeaking()
                recorder.start()
                recording.value = true
                status.value = "Recording… tap mic again to send"
                recordTimeout = viewModelScope.launch { delay(12_000); if (recording.value) stopRecordingAndAsk() }
            }
            else -> {
                voice.stopSpeaking()
                status.value = if (voice.onDevice) "Listening (on-device)…" else "Listening…"
                transcript.value = ""
                voice.startListening()
            }
        }
    }

    private fun stopRecordingAndAsk() {
        recordTimeout?.cancel()
        val wav = recorder.stop()
        recording.value = false
        if (wav.size < 44 + 16000) { status.value = "Too short, try again"; return }
        clearAnswer()
        transcript.value = "🎙 sending audio to Gemini Flash…"
        busy.value = true
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                route.value = "Gemini Flash Audio · cloud agent"
                val (t, a, calls) = gemini.answerAudioWithTools(wav, pack.value, toolsForScreen())
                transcript.value = t
                steps.value = calls.map { ToolStep(it.first, it.second) }
                answer.value = a
                voice.speak(a)
            } catch (e: Exception) {
                status.value = "Audio failed: ${e.message?.take(120)}. Use the mic again offline or type."
                transcript.value = ""
            } finally {
                latencyMs.value = System.currentTimeMillis() - t0
                busy.value = false
            }
        }
    }

    fun refreshPack() {
        if (!online.value) { status.value = "Offline: pack refresh needs internet"; return }
        if (!hasKey) { status.value = "Add GEMINI_API_KEY to local.properties and rebuild"; return }
        if (busy.value) return
        busy.value = true
        status.value = "Gemini Flash is researching ${region.value.label} with Google Search…"
        val r = region.value
        viewModelScope.launch {
            try {
                val json = gemini.generatePack(r.label)
                store.save(r.id, json)
                if (region.value.id == r.id) { _pack.value = TripPack.parse(json); packRefreshed.value = true }
                status.value = "Pack refreshed: ${_pack.value.places.size} places, ${_pack.value.hotels.size} stays, ~${json.length / 4} tokens"
            } catch (e: Exception) {
                status.value = "Refresh failed: ${e.message}"
            } finally { busy.value = false }
        }
    }

    fun openMaps(ctx: Context, lat: Double? = null, lon: Double? = null, label: String? = null) {
        val uri = if (lat != null && lon != null) "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label ?: "")})"
        else "geo:0,0?q=${Uri.encode(label ?: pack.value.places.firstOrNull()?.name?.let { "$it, ${pack.value.base}" } ?: region.value.label)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { status.value = "No maps app" }
    }

    override fun onCleared() {
        voice.release()
        brain.close()
    }
}
