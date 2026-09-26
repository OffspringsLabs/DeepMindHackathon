package com.offspringslabs.disha

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Something that answers function calls for an agent loop: pack tools, or the intake's interactive cards. */
interface ToolHost {
    fun declarations(): JSONArray
    /** Returns the tool result text, or null if the name is not ours. */
    suspend fun call(name: String, args: JSONObject): String?
}

/** Pack tools exposed to Gemini through the shared catalogue. */
class PackToolHost(private val tools: TripTools) : ToolHost {
    override fun declarations() = ToolProtocol.geminiDeclarations()
    override suspend fun call(name: String, args: JSONObject): String? {
        val call = ToolProtocol.callFromNamed(name, args) ?: return null
        return tools.dispatch(call)
    }
}

/**
 * Gemini Flash over REST: built-in live tools (Google Maps grounding, Google Search, code execution),
 * function calling against any ToolHost, structured JSON, audio in.
 */
class GeminiClient(private val apiKey: String = BuildConfig.GEMINI_API_KEY) {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
    val hasKey: Boolean get() = apiKey.isNotBlank()

    class Gen(val text: String, val functionCalls: List<JSONObject>, val rawParts: JSONArray, val codeOutput: String, val sources: List<Source>, val queries: List<String>)
    data class AgentResult(val text: String, val toolCalls: List<Pair<String, String>>)

    // ---------- agent loop ----------

