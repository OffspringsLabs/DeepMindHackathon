package com.offspringslabs.disha

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

sealed class BrainEvent {
    data class Text(val delta: String) : BrainEvent()
    data class ToolUsed(val label: String, val result: String) : BrainEvent()
    data class Note(val text: String) : BrainEvent()
    data class Card(val card: UiCard) : BrainEvent()
}

/**
 * Gemma 3n E2B via LiteRT-LM, fully on-device.
 * Three tiers, chosen at runtime and remembered for the session:
 *  1. native tool calling (LiteRT-LM ToolSet, automatic),
 *  2. manual CALL/FINAL protocol driven by us (works with any instruct model),
 *  3. plain context-stuffing with the compact pack text (last resort).
 */
class LocalBrain(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data class Ready(val backend: String, val loadMs: Long) : State()
        data class Failed(val message: String) : State()
    }

    enum class Mode { NATIVE_TOOLS, MANUAL_TOOLS, CONTEXT }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _mode = MutableStateFlow(Mode.NATIVE_TOOLS)
    val mode: StateFlow<Mode> = _mode
    private var engine: Engine? = null

    suspend fun init() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        if (!File(MODEL_PATH).exists()) {
            _state.value = State.Failed("Model missing at $MODEL_PATH")
            return@withContext
        }
        _state.value = State.Loading
        val cacheDir = File(context.filesDir, "litert-cache").apply { mkdirs() }.absolutePath
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            val t0 = System.currentTimeMillis()
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = MODEL_PATH,
                        backend = backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = 4096,
                        maxNumImages = null,
                        cacheDir = cacheDir,
                    )
                )
                e.initialize()
                engine = e
                _state.value = State.Ready(backend.name, System.currentTimeMillis() - t0)
                Log.i(TAG, "Engine ready on ${backend.name} in ${System.currentTimeMillis() - t0} ms")
                return@withContext
            } catch (t: Throwable) {
                Log.w(TAG, "Backend ${backend.name} failed", t)
                _state.value = State.Failed("${backend.name}: ${t.message}")
            }
        }
    }

    fun ask(pack: TripPack, question: String): Flow<BrainEvent> = flow {
        val eng = engine ?: throw IllegalStateException("On-device model not ready")
        val tools = TripTools(pack)

        if (_mode.value == Mode.NATIVE_TOOLS) {
            val ok = runCatching { nativeTools(eng, tools, pack, question) }
                .onFailure { Log.w(TAG, "native tool mode failed", it) }
                .getOrDefault(false)
            if (ok) return@flow
            _mode.value = Mode.MANUAL_TOOLS
            emit(BrainEvent.Note("switched to manual tool protocol"))
        }
        if (_mode.value == Mode.MANUAL_TOOLS) {
            val ok = runCatching { manualTools(eng, tools, pack, question) }
                .onFailure { Log.w(TAG, "manual tool mode failed", it) }
                .getOrDefault(false)
            if (ok) return@flow
            _mode.value = Mode.CONTEXT
            emit(BrainEvent.Note("switched to context mode"))
        }
        contextMode(eng, pack, question)
    }.flowOn(Dispatchers.IO)

    /** Returns true only if the runtime actually invoked at least one tool and produced text. */
    private suspend fun FlowCollector<BrainEvent>.nativeTools(eng: Engine, tools: TripTools, pack: TripPack, question: String): Boolean {
        val trace = ArrayList<Pair<String, String>>()
        val cards = ArrayList<UiCard>()
        tools.trace = { trace += it to "" }
        tools.onCard = { cards += it }
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(nativePrompt(pack.summary()))),
            initialMessages = emptyList(),
            tools = listOf(tool(tools)),
            samplerConfig = SAMPLER,
            automaticToolCalling = true,
        )
        val sb = StringBuilder()
        eng.createConversation(cfg).use { conv ->
            conv.sendMessageAsync(question).collect { msg -> sb.append(textOf(msg)) }
        }
        if (trace.isEmpty() || sb.isBlank()) return false
        trace.forEach { emit(BrainEvent.ToolUsed(it.first, it.second)) }
        cards.forEach { emit(BrainEvent.Card(it)) }
        emit(BrainEvent.Text(sb.toString().trim()))
        return true
    }

    /** CALL/FINAL loop, max 4 rounds. Returns true if a FINAL answer backed by at least one tool call was produced. */
    private suspend fun FlowCollector<BrainEvent>.manualTools(eng: Engine, tools: TripTools, pack: TripPack, question: String): Boolean {
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(manualPrompt(pack.summary()))),
            initialMessages = emptyList(),
            tools = emptyList(),
            samplerConfig = SAMPLER,
        )
        val pending = ArrayList<UiCard>()
        tools.onCard = { pending += it }
        eng.createConversation(cfg).use { conv ->
            var reply = textOf(conv.sendMessage("QUESTION: $question\nReply with one CALL line."))
            var calls = 0
            repeat(4) {
                val call = ToolProtocol.parseCall(reply)
                if (call != null) {
                    calls++
                    val result = tools.dispatch(call)
                    emit(BrainEvent.ToolUsed("${call.name}(${call.args.joinToString(", ")})", result))
                    pending.forEach { emit(BrainEvent.Card(it)) }; pending.clear()
                    reply = textOf(conv.sendMessage("RESULT: $result\nNow reply with FINAL: (max 2 sentences) or another CALL line."))
                } else {
                    val fin = ToolProtocol.finalText(reply) ?: reply.trim()
                    if (calls == 0) return false
                    emit(BrainEvent.Text(fin))
                    return true
                }
            }
            val fin = ToolProtocol.finalText(reply) ?: reply.trim()
            if (calls == 0 || fin.isBlank()) return false
            emit(BrainEvent.Text(fin))
            return true
        }
    }

    private suspend fun FlowCollector<BrainEvent>.contextMode(eng: Engine, pack: TripPack, question: String) {
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(contextPrompt(pack.compactText()))),
            initialMessages = emptyList(),
            tools = emptyList(),
            samplerConfig = SAMPLER,
        )
        eng.createConversation(cfg).use { conv ->
            conv.sendMessageAsync(question).collect { msg ->
                val t = textOf(msg)
                if (t.isNotEmpty()) emit(BrainEvent.Text(t))
            }
        }
    }

    fun close() { runCatching { engine?.close() }; engine = null; _state.value = State.Idle }

    private fun textOf(m: Message): String =
        m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        private const val TAG = "LocalBrain"
        const val MODEL_PATH = "/data/local/tmp/gemma/gemma-3n-E2B-it-int4.litertlm"
        private val SAMPLER = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = 0)

        fun nativePrompt(summary: String) = """
            You are TravelFreak, an offline travel guide on the traveller's phone.
            Tools read the saved trip pack. ALWAYS call a tool before stating any distance, time, price, opening hour or plan. Never guess numbers.
            Then answer in at most 2 short spoken sentences quoting the tool's numbers.
            If tools cannot find it, say: "Not in my pack, ask me when online."

            $summary
        """.trimIndent()

        fun manualPrompt(summary: String) = """
            You are TravelFreak, an offline travel guide on the traveller's phone. You cannot know any number yourself; tools read the saved trip pack.
            Tools:
            ${ToolProtocol.toolMenu()}
            Protocol, exactly one line per reply:
            CALL toolName("arg1", "arg2")   to get facts (use "base" for the base town, "now" for the current time)
            FINAL: <at most 2 short spoken sentences with the numbers from RESULT; no greeting, no "Okay", start with the fact>
            Always start with a CALL. Never write numbers that did not come from a RESULT. If RESULT says not found, FINAL: Not in my pack, ask me when online.
            Example:
            QUESTION: how far is X from base
            CALL getDistance("base", "X")
            RESULT: base to X: 22 km, about 45 min. Bus every 5 min
            FINAL: X is 22 km away, about 45 minutes by bus, which runs every 5 minutes.

            $summary
        """.trimIndent()

        fun contextPrompt(packText: String) = """
            You are TravelFreak, an offline travel guide. Answer using ONLY the TRIP PACK below.
            Maximum 2 short sentences. Quote numbers (km, minutes, hours, ₹) exactly as written.
            If the answer is not in the pack, say: "Not in my pack, ask me when online."

            TRIP PACK:
            $packText
        """.trimIndent()
    }
}
