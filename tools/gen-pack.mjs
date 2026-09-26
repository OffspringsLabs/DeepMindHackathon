#!/usr/bin/env node
// TravelFreak pack v2 generator.
// Usage: node tools/gen-pack.mjs <id> "<Destination label>" [--days 3] [--budget 15000] [--people 2] [--from Hyderabad] [--pace balanced] [--interests temples,food] [--diet any]
// Step 1: Gemini Flash researches with Google Maps grounding + Google Search + code execution (planner persona).
// Step 2: Gemini Flash converts notes into the strict pack v2 JSON (all local-reality categories + itinerary).
// Step 3: budget is recomputed here; mismatches are overwritten and flagged.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const MODEL = "gemini-3.8-flash";

function apiKey() {
  if (process.env.GEMINI_API_KEY) return process.env.GEMINI_API_KEY.trim();
  try { const m = readFileSync(join(root, "local.properties"), "utf8").match(/^GEMINI_API_KEY=(.+)$/m); if (m && m[1].trim()) return m[1].trim(); } catch {}
  console.error("No GEMINI_API_KEY in env or local.properties"); process.exit(1);
}

// ---- args
const argv = process.argv.slice(2);
const [id, label] = argv;
if (!id || !label) { console.error('Usage: node tools/gen-pack.mjs <id> "<Destination>" [--days N --budget INR --people N --from City --pace relaxed|balanced|packed --interests a,b --diet any|veg|jain|halal]'); process.exit(1); }
const opt = (k, d) => { const i = argv.indexOf(`--${k}`); return i > 0 && argv[i + 1] ? argv[i + 1] : d; };
const C = {
  days: +opt("days", 3), budgetInr: +opt("budget", 15000), people: +opt("people", 2), startCity: opt("from", "Hyderabad"),
  pace: opt("pace", "balanced"), interests: opt("interests", "temples,nature,food").split(",").map((s) => s.trim()).filter(Boolean), diet: opt("diet", "any"),
};

export const SHAPE = `{"region":"","state":"","base":"","baseLat":0,"baseLon":0,"season":"","languages":"",
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
   "offlineMap":{"centerLat":0,"centerLon":0,"zoom":12,"mapsAreaUrl":""}}}`;

export const research = (dest, c) => `You are TravelFreak, a top-efficiency Indian travel planner. Plan ${c.days} days in ${dest}, India for ${c.people} traveller(s) from ${c.startCity}, total budget ₹${c.budgetInr} (everything except shopping), pace ${c.pace}, interests ${c.interests.join(", ")}, diet ${c.diet}.
Use Google Maps to find real, rated stays and places (with prices and hours) and Google Search for official timings, fares, permits, scams and emergency contacts. Use code execution for ALL arithmetic.
Deliver research notes with concrete numbers in these sections:
1 BASE & ARRIVAL: base town, lat/lon, nearest rail/air, how to reach from ${c.startCity} (mode, hours, ₹ per person one way), best season, languages spoken.
2 STAYS: 4-5 real stays across budgets with ₹/night, rating, distance from base; pick ONE for this budget and say why.
3 PLACES: 8-12 must-sees with type, open/close, km & minutes from base, entry ₹, lat/lon, one tip.
4 ROUTES: 6-10 point-to-point legs with km, minutes, mode and frequency.
5 ITINERARY: ${c.days} day(s), time-ordered, geographically clustered to avoid backtracking, respecting opening hours and meal windows; per stop: minutes, per-person ENTRY/ACTIVITY cost only (₹0 for meals, check-in, transfers — food is budgeted separately), travel from previous stop (km, min, mode). Day themes.
6 BUDGET (code execution): rooms = ceil(people/2); stay = ₹/night × nights × rooms; food = ₹/person/day × people × days; transport = per-person round trip × people + local transport total; entry = sum of per-person entry/activity costs of sight stops × people (meals and logistics excluded); buffer = 10% of subtotal; total; remaining vs ₹${c.budgetInr}. If over budget give 2-3 concrete swaps with ₹ saved; if under, one worthwhile upgrade.
7 LANGUAGE: primary language + script; 12 traveller phrases (where is / how much / too expensive / hospital / police / vegetarian / no spice / stop here / bus to X / water / help / thank you) each with English, NATIVE SCRIPT and roman transliteration; 6 station/place names in native script.
8 LOCAL TRANSPORT RULES: prepaid stand vs meter vs haggle; 6+ fair fares (from, to, mode, fair ₹, typical tourist quote ₹); shared routes; metro/bus board conventions; last service times.
9 PAYMENTS & PERMITS: UPI coverage, cash-only spots, tolls, permits (who needs, where, ₹, lead days), ID needed.
10 FOOD: 6 dishes with local name, where, ₹, veg?, spice 1-3, meal window, allergens; how to ask for ${c.diet}.
11 CULTURE & SAFETY: dress code, footwear, photography, alcohol rules, holidays/shutdowns in the next 60 days with dates, women's safety notes, 4 tips.
12 TRUST: 4+ common scams near the sights with the counter-move; official guide rate ₹; official counters.
13 EMERGENCY: hospital, 24h pharmacy, police, tourist police, nearest embassy/consulate city, ATMs, no-signal stretches, phone numbers, 4 emergency phrases in the native script.
Prefer approximate numbers over omissions. Never invent hotel names; if unsure, describe the type of stay.`;

