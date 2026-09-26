package com.offspringslabs.disha

import org.json.JSONArray
import org.json.JSONObject

// ---------- v1 core (kept) ----------
data class Hotel(val name: String, val area: String, val pricePerNight: Int, val kmFromBase: Double, val note: String, val rating: Double = 0.0, val mapsUri: String = "")
data class Place(
    val name: String, val type: String, val open: String, val close: String,
    val kmFromBase: Double, val minutesFromBase: Int, val fee: Int,
    val lat: Double?, val lon: Double?, val tip: String, val mapsUri: String = "",
)
data class Route(val from: String, val to: String, val km: Double, val minutes: Int, val mode: String)
data class Food(val item: String, val where: String, val price: Int, val localName: String = "", val veg: Boolean? = null, val spice: Int = 0, val mealWindow: String = "", val allergens: String = "")
data class Stop(val time: String, val stop: String)
data class DayPlan(val day: Int, val plan: List<Stop>)
data class Phone(val label: String, val phone: String)
data class Phrase(val intent: String, val english: String, val native: String, val roman: String)
data class Emergency(
    val hospital: String, val police: String, val atm: String, val noSignal: String,
    val pharmacy24h: String = "", val touristPolice: String = "", val embassy: String = "",
    val numbers: List<Phone> = emptyList(), val phrases: List<Phrase> = emptyList(),
)

// ---------- v2 local-reality sections ----------
data class Language(val primary: String, val script: String, val phrases: List<Phrase>, val stationNames: List<Pair<String, String>>)
data class Connectivity2(val deadZones: List<String>, val bestCarrier: String, val tips: String)
data class Fare(val from: String, val to: String, val mode: String, val fairInr: Int, val touristQuoteInr: Int)
data class Transport(val autoRule: String, val fares: List<Fare>, val sharedRoutes: List<String>, val metro: String, val busBoards: String, val lastServiceTimes: List<String>)
data class Permit(val name: String, val whoNeeds: String, val whereToGet: String, val costInr: Int, val leadDays: Int)
data class Payments(val upiCoverage: String, val cashOnly: List<String>, val tolls: String, val permits: List<Permit>, val idNeeded: String)
data class Shutdown(val date: String, val what: String)
data class Culture(val dressCode: String, val footwear: String, val photography: String, val alcohol: String, val holidaysShutdowns: List<Shutdown>, val womenSafety: String, val tips: List<String>)
data class Scam(val pattern: String, val counter: String)
data class Trust(val scams: List<Scam>, val officialGuideRateInr: Int, val officialCounters: List<String>)

// ---------- v2 plan ----------
data class Constraints(val days: Int, val budgetInr: Int, val people: Int, val startCity: String, val pace: String, val interests: List<String>, val diet: String)
data class TransportToRegion(val mode: String, val hours: Double, val costInrPerPerson: Int, val note: String)
data class Stay(val name: String, val area: String, val pricePerNight: Int, val nights: Int, val rating: Double, val mapsUri: String, val lat: Double?, val lon: Double?)
data class PlanStop(val time: String, val name: String, val minutes: Int, val costInr: Int, val travelKm: Double, val travelMin: Int, val mode: String, val lat: Double?, val lon: Double?, val mapsUri: String, val note: String)
data class PlanDay(val day: Int, val theme: String, val stops: List<PlanStop>)
data class Budget(val stay: Int, val food: Int, val transport: Int, val entry: Int, val buffer: Int, val total: Int, val remaining: Int)
data class Alternative(val title: String, val savesInr: Int, val tradeoff: String)
data class OfflineMap(val centerLat: Double, val centerLon: Double, val zoom: Int, val mapsAreaUrl: String)
data class Source(val title: String, val uri: String, val kind: String)
data class TripPlan(
    val constraints: Constraints, val transportToRegion: TransportToRegion, val stay: Stay?,
    val foodPerPersonPerDay: Int, val localTransportInr: Int, val days: List<PlanDay>,
    val budget: Budget, val alternatives: List<Alternative>, val offlineMap: OfflineMap?, val budgetVerified: Boolean, val overBudget: Boolean,
)

