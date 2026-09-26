package com.offspringslabs.disha

import org.json.JSONArray
import org.json.JSONObject

data class ToolSpec(val name: String, val description: String, val params: List<Pair<String, String>>)

/** One tool catalogue, three consumers: LiteRT-LM native, our CALL/FINAL protocol, and Gemini function calling. */
object ToolProtocol {
    val SPECS: List<ToolSpec> = listOf(
        ToolSpec("getPlace", "Details of one place, hotel or eatery: hours, distance from base, fee, price, tips.", listOf("name" to "Name, partial is fine")),
        ToolSpec("getDistance", "Distance in km and travel time in minutes between two places or 'base'. Use for how far / how long.", listOf("from" to "Start place name or 'base'", "to" to "Destination place name or 'base'")),
        ToolSpec("isOpen", "Whether a place is open at a time, and minutes until it closes or opens.", listOf("name" to "Place name", "time" to "HH:MM 24h or 'now'")),
        ToolSpec("estimateBudget", "Rupee budget for a stay: hotel nights plus food and fees per person, arithmetic shown.", listOf("hotel" to "Hotel name, partial ok, or 'cheapest'", "nights" to "Number of nights", "people" to "Number of people")),
        ToolSpec("getPlan", "Itinerary for a day and the next stop after a time.", listOf("day" to "Day number starting at 1", "time" to "HH:MM or 'now'")),
        ToolSpec("listAll", "List everything in the pack: places, hotels, food, routes.", emptyList()),
        ToolSpec("getEmergencyInfo", "Hospital, police, ATMs, no-signal areas, how to reach the region.", emptyList()),
        ToolSpec("currentTime", "Current local time and weekday from the phone clock.", emptyList()),
        // v2 local-reality + plan
        ToolSpec("sayIt", "Local-language phrase for an intent (where is, how much, hospital, vegetarian, help…): native script + romanised.", listOf("intent" to "What to say, in English")),
        ToolSpec("fareCheck", "Fair fare vs tourist quote for a leg by mode, plus the local auto/taxi rule.", listOf("from" to "From", "to" to "To", "mode" to "auto, taxi, bus, jeep or any")),
        ToolSpec("howToRide", "How local transport works: auto rules, shared routes, metro, bus boards, last services.", listOf("mode" to "auto, bus, metro, shared or any")),
        ToolSpec("permitsAndPayments", "UPI coverage, cash-only spots, tolls, ID and permits with cost and lead time.", emptyList()),
        ToolSpec("findFood", "Dishes and eateries for a diet and spice tolerance.", listOf("diet" to "veg, non-veg, jain, vegan, halal or any", "spice" to "mild, medium, hot or any")),
        ToolSpec("etiquette", "Dress code, footwear, photography, alcohol, women's safety, upcoming shutdowns.", listOf("place" to "Place name or general")),
        ToolSpec("isShutdown", "Whether anything is shut or special on a date.", listOf("date" to "YYYY-MM-DD, today or tomorrow")),
        ToolSpec("scamCheck", "Match a situation to known scams and get the counter-move.", listOf("situation" to "What is happening")),
        ToolSpec("emergency", "Emergency contact by kind plus the local-language phrase.", listOf("kind" to "hospital, pharmacy, police, tourist police, embassy or any")),
        ToolSpec("signalMap", "Where mobile signal drops and the best carrier.", emptyList()),
        ToolSpec("getBudget", "Planned trip total vs budget by category, remaining, per person.", emptyList()),
        ToolSpec("whatIfSkip", "Rupees and minutes saved by skipping a planned stop, with alternatives.", listOf("stop" to "Stop name")),
        ToolSpec("findStay", "Stays at or under a nightly price.", listOf("maxPerNight" to "Max ₹ per night")),
        ToolSpec("getMap", "Offline schematic map of places, stay and day routes with no-signal zones.", emptyList()),
    )

    fun toolMenu(): String = SPECS.joinToString("\n") { s -> "- ${s.name}(${s.params.joinToString(", ") { it.first }}): ${s.description.substringBefore('.').take(70)}" }

    /** Gemini `function_declarations` JSON. */
    fun geminiDeclarations(): JSONArray = JSONArray().apply {
        SPECS.forEach { s ->
            val props = JSONObject()
            s.params.forEach { (n, d) -> props.put(n, JSONObject().put("type", "STRING").put("description", d)) }
            val decl = JSONObject().put("name", s.name).put("description", s.description)
            if (s.params.isNotEmpty()) decl.put(
                "parameters",
                JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray().apply { s.params.forEach { put(it.first) } }),
            )
            put(decl)
        }
    }

    data class Call(val name: String, val args: List<String>)

    private val CALL_RE = Regex("""CALL\s+(\w+)\s*\((.*?)\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTED = Regex(""""([^"]*)"|'([^']*)'""")

    fun parseCall(text: String): Call? {
        val m = CALL_RE.find(text) ?: return null
        val spec = SPECS.firstOrNull { it.name.equals(m.groupValues[1], true) } ?: return null
        val inner = m.groupValues[2].trim()
        val quoted = QUOTED.findAll(inner).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        val args = if (quoted.isNotEmpty()) quoted
        else inner.split(',').map { it.trim().substringAfter('=').trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        return Call(spec.name, args)
    }

    /** Gemini gives named args; map them onto the spec's positional order. */
    fun callFromNamed(name: String, args: JSONObject?): Call? {
        val spec = SPECS.firstOrNull { it.name == name } ?: return null
        return Call(spec.name, spec.params.map { (p, _) -> args?.opt(p)?.toString() ?: "" })
    }

    fun finalText(text: String): String? {
        val idx = text.indexOf("FINAL:", ignoreCase = true)
        return if (idx >= 0) text.substring(idx + 6).trim().ifEmpty { null } else null
    }
}

fun TripTools.dispatch(call: ToolProtocol.Call): String {
    val a = call.args
    fun arg(i: Int, d: String = "") = a.getOrNull(i)?.takeIf { it.isNotBlank() } ?: d
    return runCatching {
        when (call.name) {
            "getPlace" -> getPlace(arg(0))
            "getDistance" -> getDistance(arg(0, "base"), arg(1))
            "isOpen" -> isOpen(arg(0), arg(1, "now"))
            "estimateBudget" -> estimateBudget(arg(0, "cheapest"), arg(1, "1"), arg(2, "1"))
            "getPlan" -> getPlan(arg(0, "1"), arg(1, "now"))
            "listAll" -> listAll()
            "getEmergencyInfo" -> getEmergencyInfo()
            "currentTime" -> currentTime()
            "sayIt" -> sayIt(arg(0))
            "fareCheck" -> fareCheck(arg(0, "base"), arg(1), arg(2, "any"))
            "howToRide" -> howToRide(arg(0, "any"))
            "permitsAndPayments" -> permitsAndPayments()
            "findFood" -> findFood(arg(0, "any"), arg(1, "any"))
            "etiquette" -> etiquette(arg(0, "general"))
            "isShutdown" -> isShutdown(arg(0, "today"))
            "scamCheck" -> scamCheck(arg(0))
            "emergency" -> emergency(arg(0, "any"))
            "signalMap" -> signalMap()
            "getBudget" -> getBudget()
            "whatIfSkip" -> whatIfSkip(arg(0))
            "findStay" -> findStay(arg(0, "999999"))
            "getMap" -> getMap()
            else -> "Unknown tool ${call.name}"
        }
    }.getOrElse { "Tool error: ${it.message}" }
}