export const toJson = (notes, c) => `Convert the research notes into ONE JSON object with EXACTLY this shape and key names. Numbers must be numbers. Times "HH:MM" 24h. Dates "YYYY-MM-DD". Keep strings short. Fill plan.constraints from: days ${c.days}, budgetInr ${c.budgetInr}, people ${c.people}, startCity "${c.startCity}", pace "${c.pace}", interests ${JSON.stringify(c.interests)}, diet "${c.diet}". Output JSON only.
${SHAPE}

RESEARCH NOTES:
${notes}`;

async function gemini(prompt, { tools = [], json = false, temperature = 0.3, latLng = null } = {}) {
  const body = {
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: { temperature, ...(json ? { response_mime_type: "application/json" } : {}) },
    ...(tools.length ? { tools } : {}),
    ...(latLng ? { toolConfig: { retrievalConfig: { latLng } } } : {}),
  };
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`, {
    method: "POST", headers: { "content-type": "application/json", "x-goog-api-key": apiKey() }, body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${JSON.stringify(data).slice(0, 400)}`);
  const cand = data.candidates?.[0] ?? {};
  const parts = cand.content?.parts ?? [];
  const text = parts.filter((p) => p.text).map((p) => p.text).join("").trim();
  const code = parts.filter((p) => p.codeExecutionResult).map((p) => p.codeExecutionResult.output).join("\n");
  const gm = cand.groundingMetadata ?? {};
  const sources = (gm.groundingChunks ?? []).map((ch) => ch.web ? { title: ch.web.title, uri: ch.web.uri, kind: "web" } : ch.maps ? { title: ch.maps.title, uri: ch.maps.uri, kind: "maps" } : null).filter(Boolean);
  return { text, code, sources, queries: gm.webSearchQueries ?? [] };
}

export function recomputeBudget(pack) {
  const p = pack.plan; if (!p) return { ok: false, notes: ["no plan"] };
  const c = p.constraints; const notes = [];
  const rooms = Math.ceil((c.people || 1) / 2);
  const nights = p.stay?.nights || Math.max(0, c.days - 1);
  const stay = Math.round((p.stay?.pricePerNight || 0) * nights * rooms);
  const food = Math.round((p.foodPerPersonPerDay || 0) * c.people * c.days);
  const transport = Math.round((p.transportToRegion?.costInrPerPerson || 0) * 2 * c.people + (p.localTransportInr || 0));
  const NON = /\b(lunch|dinner|breakfast|meal|snack|coffee|tea|check[- ]?in|check[- ]?out|arriv|depart|transfer|return|rest|hotel|resort)\b/i;
  const entry = Math.round((p.days ?? []).flatMap((d) => d.stops ?? []).filter((x) => !NON.test(x.name || "")).reduce((s, x) => s + (x.costInr || 0), 0) * c.people);
  const subtotal = stay + food + transport + entry;
  const buffer = Math.round(subtotal * 0.1);
  const total = subtotal + buffer;
  const mine = { stay, food, transport, entry, buffer, total, remaining: c.budgetInr - total };
  for (const k of Object.keys(mine)) if (Math.abs((p.budget?.[k] ?? 0) - mine[k]) > 1) notes.push(`${k}: model ${p.budget?.[k]} → recomputed ${mine[k]}`);
  p.budget = mine; p.budgetVerified = true; p.overBudget = mine.remaining < 0;
  return { ok: true, notes, mine };
}

const strip = (s) => s.replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "").trim();

