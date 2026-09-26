package com.offspringslabs.disha

import org.json.JSONArray
import org.json.JSONObject

data class Hotel(val name: String, val area: String, val pricePerNight: Int, val kmFromBase: Double, val note: String)
data class Place(
    val name: String, val type: String, val open: String, val close: String,
    val kmFromBase: Double, val minutesFromBase: Int, val fee: Int,
    val lat: Double?, val lon: Double?, val tip: String
)
data class Route(val from: String, val to: String, val km: Double, val minutes: Int, val mode: String)
data class Food(val item: String, val where: String, val price: Int)
data class Stop(val time: String, val stop: String)
data class DayPlan(val day: Int, val plan: List<Stop>)
data class Emergency(val hospital: String, val police: String, val atm: String, val noSignal: String)

/** Structured, offline trip data. Parsed once from JSON; tools read from it, never from the model's memory. */
data class TripPack(
    val region: String,
    val state: String,
    val base: String,
    val season: String,
    val languages: String,
    val arrival: Map<String, String>,
    val hotels: List<Hotel>,
    val places: List<Place>,
    val routes: List<Route>,
    val food: List<Food>,
    val days: List<DayPlan>,
    val emergency: Emergency,
    val tips: List<String>,
    val raw: String,
) {
    /** Short index for the on-device system prompt: names only, so the 4k context stays free for reasoning. */
    fun summary(): String = buildString {
        append("REGION: $region, $state. BASE: $base. SEASON: $season.\n")
        append("PLACES: ").append(places.joinToString(", ") { it.name }).append('\n')
        append("HOTELS: ").append(hotels.joinToString(", ") { it.name }).append('\n')
        append("FOOD: ").append(food.joinToString(", ") { it.item }).append('\n')
        append("DAYS PLANNED: ${days.size}")
    }

    /** Compact full text, used as fallback context when tool calling is unavailable. */
    fun compactText(): String = buildString {
        append(summary()).append('\n')
        arrival.forEach { (k, v) -> append("ARRIVAL $k: $v\n") }
        hotels.forEach { append("HOTEL: ${it.name} | ${it.area} | ₹${it.pricePerNight}/night | ${it.kmFromBase} km from $base | ${it.note}\n") }
        places.forEach { append("PLACE: ${it.name} | ${it.type} | ${it.open}-${it.close} | ${it.kmFromBase} km / ${it.minutesFromBase} min from $base | fee ₹${it.fee} | ${it.tip}\n") }
        routes.forEach { append("ROUTE: ${it.from} → ${it.to} | ${it.km} km | ${it.minutes} min | ${it.mode}\n") }
        food.forEach { append("FOOD: ${it.item} | ${it.where} | ₹${it.price}\n") }
        days.forEach { d -> append("DAY${d.day}: ").append(d.plan.joinToString(" → ") { "${it.time} ${it.stop}" }).append('\n') }
        append("EMERGENCY: ${emergency.hospital} | police ${emergency.police} | ATM ${emergency.atm} | no signal: ${emergency.noSignal}\n")
        tips.forEach { append("TIP: $it\n") }
    }

    companion object {
        fun parse(json: String): TripPack {
            val o = JSONObject(json)
            fun JSONObject.str(k: String, d: String = "") = optString(k, d)
            fun JSONObject.dbl(k: String, d: Double = 0.0) = optDouble(k, d)
            fun JSONObject.int(k: String, d: Int = 0) = optInt(k, d)
            fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
                if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }

            val arrival = o.optJSONObject("arrival")?.let { a ->
                a.keys().asSequence().associateWith { a.optString(it) }
            } ?: emptyMap()
            val em = o.optJSONObject("emergency") ?: JSONObject()
            val tipsArr = o.optJSONArray("tips")
            return TripPack(
                region = o.str("region"),
                state = o.str("state"),
                base = o.str("base"),
                season = o.str("season"),
                languages = o.str("languages"),
                arrival = arrival,
                hotels = o.optJSONArray("hotels").mapObj {
                    Hotel(it.str("name"), it.str("area"), it.int("pricePerNight"), it.dbl("kmFromBase"), it.str("note"))
                },
                places = o.optJSONArray("places").mapObj {
                    Place(
                        it.str("name"), it.str("type"), it.str("open", "00:00"), it.str("close", "23:59"),
                        it.dbl("kmFromBase"), it.int("minutesFromBase"), it.int("fee"),
                        if (it.has("lat")) it.dbl("lat") else null, if (it.has("lon")) it.dbl("lon") else null,
                        it.str("tip")
                    )
                },
                routes = o.optJSONArray("routes").mapObj {
                    Route(it.str("from"), it.str("to"), it.dbl("km"), it.int("minutes"), it.str("mode"))
                },
                food = o.optJSONArray("food").mapObj { Food(it.str("item"), it.str("where"), it.int("price")) },
                days = o.optJSONArray("days").mapObj { d ->
                    DayPlan(d.int("day", 1), d.optJSONArray("plan").mapObj { Stop(it.str("time"), it.str("stop")) })
                },
                emergency = Emergency(em.str("hospital"), em.str("police", "112"), em.str("atm"), em.str("noSignal")),
                tips = if (tipsArr == null) emptyList() else (0 until tipsArr.length()).map { tipsArr.optString(it) },
                raw = json,
            )
        }
    }
}
