# AAPS Glass: complete face spec (design system v2)

Version 2 · 17 Oct 2026 · design only. Nothing in the repo, on the phone or on the watch was changed.

This spec replaces `../designs/glass_spec.md` and Glass spec v1. It follows `states.md` (the family contract) **plus the family-wide critique decisions listed in §1.B**, which states.md v1 does not contain yet. Where states.md v1 and §1.B disagree, §1.B wins (it is the newer family decision and must be merged into states.md). Otherwise states.md decides **what** is shown, **which colour** it gets and **what wins**; this file decides **where** and **in which shape** Glass draws it.

- Reference renderer: `glass_states.py` in this folder (`uv run --with pillow python3 glass_states.py`). It contains a Python copy of the family `FaceState` rules (`face_state()`, `sec_state()`), so every tile is computed from numeric inputs, not hand-painted. Tile captions end with a **red-budget audit** (`reds=N: …`) computed while drawing.
- State sheets (every tile is labelled with its states.md id):
  - `glass_states_data_bg.png`: BG, age, trend, delta, the bg.low vs bg.urgent_low test tile
  - `glass_states_data_values.png`: graph, IOB, KH, basal, TT
  - `glass_states_warnings.png`: connection, loop, pump, phone, status slot W1–W10, three worst-case red-budget tiles
  - `glass_states_ball.png`: bolus ball state machine, all guard verdicts, shape comparison at 450 and 384
  - `glass_states_ambient.png`: ambient, low-bit, burn-in, the shared SimpleUi reference (8 tiles)
  - `glass_states_units_locale.png`: mmol/mg/dL, de/en/fr, every display toggle, picker preview
  - `glass_states_multi.png`: AAPSClient datasets 1/2, secondary low/urgent/very stale, switched
  - `glass_states_shapes.png`: round 384–480, square, rect, flat tyre incl. multi.three (real device pixels)
- Implementation: a native `WatchFaceService` (`GlassWatchface`) next to `HaloWatchface`. It shares `FaceState`, `BolusTapGesture` and `BolusRequestGuard` with Halo. A CWF zip can't do the tap, the armed state or the separate ambient look. The label is "AAPS Glass" (not translated).

---

## 0. Signature and principles

Frosted glass cards float over a night gradient. Behind the hero card sits an **aurora** whose hue is the BG range: green in range, amber high, red low, grey when stale. You read the state from the light before you read the number. Glass has no rings, arcs or ticks, because those belong to Halo and Orbit.

Calm rules for Glass:
1. One accent hue at a time, the range hue. Everything else is white, grey-blue text, the IOB blue (glyph and label only), the TT violet or the indigo ball.
2. Nothing moves except the armed countdown, the 150 ms send flash, the 1,5 s graph caption and 200 ms value cross-fades. No pulsing or blinking, ever.
3. One status line (the footer). Warnings replace or prefix it. They never add a second banner.
4. Colour is never the only carrier. Every amber or red state also has a word (TIEF, HOCH, Loop 23′), a number, a strike-through, a hollow or dashed shape, a doubled outline or a glyph.
5. **Red budget**: at most two red groups on screen: the BG accent group (digits, TIEF word/badge, chip, sparkline end, urgent card edge count as one) and the status slot. A secondary block in low adds its own red outline; that is the only allowed third.

---

## 1. What changed

### 1.A Against the old Glass spec (conformance with states.md §6)

| # | Old Glass | Now | Why |
|---|---|---|---|
| 1 | range #78E6BE / #FFB840 / #FF5C5C | **#3DDC97 / #FFB547 / #FF5A6E** (family) | one colour table |
| 2 | armed ball amber #FFB840 | **indigo #5B63F5 solid fill + white countdown ring** | amber means "look soon" |
| 3 | `,` replaced by `.` | **decimal separator exactly as the phone sends it** ("2,53", "0,85 U/h", "+0,1"); the ball amount uses the watch locale ("+0,5") | units.phone_locale |
| 4 | countdown 1500 ms | `BolusTapGesture.WINDOW_MS` (800 ms today); the spec names the constant, never a number | code is the source |
| 5 | `kusi_bolus_double_tap`, `kusi_bolus_amount` | **`halo_bolus_double_tap`, `halo_bolus_amount`** (family-wide) | one pref for all faces |
| 6 | footer run "90%" (profile %) | **removed** | not in the contract (G3) |
| 7 | TT #E8D28A | **tt #B388FF** | family token |
| 8 | loop ring coloured in ambient | **grey ring, weight carries the state** (§9) | ambient has no hue |
| 9 | only "Telefon getrennt" override | **full hierarchy W1–W10 + W3b** (§7) | status slot |
| 10 | trend arrows from a font | **face-drawn vector glyph** (§4.3) | double/triple variants, no tofu |
| 11 | COB label "COB" | **"KH"** in German, "COB" elsewhere | strings |
| 12 | ambient footer shows basal | **ambient footer = W1–W6 (+W3b) or TT only** | ambient keep-list |
| 13 | 4 background bitmaps | **6**: in, high, high+, low, low+, stale | emphasis step |
| 14 | square: black corners | **night gradient full-bleed** | Glass material |

### 1.B Family-wide critique decisions applied in v2 (merge into states.md)

