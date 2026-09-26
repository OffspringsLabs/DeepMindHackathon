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

/**
 * Gemini Flash over REST.
 *  - builds trip packs with Google Search grounding (two-step: research → strict JSON)
 *  - answers questions (text or audio) as an agent over the SAME deterministic tools as the on-device brain,
 *    via native function calling, so both brains surface the same UI cards.
 */
class GeminiClient(private val apiKey: String = BuildConfig.GEMINI_API_KEY) {
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
    val hasKey: Boolean get() = apiKey.isNotBlank()

    data class AgentResult(val text: String, val toolCalls: List<Pair<String, String>>)

    suspend fun generatePack(regionLabel: String): String {
        val research = generate(contents(userParts(textPart(PackPrompts.research(regionLabel)))), grounded = true, temperature = 0.4)
        val json = generate(contents(userParts(textPart(PackPrompts.toJson(research.text)))), grounded = false, temperature = 0.1, jsonMode = true)
        val obj = JSONObject(stripFences(json.text))
        obj.put("region", regionLabel)
        val cleaned = obj.toString(2)
        TripPack.parse(cleaned) // validate
        return cleaned
    }

    /** Text question → function-calling loop over TripTools → short spoken answer. */
    suspend fun answerWithTools(question: String, pack: TripPack, tools: TripTools): AgentResult =
        agentLoop(userParts(textPart("QUESTION: $question")), pack, tools, PackPrompts.agentSystem(pack.summary()))

    /** Spoken question (WAV) → same loop; the model transcribes and answers. Returns (transcript, answer, calls). */
    suspend fun answerAudioWithTools(wav: ByteArray, pack: TripPack, tools: TripTools): Triple<String, String, List<Pair<String, String>>> {
        val audioPart = JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)),
        )
        val r = agentLoop(userParts(audioPart, textPart(PackPrompts.audioTurn())), pack, tools, PackPrompts.agentSystem(pack.summary()))
        val transcript = Regex("TRANSCRIPT:\\s*(.+)").find(r.text)?.groupValues?.get(1)?.trim() ?: "(audio)"
        val answer = r.text.substringAfter("ANSWER:", r.text).trim()
        return Triple(transcript, answer, r.toolCalls)
    }

    private suspend fun agentLoop(first: JSONObject, pack: TripPack, tools: TripTools, system: String): AgentResult {
        val history = JSONArray().put(first)
        val calls = ArrayList<Pair<String, String>>()
        repeat(4) {
            val res = generate(history, grounded = false, temperature = 0.2, system = system, functions = true)
            if (res.functionCalls.isEmpty()) return AgentResult(res.text.ifBlank { "Sorry, I could not answer that." }, calls)
            // Echo the model turn, then answer every function call in one user turn.
            history.put(JSONObject().put("role", "model").put("parts", res.rawParts))
            val responses = JSONArray()
            res.functionCalls.forEach { fc ->
                val call = ToolProtocol.callFromNamed(fc.getString("name"), fc.optJSONObject("args"))
                val result = if (call == null) "Unknown tool" else tools.dispatch(call)
                calls += "${fc.getString("name")}(${call?.args?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""})" to result
                responses.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", fc.getString("name")).put("response", JSONObject().put("result", result)),
                    ),
                )
            }
            history.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return AgentResult("I gathered the facts but ran out of steps; see the cards above.", calls)
    }

    // ---- REST plumbing ----

    private class Gen(val text: String, val functionCalls: List<JSONObject>, val rawParts: JSONArray)

    private fun textPart(t: String) = JSONObject().put("text", t)
    private fun userParts(vararg parts: JSONObject) = JSONObject().put("role", "user").put("parts", JSONArray().apply { parts.forEach { put(it) } })
    private fun contents(vararg turns: JSONObject) = JSONArray().apply { turns.forEach { put(it) } }

    private suspend fun generate(
        contents: JSONArray, grounded: Boolean, temperature: Double,
        jsonMode: Boolean = false, system: String? = null, functions: Boolean = false,
    ): Gen = withContext(Dispatchers.IO) {
        if (!hasKey) throw IOException("No Gemini API key. Add GEMINI_API_KEY to local.properties and rebuild.")
        val body = JSONObject().apply {
            put("contents", contents)
            system?.let { put("system_instruction", JSONObject().put("parts", JSONArray().put(textPart(it)))) }
            val tools = JSONArray()
            if (grounded) tools.put(JSONObject().put("google_search", JSONObject()))
            if (functions) tools.put(JSONObject().put("function_declarations", ToolProtocol.geminiDeclarations()))
            if (tools.length() > 0) put("tools", tools)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                if (jsonMode) put("response_mime_type", "application/json")
            })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Gemini HTTP ${resp.code}: ${txt.take(300)}")
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").optJSONArray("parts") ?: JSONArray()
            val text = StringBuilder()
            val fcs = ArrayList<JSONObject>()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optString("text").takeIf { it.isNotEmpty() }?.let(text::append)
                p.optJSONObject("functionCall")?.let(fcs::add)
            }
            Log.d(TAG, "gemini: ${fcs.size} function calls, ${text.length} chars")
            Gen(text.toString().trim(), fcs, parts)
        }
    }

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").trim()
        if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        return t
    }

    companion object {
        const val MODEL = "gemini-3.8-flash"
        private const val TAG = "GeminiClient"
    }
}