/** Structured, offline trip data (v1 + v2). Tools read from it, never from the model's memory. */
data class TripPack(
    val region: String, val state: String, val base: String, val baseLat: Double?, val baseLon: Double?,
    val season: String, val languages: String, val arrival: Map<String, String>,
    val hotels: List<Hotel>, val places: List<Place>, val routes: List<Route>, val food: List<Food>, val days: List<DayPlan>,
    val emergency: Emergency, val tips: List<String>,
    val language: Language?, val connectivity: Connectivity2?, val transport: Transport?, val payments: Payments?,
    val culture: Culture?, val trust: Trust?, val plan: TripPlan?, val sources: List<Source>, val generatedAt: String, val version: Int,
    val raw: String,
) {
    val hasPlan get() = plan != null && plan.days.isNotEmpty()

    /** Short index for the on-device system prompt. */
    fun summary(): String = buildString {
        append("REGION: $region, $state. BASE: $base. SEASON: $season. LANGUAGE: ${language?.primary ?: languages}.\n")
        append("PLACES: ").append(places.joinToString(", ") { it.name }).append('\n')
        append("STAYS: ").append(hotels.joinToString(", ") { it.name }).append('\n')
        append("FOOD: ").append(food.joinToString(", ") { it.item }).append('\n')
        plan?.let { p -> append("TRIP: ${p.constraints.days} days, ₹${p.constraints.budgetInr} budget, ${p.constraints.people} people from ${p.constraints.startCity}; stay ${p.stay?.name ?: "-"}; total ₹${p.budget.total}, remaining ₹${p.budget.remaining}.\n") }
        append("SECTIONS: ").append(listOfNotNull(language?.let { "language phrases" }, transport?.let { "fares & transport rules" }, payments?.let { "payments & permits" }, culture?.let { "culture & safety" }, trust?.let { "scams" }, "emergency").joinToString(", "))
    }

    /** Compact full text: fallback context for models without tools. */
    fun compactText(): String = buildString {
        append(summary()).append('\n')
        arrival.forEach { (k, v) -> append("ARRIVAL $k: $v\n") }
        hotels.forEach { append("STAY: ${it.name} | ${it.area} | ₹${it.pricePerNight}/night | ${it.kmFromBase} km | ${it.note}\n") }
        places.forEach { append("PLACE: ${it.name} | ${it.type} | ${it.open}-${it.close} | ${it.kmFromBase} km / ${it.minutesFromBase} min | fee ₹${it.fee} | ${it.tip}\n") }
        routes.forEach { append("ROUTE: ${it.from} → ${it.to} | ${it.km} km | ${it.minutes} min | ${it.mode}\n") }
        food.forEach { append("FOOD: ${it.item} (${it.localName}) | ${it.where} | ₹${it.price} | ${if (it.veg == true) "veg" else if (it.veg == false) "non-veg" else ""} spice ${it.spice}\n") }
        plan?.days?.forEach { d -> append("DAY${d.day} ${d.theme}: ").append(d.stops.joinToString(" → ") { "${it.time} ${it.name} (${it.minutes}m, ₹${it.costInr})" }).append('\n') }
        plan?.let { append("BUDGET: stay ₹${it.budget.stay}, food ₹${it.budget.food}, transport ₹${it.budget.transport}, entry ₹${it.budget.entry}, buffer ₹${it.budget.buffer}, total ₹${it.budget.total}, remaining ₹${it.budget.remaining}\n") }
        transport?.let { t -> append("AUTO RULE: ${t.autoRule}\n"); t.fares.forEach { append("FARE: ${it.from} → ${it.to} by ${it.mode}: fair ₹${it.fairInr}, tourists quoted ₹${it.touristQuoteInr}\n") } }
        language?.phrases?.forEach { append("PHRASE ${it.intent}: ${it.english} = ${it.native} (${it.roman})\n") }
        trust?.scams?.forEach { append("SCAM: ${it.pattern} → ${it.counter}\n") }
        payments?.permits?.forEach { append("PERMIT: ${it.name} | ${it.whoNeeds} | ${it.whereToGet} | ₹${it.costInr} | ${it.leadDays} days\n") }
        culture?.let { append("CULTURE: dress ${it.dressCode}; footwear ${it.footwear}; photos ${it.photography}; alcohol ${it.alcohol}\n") }
        append("EMERGENCY: ${emergency.hospital} | pharmacy ${emergency.pharmacy24h} | police ${emergency.police} | ATM ${emergency.atm} | no signal: ${emergency.noSignal}\n")
        tips.forEach { append("TIP: $it\n") }
    }

    companion object {
        fun parse(json: String): TripPack {
            val o = JSONObject(json)
            fun JSONObject.str(k: String, d: String = "") = optString(k, d)
            fun JSONObject.dbl(k: String, d: Double = 0.0) = optDouble(k, d)
            fun JSONObject.int(k: String, d: Int = 0) = optInt(k, d)
            fun JSONObject.dblOrNull(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() && it != 0.0 } else null
            fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> = if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
            fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
            fun phrases(a: JSONArray?) = a.mapObj { Phrase(it.str("intent"), it.str("english"), it.str("native"), it.str("roman")) }

            val arrival = o.optJSONObject("arrival")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } } ?: emptyMap()
            val em = o.optJSONObject("emergency") ?: JSONObject()
            val lang = o.optJSONObject("language")?.let { l ->
                Language(l.str("primary"), l.str("script"), phrases(l.optJSONArray("phrases")), l.optJSONArray("stationNames").mapObj { it.str("native") to it.str("english") })
            }
            val conn = o.optJSONObject("connectivity")?.let { Connectivity2(it.optJSONArray("deadZones").strs(), it.str("bestCarrier"), it.str("tips")) }
            val trans = o.optJSONObject("transport")?.let { t ->
                Transport(t.str("autoRule"), t.optJSONArray("fares").mapObj { Fare(it.str("from"), it.str("to"), it.str("mode"), it.int("fairInr"), it.int("touristQuoteInr")) },
                    t.optJSONArray("sharedRoutes").strs(), t.str("metro"), t.str("busBoards"), t.optJSONArray("lastServiceTimes").strs())
            }
            val pay = o.optJSONObject("payments")?.let { p ->
                Payments(p.str("upiCoverage"), p.optJSONArray("cashOnly").strs(), p.str("tolls"),
                    p.optJSONArray("permits").mapObj { Permit(it.str("name"), it.str("whoNeeds"), it.str("whereToGet"), it.int("costInr"), it.int("leadDays")) }, p.str("idNeeded"))
            }
            val cul = o.optJSONObject("culture")?.let { c ->
                Culture(c.str("dressCode"), c.str("footwear"), c.str("photography"), c.str("alcohol"),
                    c.optJSONArray("holidaysShutdowns").mapObj { Shutdown(it.str("date"), it.str("what")) }, c.str("womenSafety"), c.optJSONArray("tips").strs())
            }
            val trust = o.optJSONObject("trust")?.let { t ->
                Trust(t.optJSONArray("scams").mapObj { Scam(it.str("pattern"), it.str("counter")) }, t.int("officialGuideRateInr"), t.optJSONArray("officialCounters").strs())
            }
            val plan = o.optJSONObject("plan")?.let { p ->
                val c = p.optJSONObject("constraints") ?: JSONObject()
                val ttr = p.optJSONObject("transportToRegion") ?: JSONObject()
                val st = p.optJSONObject("stay")
                val b = p.optJSONObject("budget") ?: JSONObject()
                val om = p.optJSONObject("offlineMap")
                TripPlan(
                    constraints = Constraints(c.int("days", 1), c.int("budgetInr"), c.int("people", 1), c.str("startCity", "Hyderabad"), c.str("pace", "balanced"), c.optJSONArray("interests").strs(), c.str("diet", "any")),
                    transportToRegion = TransportToRegion(ttr.str("mode"), ttr.dbl("hours"), ttr.int("costInrPerPerson"), ttr.str("note")),
                    stay = st?.let { Stay(it.str("name"), it.str("area"), it.int("pricePerNight"), it.int("nights"), it.dbl("rating"), it.str("mapsUri"), it.dblOrNull("lat"), it.dblOrNull("lon")) },
                    foodPerPersonPerDay = p.int("foodPerPersonPerDay"), localTransportInr = p.int("localTransportInr"),
                    days = p.optJSONArray("days").mapObj { d ->
                        PlanDay(d.int("day", 1), d.str("theme"), d.optJSONArray("stops").mapObj {
                            PlanStop(it.str("time"), it.str("name"), it.int("minutes"), it.int("costInr"), it.dbl("travelKm"), it.int("travelMin"), it.str("mode"), it.dblOrNull("lat"), it.dblOrNull("lon"), it.str("mapsUri"), it.str("note"))
                        })
                    },
                    budget = Budget(b.int("stay"), b.int("food"), b.int("transport"), b.int("entry"), b.int("buffer"), b.int("total"), b.int("remaining")),
                    alternatives = p.optJSONArray("alternatives").mapObj { Alternative(it.str("title"), it.int("savesInr"), it.str("tradeoff")) },
                    offlineMap = om?.let { OfflineMap(it.dbl("centerLat"), it.dbl("centerLon"), it.int("zoom", 12), it.str("mapsAreaUrl")) },
                    budgetVerified = p.optBoolean("budgetVerified", false), overBudget = p.optBoolean("overBudget", false),
                )
            }
            val planDays = o.optJSONArray("days").mapObj { d -> DayPlan(d.int("day", 1), d.optJSONArray("plan").mapObj { Stop(it.str("time"), it.str("stop")) }) }
                .ifEmpty { plan?.days?.map { d -> DayPlan(d.day, d.stops.map { Stop(it.time, it.name) }) } ?: emptyList() }

            return TripPack(
                region = o.str("region"), state = o.str("state"), base = o.str("base"), baseLat = o.dblOrNull("baseLat"), baseLon = o.dblOrNull("baseLon"),
                season = o.str("season"), languages = o.str("languages"), arrival = arrival,
                hotels = o.optJSONArray("hotels").mapObj { Hotel(it.str("name"), it.str("area"), it.int("pricePerNight"), it.dbl("kmFromBase"), it.str("note"), it.dbl("rating"), it.str("mapsUri")) },
                places = o.optJSONArray("places").mapObj {
                    Place(it.str("name"), it.str("type"), it.str("open", "00:00"), it.str("close", "23:59"), it.dbl("kmFromBase"), it.int("minutesFromBase"), it.int("fee"), it.dblOrNull("lat"), it.dblOrNull("lon"), it.str("tip"), it.str("mapsUri"))
                },
                routes = o.optJSONArray("routes").mapObj { Route(it.str("from"), it.str("to"), it.dbl("km"), it.int("minutes"), it.str("mode")) },
                food = o.optJSONArray("food").mapObj { Food(it.str("item"), it.str("where"), it.int("price"), it.str("localName"), if (it.has("veg")) it.optBoolean("veg") else null, it.int("spice"), it.str("mealWindow"), it.str("allergens")) },
                days = planDays,
                emergency = Emergency(em.str("hospital"), em.str("police", "112"), em.str("atm"), em.str("noSignal"), em.str("pharmacy24h"), em.str("touristPolice"), em.str("embassy"),
                    em.optJSONArray("numbers").mapObj { Phone(it.str("label"), it.str("phone")) }, phrases(em.optJSONArray("phrases"))),
                tips = o.optJSONArray("tips").strs(),
                language = lang, connectivity = conn, transport = trans, payments = pay, culture = cul, trust = trust, plan = plan,
                sources = o.optJSONArray("sources").mapObj { Source(it.str("title"), it.str("uri"), it.str("kind", "web")) },
                generatedAt = o.str("generatedAt"), version = o.int("version", 1), raw = json,
            )
        }
    }
}
