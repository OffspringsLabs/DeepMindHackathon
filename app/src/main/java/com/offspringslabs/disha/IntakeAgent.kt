package com.offspringslabs.disha

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent that asks. Its questions are interactive components summoned as client-side tools;
 * the loop suspends until the traveller answers, the answer is the tool result, and it continues.
 * When it has enough, it calls buildTrip → TripBuilder → pack saved for the on-device model.
 */
class IntakeAgent(private val gemini: GeminiClient, private val builder: TripBuilder) {

    interface Ui {
        /** Render the card, suspend until answered; return the answer as JSON. */
        suspend fun ask(card: UiCard, say: String): JSONObject
        fun progress(stage: Int, total: Int, label: String)
        fun say(text: String)
    }

    data class Outcome(val result: TripBuilder.Result?, val closing: String)

    suspend fun run(utterance: String?, ui: Ui, lang: ReplyLang, knownTrips: List<String>): Outcome {
        var built: TripBuilder.Result? = null
        var lastSay = ""
        val host = object : ToolHost {
            override fun declarations() = DECLS
            override suspend fun call(name: String, args: JSONObject): String? {
                fun s(k: String, d: String = "") = args.optString(k, d).ifBlank { d }
                fun i(k: String, d: Int) = args.optString(k).filter { it.isDigit() }.toIntOrNull() ?: args.optInt(k, d)
                return when (name) {
                    "askDestination" -> ui.ask(UiCard.AskDestination(s("prompt", "Where do you want to go?"), s("suggestions", "Hampi, Araku Valley, Gokarna, Coorg, Pondicherry, Tirupati").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(6)), lastSay).toString()
                    "askDays" -> ui.ask(UiCard.AskDays(s("prompt", "How many days do you have?"), i("min", 1), i("max", 10), i("suggested", 3), s("reason")), lastSay).toString()
                    "askBudget" -> ui.ask(UiCard.AskBudget(s("prompt", "What's your total budget?"), i("minInr", 3000), i("maxInr", 80000), i("suggestedInr", 15000), s("perDayHint")), lastSay).toString()
                    "askTravellers" -> ui.ask(UiCard.AskTravellers(s("prompt", "Who's travelling, and how do you like to travel?")), lastSay).toString()
                    "confirmTrip" -> ui.ask(UiCard.Confirm(s("prompt", "Shall I build this trip?"), s("summary")), lastSay).toString()
                    "buildTrip" -> {
                        val c = Constraints(i("days", 3), i("budgetInr", 15000), i("people", 2), s("startCity", "Hyderabad"), s("pace", "balanced"), s("interests").split(',').map { it.trim() }.filter { it.isNotBlank() }, s("diet", "any"))
                        val dest = s("destination")
                        try {
                            val r = builder.build(dest, c) { st -> ui.progress(st, builder.stages.size, builder.stages[st]) }
                            built = r
                            val p = r.pack.plan
                            JSONObject().put("ok", true).put("id", r.id).put("region", r.pack.region).put("places", r.pack.places.size).put("days", p?.days?.size ?: 0)
                                .put("totalInr", p?.budget?.total ?: 0).put("remainingInr", p?.budget?.remaining ?: 0).put("overBudget", p?.overBudget ?: false)
                                .put("stay", p?.stay?.name ?: "").put("alternatives", p?.alternatives?.size ?: 0).toString()
                        } catch (e: Exception) {
                            Log.w("IntakeAgent", "build failed", e)
                            JSONObject().put("ok", false).put("error", e.message?.take(200)).toString()
                        }
                    }
                    else -> null
                }
            }
        }
        val first = gemini.userParts(gemini.textPart(utterance?.takeIf { it.isNotBlank() }?.let { "TRAVELLER SAYS: $it" } ?: "TRAVELLER: (opened the planner)"))
        val r = gemini.agentLoop(first, system(lang, knownTrips), host, rounds = 12, onSay = { t -> lastSay = t; ui.say(t) })
        return Outcome(built, r.text)
    }

    companion object {
        fun system(lang: ReplyLang, known: List<String>) = """
            You are TravelFreak, a friendly, efficient Indian travel planner talking on the traveller's phone. Your job: learn WHERE (destination in India), HOW LONG (days), BUDGET (₹ total) and WHO/HOW (people, start city, pace, interests, diet), then build the trip.
            You ask by calling ask* tools; each renders an interactive card the traveller answers. Ask ONE thing per call, in a sensible order: destination → days → budget → travellers → confirmTrip → buildTrip.
            Extract anything the traveller already said and skip those questions. Suggest sensible defaults with a one-line reason (e.g. askDays suggested=2 reason="Hampi's core fits in 2 days; 3 adds Anegundi"). For askBudget give a realistic per-day hint for that destination and party.
            With every tool call include ONE short, warm spoken sentence as text (it is read aloud). ${lang.instruction}
            After confirmTrip returns confirmed=true call buildTrip with all fields (interests comma-separated). If it returns edit=<field>, ask that field again.
            When buildTrip returns ok=true, finish with 2 sentences: what was built (days, stay, total vs budget, remaining or over-budget with how many alternatives) and that it now works offline. If ok=false, apologise briefly and suggest retrying online.
            ${if (known.isNotEmpty()) "Trips already saved on this phone: ${known.joinToString(", ")} (offer them as suggestions too)." else ""}
        """.trimIndent()

        private fun decl(name: String, desc: String, vararg params: Pair<String, String>): JSONObject {
            val props = JSONObject(); params.forEach { (n, d) -> props.put(n, JSONObject().put("type", "STRING").put("description", d)) }
            val o = JSONObject().put("name", name).put("description", desc)
            if (params.isNotEmpty()) o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray().apply { params.forEach { put(it.first) } }))
            return o
        }

        val DECLS: JSONArray = JSONArray().apply {
            put(decl("askDestination", "Show a destination picker card. Returns {destination}.", "prompt" to "Question text", "suggestions" to "Comma-separated 6 destination suggestions in India"))
            put(decl("askDays", "Show a days stepper card. Returns {days}.", "prompt" to "Question text", "min" to "Min days", "max" to "Max days", "suggested" to "Suggested days", "reason" to "One-line reason for the suggestion"))
            put(decl("askBudget", "Show a rupee budget slider card. Returns {budgetInr}.", "prompt" to "Question text", "minInr" to "Slider min ₹", "maxInr" to "Slider max ₹", "suggestedInr" to "Suggested total ₹", "perDayHint" to "e.g. 'Hampi runs ₹2,500-4,000 per person per day'"))
            put(decl("askTravellers", "Show the travellers card (people, start city, pace, interests, diet). Returns {people,startCity,pace,interests,diet}.", "prompt" to "Question text"))
            put(decl("confirmTrip", "Show a confirmation card with Edit/Build. Returns {confirmed} or {edit:field}.", "prompt" to "Question text", "summary" to "One-line summary: destination · days · ₹budget · people · from city · pace"))
            put(decl("buildTrip", "Build and save the full offline trip. Returns {ok,id,region,days,totalInr,remainingInr,overBudget,alternatives}.",
                "destination" to "Destination", "days" to "Days", "budgetInr" to "Total budget ₹", "people" to "People", "startCity" to "Start city", "pace" to "relaxed|balanced|packed", "interests" to "Comma-separated interests", "diet" to "any|veg|jain|halal|vegan"))
        }
    }
}