object PackPrompts {
    fun research(region: String) = """
        You are preparing an OFFLINE trip pack for a traveller visiting $region, India.
        You MUST use Google Search: run separate searches for (a) official temple/monument timings and entry fees, (b) current hotel prices, (c) bus/train frequencies and fares, (d) hospital and mobile-network coverage.
        Cover, with concrete numbers: the base town and how to reach it from Hyderabad (train/bus/flight, hours, approx ₹);
        nearest railway station and airport with distance; 4 to 5 stays across budgets with approx ₹ per night and distance from base;
        8 to 12 must-see places with type, opening and closing times, distance in km and minutes from the base, entry fee in ₹,
        approximate latitude/longitude, and one practical tip each; 6 to 10 point-to-point routes with km, minutes, mode and frequency;
        3 to 5 local dishes or eateries with approx ₹; a realistic 2 or 3 day plan with times; nearest hospital, ATM/fuel availability,
        and stretches with no mobile signal; 4 practical tips (dress code, best season, what to book ahead).
        Prefer approximate numbers over omissions. Do not invent hotel names; if unsure, describe the type of stay.
    """.trimIndent()

    fun toJson(research: String) = """
        Convert the research notes below into ONE JSON object with EXACTLY this shape and key names. Numbers must be numbers, not strings.
        Times are "HH:MM" 24h. Keep strings short. Output JSON only.
        {"region":"","state":"","base":"","season":"","languages":"",
         "arrival":{"rail":"","air":"","fromHyderabad":""},
         "hotels":[{"name":"","area":"","pricePerNight":0,"kmFromBase":0,"note":""}],
         "places":[{"name":"","type":"","open":"HH:MM","close":"HH:MM","kmFromBase":0,"minutesFromBase":0,"fee":0,"lat":0,"lon":0,"tip":""}],
         "routes":[{"from":"","to":"","km":0,"minutes":0,"mode":""}],
         "food":[{"item":"","where":"","price":0}],
         "days":[{"day":1,"plan":[{"time":"HH:MM","stop":""}]}],
         "emergency":{"hospital":"","police":"112","atm":"","noSignal":""},
         "tips":[""]}

        RESEARCH NOTES:
        $research
    """.trimIndent()

    fun agentSystem(summary: String) = """
        You are Disha, a travel guide on the traveller's phone. You have tools that read the saved trip pack; the app renders a card for every tool you call.
        ALWAYS call a tool before stating any distance, time, price, opening hour or plan; never guess numbers. Call several tools if the question needs them.
        Then answer in at most 2 short spoken sentences quoting the tool numbers. The traveller may speak Indian English, Telugu or Hindi; always answer in English.
        If tools cannot find it, say so in one sentence and give one sentence of general guidance.

        $summary
    """.trimIndent()

    fun audioTurn() = """
        The audio is the traveller's spoken question. Use tools as needed, then reply EXACTLY in this format:
        TRANSCRIPT: <the question in English>
        ANSWER: <at most 2 short spoken sentences>
    """.trimIndent()
}