| # | Topic | Decision (all three faces) | Glass realisation | Tile |
|---|---|---|---|---|
| F1 | bg.low cue | Every fresh low has a non-colour hero cue: the word **TIEF** under the digits; digits tinted **range.low #FF5A6E** (never white) | §4.1 | bg.low |
| F2 | bg.urgent_low | Urgent is a distinct step: **filled red TIEF badge** (white text) + heavier track. `range.low.text #FFD6DC` is **dropped** | badge + 2 px red card edge + aurora +20 % | bg.urgent_low, "bg.low vs bg.urgent_low" |
| F3 | Ball gating | New guard verdicts **REFUSED_LOW_BG** (URGENT_LOW, or LOW with a falling trend / negative delta), **REFUSED_NO_PROFILE**, **REFUSED_LOOP_OFF** → DISABLED + toast with the reason | §5 | ball.disabled (…) |
| F4 | Ball states by shape | IDLE hollow/translucent + indigo text; ARMED solid #5B63F5 + white text + "nochmal" + countdown; SENT solid indigo outline + "…" + static ¾ ring; DISABLED dashed grey outline + diagonal slash + txt3 | §5 | ball.shapes |
| F5 | multi.switched | Every dataset-0-only element (IOB, KH, basal, TT, LOOP pill, reservoir, phone battery, W4–W9) is removed next to the other patient's BG and moves into dataset 0's block; hero patient name is a **header chip** | §10.4 | multi.switched |
| F6 | Secondary low | Secondary block in low: **doubled outline** + down arrow; urgent low: + filled **TIEF** marker. Secondary urgent low or very stale (≥ 30′) **escalates to the face slot** as **W3b** ("Anna TIEF 2,8", "Ben · Keine Daten 42′"), alert, below W1–W3 | §7, §10 | multi.secondary_* |
| F7 | urgent low vs W5/W6 | Fresh low suppresses **W7–W9 only**. W1–W6 always show, also during urgent low (states.md line 69 "everything below W4" is wrong) | §7 | prio: urgent low + loop.bad |
| F8 | SimpleUi | One shared reference: time top, digits txt1, range hue on the arrow only, age line (warn when aging), strike + "Keine Daten · NN′", "Telefon getrennt · NN′", TIEF word/badge, ambient doubled frame (low) / dashed frame (high) | §9.3 | sys.simplified · * |
| F9 | age.none | Slot text **"Kein BG"** in warn, rank W3 | §7 | age.none |
| F10 | W10 during low | The phone-off glyph is **exempt** from low suppression | §4.10 | conn.phone_lost_fresh + bg.low |
| F11 | Stale reds | When W2/W3 owns the slot, the hero age is **txt2**; when W2/W3/W6 owns it, the loop element is a **neutral grey ring with txt2 number** | §4.4, §4.6 | conn.phone_silent (worst case) |
| F12 | loop.bad colour | openApsStatus is the last TBR time, so 15–29′ is **warn** ("Loop 23′" amber), **alert from 30′** | §7 | loop.bad 15-29′, ≥30′ |
| F13 | IOB colour | **#5AC8FA lives on the glyph/label, the value is txt1** (zero/unknown txt3) | §4.7 | iob.* |
| F14 | Pill labels | Labels are **never dropped** (rule 2). Text floor 11 px physical; the value steps down first | §4.6, §11 | shape.round_384 |
| F15 | Flat tyre + blocks | Basal run dropped, layout moves up 16 px, block bottom ≥ 6 px above the chin | §11 | shape.flat_tyre · multi.three |
| F16 | Ball disabled table | MAX_BOLUS → disabled 40 %-look + toast; phone lost / stale → always disabled, never hidden (unless pref off) | §5 | ball.disabled (MAX_BOLUS) |
| F17 | Strings | pill "aus" lower case; W10 glyph only on every face; German unit spacing "0,85 U/h", "120 %", "18 U", "KH 24 g", "Tel 18 %"; "Reservoir" in both tables | §4.8, §12 | cob.values, basal.* |
| F18 | Zero basal during low | "0,00 U/h" / "0 %" in **txt2** while a fresh low shows; warn only in range or high | §4.8 | basal.zero_abs + bg.low |
| F19 | Ambient marks | No "!" suffix on loop ages (removed from Glass) | §4.6 | mode.ambient_warning · loop.bad |
| F20 | sentinel_high | **HOCH** caption (range.high) under "22,2+" / "400+" | §4.1 | bg.sentinel_high |

---

## 2. Canvas, tokens, fonts

Design canvas: **450×450 px** round (Galaxy Watch5 Pro, density 320, 1 dp = 2 px). All coordinates below are design px, origin top-left, centre (225,225). Scaling is in §11.

### 2.1 Colour tokens used by Glass (states.md §3, minus range.low.text)

| Token | Hex | Glass use |
|---|---|---|
| range.in | #3DDC97 | aurora, BG glow, chip, band, sparkline end |
| range.high | #FFB547 | same slots, BG > highLine; HOCH caption |
| range.low | #FF5A6E | same slots + **BG digits**, TIEF word, TIEF badge fill, urgent card edge, low block outline |
| range.stale | #8A93A8 | every range slot while stale/none; neutral loop ring |
| warn / alert | #FFB547 / #FF5A6E | age, loop, footer warnings, zero basal |
| ins | #5AC8FA | IOB drop glyph + "IOB" label (not the value) |
| tbr | #3DDBD9 | percent TBR run |
| tt | #B388FF | TT run |
| pred | #AA82FF | prediction dots |
| ball.idle | #7888FF | idle rim, idle text (#AEB6FF for the amount), sent outline/ring |
| ball.armed | #5B63F5 | armed solid sphere + glow |
| loop.ok | #40D68C | loop dot |
| txt1 / txt2 / txt3 | #EEF1F7 / #9AA6C4 / #6F7A99 | text levels |
| label | #8E99B8 | LOOP / LGS labels (Glass tint of txt2) |
| delta | #C8D2E6 | delta text (Glass-only) |
| amb1 / amb2 / amb3 / ambLine | #E0E0E0 / #9A9A9A / #6E6E6E / #3C3C3C | ambient |

Material: night gradient #141A33 (y 0) → #0B0F22 (y 248) → #04050B (y 450). Accent blobs violet #5A46C8 α85, blue #2A5AC8 α60. Glass overlays white with alpha only.

### 2.2 Fonts
Roboto Flex (or Inter) with tabular figures (`tnum`). Mockups use Avenir Next as a stand-in and Arial Unicode for the prime `′`. Weights: time 400 (ambient 200), BG 600 (ambient 400; ambient urgent low 600), meta/footer 500, pill values 600, labels 600 +0,05 em, TIEF/HOCH 700.

**Text floor**: 11 px physical for every string (`FLOOR = 11 / s` design px). Applies to pill labels, block names/ages, the ball sub-label and the name chip.

---

## 3. Layout

### 3.1 Single patient

| Element | Geometry (450 px) | Notes |
|---|---|---|
| Time | centre (225,58), 42 px | y=74 when the date is off |
| Date | centre (225,92), 18 px Medium | |
| Bolus ball | centre (46,176), r=30; hit r=44 | interactive only |
| Hero card | rounded rect (92,110)-(358,240), r=30 | glass |
| BG digits | centre (184,176); **(184,169) when a hero word shows** | auto-fit, §4.1 |
| Hero word | centre (184,225) | TIEF / HOCH, §4.1 |
| Trend chip | centre (312,152), r=22 | (312,175) when the meta line is empty |
| Meta line | centre (312,200), 18 px Medium | delta · age |
| Phone-off glyph | centre (312,224), 8×14 | W10 only |
| Sparkline | plot rect (84,256)-(366,312) | |
| LOOP pill | **(96,324)-(220,366)**, r=21 | widened from 113 to 124 px so labels always fit |
| IOB pill | **(230,324)-(354,366)**, r=21 | |
| Single pill (other hidden) | (163,324)-(287,366) | |
| Footer = status slot | centre (225,390), 18 px Medium, max width 257 px | chord at 0.93 R |

