package com.offspringslabs.disha

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class ToolStep(val label: String, val result: String)

class DishaViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TripPackStore(app)
    private val brain = LocalBrain(app)
    private val gemini = GeminiClient()
    private val connectivity = Connectivity(app)
    private val voice = VoiceIO(app)
    private val recorder = AudioRecorder()
    private val geminiTts = GeminiTts()
    private val builder = TripBuilder(gemini, store)
    private val intake = IntakeAgent(gemini, builder)

    // ---- trips
    val trips = MutableStateFlow(store.regions)
    private val _region = MutableStateFlow(trips.value.first())
    val region: StateFlow<Region> = _region
    private val _pack = MutableStateFlow(store.load(trips.value.first().id))
    val pack: StateFlow<TripPack> = _pack
    val packRefreshed = MutableStateFlow(store.isRefreshed(trips.value.first().id))

    // ---- modes
    val online: StateFlow<Boolean> = connectivity.online
    val forceOffline = MutableStateFlow(false)
    val brainState: StateFlow<LocalBrain.State> = brain.state
    val brainMode: StateFlow<LocalBrain.Mode> = brain.mode
    val hasKey: Boolean = gemini.hasKey
    val replyLang = MutableStateFlow(ReplyLang.AUTO)
    val ttsVoice = MutableStateFlow(GeminiTts.VOICES.first())
    val speaking = MutableStateFlow("")

    // ---- conversation
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

    // ---- intake (the agent asks; cards answer)
    val intakeActive = MutableStateFlow(false)
    val intakeCard = MutableStateFlow<UiCard?>(null)
    val intakeSay = MutableStateFlow("")
    private var pending: CompletableDeferred<JSONObject>? = null
    private var intakeJob: Job? = null
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
        _pack.value = runCatching { store.load(r.id) }.getOrElse { e -> status.value = "Could not load ${r.label}: ${e.message}"; return }
        packRefreshed.value = store.isRefreshed(r.id)
        transcript.value = ""
        clearAnswer()
    }

    fun toggleForceOffline() { forceOffline.value = !forceOffline.value }
    fun togglePack() { showPack.value = !showPack.value }
    fun setStatus(s: String) { status.value = s }
    fun setReplyLang(l: ReplyLang) { replyLang.value = l }
    fun nextVoice() { val i = GeminiTts.VOICES.indexOf(ttsVoice.value); ttsVoice.value = GeminiTts.VOICES[(i + 1) % GeminiTts.VOICES.size] }

    private fun clearAnswer() {
        answer.value = ""; steps.value = emptyList(); cards.value = emptyList(); notes.value = emptyList(); route.value = ""; status.value = ""
    }

    private fun stopAllSpeech() { voice.stopSpeaking(); geminiTts.stop(); speaking.value = "" }

    /** Speaks [text]; [onStart] fires the moment audio actually begins (or immediately for the device voice). */
    private suspend fun speakOut(text: String, preferGemini: Boolean, onStart: () -> Unit = {}) {
        if (text.isBlank()) { onStart(); return }
        if (preferGemini) {
            try {
                speaking.value = "gemini"
                val pcm = geminiTts.synthesize(text, ttsVoice.value, "Read this aloud ${replyLang.value.ttsHint}, warmly and briskly, like a friendly local guide. Say numbers naturally.")
                geminiTts.play(pcm) { Log.i("Choreo", "voice started (gemini)"); onStart() }
                speaking.value = ""; return
            } catch (e: Exception) {
                Log.w("Disha", "Gemini TTS failed, using Android TTS", e)
                notes.value = notes.value + "Gemini voice unavailable, using device voice"
            }
        }
        speaking.value = "android"; voice.speak(text); Log.i("Choreo", "voice started (device)"); onStart(); speaking.value = ""
    }

    /**
     * Voice-first choreography: start speaking, and only once audio begins (or after [VOICE_WAIT_MS])
     * show the answer text; one second later the components slide in, staggered.
     */
    private suspend fun choreograph(text: String, staged: List<UiCard>, preferGemini: Boolean) {
        val started = CompletableDeferred<Unit>()
        viewModelScope.launch { speakOut(text, preferGemini) { started.complete(Unit) }; started.complete(Unit) }
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(VOICE_WAIT_MS) { started.await() }
        answer.value = text
        delay(CARD_LEAD_MS)
        for (c in staged) { addCard(c); Log.i("Choreo", "card ${c::class.simpleName} +${System.currentTimeMillis() - t0} ms"); delay(CARD_STAGGER_MS) }
    }

    /** Tools shared by both brains; every tool call lands a precompiled card on screen. */
    private fun addCard(c: UiCard) { if (c !in cards.value) cards.value = cards.value + c }
    /** Cards are collected while the agent works and revealed by [choreograph] once the voice starts. */
    private fun stagedTools(staged: MutableList<UiCard>): TripTools = TripTools(pack.value).also { t -> t.onCard = { c -> if (c !in staged) staged += c } }
    private fun toolsForScreen(): TripTools = TripTools(pack.value).also { t -> t.onCard = ::addCard }

    private fun onStep(label: String, result: String) { steps.value = steps.value + ToolStep(label, result) }

    /** DECISION / WHY / ALTERNATIVE → Decision card; returns the spoken line. */
    private fun absorbDecision(text: String, staged: MutableList<UiCard>): String {
        if (!text.contains("DECISION:", true)) return text
        val rec = Regex("DECISION:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val why = Regex("WHY:\\s*(.+)").find(text)?.groupValues?.get(1)?.split('|')?.map { it.trim().trimStart('-', '•', ' ') }?.filter { it.isNotBlank() } ?: emptyList()
        val alt = Regex("ALTERNATIVE:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        staged.add(0, UiCard.Decision(rec, why, alt, ""))
        return rec
    }

    // ---------- Q&A ----------

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty()) return
        // While the agent is asking, free text answers its current card.
        if (intakeActive.value && pending != null) { answerIntake(JSONObject().put("text", question)); return }
        if (busy.value) return
        stopAllSpeech()
        transcript.value = question
        clearAnswer()
        busy.value = true
        val t0 = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                var done = false
                val staged = ArrayList<UiCard>()
                var text = ""
                if (useCloud()) {
                    route.value = "Gemini Flash · cloud agent"
                    try {
                        val r = gemini.answerWithTools(question, pack.value, stagedTools(staged), replyLang.value, live = true, onStep = ::onStep)
                        text = absorbDecision(r.text, staged)
                        done = true
                    } catch (e: Exception) {
                        Log.w("Disha", "cloud failed, falling back on-device", e)
                        notes.value = notes.value + "cloud unreachable (${e.message?.take(60)}), answering on-device"
                        staged.clear(); steps.value = emptyList()
                    }
                }
                if (!done) text = askOnDevice(question, staged)
                latencyMs.value = System.currentTimeMillis() - t0
                busy.value = false
                choreograph(text, staged, preferGemini = done)
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                if (busy.value) { latencyMs.value = System.currentTimeMillis() - t0; busy.value = false }
            }
        }
    }

    private suspend fun askOnDevice(question: String, staged: MutableList<UiCard>): String {
        route.value = "Gemma 3n E2B · on-device agent"
        val sb = StringBuilder()
        brain.ask(pack.value, question).collect { ev ->
            when (ev) {
                is BrainEvent.Text -> sb.append(ev.delta)
                is BrainEvent.ToolUsed -> steps.value = steps.value + ToolStep(ev.label, ev.result)
                is BrainEvent.Card -> if (ev.card !in staged) staged += ev.card
                is BrainEvent.Note -> notes.value = notes.value + ev.text
            }
        }
        return absorbDecision(sb.toString(), staged)
    }

    // ---------- intake ----------

    fun startNewTrip(utterance: String? = null) {
        if (intakeActive.value) return
        if (!online.value || !hasKey) { status.value = "Planning a new trip needs internet and a Gemini key. Saved trips still work offline."; return }
        stopAllSpeech(); clearAnswer(); transcript.value = utterance ?: ""
        intakeActive.value = true; intakeSay.value = ""; intakeCard.value = null
        val ui = object : IntakeAgent.Ui {
            override suspend fun ask(card: UiCard, say: String): JSONObject {
                val d = CompletableDeferred<JSONObject>()
                pending = d
                val line = say.ifBlank {
                    when (card) {
                        is UiCard.AskDestination -> card.prompt; is UiCard.AskDays -> card.prompt + (if (card.reason.isNotBlank()) " " + card.reason else "")
                        is UiCard.AskBudget -> card.prompt + (if (card.perDayHint.isNotBlank()) " " + card.perDayHint else ""); is UiCard.AskTravellers -> card.prompt
                        is UiCard.Confirm -> card.prompt; else -> ""
                    }
                }
                if (line.isNotBlank()) {
                    intakeSay.value = line
                    val started = CompletableDeferred<Unit>()
                    viewModelScope.launch { speakOut(line, preferGemini = true) { started.complete(Unit) }; started.complete(Unit) }
                    withTimeoutOrNull(VOICE_WAIT_MS) { started.await() }
                    delay(CARD_LEAD_MS)
                }
                intakeCard.value = card
                return d.await()
            }
            override fun progress(stage: Int, total: Int, label: String) { intakeCard.value = UiCard.Progress(label, builder.stages, stage) }
            override fun say(text: String) { intakeSay.value = text }
        }
        intakeJob = viewModelScope.launch {
            try {
                val out = intake.run(utterance, ui, replyLang.value, store.savedTrips().map { it.label })
                val staged = ArrayList<UiCard>()
                out.result?.let { r ->
                    trips.value = store.regions
                    selectRegion(Region(r.id, r.pack.region, saved = true))
                    if (r.fixes.isNotEmpty()) notes.value = notes.value + "budget re-verified: ${r.fixes.joinToString("; ")}"
                    val t = stagedTools(staged); t.getBudget(); t.getPlan("1", "now"); t.getMap()
                }
                route.value = "Gemini Flash · planner agent"
                intakeActive.value = false; intakeCard.value = null
                choreograph(out.closing, staged, preferGemini = true)
            } catch (e: Exception) {
                status.value = "Planner stopped: ${e.message?.take(160)}"
            } finally {
                intakeActive.value = false; intakeCard.value = null; pending = null
            }
        }
    }

    fun answerIntake(answer: JSONObject) {
        val d = pending ?: return
        pending = null
        intakeCard.value = null
        d.complete(answer)
    }

    fun cancelIntake() {
        intakeJob?.cancel(); intakeJob = null; pending = null
        intakeActive.value = false; intakeCard.value = null; intakeSay.value = ""
        status.value = "Planner cancelled"
    }

    // ---------- voice ----------

    fun onMicTap() {
        when {
            listening.value -> voice.stopListening()
            recording.value -> stopRecordingAndAsk()
            busy.value -> Unit
            useCloud() && !intakeActive.value -> {
                stopAllSpeech(); recorder.start(); recording.value = true
                status.value = "Recording… tap mic again to send"
                recordTimeout = viewModelScope.launch { delay(12_000); if (recording.value) stopRecordingAndAsk() }
            }
            else -> {
                stopAllSpeech()
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
                val staged = ArrayList<UiCard>()
                val (t, a, _) = gemini.answerAudioWithTools(wav, pack.value, stagedTools(staged), replyLang.value, ::onStep)
                transcript.value = t
                val text = absorbDecision(a, staged)
                latencyMs.value = System.currentTimeMillis() - t0
                busy.value = false
                choreograph(text, staged, preferGemini = true)
            } catch (e: Exception) {
                status.value = "Audio failed: ${e.message?.take(120)}. Use the mic again offline or type."
                transcript.value = ""
            } finally {
                if (busy.value) { latencyMs.value = System.currentTimeMillis() - t0; busy.value = false }
            }
        }
    }

    // ---------- pack refresh (rebuild this trip with its constraints) ----------

    fun refreshPack() {
        if (!online.value) { status.value = "Offline: rebuilding needs internet"; return }
        if (!hasKey) { status.value = "Add GEMINI_API_KEY to local.properties and rebuild"; return }
        if (busy.value || intakeActive.value) return
        busy.value = true
        val r = region.value
        val c = pack.value.plan?.constraints ?: Constraints(3, 15000, 2, "Hyderabad", "balanced", listOf("temples", "nature", "food"), "any")
        viewModelScope.launch {
            try {
                val res = builder.build(r.label.substringBefore(",").ifBlank { r.label }, c) { st -> status.value = "${st + 1}/${builder.stages.size} ${builder.stages[st]}…" }
                trips.value = store.regions
                selectRegion(Region(res.id, res.pack.region, saved = true))
                status.value = "Rebuilt: ${res.pack.places.size} places, plan ₹${res.pack.plan?.budget?.total} of ₹${c.budgetInr}" + if (res.fixes.isNotEmpty()) " (budget re-verified)" else ""
            } catch (e: Exception) {
                status.value = "Rebuild failed: ${e.message?.take(160)}"
            } finally { busy.value = false }
        }
    }

    fun openMaps(ctx: Context, lat: Double? = null, lon: Double? = null, label: String? = null) {
        val uri = if (lat != null && lon != null) "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label ?: "")})"
        else pack.value.plan?.offlineMap?.mapsAreaUrl?.takeIf { it.isNotBlank() } ?: "geo:0,0?q=${Uri.encode(label ?: pack.value.places.firstOrNull()?.name?.let { "$it, ${pack.value.base}" } ?: region.value.label)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { status.value = "No maps app" }
    }

    override fun onCleared() {
        geminiTts.stop(); voice.release(); brain.close()
    }

    companion object {
        const val VOICE_WAIT_MS = 7_000L   // never hold the screen longer than this waiting for audio
        const val CARD_LEAD_MS = 1_000L    // voice leads, components follow
        const val CARD_STAGGER_MS = 650L
    }
}
