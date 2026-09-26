# TravelFreak — the travel guide that never leaves you lost

> **v2 (afternoon build):** TravelFreak now *plans*. Tap **＋ New trip** and the agent asks where, how long, what budget and who — each question is an interactive card it summons on demand. It then researches with Gemini's live tools (Google Maps grounding, Google Search, code execution), builds a day-by-day itinerary that fits your **days and budget**, re-verifies every rupee deterministically, and saves the whole trip — places, fares, phrases in the local script, permits, scams, emergency numbers, offline map — for the on-device model.

**GDG Hyderabad × Google DeepMind Hackathon, 26 Sep 2026.** Built with Gemini Flash (cloud) and Gemma 3n E2B (on-device, LiteRT-LM).

Every travel app goes quiet the moment the network drops on a ghat road. TravelFreak doesn't.

1. **Online:** Gemini Flash, grounded with Google Search, researches a region and compiles a compact **trip pack** (stays, places, hours, distances, routes, food, day plans, emergency info) as structured JSON.
2. **The pack is cached on the phone.**
3. **Offline:** Gemma 3n E2B runs fully on-device and answers by **calling deterministic tools over the pack**. Every km, minute, ₹ and opening hour comes from code, not from the model's memory.
4. **Generative UI, the safe way:** the model never draws pixels. Each tool returns facts **plus a typed card**, and the screen renders a precompiled Compose component for it: Route, Open/Closed, Budget, Day-plan timeline, Safety, Place, Inventory. The agent's decision about *which tool to call* is the decision about *which component to show*. Online (Gemini Flash, native function calling) and offline (Gemma 3n) share the same tools, so the UI is identical either way.
5. **Voice both ways, multilingual:** online, your spoken question goes to **Gemini Flash Audio** (English, Telugu or Hindi), the agent replies in the same language (or the one you pick: Auto / English / తెలుగు / हिन्दी), and **Gemini TTS** reads it back in a natural guide-like voice you can switch (Kore, Puck, Zephyr…). Offline, Android's on-device recognizer feeds Gemma and the device voice speaks the answer.

## What the pack covers now (the things that actually strand people)

| Category | Offline tool | Card |
|---|---|---|
| Language & script | `sayIt(intent)` — native script + romanised | Phrase |
| Local transport rules | `fareCheck(from,to,mode)`, `howToRide(mode)` | Fair fare vs tourist quote |
| Payments, ID & permits | `permitsAndPayments()` | Checklist |
| Food & diet | `findFood(diet, spice)` | Food list (veg/spice/allergens) |
| Culture & safety | `etiquette(place)`, `isShutdown(date)` | Checklist |
| Trust & scams | `scamCheck(situation)` | Alert with counter-moves |
| Emergencies | `emergency(kind)` | Tap-to-call numbers + local phrase |
| Connectivity | `signalMap()` | Offline map with dead zones |
| Days & budget | `getBudget()`, `getPlan(day,time)`, `whatIfSkip(stop)`, `findStay(max)`, `getMap()` | Budget bar, timeline, what-if, map |

Online, the same tools plus Google Search run in a **multi-round decision loop**: for "9 pm, bus or auto back?" the agent gathers fares, hours and time, then answers `DECISION / WHY / ALTERNATIVE`, rendered as a decision card.

## Voice-first choreography

Components never race the voice. While the agent works you see only the live tool trail; the answer's first sentence is synthesized on its own so Gemini's voice starts as early as possible (the rest synthesizes in parallel and plays back to back). The answer text appears the moment audio starts, and one second later the cards slide in, staggered 650 ms apart. A 9-second cap means a slow voice can never hold the screen hostage. Intake questions follow the same rule: spoken first, card a second later.

## Planner agent: how the conversation works

The intake agent's questions are **client-side tools**. When Gemini calls `askBudget(...)`, the app renders a ₹ slider card and suspends the loop until you answer; your answer is returned as the tool result and the agent continues. Free text or voice also answers the current card, and if you say "2 days in Araku for two under ₹8,000" it skips what it already knows. `buildTrip` then runs three research calls (Maps ∥ Search → code execution), one structuring call, and a Kotlin re-computation of the budget (models do slip: the demo Tirupati plan claimed ₹14,300; the real total was ₹17,072, so it is flagged over budget with alternatives).

Gemini API constraints we hit: Maps grounding cannot share a request with Search or code execution, and mixing built-in tools with function declarations needs `toolConfig.includeServerSideToolInvocations`. Third-party apps cannot download Google Maps offline areas, so the map card draws a schematic from stored coordinates and hands off to Maps for the download.

## Demo script (90 s)

