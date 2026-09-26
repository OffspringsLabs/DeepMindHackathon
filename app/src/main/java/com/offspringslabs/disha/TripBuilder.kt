package com.offspringslabs.disha

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Builds a complete offline trip (pack v2) for any Indian destination under days/budget constraints.
 * Research: Google Maps grounding (places, stays, fares) ∥ Google Search (official facts, rules, phrases, scams, emergency)
 * → code execution (itinerary + budget arithmetic) → structured JSON → deterministic re-verification → saved for the on-device model.
 * Kotlin twin of tools/gen-pack.mjs.
 */
class TripBuilder(private val gemini: GeminiClient, private val store: TripPackStore) {
    data class Result(val id: String, val pack: TripPack, val fixes: List<String>)

    val stages = listOf("Finding real places & stays on Google Maps", "Searching official timings, fares, permits, scams", "Building the itinerary & budget with code", "Structuring the offline pack", "Verifying every rupee", "Saving for offline use")

    suspend fun build(destination: String, c: Constraints, progress: (Int) -> Unit): Result = coroutineScope {
        val base = research(destination, c)
        progress(0)
        val maps = async { gemini.generate(gemini.single(base + "\n\nFOCUS NOW (Google Maps): sections 1-4 and stay/food/fare facts — real rated places with prices, hours, coordinates and Maps links."), builtins = listOf("google_maps"), temperature = 0.4) }
        val web = async { gemini.generate(gemini.single(base + "\n\nFOCUS NOW (Google Search): sections 7-13 — official timings & fees, transport rules and fair fares, permits, food, culture/holidays, scams, emergency contacts, native-script phrases."), builtins = listOf("google_search"), temperature = 0.4) }
        val m = maps.await(); progress(1)
        val w = web.await(); progress(2)
        val calc = gemini.generate(
            gemini.single("Using code execution, build the ${c.days}-day itinerary (section 5) and compute the BUDGET (section 6) exactly as specified, from these notes. Print every budget line and the itinerary with per-stop minutes, ₹ per person, and travel km/min.\n\nCONSTRAINTS: ${c.json()}\n\nNOTES A (Maps):\n${m.text}\n\nNOTES B (Search):\n${w.text}"),
            builtins = listOf("code_execution"), temperature = 0.2,
        )
        progress(3)
        val notes = "${m.text}\n\n${w.text}\n\nITINERARY & BUDGET:\n${calc.text}\n${calc.codeOutput}"
        val j = gemini.generate(gemini.single(toJson(notes, destination, c)), jsonMode = true, temperature = 0.1)
        val obj = JSONObject(strip(j.text))
        progress(4)
        // fix-ups + verification
        obj.put("region", destination); obj.put("generatedAt", Instant.now().toString()); obj.put("version", 2)
        obj.put("sources", JSONArray().apply { (m.sources + w.sources).take(20).forEach { put(JSONObject().put("title", it.title).put("uri", it.uri).put("kind", it.kind)) } })
        val fixes = ArrayList<String>()
        val plan = obj.optJSONObject("plan")
        if (plan != null) {
            plan.put("constraints", JSONObject(c.json()))
            val om = plan.optJSONObject("offlineMap") ?: JSONObject().also { plan.put("offlineMap", it) }
            val lat = om.optDouble("centerLat").takeIf { !it.isNaN() && it != 0.0 } ?: obj.optDouble("baseLat", 0.0)
            val lon = om.optDouble("centerLon").takeIf { !it.isNaN() && it != 0.0 } ?: obj.optDouble("baseLon", 0.0)
            om.put("centerLat", lat).put("centerLon", lon).put("zoom", om.optInt("zoom", 12)).put("mapsAreaUrl", "https://www.google.com/maps/@$lat,$lon,${om.optInt("zoom", 12)}z")
            val parsed = TripPack.parse(obj.toString()).plan
            if (parsed != null) {
                val mine = PlanMath.recompute(parsed)
                val b = plan.optJSONObject("budget") ?: JSONObject()
                mapOf("stay" to mine.stay, "food" to mine.food, "transport" to mine.transport, "entry" to mine.entry, "buffer" to mine.buffer, "total" to mine.total, "remaining" to mine.remaining).forEach { (k, v) ->
                    if (kotlin.math.abs(b.optInt(k) - v) > 1) fixes += "$k: model ₹${b.optInt(k)} → ₹$v"
                    b.put(k, v)
                }
                plan.put("budget", b).put("budgetVerified", true).put("overBudget", mine.remaining < 0)
            }
            // v1 compatibility days[]
            val days = JSONArray()
            plan.optJSONArray("days")?.let { ds -> for (i in 0 until ds.length()) { val d = ds.getJSONObject(i); val stops = JSONArray(); d.optJSONArray("stops")?.let { ss -> for (k in 0 until ss.length()) { val s = ss.getJSONObject(k); stops.put(JSONObject().put("time", s.optString("time")).put("stop", s.optString("name"))) } }; days.put(JSONObject().put("day", d.optInt("day", i + 1)).put("plan", stops)) } }
            obj.put("days", days)
        }
        progress(5)
        val id = slug(destination)
        val json = obj.toString(2)
        val pack = TripPack.parse(json)
        store.saveTrip(id, destination, json)
        Log.i("TripBuilder", "built $id: ${pack.places.size} places, plan ${pack.plan?.days?.size} days, total ₹${pack.plan?.budget?.total}; fixes=$fixes")
        Result(id, pack, fixes)
    }

