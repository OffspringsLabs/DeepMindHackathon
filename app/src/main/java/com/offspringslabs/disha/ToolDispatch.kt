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
    )

    fun toolMenu(): String = SPECS.joinToString("\n") { s -> "- ${s.name}(${s.params.joinToString(", ") { it.first }})" }

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
            else -> "Unknown tool ${call.name}"
        }
    }.getOrElse { "Tool error: ${it.message}" }
}