Top 24 px (12 o'clock rim) carries nothing essential (sys.notification_dot).

### 3.2 Multi-patient (dataset 1 and/or 2 present)

| Element | Normal | Flat tyre (360×330) |
|---|---|---|
| Name chip (hero) | centre (184,128), 14 px, BG digits +6 px | same |
| Sparkline | (84,252)-(366,290) | (84,246)-(366,278) |
| Pills | y 300–336, r=18, values 18 px | y 286–320, r=17 |
| Footer | y=354, 16 px | y=338, **basal run dropped** |
| Blocks | y 368–406 | **y 352–390** (bottom = 312 physical, ≥ 6 px above 324) |

### 3.3 multi.switched
Hero = dataset 1 with its name chip. Sparkline and both pills are **removed**. Dataset 0's block (200×50, x 125–325, y 262–312; two blocks: 150×50 at x 72 and 228) sits in the graph area. Footer at y=390 carries only W1–W3b.

---

## 4. Elements

"Phone string" means the string exactly as sent, after `display()` (states.md §1) maps placeholders to "--". In de/fr, `unitSpace()` inserts U+202F (narrow no-break space) between a number or ")" and a unit (`U/h`, `U`, `%`, `g`); the decimal separator is never touched. English strings are left as sent.

### 4.1 BG digits and hero word
- **Source**: `SingleBg[0].sgvString` (text), `sgv` (mg/dL, logic), `timeStamp` (age), `low`/`high` (fallback 70/180).
- **Format**: phone string. `sgv >= 400` appends "+". "---" when: first start, `timeStamp == 0`, or bgAge ≥ 60 min.
- **Size**: 84 px DemiBold, −2 px steps until width ≤ 170 px (≤ 230 px with the chip off), floor 56 px. Measure, never count characters.
- **Glow**: `setShadowLayer(12, 0, 0, range α0x96)`; low/urgent α0x5A (so red digits stay crisp). Off when stale/none and in ambient.

| State | Digits | Hero word at (184,225) | Card |
|---|---|---|---|
| bg.in_range / high / very_high | white | — | glass |
| bg.low | **#FF5A6E** | **"TIEF"** 14 px Bold #FF5A6E (text only) | glass |
| bg.urgent_low (incl. sentinel_low) | **#FF5A6E** | **filled badge**: pill #FF5A6E, h 22, r 11, padding 10, "TIEF" 14 px Bold white | glass + **2 px #FF5A6E edge (α230)** |
| bg.sentinel_high | white + "+" | **"HOCH"** 14 px Bold #FFB547 | glass |
| age.stale / very_stale < 60′ | txt2 | — | 3 px strike-through at y+2, 4 px overhang |
| age.very_stale ≥ 60′, age.none, first_start | "---" txt2 | — | |

While a hero word shows, the digits move up 7 px. The word sits under the digits (it never overlaps the comma descender; verified at 384–480).

- **Ambient**: digits amb1, weight 400 (urgent 600). TIEF word amb1. Urgent badge: filled amb1 with black text (AMBIENT, LOW_BIT); **outlined** 1,5 px amb1 with amb1 text in BURN_IN. Burn-in digits outlined (1,5 px). Strike 2 px. HOCH amb1.

### 4.2 Aurora and background
- **Source**: `FaceState.bgRange`.
- **Interactive**: gradient + range blob ellipse (130,100)-(320,250) α150 blur 40, core (150,120)-(260,220) α120 blur 26, violet and blue accents, rim circle inset 3 px white α22 (round only).
- **Emphasis** (very_high, urgent_low): blob and core alpha ×1,2. No new hue, no motion.
- **Stale/none**: range.stale blobs.
- **Implementation**: 6 pre-rendered bitmaps (in, high, high+, low, low+, stale) at the real screen size in `onSurfaceChanged`, with the frosted card/pill regions baked in. The urgent card edge is drawn live (one `drawRoundRect`). Swap only on key change. No runtime blur.
- **Ambient**: none.

### 4.3 Trend chip
- **Source**: `slopeArrow` mapped by `FaceState.trend`.
- **Chip**: circle r=22, fill range α40, 2 px stroke range α210. Ambient: 1 px amb3 outline.
- **Glyph** (vector, 2,6 px round caps, 20 px, chevron 8 px at ±42°): FLAT, UP45, UP, DOWN45, DOWN; UP2/DOWN2 second chevron 7 px behind; UP3/DOWN3 double chevron at 3,4 px. Colour range hue (ambient amb1; burn-in/low-bit ≤ 2 px). Tile trend.glyphs.
- **Empty chip**: trend.none, stale, none, first start.
- **trend.hidden_pref**: chip removed, BG at (225,166), meta at (225,220); empty meta → BG (225,175).

### 4.4 Meta line (delta · age)
- **Source**: delta string per prefs (`key_show_detailed_delta`, `key_show_avg_delta`, else delta). Age = `FaceState.bgAgeMin`.
- **Format**: `<delta> · <age>`, 18 px Medium, separator txt3. Age "1′", "17′", "3 Std", "2 T" (en "3 h", "2 d"). Slot fits "-12,5 · 47′".

| State | Delta | Age |
|---|---|---|
| age.fresh | #C8D2E6 | txt2 (hidden if `key_show_ago` off) |
| age.aging (6–11′) | delta | **warn**, shown even if `key_show_ago` is off |
| age.stale / very_stale, slot owned by W2/W3 | hidden | **txt2** (F11: the slot already says "· 17′") |
| age.stale while W1 owns the slot (theoretical) | hidden | alert |
| age.none / first_start | hidden | hidden |
| delta.missing | "--" txt3 | as above |

- **Ambient**: delta amb2, separator amb3, age amb2 (fresh) / amb1 (aging and older).

### 4.5 Sparkline
- **Source**: `GraphData.entries` (dataset 0), `TreatmentData.predictions`. Window `key_chart_time_frame` (mockups 3 h: 36 + 12 points). "Now" at x = 84 + 35/47·282.
- **Y**: 45–252 mg/dL clamped, y 312 → 256.
- **Layers**: target band y(high)..y(low) r=8 range α24 · low/high dashes 3/5, 1 px white α46 · now line dashed 2/4 white α60 · history 2,5 px gradient #5AAAFF → range hue, glow r=4 · prediction dots r=1,6 pred α210 · current point white r=4 with 2 px range ring + glow r=7.
- **Honest position**: last point at now − age.

| State | Rendering |
|---|---|
| graph.normal | as above |
| graph.empty | band, dashes, now line |
| graph.gaps (> 15′) | line broken |
| graph.no_predictions | no dots: empty list, `key_prediction_lines` off, loop open, client/pumpcontrol, stale |
| graph.timeframe_changed | caption "3 Std" 16 px DemiBold in a capsule (#0B0F22 α230, 1 px white α60) for 1,5 s, interactive only |
| stale | grey end point, no glow |
| multi.switched | **removed** (dataset 0's block takes the area) |

- **Ambient**: no band; dashes/now line ambLine; history 1,5 px #B4B4B4; point black with 1 px amb1 ring; no dots. Low-bit: history and point only.

### 4.6 LOOP pill
- **Source**: `Status[0].openApsStatus` (age), `externalStatus` (loopRun), `LoopStatesList` (mode), flavour. All via `FaceState.loopHealth`.
- **Layout**: dot at (x0+18, mid) r=5. Value left at dot+12, 20 px DemiBold (multi 18), **stepping down to 15 px** until value + label fit. Label "LOOP"/"LGS" right-anchored at x1−12, 12 px DemiBold (floor 11 px physical), **always drawn**.

| State | Dot | Value | Label |
|---|---|---|---|
| loop.ok (≤ 5′) | filled loop.ok + glow r=9 α190 | "2′" txt1 | LOOP |
| loop.warn (6–14′) | filled warn + glow | "8′" warn | LOOP |
| loop.bad (≥ 15′) — W6 owns the slot | **hollow 2 px range.stale ring** | "23′" **txt2** | LOOP |
| any OK/WARN while **W2/W3/W6** own the slot | hollow range.stale ring | txt2 | LOOP |
| loop.never (−1) | hollow range.stale | "--" txt3 | LOOP |
| loop.open | hollow loop.ok ring | "offen" txt2 | LOOP |
| loop.lgs | as ok/warn/bad | minutes | **LGS** |
| loop.off | hollow warn ring + two pause bars | "aus" warn | LOOP |
| conn.no_profile | hollow range.stale | minutes txt2 | LOOP |
| loop.unknown_mode (client) | age-based | minutes | LOOP |
| loop.no_aps (pumpcontrol) | **pill hidden**, IOB pill centred | | |
| multi.switched | **pill removed** | | |

The pill never shows red (F11): the loop problem is said once, in the slot.

- **Ambient**: OK/never/neutral/open/BAD 1 px amb2 ring; WARN/OFF 2 px amb1 ring; OFF keeps the pause bars; value amb1; label amb3. **No "!" suffix.** Low-bit: rings 1 px white, labels white.
- **Tap (P2)**: `ActionLoopStatusDetailed`.

### 4.7 IOB pill
- **Source**: `Status[0].iobSum` (phone string), parsed with comma support only to pick the colour.
- **Layout**: insulin **drop glyph** (8×11, filled #5AC8FA) at (x0+18, mid); value at glyph+11, 20 px DemiBold stepping down to 15 px; label "IOB" in **#5AC8FA**, right-anchored at x1−12, always drawn.

| State | Value |
|---|---|
| iob.normal (> 0), iob.negative | txt1 |
| iob.zero ("0,00") | txt3 |
| iob.large ("12,40") | txt1, value steps down to fit beside the label |
| iob.unknown / no profile / first start | "--" txt3 |
| iob.detailed (pref) | footer run "(2,10\|0,43)", low priority |
| iob.hidden_pref | pill removed, LOOP centred |
| multi.switched | pill removed (dataset 0's IOB is in its block) |

- **Ambient**: glyph amb2 (burn-in: outlined), value amb1, label amb3, 1 px ambLine outline. Low-bit: white, no outline.

### 4.8 Footer (status slot + indicator runs)
One line at (225,390), 18 px Medium (floor 11 px physical), max width **257 px** (0.93 R chord). Runs joined by "  ·  " (txt3).

**A.** W1–W6 and W3b own the whole line in their colour (§7). Too wide → shrink to 16 px, then ellipsise.

**B.** Otherwise these runs, lowest keep-priority dropped first:

| Run | Source | Condition | Format (de) | Colour | Keep |
|---|---|---|---|---|---|
| W7/W8/W9 owner | reservoirLevel / batteryLevel | owns the slot, no fresh low | "Reservoir 5 U", "Tel 18 %" | alert / warn (battery < 10 %: alert) | 100 |
| TT | tempTarget, level | level 2, `key_show_temp_target` | "TT 5,6", "TT 4,4 - 5,0" | tt | 90 |
| Basal | currentBasal | `key_show_temp_basal` (not on flat-tyre multi) | "0,85 U/h", "120 %" | txt2; "%" → tbr; **"0 %"/"0,00…" → warn, but txt2 during a fresh low**; "--" → txt3 | 50 |
| KH/COB | cob | `key_show_cob`, not "0g" | "KH 24 g", "KH 20(30) g"; unknown "KH --" | txt2 / txt3 | 40 |
| Phone battery | battery | `key_show_uploader_battery`, level 1 | "Tel 64 %" | txt2 | 25 |
| Reservoir | reservoirString | `key_show_reservoir_level`, level 0 | "Reservoir 120 U" (en "Res 120U") | txt2 | 20 |
| IOB detail | iobDetail | `key_show_detailed_iob` | "(2,10\|0,43)" | txt2 | 15 |
| BGI | bgi | `key_show_bgi` | "BGI -1,2" | txt2 | 10 |
| Rig battery | rigBattery | `key_show_rig_battery` | "Rig 80 %" | txt2 | 5 |

- tt level 0/1 and unknown hidden. pump.res_unknown never shown, never red. Battery "0" = unknown, hidden. `status.external` text is never drawn.
- **multi.switched**: no runs at all (all are dataset 0).
- **Ambient**: only W1–W6/W3b (amb1), else only TT (amb2), else empty.

### 4.9 Time and date
- Time: system 12/24 h. 12 h: "2:07" + "PM" 17 px Medium txt2, 7 px lower; composite centred.
- `key_show_seconds`: ":07" 17 px txt2, interactive only.
- Date: German `EE d. MMM` without dots → "Fr 17. Okt". Other locales `getBestDateTimePattern(locale,"EEEdMMM")`. `key_show_week_number` → " · KW 42" (en "W42").
- Date off: time at y=74. Ambient: time weight 200 amb1, date amb2. Tap → MainMenuActivity.

### 4.10 Phone-off glyph (W10, conn.phone_lost_fresh)
Outline phone 8×14 with a slash, 1,3 px txt2, at (chip x, 224). Interactive only. **Shown also during a fresh low** (F10); it is a glyph, not slot text. Glyph only on every face (no text).

---

## 5. Bolus ball (dataset 0 only, request only)

**Prefs**: `halo_bolus_double_tap` (default off) and `halo_bolus_amount` ∈ `BolusTapGesture.ALLOWED_AMOUNTS` (user: 0,5). Amount in the **watch** locale ("+0,5" de, "+0.5" en). Unit line "U" (Q1). One amount string family-wide: `"+" + amount` over the unit line.

**Geometry**: centre (46,176), sphere r=30, countdown/progress ring r=35. Hit area r=44 clipped to the screen; > 8 px from the graph tap zone; never covers the BG digits.

**Guard** (extends `BolusRequestGuard.Verdict`): HIDDEN beats DISABLED beats IDLE.

| Verdict | When | Ball | Toast on tap (string) |
|---|---|---|---|
| (hidden) | ambient, preview, headless, wearControl off, amount not allowed, first start, multi.switched | absent | — |
| REFUSED_NO_PHONE | phoneLink LOST (fresh or stale) | DISABLED | `halo_bolus_refused_phone` |
| REFUSED_STALE_DATA | bgAge ≥ `DATA_FRESH_MS` or none | DISABLED | `halo_bolus_refused_stale` |
| **REFUSED_LOW_BG** (new) | bgRange URGENT_LOW, or LOW with trend DOWN45/DOWN/DOWN2/DOWN3 or deltaMgdl < 0 | DISABLED | `halo_bolus_refused_low_bg` "BG zu niedrig. Keine Anfrage gesendet." |
| **REFUSED_NO_PROFILE** (new) | noProfile | DISABLED | `halo_bolus_refused_no_profile` "Kein Profil. Keine Anfrage gesendet." |
| **REFUSED_LOOP_OFF** (new) | loopHealth OFF (disabled, suspended, pump disconnected or superbolus: indistinguishable, G6) | DISABLED | `halo_bolus_refused_loop_off` "Loop aus. Keine Anfrage gesendet." |
| REFUSED_MAX_BOLUS | amount > phone max | DISABLED | `halo_bolus_refused_max` |
| REFUSED_PENDING | within `PENDING_MS` | stays SENT | `halo_bolus_refused_pending` |
| SEND | otherwise | IDLE / ARMED | — |

A low that is flat or rising (tile "ball.idle (low, flat)") keeps the ball idle; the phone runs its own checks on any request (see Q4). loop.never stays idle (pumpcontrol and manual use are legitimate).

**States by shape (F4)** — tile ball.shapes (450) and (384):

| State | Shape | Text | Extra |
|---|---|---|---|
| ball.off / hidden | absent, face symmetric | | |
| **IDLE** | **hollow glass**: radial #1C2060 → #5560C8 at **α56**, **2 px #7888FF rim**, soft rim glow, faint specular α36 | "+0,5" 18 px Bold **#AEB6FF**, "U" 11 px DemiBold #7888FF | |
| **ARMED** | **solid** radial #3A40C8 → #8C93FF α245, glow #5B63F5 α150 r=7, specular α70, 1 px white rim | "+0,5" white, **"nochmal"** 11 px white (**"2×"** when s < 0.92) | countdown ring r=35: track 2 px white α60, progress 3 px white from 12 o'clock, 360° → 0° over `WINDOW_MS`, 30 fps, invalidate (0,128)-(92,224) only |
| SENT flash | as SENT, 3 px white rim | | 150 ms + haptic |
| **SENT** | translucent fill α40, **2 px solid #7888FF outline** | **"…"** 24 px Bold txt1, "+0,5 U" 11 px txt2 | **static ¾ ring** r=35, 2 px #7888FF α170, 12 → 9 o'clock; until `PENDING_MS` |
| **DISABLED** | no fill, **dashed outline** 1,5 px txt3 (5 on, 4 off), **diagonal slash** 1,5 px txt3 α200 (NE → SW, 0,62 r) | "+0,5" / "U" txt3 | no glow; tap → toast, no arming |
| ball.delivering (P2) | SENT shape | "…" | progress ring r=35 2 px #7888FF from `BolusProgress.percent` (replaces the ¾ ring) |
| ball.delivered (P2) | IDLE shape | indigo check mark 3 px, 2 s | then IDLE |

Rules: the ball never uses range, warn or alert hues. Entering ambient calls `gesture.reset()`; the ball is never drawn or hit-tested in ambient. A second tap in the window sends `ActionBolusPreCheck(amount, carbs = 0)`; the phone checks and confirms. Glass never delivers anything.

---

## 6. State → Glass treatment index

Sheet abbreviations: bg = data_bg, val = data_values, warn, ball, amb, loc = units_locale, multi, shape.

| states.md id | prio | Glass treatment (summary) | tile |
|---|---|---|---|
| conn.first_start | P0 | "---" txt2, empty chip, pills "--", grey aurora, slot "Warte auf AAPS" txt2, ball hidden | warn, amb |
| conn.restored_old_data | P0 | age rules from the first frame (3 h → "---", W3 "Keine Daten · 3 Std") | bg |
| conn.no_profile | P0 | BG normal; IOB/basal "--"; loop hollow grey; W4 "Kein Profil" warn; **ball disabled** | warn, ball |
| conn.phone_ok | P0 | nothing | all |
| conn.phone_lost_fresh | P1 | phone-off glyph (also during a low); ball disabled | warn, ball |
| conn.phone_lost_stale | P0 | stale treatment + W2 alert; age txt2; loop neutral; ball disabled | warn, amb |
| conn.phone_silent | P1 | stale treatment + W3 alert; age txt2; loop neutral; reds = 1 | warn |
| bg.in_range / high | P0 | aurora, chip, band, line end, glow in range hue | bg |
| bg.very_high | P1 | high + aurora ×1,2 | bg, amb |
| bg.low | P0 | **red digits + TIEF word**; W7–W9 suppressed; zero basal txt2; ball disabled if falling | bg |
| bg.urgent_low | P0 | **red digits + filled TIEF badge + 2 px red card edge** + aurora ×1,2; W1–W6 still show; ball disabled | bg, warn, amb |
| bg.sentinel_low / high | P1 | urgent treatment / "+" + **HOCH** | bg, loc |
| age.fresh / aging / stale / very_stale | P0/P1 | §4.1, §4.4 | bg |
| age.none | P1 | "---" + **W3 "Kein BG" warn**; loop neutral | bg |
| trend.* / delta.* | P0/P1 | §4.3, §4.4 | bg, loc |
| units.mmol / mgdl / phone_locale | P0 | phone strings, auto-fit BG, de/fr unit spacing | loc |
| graph.* | P0/P1 | §4.5 | val |
| iob.* | P0/P1 | §4.7 | val |
| cob.values | P0 | "KH 24 g" | val |
| basal.* | P0/P1 | §4.8 colours | val |
| tt.* | P0/P1 | TT run, violet | val |
| loop.* | P0/P1 | §4.6, W5/W6 | warn |
| pump.res_low / res_critical / res_unknown | P1/P0 | W9 / W7 first run; unknown hidden | warn |
| batt.phone_low | P1 | W8 "Tel 18 %" warn, < 10 % alert | warn |
| multi.* | P1 | §10 | multi |
| flavor.full / client / pumpcontrol | P0 | client: age-only loop dot, no predictions; pumpcontrol: loop pill hidden | warn |
| mode.* | P0/P1 | §9 | amb, ball |
| pref.* | P0/P1 | §8 | loc |
| sys.simplified | P0 | shared SimpleUi reference (§9.3) | amb |
| sys.preview | P0 | 6,4, +0,1 1′, loop 2′, IOB 1,25, 10:08, "Fr 17. Okt", no ball | loc |
| locale.de_24h / en_12h / long | P0/P1 | §4.9; slot strings fit 257 px | loc |
| tap.graph / menu / ambient | P0 | §5 geometry; graph tap = MainMenu, double tap = time frame | ball |

P2 ids are handled as in states.md. Glass: res_ok, rig, bgi are footer runs; layout not mirrored for RTL; numbers stay LTR.

---

## 7. Status slot hierarchy on Glass

Evaluated top-down (`FaceState.statusSlot`). The first match owns the footer.

| Rank | Footer (de) | Colour | Ambient | Notes |
|---|---|---|---|---|
| W1 | Warte auf AAPS | txt2 | yes | |
| W2 | Telefon getrennt · 14′ | alert | yes | loop pill neutral, hero age txt2 |
| W3 | Keine Daten · 17′ | alert | yes | loop pill neutral, hero age txt2 |
| W3 | **Kein BG** (age.none) | **warn** | yes | loop pill neutral |
| **W3b** | **Anna TIEF 2,8** / **Ben · Keine Daten 42′** (secondary urgent low / very stale ≥ 30′) | alert | yes | first matching secondary, dataset 1 before 2 |
| W4 | Kein Profil | warn | yes | ball disabled |
| W5 | Loop aus | warn | yes | ball disabled |
| W6 | Loop 23′ | **warn 15–29′, alert ≥ 30′** | yes | loop pill neutral |
| W7 | Reservoir 5 U + runs | alert | no | suppressed by a fresh low |
| W8 | Tel 18 % + runs | warn / alert < 10 % | no | suppressed by a fresh low |
| W9 | Reservoir 18 U + runs | warn | no | suppressed by a fresh low |
| W10 | (glyph in the card) + runs | txt2 | no | **not** suppressed by a low |

- W1–W6 (and W3b) replace the whole footer. W7–W9 become the first run.
- **Fresh low/urgent low** (bgAge FRESH or AGING): W7–W9 suppressed. **W1–W6 always show** (F7). Test rows:

| Input | Expected footer | Tile |
|---|---|---|
| urgent low 2,8 + loop 34′ | "Loop 34′" alert, TIEF badge, pill neutral; reds = 2 | prio: urgent low + loop.bad |
| low 3,4 + loop 23′ | "Loop 23′" warn; reds = 1 | loop.bad + bg.low |
| low 3,4 + res 5U | "TT 8,0 · 0,00 U/h" (W7 hidden, basal neutral) | warn.suppressed_by_low |
| low 3,4 + phone lost fresh | glyph under the meta line | conn.phone_lost_fresh + bg.low |
| stale 17′ (phone reachable) | "Keine Daten · 17′"; age grey; pill grey; reds = 1 | conn.phone_silent (worst case) |

- **multi.switched**: only W1–W3b (watch-level and other-patient alarms). W4–W9 are dataset 0's and live in its block (loop dot, IOB).

---

## 8. Display preferences (on, on without data, off)

| Pref | On | On, no data | Off (reflow) |
|---|---|---|---|
| key_show_date | date line | n/a | time to y=74 |
| key_show_week_number | " · KW 42" | n/a | absent |
| key_show_seconds | ":07" interactive | n/a | absent |
| key_show_direction | chip | empty chip | chip removed, BG and meta centred |
| key_show_delta | delta | "--" txt3 | meta = age only |
| key_show_avg_delta / detailed_delta | picks the delta string | "--" | n/a |
| key_show_ago | age | "--" | hidden only while FRESH |
| key_show_iob | IOB pill | "--" txt3 | pill removed, LOOP centred |
| key_show_detailed_iob | footer run | omitted | absent |
| key_show_cob | "KH 24 g" (0g omitted) | "KH --" | absent |
| key_show_temp_basal | basal run | "--" txt3 | absent |
| key_show_temp_target | TT run (level 2) | hidden | absent |
| key_show_bgi | "BGI -1,2" | omitted | absent |
| key_show_uploader_battery | "Tel 64 %" | hidden ("0") | absent, **W8 still shows** |
| key_show_rig_battery | "Rig 80 %" | omitted | absent |
| key_show_reservoir_level | "Reservoir 120 U" | hidden | absent, **W7 still shows** |
| key_prediction_lines | dots | none | none |
| key_chart_time_frame | x scale | n/a | n/a |
| key_show_external_status | ignored (mapped to W1/W4/W5) | | |
| key_show_bg, key_dark, key_match_divider, graph layer prefs | ignored on Glass | | |
| simplify_ui | sys.simplified replaces Glass (§9.3) | | |

pref.all_on: footer TT · KH · basal at 450; BGI, Reservoir, Tel, Rig, iobDetail dropped by width. pref.minimal: time, card, sparkline, LOOP pill; no holes.

---

## 9. Ambient variants

Common: pure black; no gradient, aurora, glow, glass fill, prediction dots, band, ball, toasts or caption. Once a minute; ages at draw time; exiting ambient recomputes before the first frame. Kept: time, date, BG + hero word, chip glyph, meta line, sparkline line, pills (glyph, value, label), footer (W1–W6/W3b or TT), patient blocks (name + BG + age + low shapes).

### 9.1 Range as shape (no hue)

| Range | AMBIENT | LOW_BIT | Hero word |
|---|---|---|---|
| in | solid 1 px ambLine card | no outline | — |
| high | dashed 6/5, 1 px amb2 | dashed white | — |
| very_high | dashed 2 px amb1 | dashed white | HOCH only at sentinel |
| low | doubled outline (5 px apart) 1 px amb2 | doubled white | TIEF amb1 |
| urgent_low | doubled, inner 2 px amb1; BG weight 600 | doubled white | **filled** amb1 badge, black text (burn-in: outlined) |
| stale | solid ambLine + 2 px strike + slot text | strike + text | — |

Secondary blocks: low → doubled amb1 outline; urgent → + filled TIEF marker (burn-in outlined). Low-bit: white.

### 9.2 Variants and measured lit pixels (luma > 24)

| Variant | Differences | Lit |
|---|---|---|
| AMBIENT | greys, AA on | 3,2 % |
| LOW_BIT | 1 bit, AA off, white only; pill outlines, dashes, now line, ambLine dropped; labels kept | 1,6 % |
| BURN_IN | outlined digits (1,5 px), hollow dot, outlined badge/glyph, strokes ≤ 2 px, no filled area > 4×4 px, shift ∈ [−4,4]·s px per minute | 3,0 % (in) / 4,1 % (urgent low) |
| LOW_BIT + BURN_IN | union | 1,9 % |

All under the 10 % budget.

### 9.3 sys.simplified (shared SimpleUi reference, F8)
Same layout on every face; only the BG font varies (Glass: the hero font).

| Element | Interactive (charging) | Ambient |
|---|---|---|
| Time | centre (225,112), 46 px, txt1 | weight 200, amb1 |
| BG | centre (200,222), 104 px DemiBold, **txt1** (stale txt2) | amb1, weight 400 (urgent 600) |
| Arrow | vector, ×1,3, **range hue** (the only hue) | amb1 |
| Stale | 3 px strike, no arrow | same |
| Line at y=318 | W2/W3 text (alert) › TIEF word / badge (+ age 20 px txt2 below) › age 22 px (txt2, warn when aging) | amb1 |
| W10 | phone-off glyph at (225,368) | amb2 |
| Range shape | — | doubled frame around BG = low (inner 2 px urgent), dashed = high |

Tiles: sys.simplified · in, aging, low, urgent_low, stale, phone_lost, ambient low, ambient urgent.

---

## 10. AAPSClient multi-patient (datasets 1 and 2)

### 10.1 Visibility
Shown only when `SingleBg[1/2].timeStamp > 0` and `key_include_external` is on. Dataset 0 keeps hero, sparkline, pills, footer and ball.

### 10.2 Block (100×38, r=12, glass)
- Row 1 (y+11): name 12 px DemiBold txt2 (floor 11 px physical), left; age right (txt2 / warn / alert by the block's bgAge). Urgent low: a filled **TIEF** marker (#FF5A6E, white 10,5 px Bold, r=7) replaces the age.
- Row 2 (y+28): BG 18 px DemiBold in the block's range hue (stale txt2 + strike), vector arrow ×0,55, loop dot r=3,5 (ok/warn filled, ≥ 15′ hollow; warn < 30′, alert ≥ 30′).
- **Low** (F6): outer outline 1,5 px #FF5A6E + inner outline 3 px inside, 1 px. Urgent: outer 2 px.
- Name: `patientName` cut at 10 + "…"; empty → "P2"/"P3".
- Hero name: header chip (§3.2), shown only when a block exists or when switched.
- Units: each block uses its own SingleBg string.
- @384: blocks drop the loop dot; with two blocks the age shows only when not fresh.

### 10.3 Escalation (W3b)
A secondary **urgent low** or **very stale (≥ 30′)** writes "Anna TIEF 2,8" / "Ben · Keine Daten 42′" into the face slot (alert, rank between W3 and W4, ambient yes). Stale (12–29′), low, high and loop states stay inside the block.

### 10.4 multi.switched (P2)
Hero = dataset 1 with the name chip. Removed: sparkline, predictions, both pills, every footer run, W4–W9, ball. Dataset 0's block (200×50, 14 px name, 24 px BG, IOB with drop glyph, loop dot + minutes) sits at y 262–312. If dataset 0 goes low, its block doubles red; urgent escalates via W3b ("Markus TIEF 2,8").

---

## 11. Scaling and screen shapes

- `s = diameter / 450`, diameter = `min(width, height)`. Geometry, fonts, strokes × s; strokes ≥ 1 px; text ≥ 11 px physical.
- **Round** 384 (Pixel Watch 1/2), 396 (GW 40 mm), 416, 450 (design), 454/456/466, 480 (GW 47 mm): proportional. At 384–416 the pill labels stay (floor 11 px), values step down 1 px at a time to 15 px design if needed ("12,40" fits at 384); the ball sub-label becomes "2×".
- **Square/rect** (360, 450, 368×448): face in the inscribed circle, centred; **night gradient full-bleed**; circle edge feathered (2 % blur); no rim circle.
- **Flat tyre** (360×330 visible): circle diameter = width. Single patient: nothing moves (footer bottom about 319 physical, above 324). **Multi**: layout §3.2 flat column, basal run dropped, block bottom at design 390 = 312 physical (≥ 6 px above 324).
- **Safe radius**: essential text inside 0.86 R; footer on the 0.93 R chord.
- **Bitmaps**: 6 aurora variants + glass chrome generated at the real size in `onSurfaceChanged`.

---

## 12. Strings (add to `strings_face.xml` + `values-de`; en / fr given for width tests)

| Key (proposed) | de | en | fr |
|---|---|---|---|
| face_status_waiting | Warte auf AAPS | Waiting for AAPS | En attente d'AAPS |
| face_status_phone_lost | Telefon getrennt · %1$s | Phone disconnected · %1$s | Téléphone déconnecté · %1$s |
| face_status_no_data | Keine Daten · %1$s | No data · %1$s | Aucune donnée · %1$s |
| **face_status_no_bg** | Kein BG | No BG | Pas de glycémie |
| **face_status_secondary_low** | %1$s TIEF %2$s | %1$s LOW %2$s | %1$s BAS %2$s |
| **face_status_secondary_stale** | %1$s · Keine Daten %2$s | %1$s · No data %2$s | %1$s · Aucune donnée %2$s |
| face_status_no_profile | Kein Profil | No profile | Aucun profil |
| face_status_loop_off | Loop aus | Loop off | Boucle arrêtée |
| face_status_loop_age | Loop %1$s | Loop %1$s | Boucle %1$s |
| face_status_reservoir | Reservoir %1$s | Reservoir %1$s | Réservoir %1$s |
| face_status_phone_battery | Tel %1$s %% | Phone %1$s%% | Tél %1$s %% |
| face_low | TIEF | LOW | BAS |
| face_high | HOCH | HIGH | HAUT |
| face_loop_open | offen | open | ouverte |
| face_loop_off | aus | off | arrêt |
| face_cob | KH | COB | GA |
| face_age_hours / days | %1$d Std / %1$d T | %1$d h / %1$d d | %1$d h / %1$d j |
| halo_bolus_again | nochmal | again | encore |
| **halo_bolus_again_short** | 2× | 2× | 2× |
| **halo_bolus_refused_low_bg** | BG zu niedrig. Keine Anfrage gesendet. | BG too low. No request sent. | Glycémie trop basse. Aucune demande envoyée. |
| **halo_bolus_refused_no_profile** | Kein Profil. Keine Anfrage gesendet. | No profile. No request sent. | Aucun profil. Aucune demande envoyée. |
| **halo_bolus_refused_loop_off** | Loop aus. Keine Anfrage gesendet. | Loop off. No request sent. | Boucle arrêtée. Aucune demande envoyée. |

Unit spacing (de/fr): U+202F between number and `U`, `U/h`, `%`, `g`. Ages use the prime U+2032 ("17′"); bundle a font that has it or draw it.

---

## 13. Rendering and update cadence (implementation)

- Pattern "store, redraw and refresh only what changed" (commit fe418ea985).
- **Minute tick**: time, date, ages, loop minutes, phoneLink timer, stale check (can swap the aurora bitmap, enable/disable the ball), sparkline x-shift on 5-minute boundaries.
- **New SingleBg[0]**: BG, hero word, chip, meta, range key, sparkline end, slot, ball verdict. **SingleBg[1/2]**: blocks, W3b.
- **New Status**: pills, footer, slot, ball verdict (noProfile, loop off). **GraphData / TreatmentData**: sparkline.
- **LoopStatesList**: LGS label, "offen", loop off. **Preferences** (`wearControl`, `unitsMgdl`): ball visibility. **CapabilityClient**: phoneLink (W2/W10, ball).
- Armed: 30 fps for at most `WINDOW_MS`, ball rect only. Sent flash: one 150 ms frame. Cross-fade 200 ms on a new BG.

### 13.1 FaceState additions (pure Kotlin, unit-tested like HaloFaceLogic)

```kotlin
enum class BgRange { IN, HIGH, VERY_HIGH, LOW, URGENT_LOW, STALE }
enum class HeroWord { NONE, LOW_WORD, LOW_BADGE, HIGH_WORD }          // TIEF / TIEF badge / HOCH
data class Slot(val rank: Float, val text: String?, val tone: Tone)   // rank 1..10, W3b = 3.5
enum class Tone { NEUTRAL, WARN, ALERT }

fun heroWord(range, sentinel) = when {
    range == URGENT_LOW -> LOW_BADGE; range == LOW -> LOW_WORD
    sentinel == HIGH -> HIGH_WORD; else -> NONE }
fun statusSlot(…): Slot?            // §7, incl. "Kein BG", W3b, W6 tone by 30′, low suppresses 7..9 only
fun loopIsNeutral(slot) = slot?.rank in setOf(2f, 3f, 6f)
fun heroAgeTone(bgAge, slot) = if (slot?.rank in 2f..3f) NEUTRAL else …
fun zeroBasalTone(freshLow) = if (freshLow) NEUTRAL else WARN
fun ballVerdict(…): Verdict          // §5 table, order: hidden → NO_PHONE → STALE → LOW_BG → NO_PROFILE → LOOP_OFF → MAX_BOLUS → SEND
```

Unit-test rows: every line of the §7 test table, plus `ballVerdict(LOW, DOWN45) == REFUSED_LOW_BG`, `ballVerdict(LOW, FLAT, delta 0) == SEND`, `ballVerdict(URGENT_LOW, FLAT) == REFUSED_LOW_BG`.

---

## 14. Open items

1. **multi.switched** now has no hole (dataset 0's block fills the graph area), but dataset 1 still has no graph (contract has GraphData for dataset 0 only).
2. **Blocks are small** (12 px names at the 11 px floor at 384). The W3b escalation carries the urgent cases; the block alone is not glanceable.
3. **conn.phone_lost_fresh** glyph (8×14) is easy to miss; intentional while data is fresh. A one-time toast on the first loss is suggested.
4. **loop.off** can't tell disabled / suspended / pump disconnected / superbolus apart (G6); the ball refuses for all of them (REFUSED_LOOP_OFF), which may be stricter than the phone.
5. **REFUSED_LOW_BG** for a falling low uses the trend or a negative delta; a slightly negative delta on a flat low will disable the ball. Phone-side checks remain authoritative.
6. **"22,2+"** drops to 60 px; the HOCH caption compensates. Accepted.
7. **Ambient range shapes** are a learned code; the number and the TIEF word stay primary.
8. **Flat tyre ball** hit circle is clipped by the left edge at 360 px; geometry checked only.
9. **locale.rtl**, ru, ar not rendered (no font in the toolchain).
10. **Prediction dots #AA82FF vs TT #B388FF** remain the same family; flagged.
11. Halo and Orbit must apply §1.B too, and states.md needs the merge (line 69, §4.6 verdicts and shapes, §177 switched, §4.9 W3b, drop #FFD6DC).

## 15. Questions for the user

- **Q1** Ball unit in German: "U" (current values-de strings) or "IE".
- **Q2** Should the 54 / 250 mg/dL limits (urgent low / very high) become watch settings (gap G1)?
- **Q3** (resolved by F11) The stale hero age is grey while W2/W3 owns the slot. Confirm.
- **Q4** Should REFUSED_LOW_BG also cover a plain low that is not falling (stricter), or stay as designed (falling only)?

## 16. Implementation amendments, Glass/Orbit UX round 3

These override the sections above where they differ (code: `GlassFaceLogic`, `GlassWatchface`).

- **Five text tiers, as on Halo:** time, date, BG, delta, then the stats row. The footer line (y 402, 188 px chord) appears only when it has something to say: a non-numeric W-level warning, a TT, or a non-default basal (TBR % or a zero rate). A healthy reservoir and phone battery are no longer always-on runs.
- **One owner per fact:** interactive, a bad loop is said only by the red loop glyph and its red age. The footer keeps "Loop aus", "Kein Profil" and "Reservoir 5 U". Ambient keeps the "Loop 23′" footer.
- **Sparkline:** no plate. In range, the band is a 10 % tint with no hairlines. Values sit 9 px inside y 292..348. The trace is coloured by the value zone of each point (amber / green / red, never the insulin blue), and the history fades from 45 % to 100 %.
- **Stats row:** y 372 (12 px lower), x 90..360.
- **Trend chip:** a 12 % disc with a 1 px rim at 30 % of the state colour.
- **Card:** the neutral frost is 30 % fainter (top 24, bottom 8).
- **Date:** 22 px in TXT2, at y 99.