    private fun strip(s: String): String { var t = s.trim(); if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").trim(); if (t.endsWith("```")) t = t.removeSuffix("```").trim(); return t }

    companion object {
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

        fun Constraints.json(): String = JSONObject().put("days", days).put("budgetInr", budgetInr).put("people", people).put("startCity", startCity).put("pace", pace).put("interests", JSONArray(interests)).put("diet", diet).toString()

        const val SHAPE = """{"region":"","state":"","base":"","baseLat":0,"baseLon":0,"season":"","languages":"",
 "arrival":{"rail":"","air":"","fromStart":""},
 "hotels":[{"name":"","area":"","pricePerNight":0,"kmFromBase":0,"rating":0,"mapsUri":"","note":""}],
 "places":[{"name":"","type":"","open":"HH:MM","close":"HH:MM","kmFromBase":0,"minutesFromBase":0,"fee":0,"lat":0,"lon":0,"mapsUri":"","tip":""}],
 "routes":[{"from":"","to":"","km":0,"minutes":0,"mode":""}],
 "food":[{"item":"","localName":"","where":"","price":0,"veg":true,"spice":1,"mealWindow":"","allergens":""}],
 "language":{"primary":"","script":"","phrases":[{"intent":"","english":"","native":"","roman":""}],"stationNames":[{"native":"","english":""}]},
 "connectivity":{"deadZones":[""],"bestCarrier":"","tips":""},
 "transport":{"autoRule":"","fares":[{"from":"","to":"","mode":"","fairInr":0,"touristQuoteInr":0}],"sharedRoutes":[""],"metro":"","busBoards":"","lastServiceTimes":[""]},
 "payments":{"upiCoverage":"","cashOnly":[""],"tolls":"","permits":[{"name":"","whoNeeds":"","whereToGet":"","costInr":0,"leadDays":0}],"idNeeded":""},
 "culture":{"dressCode":"","footwear":"","photography":"","alcohol":"","holidaysShutdowns":[{"date":"","what":""}],"womenSafety":"","tips":[""]},
 "trust":{"scams":[{"pattern":"","counter":""}],"officialGuideRateInr":0,"officialCounters":[""]},
 "emergency":{"hospital":"","pharmacy24h":"","police":"112","touristPolice":"","embassy":"","atm":"","noSignal":"","numbers":[{"label":"","phone":""}],"phrases":[{"english":"","native":"","roman":""}]},
 "tips":[""],
 "plan":{"constraints":{"days":0,"budgetInr":0,"people":0,"startCity":"","pace":"","interests":[""],"diet":""},
   "transportToRegion":{"mode":"","hours":0,"costInrPerPerson":0,"note":""},
   "stay":{"name":"","area":"","pricePerNight":0,"nights":0,"rating":0,"mapsUri":"","lat":0,"lon":0},
   "foodPerPersonPerDay":0,"localTransportInr":0,
   "days":[{"day":1,"theme":"","stops":[{"time":"HH:MM","name":"","minutes":0,"costInr":0,"travelKm":0,"travelMin":0,"mode":"","lat":0,"lon":0,"mapsUri":"","note":""}]}],
   "budget":{"stay":0,"food":0,"transport":0,"entry":0,"buffer":0,"total":0,"remaining":0},
   "alternatives":[{"title":"","savesInr":0,"tradeoff":""}],
   "offlineMap":{"centerLat":0,"centerLon":0,"zoom":12,"mapsAreaUrl":""}}}"""

        fun research(dest: String, c: Constraints) = """
You are TravelFreak, a top-efficiency Indian travel planner. Plan ${c.days} days in $dest, India for ${c.people} traveller(s) from ${c.startCity}, total budget ₹${c.budgetInr} (everything except shopping), pace ${c.pace}, interests ${c.interests.joinToString(", ")}, diet ${c.diet}.
Deliver research notes with concrete numbers in these sections:
1 BASE & ARRIVAL: base town, lat/lon, nearest rail/air, how to reach from ${c.startCity} (mode, hours, ₹ per person one way), best season, languages spoken.
2 STAYS: 4-5 real stays across budgets with ₹/night, rating, distance from base; pick ONE for this budget and say why.
3 PLACES: 8-12 must-sees with type, open/close, km & minutes from base, entry ₹, lat/lon, one tip.
4 ROUTES: 6-10 point-to-point legs with km, minutes, mode and frequency.
5 ITINERARY: ${c.days} day(s), time-ordered, geographically clustered to avoid backtracking, respecting opening hours and meal windows; per stop: minutes, per-person ENTRY/ACTIVITY cost only (₹0 for meals, check-in, transfers — food is budgeted separately), travel from previous stop (km, min, mode). Day themes.
6 BUDGET: rooms = ceil(people/2); stay = ₹/night × nights × rooms; food = ₹/person/day × people × days; transport = per-person round trip × people + local transport total; entry = sum of per-person entry/activity costs of sight stops × people (meals and logistics excluded); buffer = 10% of subtotal; total; remaining vs ₹${c.budgetInr}. If over budget give 2-3 concrete swaps with ₹ saved; if under, one worthwhile upgrade.
7 LANGUAGE: primary language + script; 12 traveller phrases (where is / how much / too expensive / hospital / police / vegetarian / no spice / stop here / bus to X / water / help / thank you) each with English, NATIVE SCRIPT and roman transliteration; 6 station/place names in native script.
8 LOCAL TRANSPORT RULES: prepaid stand vs meter vs haggle; 6+ fair fares (from, to, mode, fair ₹, typical tourist quote ₹); shared routes; metro/bus board conventions; last service times.
9 PAYMENTS & PERMITS: UPI coverage, cash-only spots, tolls, permits (who needs, where, ₹, lead days), ID needed.
10 FOOD: 6 dishes with local name, where, ₹, veg?, spice 1-3, meal window, allergens; how to ask for ${c.diet}.
11 CULTURE & SAFETY: dress code, footwear, photography, alcohol rules, holidays/shutdowns in the next 60 days with dates, women's safety notes, 4 tips.
12 TRUST: 4+ common scams near the sights with the counter-move; official guide rate ₹; official counters.
13 EMERGENCY: hospital, 24h pharmacy, police, tourist police, nearest embassy/consulate city, ATMs, no-signal stretches, phone numbers, 4 emergency phrases in the native script.
Prefer approximate numbers over omissions. Never invent hotel names; if unsure, describe the type of stay.
""".trimIndent()

        fun toJson(notes: String, dest: String, c: Constraints) = """
Convert the research notes into ONE JSON object with EXACTLY this shape and key names. Numbers must be numbers. Times "HH:MM" 24h. Dates "YYYY-MM-DD". Keep strings short. region = "$dest". Fill plan.constraints from ${c.json()}. Output JSON only.
$SHAPE

RESEARCH NOTES:
$notes
""".trimIndent()
    }
}