    /**
     * Function-calling loop. [onStep] fires per tool call as it happens (label, result).
     * [builtins] adds Gemini live tools alongside our declarations (needs the server-side-invocation flag).
     * On a 4xx caused by the built-in mix, retries once without built-ins.
     */
    suspend fun agentLoop(
        first: JSONObject, system: String, host: ToolHost, rounds: Int = 6,
        builtins: List<String> = emptyList(), latLng: Pair<Double, Double>? = null,
        onStep: (String, String) -> Unit = { _, _ -> }, onSay: (String) -> Unit = {},
    ): AgentResult {
        val history = JSONArray().put(first)
        val calls = ArrayList<Pair<String, String>>()
        var useBuiltins = builtins
        repeat(rounds) {
            val res = try {
                generate(history, system = system, declarations = host.declarations(), builtins = useBuiltins, latLng = latLng, temperature = 0.2)
            } catch (e: IOException) {
                if (useBuiltins.isNotEmpty() && (e.message ?: "").contains("HTTP 4")) { Log.w(TAG, "retrying without built-ins: ${e.message}"); useBuiltins = emptyList(); return@repeat }
                throw e
            }
            if (res.text.isNotBlank() && res.functionCalls.isNotEmpty()) onSay(res.text)
            if (res.functionCalls.isEmpty()) return AgentResult(res.text.ifBlank { "Sorry, I could not answer that." }, calls)
            history.put(JSONObject().put("role", "model").put("parts", res.rawParts))
            val responses = JSONArray()
            for (fc in res.functionCalls) {
                val name = fc.getString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                Log.i(TAG, "fn call: $name ${args.toString().take(160)}")
                val result = host.call(name, args) ?: "Unknown tool $name"
                Log.i(TAG, "fn result: $name → ${result.take(160)}")
                val label = "$name(${args.keys().asSequence().map { args.opt(it).toString() }.filter { it.isNotBlank() }.joinToString(", ").take(80)})"
                calls += label to result
                onStep(label, result)
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", result))))
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return AgentResult("I gathered the facts but ran out of steps; see the cards above.", calls)
    }

    /** Text question → pack tools (+ live Google Search when [live]) → short answer or DECISION block. */
    suspend fun answerWithTools(question: String, pack: TripPack, tools: TripTools, lang: ReplyLang, live: Boolean, onStep: (String, String) -> Unit): AgentResult =
        agentLoop(
            userParts(textPart("QUESTION: $question")), PackPrompts.agentSystem(pack.summary(), lang), PackToolHost(tools), rounds = 6,
            builtins = if (live) listOf("google_search") else emptyList(), latLng = pack.baseLat?.let { la -> pack.baseLon?.let { lo -> la to lo } }, onStep = onStep,
        )

    /** Spoken question (WAV) → same loop. Returns (transcript, answer, calls). */
    suspend fun answerAudioWithTools(wav: ByteArray, pack: TripPack, tools: TripTools, lang: ReplyLang, onStep: (String, String) -> Unit): Triple<String, String, List<Pair<String, String>>> {
        val audioPart = JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        val r = agentLoop(userParts(audioPart, textPart(PackPrompts.audioTurn())), PackPrompts.agentSystem(pack.summary(), lang), PackToolHost(tools), rounds = 6, onStep = onStep)
        val transcript = Regex("TRANSCRIPT:\\s*(.+)").find(r.text)?.groupValues?.get(1)?.trim() ?: "(audio)"
        val answer = r.text.substringAfter("ANSWER:", r.text).trim()
        return Triple(transcript, answer, r.toolCalls)
    }

    // ---------- REST ----------

    fun textPart(t: String) = JSONObject().put("text", t)
    fun userParts(vararg parts: JSONObject) = JSONObject().put("role", "user").put("parts", JSONArray().apply { parts.forEach { put(it) } })
    fun single(prompt: String): JSONArray = JSONArray().put(userParts(textPart(prompt)))

    suspend fun generate(
        contents: JSONArray, system: String? = null, builtins: List<String> = emptyList(), declarations: JSONArray? = null,
        latLng: Pair<Double, Double>? = null, jsonMode: Boolean = false, temperature: Double = 0.3, model: String = MODEL,
    ): Gen = withContext(Dispatchers.IO) {
        if (!hasKey) throw IOException("No Gemini API key. Add GEMINI_API_KEY to local.properties and rebuild.")
        val body = JSONObject().apply {
            put("contents", contents)
            system?.let { put("system_instruction", JSONObject().put("parts", JSONArray().put(textPart(it)))) }
            val tools = JSONArray()
            builtins.forEach { tools.put(JSONObject().put(it, JSONObject())) }
            if (declarations != null && declarations.length() > 0) tools.put(JSONObject().put("function_declarations", declarations))
            if (tools.length() > 0) put("tools", tools)
            val tc = JSONObject()
            if (builtins.isNotEmpty() && declarations != null && declarations.length() > 0) tc.put("includeServerSideToolInvocations", true)
            if (latLng != null && "google_maps" in builtins) tc.put("retrievalConfig", JSONObject().put("latLng", JSONObject().put("latitude", latLng.first).put("longitude", latLng.second)))
            if (tc.length() > 0) put("toolConfig", tc)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                if (jsonMode) put("response_mime_type", "application/json")
            })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Gemini HTTP ${resp.code}: ${txt.take(300)}")
            val cand = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = StringBuilder(); val code = StringBuilder(); val fcs = ArrayList<JSONObject>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optString("text").takeIf { it.isNotEmpty() }?.let(text::append)
                p.optJSONObject("functionCall")?.let(fcs::add)
                p.optJSONObject("codeExecutionResult")?.optString("output")?.let { code.append(it).append('\n') }
            }
            val gm = cand.optJSONObject("groundingMetadata")
            val sources = ArrayList<Source>()
            gm?.optJSONArray("groundingChunks")?.let { ch ->
                for (i in 0 until ch.length()) {
                    val c = ch.getJSONObject(i)
                    c.optJSONObject("web")?.let { sources += Source(it.optString("title"), it.optString("uri"), "web") }
                    c.optJSONObject("maps")?.let { sources += Source(it.optString("title"), it.optString("uri"), "maps") }
                }
            }
            val queries = gm?.optJSONArray("webSearchQueries")?.let { q -> (0 until q.length()).map { q.optString(it) } } ?: emptyList()
            Log.d(TAG, "gemini[$model]: ${fcs.size} fn calls, ${text.length} chars, ${sources.size} sources, code ${code.length}")
            Gen(text.toString().trim(), fcs, parts, code.toString().trim(), sources, queries)
        }
    }

    companion object {
        const val MODEL = "gemini-3.8-flash"
        private const val TAG = "GeminiClient"
    }
}

object PackPrompts {
    fun agentSystem(summary: String, lang: ReplyLang) = """
        You are TravelFreak, a warm, quick local travel guide on the traveller's phone. Tools read the saved trip pack (facts, fares, phrases, scams, emergency, the itinerary and budget); the app renders a card for every tool you call. Google Search may be available for live facts.
        ALWAYS call a tool before stating any distance, time, price, fare, opening hour, phrase or plan; never guess numbers. Call several tools if the question needs them.
        If the traveller is choosing between options (bus or auto, skip or keep, stay A or B), gather the facts then reply EXACTLY as:
        DECISION: <one sentence>
        WHY: <bullet 1> | <bullet 2> | <bullet 3>
        ALTERNATIVE: <one sentence>
        Otherwise answer in at most 2 short spoken sentences quoting the tool numbers, as a friendly guide would say aloud. Tool arguments are always in English.
        ${lang.instruction}
        If tools cannot find it, say so in one sentence and give one sentence of general guidance.

        $summary
    """.trimIndent()

    fun audioTurn() = """
        The audio is the traveller's spoken question (Indian English, Telugu or Hindi). Use tools as needed, then reply EXACTLY in this format:
        TRANSCRIPT: <the question as spoken, in its own language and script>
        ANSWER: <at most 2 short spoken sentences, following the language rule>
    """.trimIndent()
}