1. Pick **Tirupati & Tirumala**. Badge shows ONLINE. Ask by voice in Telugu: *"గుడి ఎంత దూరం, ఎంత సమయం పడుతుంది?"* → Gemini Flash Audio transcribes it, calls `getDistance`, shows the ROUTE card and **answers aloud in Telugu** with Gemini's voice.
2. **Turn on airplane mode.** Badge flips to OFFLINE.
3. Ask again (mic or text): *"Is the temple open right now?"* → Gemma 3n, on-device, calls `isOpen(...)`; an **OPEN NOW** card with "closes in 12 h" appears, then the spoken answer, in ~5 s.
4. *"Budget for 2 people, 2 nights at the cheapest hotel?"* → `estimateBudget(...)` renders the **Budget** card with every line of arithmetic.
   Bonus: *"Is Chandragiri Fort open at 6 pm and how far is it from the temple?"* → two tools, two cards (CLOSED + ROUTE), one answer.
5. Close: "No signal, no server, still not lost."

## Why tools, not a bigger prompt

Gemma 3n E2B has a 4 096-token context. Stuffing a whole trip pack in leaves no room to think and invites hallucinated numbers. Instead the model gets a 5-line index of the pack and eight tools:

| Tool | What it computes |
|---|---|
| `getPlace(name)` | hours, distance, fee, price, tips (fuzzy name match) |
| `getDistance(from, to)` | from route table → else haversine × road factor → else via base |
| `isOpen(name, time)` | open/closed now or at HH:MM, minutes to close/open, handles overnight hours |
| `estimateBudget(hotel, nights, people)` | rooms × nights × price + food + fees, arithmetic shown |
| `getPlan(day, time)` | day itinerary and next stop after a time |
| `listAll()` / `getEmergencyInfo()` / `currentTime()` | inventory, safety, phone clock |

Each tool also emits a `UiCard` (see `AgentCards.kt`); `ui/CardViews.kt` holds the precompiled components. Adding a capability = one tool function + one card + one composable. No prompt surgery.

Both brains consume the same catalogue in `ToolDispatch.kt`: LiteRT-LM `@Tool` annotations for native calling, a `CALL name("a","b")` text protocol for models without a tool template, and Gemini `function_declarations` generated from the same specs.

The on-device brain has three tiers chosen at runtime: LiteRT-LM native tool calling → our own `CALL name("a","b")` / `FINAL:` protocol (what Gemma 3n uses today) → plain context mode as a last resort.

Measured on a Nothing Phone (2), Snapdragon 8+ Gen 1, GPU backend: model load 18 s cold, answers 5–11 s including one tool round-trip.

## Run it

Requirements: Android Studio (for its JDK), Android SDK 36, an arm64 phone with ≥8 GB RAM, USB debugging on.

```bash
# 1. Gemini key (never committed)
echo "GEMINI_API_KEY=your_key" >> local.properties

# 2. Put the Gemma 3n E2B LiteRT-LM model on the phone (download it once in Google AI Edge Gallery, then copy)
adb shell mkdir -p /data/local/tmp/gemma
adb shell cp "/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_3n_E2B_it/*/gemma-3n-E2B-it-int4.litertlm" /data/local/tmp/gemma/
adb shell chmod 644 /data/local/tmp/gemma/*

# 3. Build and install
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
```

Regenerate trip packs (Gemini Flash + Google Search → JSON in `app/src/main/assets/packs/`):

```bash
node tools/gen-pack.mjs tirupati "Tirupati and Tirumala, Andhra Pradesh"
```

The **Refresh pack online** button does the same from inside the app and stores the result in app storage.

## Layout

```
app/src/main/java/com/offspringslabs/disha/
  TripPack.kt        structured pack model + JSON parser + compact text views
  TripTools.kt       the deterministic tools (LiteRT-LM @Tool annotated); each emits a UiCard
  AgentCards.kt      typed UI cards the agent can surface
  ToolDispatch.kt    one tool catalogue → LiteRT-LM, CALL/FINAL protocol, Gemini function declarations
  LocalBrain.kt      Gemma 3n via LiteRT-LM, three-tier agent loop
  GeminiClient.kt    Gemini Flash REST: grounded pack research, function-calling agent loop (text + audio)
  GeminiTts.kt       Gemini TTS (gemini-3.8-flash-lite-tts → 2.5 preview fallback), PCM playback, ReplyLang
  VoiceIO.kt         on-device SpeechRecognizer + TTS
  AudioRecorder.kt   16 kHz WAV capture for Gemini Flash Audio
  Connectivity.kt    online/offline state
  DishaViewModel.kt  routing: cloud when online, on-device otherwise
  ui/DishaScreen.kt  single-screen Compose UI
  ui/CardViews.kt    precompiled Route / OpenStatus / Budget / Plan / Emergency / Place / Inventory components
tools/gen-pack.mjs   pack generator (Node 22, no dependencies)
```

## Honest limits

- Packs are only as good as the grounded research; refresh before travelling and skim them (**View pack**).
- No offline turn-by-turn navigation; **Open in Maps** hands off when you're back online.
- Gemma 3n's LiteRT-LM build doesn't yet emit native tool calls, so we drive the tool protocol ourselves. When it does, tier 1 kicks in automatically.
- If the cloud call fails while the badge still says online (flaky venue Wi-Fi), the same question is answered on-device automatically.
