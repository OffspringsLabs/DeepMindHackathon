#!/usr/bin/env node
// Usage: GEMINI_API_KEY=... node tools/gen-pack.mjs <id> "<Region label>"
// Two calls: (1) Gemini Flash with Google Search grounding researches the region,
// (2) Gemini Flash converts the notes into the strict JSON schema the app's tools read.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const MODEL = "gemini-3.8-flash";

function apiKey() {
  if (process.env.GEMINI_API_KEY) return process.env.GEMINI_API_KEY.trim();
  try {
    const lp = readFileSync(join(root, "local.properties"), "utf8");
    const m = lp.match(/^GEMINI_API_KEY=(.+)$/m);
    if (m && m[1].trim()) return m[1].trim();
  } catch {}
  console.error("No GEMINI_API_KEY in env or local.properties");
  process.exit(1);
}

const [id, label] = process.argv.slice(2);
if (!id || !label) {
  console.error('Usage: node tools/gen-pack.mjs <id> "<Region label>"');
  process.exit(1);
}

const research = (region) => `You are preparing an OFFLINE trip pack for a traveller visiting ${region}, India.
You MUST use Google Search: run separate searches for (a) official temple/monument timings and entry fees, (b) current hotel prices, (c) bus/train frequencies and fares, (d) hospital and mobile-network coverage. Cite what you found.
Cover, with concrete numbers: the base town and how to reach it from Hyderabad (train/bus/flight, hours, approx ₹);
nearest railway station and airport with distance; 4 to 5 stays across budgets with approx ₹ per night and distance from base;
8 to 12 must-see places with type, opening and closing times, distance in km and minutes from the base, entry fee in ₹,
approximate latitude/longitude, and one practical tip each; 6 to 10 point-to-point routes with km, minutes, mode and frequency;
3 to 5 local dishes or eateries with approx ₹; a realistic 2 or 3 day plan with times; nearest hospital, ATM/fuel availability,
and stretches with no mobile signal; 4 practical tips (dress code, best season, what to book ahead).
Prefer approximate numbers over omissions. Do not invent hotel names; if unsure, describe the type of stay.`;

const toJson = (notes) => `Convert the research notes below into ONE JSON object with EXACTLY this shape and key names. Numbers must be numbers, not strings.
Times are "HH:MM" 24h. Keep strings short. Output JSON only.
{"region":"","state":"","base":"","season":"","languages":"",
 "arrival":{"rail":"","air":"","fromHyderabad":""},
 "hotels":[{"name":"","area":"","pricePerNight":0,"kmFromBase":0,"note":""}],
 "places":[{"name":"","type":"","open":"HH:MM","close":"HH:MM","kmFromBase":0,"minutesFromBase":0,"fee":0,"lat":0,"lon":0,"tip":""}],
 "routes":[{"from":"","to":"","km":0,"minutes":0,"mode":""}],
 "food":[{"item":"","where":"","price":0}],
 "days":[{"day":1,"plan":[{"time":"HH:MM","stop":""}]}],
 "emergency":{"hospital":"","police":"112","atm":"","noSignal":""},
 "tips":[""]}

RESEARCH NOTES:
${notes}`;

async function gemini(prompt, { grounded = false, json = false, temperature = 0.3 } = {}) {
  const body = {
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: { temperature, ...(json ? { response_mime_type: "application/json" } : {}) },
    ...(grounded ? { tools: [{ google_search: {} }] } : {}),
  };
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": apiKey() },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${JSON.stringify(data).slice(0, 400)}`);
  const parts = data.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p) => p.text ?? "").join("").trim();
  const gm = data.candidates?.[0]?.groundingMetadata ?? {};
  const sources = gm.groundingChunks?.map((c) => c.web?.uri).filter(Boolean) ?? [];
  const queries = gm.webSearchQueries ?? [];
  return { text, sources, queries };
}

const strip = (s) => s.replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "").trim();

console.log(`[1/2] Researching ${label} with Gemini Flash + Google Search…`);
const r = await gemini(research(label), { grounded: true, temperature: 0.4 });
console.log(`      ${r.text.length} chars of notes, ${r.queries.length} Google searches, ${r.sources.length} web sources`);
if (r.queries.length) console.log("      searches: " + r.queries.slice(0, 6).join(" | "));

console.log(`[2/2] Converting to trip-pack JSON…`);
const j = await gemini(toJson(r.text), { json: true, temperature: 0.1 });
const jsonText = strip(j.text);
const pack = JSON.parse(jsonText); // throws if malformed

pack.region = label; // keep the human-facing label, models sometimes return the district instead
pack.generatedAt = new Date().toISOString();
pack.sources = r.sources.slice(0, 12);
const required = ["region", "base", "hotels", "places", "routes", "days", "emergency"];
for (const k of required) if (!(k in pack)) throw new Error(`pack missing key ${k}`);

const out = join(root, "app/src/main/assets/packs", `${id}.json`);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(pack, null, 2) + "\n");

const tokens = Math.round(jsonText.length / 4);
console.log(`✔ wrote ${out}`);
console.log(`  ${pack.places.length} places, ${pack.hotels.length} hotels, ${pack.routes.length} routes, ${pack.days.length} days, ~${tokens} tokens`);
if (tokens > 2400) console.warn("  ⚠ pack is large; the on-device fallback context mode may truncate. Tools mode is unaffected.");
if (r.sources.length) console.log("  sources:\n   - " + r.sources.slice(0, 8).join("\n   - "));
