package com.offspringslabs.disha

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic tools over the cached trip pack. The on-device model decides WHICH tool to call;
 * every number in the answer comes from here, not from the model's memory.
 * All params are strings so the schema is trivially satisfiable by a 2B model.
 */
class TripTools(
    private val pack: TripPack,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : ToolSet {

    var trace: (String) -> Unit = {}
    /** Precompiled UI component chosen as a by-product of the tool the agent picked. */
    var onCard: (UiCard) -> Unit = {}

    @Tool(description = "Details of one place, hotel or eatery from the trip pack: hours, distance from base, fee, price, tips.")
    fun getPlace(@ToolParam(description = "Name, partial is fine") name: String): String {
        trace("getPlace($name)")
        matchPlace(name)?.let { p ->
            onCard(UiCard.PlaceInfo(p.name, p.type, "${p.open}–${p.close}", "${fmt(p.kmFromBase)} km · ${p.minutesFromBase} min from ${pack.base}", if (p.fee > 0) "₹${p.fee} entry" else "Free entry", p.tip, p.lat, p.lon))
            return "${p.name} (${p.type}): open ${p.open}-${p.close}, ${fmt(p.kmFromBase)} km / ${p.minutesFromBase} min from ${pack.base}, entry ₹${p.fee}. ${p.tip}"
        }
        matchHotel(name)?.let { h ->
            onCard(UiCard.PlaceInfo(h.name, "stay · ${h.area}", null, "${fmt(h.kmFromBase)} km from ${pack.base}", "₹${h.pricePerNight} / night", h.note, null, null))
            return "${h.name} (hotel) in ${h.area}: about ₹${h.pricePerNight} per night, ${fmt(h.kmFromBase)} km from ${pack.base}. ${h.note}"
        }
        pack.food.firstOrNull { sim(it.item, name) > 0.5 || sim(it.where, name) > 0.5 }?.let { f ->
            onCard(UiCard.PlaceInfo(f.item, "food", null, f.where, "₹${f.price}", "", null, null))
            return "${f.item} at ${f.where}, about ₹${f.price}."
        }
        return "Not found in pack. Known: " + (pack.places.map { it.name } + pack.hotels.map { it.name }).joinToString(", ")
    }

    @Tool(description = "Distance in km and travel time in minutes between two places or 'base'. Use for how far / how long questions.")
    fun getDistance(
        @ToolParam(description = "Start: place name or 'base'") from: String,
        @ToolParam(description = "Destination place name or 'base'") to: String,
    ): String {
        trace("getDistance($from → $to)")
        val a = resolve(from)
        val b = resolve(to)
        if (a == null || b == null) return "Unknown place: ${if (a == null) from else to}. Known: ${pack.places.joinToString(", ") { it.name }}"
        if (a.name.equals(b.name, true)) return "${a.name} to ${b.name}: 0 km."

        pack.routes.firstOrNull { (sim(it.from, a.name) > 0.6 && sim(it.to, b.name) > 0.6) || (sim(it.from, b.name) > 0.6 && sim(it.to, a.name) > 0.6) }
            ?.let {
                onCard(UiCard.Route(a.name, b.name, it.km, it.minutes, it.mode, estimated = false))
                return "${a.name} to ${b.name}: ${fmt(it.km)} km, about ${it.minutes} min. ${it.mode}"
            }

        if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) {
            val km = haversineKm(a.lat, a.lon, b.lat, b.lon) * 1.3 // road factor
            val min = (km / 35.0 * 60).roundToInt()
            onCard(UiCard.Route(a.name, b.name, km, min, "Estimated by road from coordinates", estimated = true))
            return "${a.name} to ${b.name}: about ${fmt(km)} km by road, roughly $min min (estimated from coordinates)."
        }
        // Fall back: both legs via base.
        val km = a.kmFromBase + b.kmFromBase
        val min = a.minutesFromBase + b.minutesFromBase
        onCard(UiCard.Route(a.name, b.name, km, min, "Via ${pack.base}, no direct route in pack", estimated = true))
        return "${a.name} to ${b.name}: about ${fmt(km)} km, roughly $min min going via ${pack.base} (no direct route in pack)."
    }

    @Tool(description = "Whether a place is open at a time, and minutes until it closes or opens.")
    fun isOpen(
        @ToolParam(description = "Place name") name: String,
        @ToolParam(description = "Time as HH:MM in 24h, or 'now'") time: String,
    ): String {
        trace("isOpen($name @ $time)")
        val p = matchPlace(name) ?: return "Unknown place: $name"
        val t = parseTime(time) ?: now().toLocalTime()
        val open = parseTime(p.open) ?: LocalTime.MIDNIGHT
        val close = parseTime(p.close) ?: LocalTime.of(23, 59)
        val tm = t.toSecondOfDay() / 60
        val om = open.toSecondOfDay() / 60
        val cm = close.toSecondOfDay() / 60
        val overnight = cm <= om
        val isOpen = if (!overnight) tm in om until cm else (tm >= om || tm < cm)
        val label = "%02d:%02d".format(t.hour, t.minute)
        return if (isOpen) {
            val untilClose = if (cm > tm) cm - tm else (cm + 1440 - tm)
            onCard(UiCard.OpenStatus(p.name, true, label, "${p.open}–${p.close}", "closes in ${hm(untilClose)}", p.type))
            "${p.name} is OPEN at $label. Closes ${p.close}, in ${hm(untilClose)}."
        } else {
            val untilOpen = if (om > tm) om - tm else (om + 1440 - tm)
            onCard(UiCard.OpenStatus(p.name, false, label, "${p.open}–${p.close}", "opens in ${hm(untilOpen)}", p.type))
            "${p.name} is CLOSED at $label. Opens ${p.open}, in ${hm(untilOpen)}. Hours ${p.open}-${p.close}."
        }
    }

    @Tool(description = "Rupee budget for a stay: hotel nights plus food and entry fees per person, with the arithmetic shown.")
    fun estimateBudget(
        @ToolParam(description = "Hotel name, partial ok, or 'cheapest'") hotel: String,
        @ToolParam(description = "Number of nights, e.g. 2") nights: String,
        @ToolParam(description = "Number of people, e.g. 2") people: String,
    ): String {
        trace("estimateBudget($hotel, $nights nights, $people people)")
        val n = nights.filter { it.isDigit() }.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val ppl = people.filter { it.isDigit() }.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val h = if (hotel.contains("cheap", true)) pack.hotels.minByOrNull { it.pricePerNight } else matchHotel(hotel)
            ?: pack.hotels.minByOrNull { it.pricePerNight }
        if (h == null) return "No hotels in pack."
        val rooms = (ppl + 1) / 2
        val stay = h.pricePerNight * n * rooms
        val foodPerDay = pack.food.takeIf { it.isNotEmpty() }?.let { f -> f.sumOf { it.price } / f.size * 3 } ?: 600
        val food = foodPerDay * (n + 1) * ppl
        val fees = pack.places.sumOf { it.fee } * ppl
        val total = stay + food + fees
        onCard(UiCard.Budget(
            "$ppl people · $n night${if (n > 1) "s" else ""} · ${h.name}",
            listOf("Stay ₹${h.pricePerNight} × $n × $rooms room${if (rooms > 1) "s" else ""}" to stay, "Food ₹$foodPerDay/person/day × ${n + 1} days × $ppl" to food, "Entry fees × $ppl" to fees),
            total, "Transport not included",
        ))
        return "Budget for $ppl people, $n nights at ${h.name}: stay ₹${h.pricePerNight} × $n nights × $rooms room(s) = ₹$stay; food about ₹$foodPerDay/person/day × ${n + 1} days × $ppl = ₹$food; entry fees ₹$fees. TOTAL ≈ ₹$total (excluding transport)."
    }

    @Tool(description = "Itinerary for a day number, and the next stop after a time. Use for what's next / schedule questions.")
    fun getPlan(
        @ToolParam(description = "Day number starting at 1") day: String,
        @ToolParam(description = "Time HH:MM or 'now'") time: String,
    ): String {
        trace("getPlan(day $day @ $time)")
        val d = day.filter { it.isDigit() }.toIntOrNull() ?: 1
        val plan = pack.days.firstOrNull { it.day == d } ?: pack.days.firstOrNull() ?: return "No day plan in pack."
        val t = parseTime(time) ?: now().toLocalTime()
        val next = plan.plan.firstOrNull { s -> (parseTime(s.time) ?: LocalTime.MIDNIGHT) > t }
        onCard(UiCard.Plan(plan.day, plan.plan, plan.plan.indexOf(next), "%02d:%02d".format(t.hour, t.minute)))
        val full = plan.plan.joinToString(" → ") { "${it.time} ${it.stop}" }
        return "Day ${plan.day}: $full. " + (next?.let { "Next stop after %02d:%02d: ${it.time} ${it.stop}.".format(t.hour, t.minute) } ?: "No more stops today.")
    }

    @Tool(description = "List everything in the pack: places with type, hotels with price, food, routes.")
    fun listAll(): String {
        trace("listAll()")
        onCard(UiCard.Inventory(
            pack.places.map { it.name to "${it.type} · ${fmt(it.kmFromBase)} km" },
            pack.hotels.map { it.name to "₹${it.pricePerNight}/night" },
            pack.food.map { it.item to "₹${it.price}" },
        ))
        return buildString {
            append("Places: ").append(pack.places.joinToString("; ") { "${it.name} (${it.type}, ${fmt(it.kmFromBase)} km)" }).append(". ")
            append("Hotels: ").append(pack.hotels.joinToString("; ") { "${it.name} ₹${it.pricePerNight}" }).append(". ")
            append("Food: ").append(pack.food.joinToString("; ") { "${it.item} ₹${it.price}" }).append(". ")
            append("Routes: ").append(pack.routes.joinToString("; ") { "${it.from}→${it.to} ${fmt(it.km)} km" }).append('.')
        }
    }

    @Tool(description = "Safety and practical info: hospital, police, ATMs, areas with no mobile signal, how to reach the region.")
    fun getEmergencyInfo(): String {
        trace("getEmergencyInfo()")
        val e = pack.emergency
        onCard(UiCard.Emergency(e.hospital, e.police, e.atm, e.noSignal, pack.arrival.entries.map { it.key to it.value }))
        val arrival = pack.arrival.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "Hospital: ${e.hospital}. Police: ${e.police}. ATMs: ${e.atm}. No signal: ${e.noSignal}. Arrival: $arrival. Tips: ${pack.tips.joinToString(" ")}"
    }

    @Tool(description = "Current local time HH:MM and weekday from the phone clock.")
    fun currentTime(): String {
        trace("currentTime()")
        val n = now()
        return "%02d:%02d, %s".format(n.hour, n.minute, n.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
    }

    // ---- helpers ----

    private data class Node(val name: String, val kmFromBase: Double, val minutesFromBase: Int, val lat: Double?, val lon: Double?)

    private fun resolve(q: String): Node? {
        val s = q.trim()
        if (s.isEmpty() || s.equals("base", true) || sim(s, pack.base) > 0.6) return Node(pack.base, 0.0, 0, null, null)
        matchPlace(s)?.let { return Node(it.name, it.kmFromBase, it.minutesFromBase, it.lat, it.lon) }
        matchHotel(s)?.let { return Node(it.name, it.kmFromBase, (it.kmFromBase * 2).roundToInt(), null, null) }
        return null
    }

    private fun matchPlace(q: String): Place? = pack.places.maxByOrNull { sim(it.name, q) }?.takeIf { sim(it.name, q) > 0.34 }
    private fun matchHotel(q: String): Hotel? = pack.hotels.maxByOrNull { sim(it.name, q) }?.takeIf { sim(it.name, q) > 0.34 }

    /** Token-overlap similarity, tolerant of partial names and casing. */
    private fun sim(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        if (a.contains(b, true) || b.contains(a, true)) return 1.0
        val inter = ta.count { x -> tb.any { y -> x == y || (x.length > 3 && y.startsWith(x)) || (y.length > 3 && x.startsWith(y)) } }
        return inter.toDouble() / minOf(ta.size, tb.size)
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }

    private fun parseTime(s: String): LocalTime? {
        val m = Regex("(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(s) ?: run {
            val h = Regex("^(\\d{1,2})\\s*(am|pm)$", RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
            var hh = h.groupValues[1].toInt() % 12
            if (h.groupValues[2].equals("pm", true)) hh += 12
            return LocalTime.of(hh, 0)
        }
        var hh = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        val ap = m.groupValues[3]
        if (ap.equals("pm", true) && hh < 12) hh += 12
        if (ap.equals("am", true) && hh == 12) hh = 0
        return if (hh in 0..23 && mm in 0..59) LocalTime.of(hh, mm) else null
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun fmt(d: Double) = if (d == d.roundToInt().toDouble()) d.roundToInt().toString() else "%.1f".format(d)
    private fun hm(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

    companion object {
        private val STOP = setOf("the", "of", "to", "and", "at", "in", "temple", "hotel", "sri", "shri")
    }
}