console.log(`[1/3] Researching ${label} · ${C.days}d · ₹${C.budgetInr} · ${C.people}p from ${C.startCity}…`);
// Gemini forbids mixing google_maps with code_execution (and some other pairs), so research runs as three single-tool calls.
const base = research(label, C);
const [maps, web] = await Promise.all([
  gemini(base + "\n\nFOCUS NOW (Google Maps): sections 1-4 and the stay/food/fare facts — real rated places with prices, hours, coordinates and Maps links.", { tools: [{ google_maps: {} }], temperature: 0.4 }),
  gemini(base + "\n\nFOCUS NOW (Google Search): sections 7-13 — official timings & fees, transport rules and fair fares, permits, food, culture/holidays, scams, emergency contacts, and the native-script phrases.", { tools: [{ google_search: {} }], temperature: 0.4 }),
]);
console.log(`      maps: ${maps.text.length} chars, ${maps.sources.length} Maps places · web: ${web.text.length} chars, ${web.queries.length} searches, ${web.sources.length} sources`);
const calc = await gemini(`Using code execution, build the ${C.days}-day itinerary (section 5) and compute the BUDGET (section 6) exactly as specified, from these notes. Print every budget line and the itinerary with per-stop minutes, ₹ per person, and travel km/min.\n\nCONSTRAINTS: ${JSON.stringify(C)}\n\nNOTES A (Maps):\n${maps.text}\n\nNOTES B (Search):\n${web.text}`, { tools: [{ code_execution: {} }], temperature: 0.2 });
console.log(`      itinerary+budget: ${calc.text.length} chars, code output ${calc.code.length} chars`);
const r = { text: `${maps.text}\n\n${web.text}\n\nITINERARY & BUDGET:\n${calc.text}\n${calc.code}`, code: calc.code, sources: [...maps.sources, ...web.sources], queries: web.queries };
console.log(`[2/3] Structuring pack v2 JSON…`);
const j = await gemini(toJson(r.text + (r.code ? "\n\nCODE OUTPUT:\n" + r.code : ""), C), { json: true, temperature: 0.1 });
const pack = JSON.parse(strip(j.text));
pack.region = label; pack.generatedAt = new Date().toISOString(); pack.sources = r.sources.slice(0, 20); pack.version = 2;
if (pack.plan?.offlineMap && pack.baseLat) { pack.plan.offlineMap.centerLat ||= pack.baseLat; pack.plan.offlineMap.centerLon ||= pack.baseLon; pack.plan.offlineMap.mapsAreaUrl = `https://www.google.com/maps/@${pack.plan.offlineMap.centerLat},${pack.plan.offlineMap.centerLon},${pack.plan.offlineMap.zoom || 12}z`; }
// v1 compatibility: simple days[] from the plan
pack.days = (pack.plan?.days ?? []).map((d) => ({ day: d.day, plan: (d.stops ?? []).map((s) => ({ time: s.time, stop: s.name })) }));

console.log(`[3/3] Verifying budget…`);
const v = recomputeBudget(pack);
v.notes.forEach((n) => console.log("      fix " + n));

for (const k of ["region", "base", "hotels", "places", "routes", "language", "transport", "payments", "culture", "trust", "emergency", "plan"]) if (!(k in pack)) throw new Error(`pack missing key ${k}`);
const out = join(root, "app/src/main/assets/packs", `${id}.json`);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(pack, null, 2) + "\n");
const tokens = Math.round(JSON.stringify(pack).length / 4);
console.log(`✔ wrote ${out}`);
console.log(`  ${pack.places.length} places · ${pack.hotels.length} stays · ${pack.routes.length} routes · ${pack.language?.phrases?.length ?? 0} phrases · ${pack.transport?.fares?.length ?? 0} fares · ${pack.trust?.scams?.length ?? 0} scams · ${pack.emergency?.numbers?.length ?? 0} emergency numbers · ${pack.plan?.days?.length ?? 0}-day plan · ~${tokens} tokens`);
console.log(`  budget: total ₹${pack.plan.budget.total} vs ₹${C.budgetInr} → ${pack.plan.overBudget ? "OVER by ₹" + -pack.plan.budget.remaining + " (" + (pack.plan.alternatives?.length ?? 0) + " alternatives)" : "remaining ₹" + pack.plan.budget.remaining}`);
if (r.queries.length) console.log("  searches: " + r.queries.slice(0, 5).join(" | "));
