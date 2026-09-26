package com.offspringslabs.disha

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Deterministic money and time arithmetic over a plan. The models propose; this decides the numbers. */
object PlanMath {
    fun recompute(plan: TripPlan): Budget {
        val c = plan.constraints
        val rooms = ceil(c.people / 2.0).toInt().coerceAtLeast(1)
        val nights = plan.stay?.nights?.takeIf { it > 0 } ?: (c.days - 1).coerceAtLeast(0)
        val stay = (plan.stay?.pricePerNight ?: 0) * nights * rooms
        val food = plan.foodPerPersonPerDay * c.people * c.days
        val transport = plan.transportToRegion.costInrPerPerson * 2 * c.people + plan.localTransportInr
        val entry = plan.days.flatMap { it.stops }.filter { isPaidActivity(it.name) }.sumOf { it.costInr } * c.people
        val subtotal = stay + food + transport + entry
        val buffer = (subtotal * 0.10).roundToInt()
        val total = subtotal + buffer
        return Budget(stay, food, transport, entry, buffer, total, c.budgetInr - total)
    }

    private val NON_ACTIVITY = Regex("(?i)\\b(lunch|dinner|breakfast|meal|snack|coffee|tea|check[- ]?in|check[- ]?out|arriv|depart|transfer|return|rest|hotel|resort)\\b")
    /** Meals are budgeted per day, logistics cost nothing extra: only real sights/activities carry a per-person entry cost. */
    fun isPaidActivity(name: String) = !NON_ACTIVITY.containsMatchIn(name)

    data class Skip(val stop: PlanStop, val day: Int, val savesInr: Int, val freesMin: Int)

    /** What skipping one stop saves: entry for everyone plus the time of the stop and the leg into it. */
    fun whatIfSkip(plan: TripPlan, name: String): Skip? {
        val q = name.lowercase()
        for (d in plan.days) for (s in d.stops) {
            if (s.name.lowercase().contains(q) || q.contains(s.name.lowercase()) || tokenOverlap(s.name, name) >= 0.5) {
                return Skip(s, d.day, (if (isPaidActivity(s.name)) s.costInr else 0) * plan.constraints.people, s.minutes + s.travelMin)
            }
        }
        return null
    }

    fun perPerson(b: Budget, people: Int) = if (people > 0) b.total / people else b.total

    private fun tokenOverlap(a: String, b: String): Double {
        val ta = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        val tb = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size)
    }
}
