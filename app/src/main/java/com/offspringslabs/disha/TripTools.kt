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
        val v2 = pack.plan?.days?.firstOrNull { it.day == plan.day }
        onCard(UiCard.Plan(plan.day, plan.plan, plan.plan.indexOf(next), "%02d:%02d".format(t.hour, t.minute), v2?.theme ?: "",
            v2?.stops?.map { it.costInr } ?: emptyList(), v2?.stops?.map { st -> if (st.travelKm > 0) "${fmt(st.travelKm)} km · ${st.travelMin} min · ${st.mode}" else "" } ?: emptyList()))
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
        onCard(UiCard.Emergency(e.hospital, e.police, e.atm, e.noSignal, pack.arrival.entries.map { it.key to it.value }, e.numbers, e.phrases.firstOrNull(), e.pharmacy24h))
        val arrival = pack.arrival.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "Hospital: ${e.hospital}. Police: ${e.police}. ATMs: ${e.atm}. No signal: ${e.noSignal}. Arrival: $arrival. Tips: ${pack.tips.joinToString(" ")}"
    }

    @Tool(description = "Current local time HH:MM and weekday from the phone clock.")
    fun currentTime(): String {
        trace("currentTime()")
        val n = now()
        return "%02d:%02d, %s".format(n.hour, n.minute, n.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
    }

    // ---- v2: language, transport rules, payments, food, culture, trust, emergency, plan ----

    @Tool(description = "Say a traveller phrase in the local language: returns native script, romanised and English. Intents: where is, how much, too expensive, hospital, police, vegetarian, no spice, stop here, bus to, water, help, thank you, or any free text.")
    fun sayIt(@ToolParam(description = "What you want to say, in English") intent: String): String {
        trace("sayIt($intent)")
        val l = pack.language ?: return "No phrasebook in this pack."
        val all = l.phrases + pack.emergency.phrases
        val best = all.maxByOrNull { maxOf(sim(it.intent, intent), sim(it.english, intent)) } ?: return "No phrases in pack."
        if (maxOf(sim(best.intent, intent), sim(best.english, intent)) < 0.3) return "No phrase for '$intent'. Available: ${all.map { it.intent.ifBlank { it.english } }.joinToString(", ")}"
        onCard(UiCard.PhraseCard(best.intent, best.english, best.native, best.roman, l.primary))
        return "${l.primary}: \"${best.english}\" = ${best.native} (say: ${best.roman})"
    }

    @Tool(description = "Fair local fare between two places by a mode (auto, taxi, bus, shared jeep) and the typical tourist quote, plus the local rule (prepaid stand, meter or haggle).")
    fun fareCheck(@ToolParam(description = "From") from: String, @ToolParam(description = "To") to: String, @ToolParam(description = "auto, taxi, bus, jeep or any") mode: String): String {
        trace("fareCheck($from → $to, $mode)")
        val t = pack.transport ?: return "No fare table in this pack."
        val f = t.fares.maxByOrNull { (sim(it.from, from) + sim(it.to, to)) + (if (mode.isBlank() || mode.equals("any", true) || sim(it.mode, mode) > 0.5) 0.5 else 0.0) }
        if (f == null || sim(f.from, from) + sim(f.to, to) < 0.6) return "No fare for that leg. Rule: ${t.autoRule}. Known legs: ${t.fares.joinToString("; ") { "${it.from}→${it.to} ${it.mode} ₹${it.fairInr}" }}"
        onCard(UiCard.FareCard(f.from, f.to, f.mode, f.fairInr, f.touristQuoteInr, t.autoRule))
        return "${f.from} to ${f.to} by ${f.mode}: fair ₹${f.fairInr}; tourists are often quoted ₹${f.touristQuoteInr}. Anything above ₹${(f.fairInr * 1.3).roundToInt()} is overpaying. Rule: ${t.autoRule}"
    }

    @Tool(description = "How local transport works here: auto/taxi rules, shared routes, metro or bus board conventions, last service times.")
    fun howToRide(@ToolParam(description = "auto, bus, metro, shared, or any") mode: String): String {
        trace("howToRide($mode)")
        val t = pack.transport ?: return "No transport rules in this pack."
        val items = listOf("Autos & taxis" to t.autoRule, "Shared routes" to t.sharedRoutes.joinToString("; "), "Metro" to t.metro, "Bus boards" to t.busBoards, "Last services" to t.lastServiceTimes.joinToString("; ")).filter { it.second.isNotBlank() }
        onCard(UiCard.Checklist("How to get around", items, "info"))
        return items.joinToString(" ") { "${it.first}: ${it.second}." }
    }

    @Tool(description = "Payments, cash, tolls, ID and permits needed here, with cost and lead time.")
    fun permitsAndPayments(): String {
        trace("permitsAndPayments()")
        val p = pack.payments ?: return "No payments info in this pack."
        val items = ArrayList<Pair<String, String>>()
        if (p.upiCoverage.isNotBlank()) items += "UPI" to p.upiCoverage
        if (p.cashOnly.isNotEmpty()) items += "Cash only" to p.cashOnly.joinToString(", ")
        if (p.tolls.isNotBlank()) items += "Tolls" to p.tolls
        if (p.idNeeded.isNotBlank()) items += "ID" to p.idNeeded
        p.permits.forEach { items += "Permit: ${it.name}" to "${it.whoNeeds} · ${it.whereToGet} · ₹${it.costInr} · apply ${it.leadDays} day(s) ahead" }
        onCard(UiCard.Checklist("Payments, ID & permits", items, "warn"))
        return items.joinToString(" ") { "${it.first}: ${it.second}." }
    }

    @Tool(description = "Find dishes and eateries matching a diet (veg, non-veg, jain, halal, vegan, any) and spice tolerance (mild, medium, hot, any).")
    fun findFood(@ToolParam(description = "veg, non-veg, jain, vegan, halal or any") diet: String, @ToolParam(description = "mild, medium, hot or any") spice: String): String {
        trace("findFood($diet, $spice)")
        val maxSpice = when (spice.lowercase()) { "mild" -> 1; "medium" -> 2; else -> 3 }
        val wantVeg = diet.lowercase().let { it.startsWith("veg") || it == "jain" || it == "vegan" }
        val wantNonVeg = diet.lowercase().startsWith("non")
        val items = pack.food.filter { f -> (!wantVeg || f.veg != false) && (!wantNonVeg || f.veg != true) && (f.spice == 0 || f.spice <= maxSpice) }
        if (items.isEmpty()) return "Nothing matching in pack. All food: ${pack.food.joinToString(", ") { it.item }}"
        onCard(UiCard.FoodList("Food for $diet, $spice spice", items))
        return items.joinToString("; ") { "${it.item}${if (it.localName.isNotBlank()) " (${it.localName})" else ""} at ${it.where}, ₹${it.price}, ${if (it.veg == true) "veg" else if (it.veg == false) "non-veg" else ""} spice ${it.spice}${if (it.mealWindow.isNotBlank()) ", ${it.mealWindow}" else ""}" }
    }

    @Tool(description = "Dress code, footwear, photography, alcohol rules, women's safety notes and upcoming holidays or shutdowns.")
    fun etiquette(@ToolParam(description = "Place name or 'general'") place: String): String {
        trace("etiquette($place)")
        val c = pack.culture ?: return "No culture notes in this pack."
        val items = listOf("Dress" to c.dressCode, "Footwear" to c.footwear, "Photography" to c.photography, "Alcohol" to c.alcohol, "Women's safety" to c.womenSafety).filter { it.second.isNotBlank() } +
            c.holidaysShutdowns.map { "Shutdown ${it.date}" to it.what } + c.tips.map { "Tip" to it }
        onCard(UiCard.Checklist("Culture & safety" + if (place.isNotBlank() && !place.equals("general", true)) " · $place" else "", items, "info"))
        return items.joinToString(" ") { "${it.first}: ${it.second}." }
    }

    @Tool(description = "Whether anything is shut or special on a date (YYYY-MM-DD or 'today'/'tomorrow').")
    fun isShutdown(@ToolParam(description = "YYYY-MM-DD, today or tomorrow") date: String): String {
        trace("isShutdown($date)")
        val c = pack.culture ?: return "No holiday list in this pack."
        val d = when (date.lowercase()) { "today" -> now().toLocalDate(); "tomorrow" -> now().toLocalDate().plusDays(1); else -> runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull() }
        val hits = c.holidaysShutdowns.filter { h -> d == null || h.date.startsWith(d.toString()) }
        if (hits.isEmpty()) return "Nothing listed for ${d ?: date}. Upcoming: ${c.holidaysShutdowns.joinToString("; ") { "${it.date} ${it.what}" }}"
        onCard(UiCard.Checklist("On ${d ?: date}", hits.map { it.date to it.what }, "warn"))
        return hits.joinToString("; ") { "${it.date}: ${it.what}" }
    }

    @Tool(description = "Check a situation against known local scams and get the counter-move; also official guide rates and counters.")
    fun scamCheck(@ToolParam(description = "What is happening, e.g. 'man says temple is closed', 'auto wants 800'") situation: String): String {
        trace("scamCheck($situation)")
        val t = pack.trust ?: return "No scam list in this pack."
        val ranked = t.scams.sortedByDescending { sim(it.pattern, situation) }
        val hits = ranked.filter { sim(it.pattern, situation) >= 0.25 }.ifEmpty { ranked.take(3) }
        onCard(UiCard.Alert("Watch out", hits.map { it.pattern to it.counter } + listOfNotNull(if (t.officialGuideRateInr > 0) "Official guide rate" to "₹${t.officialGuideRateInr}" else null)))
        return hits.joinToString(" ") { "${it.pattern} → ${it.counter}." } + if (t.officialGuideRateInr > 0) " Official guide rate ₹${t.officialGuideRateInr}; official counters: ${t.officialCounters.joinToString(", ")}." else ""
    }

    @Tool(description = "Emergency help by kind: hospital, pharmacy, police, tourist police, embassy, or any. Returns the contact and the local-language phrase.")
    fun emergency(@ToolParam(description = "hospital, pharmacy, police, tourist police, embassy or any") kind: String): String {
        trace("emergency($kind)")
        val e = pack.emergency
        val k = kind.lowercase()
        val main = when {
            k.contains("pharm") -> "24h pharmacy: ${e.pharmacy24h}"
            k.contains("tourist") -> "Tourist police: ${e.touristPolice}"
            k.contains("police") -> "Police: ${e.police}"
            k.contains("embassy") || k.contains("consul") -> "Embassy/consulate: ${e.embassy}"
            else -> "Hospital: ${e.hospital}"
        }
        val phrase = e.phrases.maxByOrNull { sim(it.english, kind) } ?: e.phrases.firstOrNull()
        onCard(UiCard.Emergency(e.hospital, e.police, e.atm, e.noSignal, emptyList(), e.numbers, phrase, e.pharmacy24h))
        return "$main. Numbers: ${e.numbers.joinToString(", ") { "${it.label} ${it.phone}" }}. ${phrase?.let { "Say: ${it.native} (${it.roman}) = ${it.english}." } ?: ""}"
    }

    @Tool(description = "Where mobile signal drops in this region and which carrier works best.")
    fun signalMap(): String {
        trace("signalMap()")
        val c = pack.connectivity
        val zones = (c?.deadZones ?: emptyList()) + listOf(pack.emergency.noSignal).filter { it.isNotBlank() }
        emitMap(zones)
        return "No signal: ${zones.joinToString("; ")}. Best carrier: ${c?.bestCarrier ?: "unknown"}. ${c?.tips ?: ""}"
    }

    @Tool(description = "Trip budget status: planned total vs the traveller's budget, by category, remaining, per person.")
    fun getBudget(): String {
        trace("getBudget()")
        val p = pack.plan ?: return "No planned trip yet; ask me to plan one when online."
        val b = PlanMath.recompute(p)
        onCard(UiCard.Budget("${p.constraints.days} days · ${p.constraints.people} people · ${p.stay?.name ?: pack.base}",
            listOf("Stay" to b.stay, "Food" to b.food, "Transport" to b.transport, "Entry & activities" to b.entry, "10% buffer" to b.buffer), b.total,
            if (b.remaining >= 0) "₹${b.remaining} left of ₹${p.constraints.budgetInr}" else "OVER by ₹${-b.remaining}; ${p.alternatives.size} ways to fit", p.constraints.budgetInr, PlanMath.perPerson(b, p.constraints.people)))
        return "Planned ₹${b.total} of ₹${p.constraints.budgetInr} (stay ₹${b.stay}, food ₹${b.food}, transport ₹${b.transport}, entry ₹${b.entry}, buffer ₹${b.buffer}); ${if (b.remaining >= 0) "₹${b.remaining} remaining" else "over by ₹${-b.remaining}"}; ₹${PlanMath.perPerson(b, p.constraints.people)} per person." +
            if (p.alternatives.isNotEmpty()) " Options: ${p.alternatives.joinToString("; ") { "${it.title} saves ₹${it.savesInr}" }}." else ""
    }

    @Tool(description = "What skipping a planned stop saves in rupees and minutes, plus the plan's alternatives.")
    fun whatIfSkip(@ToolParam(description = "Stop name, partial ok") stop: String): String {
        trace("whatIfSkip($stop)")
        val p = pack.plan ?: return "No planned trip yet."
        val sk = PlanMath.whatIfSkip(p, stop) ?: return "No stop like '$stop' in the plan. Stops: ${p.days.flatMap { it.stops }.joinToString(", ") { it.name }}"
        onCard(UiCard.Tradeoff("Skip ${sk.stop.name} (day ${sk.day})", sk.savesInr, sk.freesMin, "Entry ₹${sk.stop.costInr} × ${p.constraints.people} + ${sk.stop.minutes} min there + ${sk.stop.travelMin} min travel", p.alternatives))
        return "Skipping ${sk.stop.name} on day ${sk.day} saves ₹${sk.savesInr} and frees ${sk.freesMin} min. Budget would then have ₹${PlanMath.recompute(p).remaining + sk.savesInr} remaining."
    }

    @Tool(description = "Stays at or under a nightly price, with rating and distance.")
    fun findStay(@ToolParam(description = "Max ₹ per night, e.g. 3000") maxPerNight: String): String {
        trace("findStay(≤₹$maxPerNight)")
        val cap = maxPerNight.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        val hits = pack.hotels.filter { it.pricePerNight <= cap }.sortedBy { it.pricePerNight }
        if (hits.isEmpty()) return "Nothing under ₹$cap. Cheapest: ${pack.hotels.minByOrNull { it.pricePerNight }?.let { "${it.name} ₹${it.pricePerNight}" }}"
        hits.take(3).forEach { h -> onCard(UiCard.PlaceInfo(h.name, "stay · ${h.area}${if (h.rating > 0) " · ★${h.rating}" else ""}", null, "${fmt(h.kmFromBase)} km from ${pack.base}", "₹${h.pricePerNight} / night", h.note, null, null, h.mapsUri)) }
        return hits.joinToString("; ") { "${it.name} ₹${it.pricePerNight}/night${if (it.rating > 0) " ★${it.rating}" else ""}, ${fmt(it.kmFromBase)} km" }
    }

    @Tool(description = "Offline map of the trip: places, stay and day routes from stored coordinates, with no-signal zones and a Google Maps hand-off.")
    fun getMap(): String {
        trace("getMap()")
        val n = emitMap(pack.connectivity?.deadZones ?: emptyList())
        return "Map shows $n points around ${pack.base}${pack.plan?.let { " with ${it.days.size} day routes" } ?: ""}. Open the area in Google Maps and tap 'Download offline map' before you lose signal."
    }

    private fun emitMap(deadZones: List<String>): Int {
        val pts = ArrayList<MapPoint>()
        pack.places.forEach { p -> if (p.lat != null && p.lon != null) pts += MapPoint(p.name, p.lat, p.lon, "place") }
        pack.plan?.stay?.let { s -> if (s.lat != null && s.lon != null) pts += MapPoint(s.name, s.lat, s.lon, "stay") }
        val routes = pack.plan?.days?.map { d -> d.stops.mapNotNull { st -> if (st.lat != null && st.lon != null) { val idx = pts.indexOfFirst { sim(it.name, st.name) > 0.6 }; if (idx >= 0) idx else { pts += MapPoint(st.name, st.lat, st.lon, "stop"); pts.lastIndex } } else null } } ?: emptyList()
        val baseLat = pack.baseLat ?: pts.map { it.lat }.average().takeIf { !it.isNaN() } ?: 0.0
        val baseLon = pack.baseLon ?: pts.map { it.lon }.average().takeIf { !it.isNaN() } ?: 0.0
        onCard(UiCard.MapCard(pack.base, baseLat, baseLon, pts, routes, deadZones, pack.plan?.offlineMap?.mapsAreaUrl ?: "https://www.google.com/maps/@$baseLat,$baseLon,12z"))
        return pts.size
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
