# AAPS Orbit: state-complete implementation spec (v3, family edition, critique applied)

Version 3 · 17 Oct 2026 · design only. This replaces v2 (backup: `orbit_spec_v2_before_critique.md.bak`) and `designs/orbit_spec.md` (v1). Where this file and `states.md` disagree, §15 lists the family amendments that `states.md` still has to take over. Every other conflict should be reported, not coded around.

- Generator (source of truth for every number): `designsystem/orbit_states.py`. To re-render, run `uv run --with pillow python3 orbit_states.py [sheet…]`.
- State sheets (each tile is labelled with its `states.md` id; the subtitle states the rule shown, and for the worst-case tiles it gives the red count):
  - `orbit_states_data.png`: BG (including the low and urgent-low badges and a **bg.low vs bg.urgent_low** comparison tile), age, trend, delta, graph and first start
  - `orbit_states_indicators.png`: IOB, COB, basal (including zero basal during a low), TT, LGS, all-on, minimal
  - `orbit_states_warnings.png`: connection, loop (amber 15–29′ / red ≥ 30′), red-budget worst cases, pump, phone, suppression, French truncation
  - `orbit_states_ball.png`: every bolus-ball state, a side-by-side **idle | disabled | sent | armed** comparison, every disabled reason with its toast, and the tap zones
  - `orbit_states_ambient.png`: ambient, low-bit, burn-in, both, multi in ambient, and the **SimpleUi reference** (`sys.simplified` ×9)
  - `orbit_states_units.png`: mmol, mg/dL, sentinels (TIEF / HOCH), an English phone on a German watch, de/en/fr, date and week, seconds, preview
  - `orbit_states_multi.png`: datasets 1 and 2 (low, urgent low escalation, very stale escalation), AAPSClient, switched
  - `orbit_states_shapes.png`: round 384/396/416/450/456/480 (including 384 urgent low), square 360/450, rect 368x448, flat tyre 360x330
- Data contract: `EventData.SingleBg`, `Status`, `GraphData`, `TreatmentData`, `LoopStatesList`, `Preferences`, `BolusProgress`, `ActionBolusPreCheck`.
- Every derived signal comes from the shared `FaceState` helper (states.md §1, plus the §15 additions `loopSevere`, `lowFalling`, `phoneGlyph`, `ballVerdict` and the secondary-dataset `secState`). Orbit never computes its own thresholds. `derive()`, `sec_state()`, `status_slot()` and `ball_verdict()` in the generator are a reference copy of that helper, and the Kotlin unit tests should mirror them.

---

## 1. Geometry and scaling

- Design canvas 450x450, centre (225,225). Angles run clockwise from 12 o'clock: `P(r,deg) = (225 + r·sin deg, 225 − r·cos deg)`.
- Scale `s = min(w, h) / 450`. For flat tyre, `s = w / 450` and the circle centre is `(w/2, w/2)`. Multiply all geometry, fonts and strokes by `s`.
  - Strokes are at least 1 px.
  - Text is at least 11 px physical. This floor already applies at 384, where the 14 px ball labels become 12 px.
- Safe radius: essential text stays inside r 0.86·R (193 at 450). Ball rims, the urgent-low double track and the loop dot of a secondary patient may reach r 202 (0.9·R).
- Font: Google Sans Rounded, or a bundled Nunito / SF Rounded equivalent. Weights used: Light, Regular, Medium, Semibold, Bold.

| Shape | Rule | Sheet tile |
|---|---|---|
| Round 384–416 (Pixel Watch 1/2, GW 40 mm, 416) | s 0.853–0.924. Labels hit the 11 px floor. Below s 0.9 the secondary loop dot is dropped and the armed sub-label becomes "2×". | `round 384`, `round 384 all-on stale`, `round 384 urgent low`, `396`, `416`, `multi.three @384`, `ball.armed @384` |
| Round 450–480 (GW5 Pro, Pixel Watch 3, GW 47 mm, TicWatch) | s 1.0–1.067 | `450`, `456`, `480` |
| Square / rect (360, 450, 368x448) | Draw the face in the inscribed circle, centred. Corners stay black. Orbit does **not** use the corners: the status pill stays inside the circle. | `square 360`, `square 450`, `rect 368x448` |
| Flat tyre (WindowInsets.bottom > 0, e.g. 360x330) | Circle diameter = width. `date_y = min(393, 225 + (h − 6 − cy)/s − 9)`, so the date row sits above the chin. Nothing else is below r 150 at 6 o'clock. | `flat tyre 360x330`, `… ambient` |

Bitmaps (corona, ball shells, track with gaps) are rendered at the real size in `onSurfaceChanged` and never upscaled.

## 2. Layout map (450 canvas)

| Element | Position | Notes |
|---|---|---|
| Time | P(168,0) = (225,57) | Breaks the orbit track |
| Primary patient name | (225,87) | Multi only, not switched |
| Switched hero chip | (225,96), 24 tall | multi.switched only (§4.13) |
| Range badge (TIEF / HOCH) | Centre (225,124) | §4.2. Clear of the chip (bottom 108) and of the digits (top ≈ 148) |
| Hero BG + trend glyph | Centre y 186. The group is centred at x 225. | Width budget 252: BG plus 14 gap plus a 40 glyph |
| Meta row (delta · age · glyph) | y 256 | |
| Status pill | Slot A y 256 or slot B y 289 | See §4.5 |
| Sparkline | x 151..291 (now at 265). Full: y 279..323. Compact: y 309..335. | |
| Orbit track | r 168 (urgent low: r 165.5 + r 170.5) | Gaps at the time and the date |
| Balls (r 34) | LOOP 45°, IOB 90°, BASAL 135°, COB / P2 225°, TT / P3 315° | |
| Bolus button | 270° (57,225), 66x66, corner radius 16 | |
| Date | P(168,180) = (225,393) | Breaks the track |
| Minute moon | r 168, 6° per minute | Interactive only |

Positions never move between states or modes. An absent ball leaves the continuous track visible, so there is never a hole.

## 3. Colour usage (tokens from states.md §3 only)

- Hues: range.in #3DDC97, range.high/warn #FFB547, range.low/alert #FF5A6E, range.stale #8A93A8, ins #5AC8FA, tbr #3DDBD9, tt #B388FF, loop.ok #40D68C, ball.idle #7888FF, ball.armed #5B63F5.
- **range.low.text #FFD6DC is no longer a digit colour.** It survives only as `on_red_soft`, the text colour inside an alert pill (dark-red backing #3A1018). Text on a solid range.low fill (the urgent badge) is `on_red` #14060A.
- Text: txt1 #EEF1F7, txt2 #9AA6C4, txt3 #6F7A99.
- Ambient greys: amb1 #E0E0E0, amb2 #9A9A9A, amb3 #6E6E6E, ambLine #3C3C3C.
- Orbit materials (not hues):

| Material | Value |
|---|---|
| Track | #2A3446 |
| Ball shell fill | #0E131B |
| Neutral ring | #5A6B85 |
| Dim ring | #3A4250 |
| Moon | #F2F6FC |
| Time | #E6ECF4 |
| Date | #A9B4C4 |
| Pill fill: alert / warn / info | #3A1018 / #33240C / #141A25 |
| Pill text: alert / warn / info | #FFD6DC / #FFE7C2 / txt2 |

### 3.1 Red budget (exact, family rule 3)

There are at most **two red groups** on the face:

1. **The BG accent group** counts as one: digits, TIEF badge, corona, glow, trend glyph, sparkline line and the urgent double track.
2. **The status slot (pill).**

Every other element that could be red is neutral instead:

- **LOOP ball:** whenever the slot owns W2, W3 or W6, the ball is a neutral #5A6B85 outline with its number and label in txt2. In `loop.bad` the slot always owns W6 or a higher warning, so the LOOP ball is never red.
- **Secondary block in urgent low:** this is not a third group. Its message is the pill (W3.5), so the block and the pill count as one group (`multi.secondary urgent + primary low`).
- **Zero basal during a fresh low:** neutral, not amber (§4.9).

Red counts are written on the worst-case tiles: `prio urgent low + loop.bad` = 2, `loop.bad + bg.low` = 2, `conn.phone_silent` = 1, `conn.phone_lost_stale` = 1.

- No glow outside interactive. Glow is only in a range hue or the ball hue, radius 12 or less.

## 4. Elements

### 4.1 Corona and track (BG accent)
- **Corona:**
  - Shape: ellipse 240x200 centred at (225,215), blur 60, in the range hue at alpha 34.
  - VERY_HIGH and URGENT_LOW use alpha 41 (+20 %: the emphasis step).
  - Drawn only in interactive mode while bgAge is FRESH or AGING.
  - Pre-render 3 hues × 2 alphas.
- **Track (interactive):**
  - Normal: 1.5 px #2A3446 at r 168.
  - **URGENT_LOW:** a **doubled** track, two 1.5 px arcs at r 165.5 and r 170.5 in range.low at alpha 170, with the same gaps. This is the "heavier track" urgency step. It is static and belongs to the BG accent group.
  - Ambient: see §6.

### 4.2 Hero BG (sun) and range badge
- **Source:** `SingleBg[d].sgvString`, exactly as sent, with the phone's decimal separator. The renderer never replaces "," with "." (states.md §6.3; tile `units.phone_locale` shows "6.4" from an English phone on a German watch).
  - Sentinel HIGH (sgv ≥ 400): append "+" ("400+", "22,2+").
  - bgAge NONE, or age ≥ 60′: "---".
- **Font:** 108 px Bold. The group (BG + 14 + 40 glyph) must fit in 252. If it does not, step the size down by 4 until it fits (minimum 60). "22,2+" lands at about 88 px.
- **Digit colour:**

| bgRange / age | Digits |
|---|---|
| IN, HIGH, VERY_HIGH (fresh) | txt1. The range lives in the corona, glyph, glow and sparkline. |
| **LOW, URGENT_LOW (fresh)** | **range.low #FF5A6E** |
| STALE+ | txt2, with a 3 px strike-through at y +4 from the left of the BG − 6 to the right of the BG + 6. The trend glyph is removed. "---" is never struck. |

- **Range badge** (the non-colour cue, centre (225,124), 16 px Bold, padding 10, height 25, fully rounded, outline 1.6):

| State | Badge | Tile |
|---|---|---|
| bg.low (fresh) | **Outlined** "TIEF" (`face_low`), range.low outline and text | `bg.low` |
| bg.urgent_low (fresh), including bg.sentinel_low | **Filled** "TIEF": range.low fill, text `on_red` #14060A | `bg.urgent_low`, `bg.sentinel_low`, `units.mgdl 39 TIEF` |
| bg.sentinel_high (sgv ≥ 400) | **Outlined** "HOCH" (`face_high`) in range.high | `bg.sentinel_high`, `units.mgdl 400+` |
| anything else | none | |

- **How low and urgent low differ:** urgent low is a step of its own, not a tint. It has a filled badge instead of an outlined one, a doubled red track, a corona at +20 % and a double-headed glyph whenever fastFall is true. Tile `bg.low vs bg.urgent_low` shows both side by side.
- **Glow:** 12 px, range hue at alpha 120, or 150 for the emphasis step. Interactive and fresh only.
- **Ambient:**
  - Digits: Light in amb1. URGENT_LOW uses Regular (+1 weight step).
  - Burn-in: outlined text (black fill, stroke 2 px, or 3 px for URGENT_LOW).
  - Low-bit: white.
  - The badge stays. It is outlined in amb1 for LOW and HOCH, and filled amb1 with black text for URGENT_LOW. In burn-in nothing may be filled, so the urgent badge becomes a **doubled outline** (second outline 3 px further out). In low-bit the badge is white.
- **Tiles:** `bg.*`, `age.*`, `units.*`, `mode.ambient low / urgent_low`, `mode.ambient_burn_in urgent`.

### 4.3 Trend glyph (face-own, no font arrow)
- **Source:** `trend` from FaceState (slopeArrow mapping; "X" resolved by the sign of deltaMgdl).
- **Shape:** a vector shaft (0.62·size) plus a head (two 0.30·size strokes at ±42°), round caps, size 48·(BG size/108), stroke 4.6.
  - Angles: UP 0°, UP45 45°, FLAT 90°, DOWN45 135°, DOWN 180°.
  - `trend.fast` (UP2/DOWN2): a second head 0.24·size behind the tip. UP3/DOWN3: the same, with a stroke ×1.45.
- **Colour:** range hue. Ambient: amb1, stroke 2.
- **States:**
  - NONE, or `key_show_direction` off: no glyph, and the BG group re-centres (trend.none, trend.hidden_pref).
  - STALE+: removed.

### 4.4 Meta row (y 256, 21 px Medium; ambient Regular)
- **Parts:** `[delta] · [age] [· TT x] [phone-off glyph]`, with a txt3 separator "  ·  ".
- **Delta:**
  - Source: key_show_detailed_delta → `deltaDetailed`; key_show_avg_delta → `avgDelta`; otherwise `delta`. Shown as is ("+0,1", "-12,5", "+0,08").
  - Colour txt2. "--" or "" → "--" in txt3. Off → removed.
- **Age:**
  - Format: `fmt_age` ("1′", "17′", "3 Std", "2 T").
  - FRESH: txt2, and only when key_show_ago is on.
  - AGING (6–11′): warn, shown even when key_show_ago is off (`pref.minimal + aging`).
- **TT x:** tt colour. Only in `multi.three`, where the TT ball yields its place (§4.11).
- **Phone-off glyph (W10):** a 14x14 phone outline with a slash in txt2 (amb2 in ambient), 11 px after the last part.
  - Shown whenever `phoneLink == LOST_FRESH`, independent of the slot.
  - **Never suppressed by a fresh low** (`conn.phone_lost_fresh + bg.low`) and **kept in ambient** (`mode.ambient phone_lost_fresh`), because it is a glyph, not slot text.
  - W10 is glyph-only on every face; it never has slot text.
- **When STALE+, first start or age.none:** the row is not drawn, and the W1/W2/W3 pill takes slot A instead.
- **Ambient:** amb2. An AGING age is drawn in amb1.

### 4.5 Status slot (pill)
- **Content:** exactly one pill, from the hierarchy in §5.
- **Shape:** 30 tall, radius 15, padding 14, 18 px Semibold, outline 1.5.

| Tone | Used by | Fill / outline / text |
|---|---|---|
| alert | W2, W3, W3.5, W6 ≥ 30′, W7, W8 < 10 % | #3A1018 / #FF5A6E / #FFD6DC |
| warn | W3 "Kein BG", W4, W5, W6 15–29′, W6.5, W8, W9 | #33240C / #FFB547 / #FFE7C2 |
| info | W1 | #141A25 / #3A4250 / txt2 |

- **Placement:**
  - **Slot A (y 256)** replaces the meta row. It is used for rank ≤ 3, or whenever the meta row is empty. Maximum width 236, limited by the bolus button and the IOB ball.
  - **Slot B (y 289)** is used otherwise. The sparkline then switches to its compact box (y 309..335, the same x). Maximum width 290.
- **Overflow:** truncate the **words**, never the trailing " · N′". Use `ellipsize_keep_suffix`: split at the last " · ", keep the tail, and ellipsise the head. In French, "Téléphone déconnecté · 14′" becomes "Téléphone déconn… · 14′" (`locale.long (fr) W2`). Shorter translations ("Tél. déconnecté · {}") are still preferred and would avoid the cut.
- **Ambient:** ranks ≤ 6 only (W3.5 is kept; W6.5 and W7–W9 are dropped). The pill becomes a 1 px amb2 outline with no fill, and amb1 18 px Medium text. Low-bit: white.

### 4.6 Sparkline (graph)
- **Source:** `GraphData.entries` (dataset 0) and `TreatmentData.predictions`.
- **Window:** `key_chart_time_frame` hours, default 3.
- **X axis:** by timestamp, `x = 265 − minutesAgo/window·114`. A stale BG therefore ends left of "now", and the empty right side is honest.
- **Y axis:** fixed 45–252 mg/dL (2,5–14 mmol). Values outside are clamped, with a 3 px caret on the edge.
- **Targets:** `SingleBg.low/high` (fallback 70/180) as dashed lines (2 on, 4 off) in range.in at alpha 120. Interactive only. These are all that `graph.empty` shows.
- **Line:**
  - 2.5 px in the range hue, with a 4 px glow at alpha 150 when fresh.
  - STALE: range.stale, no glow.
  - Gaps over 15 min break the line, with no interpolation (`graph.gaps`).
- **Now dot:** r 4, white. Hollow (1.2 px #C8C8C8) when STALE+ or in ambient.
- **Predictions:** 5 dots, r 2.2, tt colour at alpha 220, across x 265..291.
  - Hidden when not fresh, when the list is empty (client, open loop, pumpcontrol), when key_prediction_lines is off (`graph.no_predictions`), or when switched.
- **multi.switched:** no line and no predictions. The targets plus a centred "Kein Verlauf" (`face_no_graph`, 14 px Medium txt3) are shown, so the empty band is explained.
- **Layers:** bolus, carbs and basal prefs are not drawn (states.md graph.layers). `isValid == false` treatments are never drawn.
- **Caption `graph.timeframe_changed`:** "5 Std" at 13 px Semibold txt2, top-left of the box, for 1,5 s. Interactive only.
- **Ambient:** history only, 1.5 px amb3 (white in low-bit), hollow now dot. No targets, predictions or captions.

### 4.7 LOOP ball (45°)
- **Visible when:** key_show_external_status is on and the face is not switched.
  - The v2 "forced on for loop.bad" exception is removed. W6 is always in the pill, and a forced grey ball would add nothing (`pref.minimal + loop.bad`).
- **Value:** 22 px Semibold, falling back to 17 Bold and then 14 Bold when wider than 56.
- **Label:** 14 px Semibold, **at most 50 wide**, stepping down 14 → 12 → 11. "BOUCLE" lands at 12 and never touches the ring (`locale.long (fr) labels`).

| State (id) | Ring | Value | Label |
|---|---|---|---|
| loop.ok / closed / unknown_mode, health OK | loop.ok 2 px | "2′" txt1 | LOOP (in ring colour) |
| loop.lgs | as ok | "2′" | **LGS** |
| loop.warn (6–14′) | warn | "9′" in warn | LOOP in warn |
| loop.bad (≥ 15′), or slot owns W2/W3/W6 | **neutral #5A6B85** | number in **txt2** | LOOP txt2. The pill carries the message. |
| loop.never (−1) / first start | dim, **dashed** | "--" txt3 | LOOP txt3. Never red. |
| conn.no_profile | dim | "--" txt3 | LOOP txt3 |
| loop.open | loop.ok, dashed 1.6 px | "Offen" (`face_loop_open`) | LOOP. The age is not an alarm. |
| loop.off | warn | pause glyph (two 4x14 bars) in warn; hollow in burn-in | **"aus"** (lower case, `face_loop_off_short`), plus pill W5 |
| loop.no_aps (pumpcontrol) | dim | "Pumpe" txt2 (17 px) | none |

- **Ambient:** amb3 outline (dashed for never/open), value amb1 Regular, label amb2.
- **Tap (optional, P2):** ActionLoopStatusDetailed. Hit circle r 36.

### 4.8 IOB ball (90°), key_show_iob
- **Source:** `Status.iobSum` through `display()`. With key_show_detailed_iob on, Orbit ignores `iobDetail`: there is no slot for it (documented).
- **Colour rule:** the **value is txt1**. Only the label and the arc carry ins #5AC8FA. This is Orbit's application of the family rule proposed in §15.
- **Ring:** dim 2 px plus a progress arc in ins, 3 px, starting at 12 o'clock clockwise, fraction = value/6 U capped at 1. The value is parsed with comma support; this is the only parse, and it is for the arc only.

| State | Value colour | Arc | Label |
|---|---|---|---|
| iob.normal | txt1 | value/6 | IOB in ins |
| iob.zero ("0,00") | txt3 | empty | IOB in ins |
| iob.negative | txt1, with minus | empty | IOB in ins |
| iob.large (≥ 6) | txt1, 17 px fallback ("12,40") | full | IOB in ins |
| iob.unknown | "--" txt3 | none | IOB txt3 |

- **Ambient:** kept, no arc. **Switched:** hidden (dataset 0 only).

### 4.9 BASAL ball (135°), key_show_temp_basal
- **Source:** `Status.currentBasal`. For display, a trailing "U/h" is stripped ("0,85U/h" → "0,85"). A trailing "%" gets a German space ("120%" → "120 %", `de_space`). Digits and the separator are never touched.

| State | Ring | Value | Label |
|---|---|---|---|
| basal.normal (incl. absolute TBR, G4) | neutral | "0,85" txt1 | BASAL txt2 |
| basal.tbr_percent | tbr | "120 %" | TBR in tbr |
| "0 %" with BG in range or high | warn | "0 %" | TBR in warn |
| basal.zero_abs ("0,00…") with BG in range or high | warn | "0,00" | BASAL in warn |
| **"0 %" / "0,00" during a fresh LOW or URGENT_LOW** | **neutral** | value txt1 | label txt2. Zero basal is the loop doing its job (`basal.zero_abs + bg.low`, `basal 0 % + bg.urgent_low`). |
| basal.unknown | dim | "--" txt3 | BASAL txt3 |

- **Ambient:** not drawn. **Switched:** hidden.

### 4.10 COB ball (225°), key_show_cob
- **Source:** `Status.cob`, with a German space before the "g" ("45g" → "45 g", "20(30)g" → "20(30) g", 14 px fallback). The label is `face_cob`: "KH" in German, "COB" in English.

| Value | Ring | Value colour |
|---|---|---|
| "0 g" | dim | txt3 |
| "45 g", "20(30) g" | neutral (**not amber**) | txt1 |
| "--g" or "" | dim | "--" txt3 |

- **Multi-patient:** gives its place to patient P2 (§4.13).
- **Ambient:** not drawn.

### 4.11 TT ball (315°), key_show_temp_target
- **Drawn only for `tt.active`** (tempTargetLevel 2):
  - Ring tt colour, value `tempTarget` with the spaces removed ("8,0", "4,4-5,0" at 14 px), label "TT" in tt.
- **Not drawn:** tt.profile (0), tt.aps_adjusted (1) and tt.unknown. The track shows through.
- **No profile-% fallback:** v1's "100%" / "Profil" is removed (G3).
- **multi.three:** the TT goes to the meta row as "TT 8,0" in tt colour.
- **Ambient:** kept, as an outline with the value. **Switched:** hidden.

### 4.12 Bolus button (270°), states.md §4.6 plus the §15 amendments
- **Prefs:** `halo_bolus_double_tap` (default off) and `halo_bolus_amount` ∈ {0.5, 1.0}. The user sets 0,5 U.
- **Timings:** referenced as constants, never written as numbers in code: `BolusTapGesture.WINDOW_MS` (800), `COOLDOWN_MS` (5000), `BolusRequestGuard.PENDING_MS` (30 000), DATA_FRESH 12 min.
- **Amount string (family-wide):** line 1 is the signed amount in the watch locale, "+0,5" (German) or "+0.5" (English), 21 px Bold. Line 2 is the unit "U", 14 px Semibold, or the state's sub-label. The unit "U" follows the existing values-de strings; "IE" is still open (§14).
- **Hit area:** circle r 44. It is at least 8 px from the graph zone (x 143) and never covers the BG digits.
- **Rule:** the fill state and the outline shape carry the state; alpha alone never does. Tile `idle | disabled | sent | armed` shows the four states side by side.

| State | Shape | Line 1 / line 2 | Tile |
|---|---|---|---|
| ball.off | Not drawn, no hit target. 9 o'clock shows the track. | | `ball.off` |
| ball.hidden (any ambient, headless/preview, !wearControl, amount not allowed, multi.switched, first start) | As off | | `ball.hidden` |
| **ball.idle** | **Hollow:** shell #0E131B, solid 2 px ball.idle #7888FF outline at full alpha | "+0,5" ball.idle / "U" ball.idle α 220 | `ball.idle`, `ball.idle 1,0 U` |
| **ball.disabled** | **Dashed** outline (7 on / 5 off, 1.8 px, txt3 α 190) plus a **diagonal slash** (2.2 px txt2) from bottom-left to top-right, no glow | "+0,5" / "U" in txt3 α 150 (≈ 40 %) | `ball.disabled *` |
| **ball.armed** | **Solid fill** ball.armed #5B63F5 with an 8 px glow at α 150. A 3.5 px white countdown runs along the outline from top-centre clockwise and drains over WINDOW_MS; the rest of the outline is white at α 60. About 10 fps only while armed. | "+0,5" white / "nochmal" white 13 px ("2×" when s < 0.9 or the word is wider than 56) | `ball.armed t=0`, `t=480 ms`, `ball.armed + bg.low flat`, `ball.armed @384`, `ball en` |
| **ball.sent** | Solid ball.idle outline at α 140, plus a **static** 3 px ball.idle arc of 16 % of the perimeter centred on top-centre | big "…" (30 px Bold, ball.idle) replaces the amount / "+0,5 U" 12 px txt2 | `ball.sent`, `ball.sent + tap` |
| ball.delivering (P2) | As sent, with the arc growing from top-centre by `BolusProgress.percent` | "45 %" ball.idle / "+0,5 U" txt2 | `ball.delivering` |
| ball.delivered (P2) | Solid ball.idle outline with a check mark, for 2 s | ✓ / "+0,5 U" ball.idle | `ball.delivered` |

- **Disabled verdicts** (`ballVerdict`, evaluated in this order on every frame and every data change; the first match wins):

| Verdict | Condition | Toast on tap (`halo_bolus_refused_*`) | Tile |
|---|---|---|---|
| NO_PHONE | phoneLink LOST_FRESH or LOST_STALE | `_phone` "Telefon nicht verbunden. Keine Anfrage gesendet." | `ball.disabled NO_PHONE` |
| STALE_DATA | data ≥ 12′, or bgAge STALE / VERY_STALE / NONE | `_stale` "Keine aktuellen Daten vom Telefon. Keine Anfrage gesendet." | `ball.disabled STALE_DATA` |
| NO_PROFILE | noProfile | **new** `_no_profile` "Kein Profil. Keine Anfrage gesendet." | `ball.disabled NO_PROFILE` |
| LOOP_OFF | loopMode off | **new** `_loop_off` "Loop aus. Keine Anfrage gesendet." | `ball.disabled LOOP_OFF` |
| **LOW_BG** | bgRange URGENT_LOW, **or** LOW with a falling trend (DOWN45, DOWN, DOWN2, DOWN3) | **new** `_low_bg` "BG zu niedrig. Keine Anfrage gesendet." | `ball.disabled LOW_BG (urgent)`, `(low falling)` |
| MAX_BOLUS | amount > maxBolus (`Preferences`) | `_max` "0,5 U liegt über dem Max-Bolus (0,3 U)" | `ball.disabled MAX_BOLUS` |

- A plain LOW that is flat or rising stays armable (`ball.armed + bg.low flat`). The phone still checks the request.
- The guard must refuse in `BolusRequestGuard.evaluate()` too, not only in the drawing. Add `REFUSED_LOW_BG`, `REFUSED_NO_PROFILE` and `REFUSED_LOOP_OFF` to `Verdict`.
- Phone lost or stale is **always disabled, never hidden**, unless the pref is off.
- **Taps:**
  - A tap on a disabled ball shows the toast and does not arm.
  - A tap in SENT shows `_pending` "Eine Anfrage läuft bereits. Bitte auf der Uhr prüfen." (`ball.sent + tap`).
- **Second tap in the window:** send `ActionBolusPreCheck(amount, 0)`. This is a request only; the phone checks it and the standard confirm screen follows. Add a white rim flash for 150 ms.
- **Disarm:** a tap elsewhere, the timeout, ambient or screen off disarms silently (`gesture.reset()`). A verdict that turns non-null while armed also disarms, to DISABLED.
- The ball always means dataset 0's pump. It is never amber, red or green, so it stays indigo next to a low BG.

### 4.13 Secondary patients (datasets 1 and 2), AAPSClient
- **Shown when:** `key_include_external` is on and `SingleBg[1|2].timeStamp > 0`.
- **Placement:** P2 is a ball at **225°** (COB is hidden while multi is active). P3 is a ball at **315°** (TT moves to the meta row).
- **Ball:** r 34, shell fill.
  - **Ring:** the patient's own range hue from `sgvLevel`. Range.stale when the reading is ≥ 12′.
  - **Name:** `patientName` at 12 px Semibold txt2, y−18, ellipsised to 50 ("Maximi…"). An empty name becomes "P2" / "P3".
  - **BG:** `sgvString` at 20 px (17/14 fallback) txt1, y+1. Stale: txt2 with a 2 px strike. ≥ 60′ → "---".
  - **Age:** 12 px at y+19. txt2, warn from 6′, alert from 12′.
  - **Loop dot:** r 4 at P(202, deg), coloured by that dataset's loop age: ok ≤ 5′, warn 6–29′, alert ≥ 30′. Hollow txt3 when −1. Dropped when s < 0.9.
- **Secondary low (shape cue, the same in every mode):** `secState` uses the same thresholds as FaceState, on the dataset's own sgv, level and trend.

| secState | Block | Face level | Tile |
|---|---|---|---|
| low (fresh, level −1) | **Doubled outline**: the ring is 2.5 px plus an inner 1.5 px ring at r 29.5 in the same hue. The BG is in range.low, shifted 5 left, with a 13 px **down-arrow** glyph to its right. | none (stays in the block) | `multi.secondary_low` |
| urgent (fresh, sgv < 54 or low + DOWN2/DOWN3) | As low, plus a **filled 11 px "TIEF" mini-badge** in place of the age | **Pill W3.5** "Tom TIEF 2,8" (alert) | `multi.secondary_urgent_low`, `… + primary low` |
| very stale (≥ 30′) | grey, struck | **Pill W6.5** "Mia: Keine Daten · 42′" (warn) | `multi.secondary_very_stale` |
| stale (12–29′) | grey, struck, alert age | none | `multi.secondary_stale` |

- **Isolation:** a secondary's loop and stale warnings stay inside its ball and never change the primary's colours (`multi + primary stale`). Only urgent low (W3.5) and very stale (W6.5) escalate to the slot.
- **Primary name:** `patientName` of dataset 0 at (225,87), 14 px Medium txt2, ellipsised to 120. It is shown only while a secondary is shown and the face is not switched.
- **Ambient:** amb3 outline with name, BG and age in amb greys, and no loop dot. The **doubled outline** still marks low. **Urgent** gets the filled amb1 mini-badge, which becomes a doubled outline in burn-in. The W3.5 pill is kept (`mode.ambient multi secondary low/urgent`).
- **multi.switched** (`key_switch_external`; tiles `multi.switched`, `multi.switched + hero low`):
  - The hero shows dataset 1. Its name is a **header chip** at (225,96): 18 px Semibold txt1, a 4 px person ring and a txt3 outline on pill_info, ellipsised to 140.
  - Dataset 0 moves into the 225° block, with its own name.
  - **Every dataset-0-only element is hidden:** the LOOP, IOB, BASAL and TT balls, predictions, the graph line ("Kein Verlauf" instead) and the bolus ball. No dataset-0 number can sit next to the other patient's BG.
  - The range badge (TIEF) of the hero sits under the chip at y 124.

### 4.14 Time (P(168,0))
- **Font:** 30 px Semibold #E6ECF4. Ambient: Regular amb1. Burn-in: Regular.
- **Format:** system 12/24 h.
- **12 h:** "PM" at 12 px Medium txt2, after the minutes with a 3 px gap (`locale.en_12h`).
- **key_show_seconds:** ":07" in the same style, interactive only (`pref.seconds`).
- **Track gap:** half-angle = (time width + suffix + 20)/2/168 rad.
- **Tap:** a single tap opens MainMenuActivity (`tap.menu`, zone x 150..300, y 35..80).

### 4.15 Date (P(168,180))
- **Font:** 18 px Medium #A9B4C4. Ambient: amb2.
- **German:** `ofPattern("EE d. MMM", GERMAN)` with the dots stripped → "Fr 17. Okt".
- **Other locales:** `getBestDateTimePattern(locale, "EEEdMMM")` ("Fri, Oct 17", "ven. 17 oct.").
- **key_show_week_number:** appends " · KW 42" / " · W42".
- **key_show_date off:** the date is removed and the track closes at 6 o'clock (`pref.date off`).
- **No warnings on the date line.**

### 4.16 Minute moon
- **Shape:** r 4.5 at P(168, minute·6), #F2F6FC. Interactive only.
- **Not drawn:** inside the time or date gap (± 2°).
- **Behind a ball:** when the angular distance is < asin(34/168) + 1.5° (for the bolus button: atan(36.3/168) + 1.5°), the dot is not drawn. Instead, draw a 44° rim highlight at ballR + 1, 1.6 px white at α 220, centred at `ballDeg + diff/halfAngle·90`.
- **Motion:** this and the armed countdown are the only motion. No pulsing, flashing or rotating warnings.

## 5. Status-slot hierarchy and overrides (what wins)

| Rank | State | Text (de) | Tone | Ambient |
|---|---|---|---|---|
| W1 | conn.first_start | Warte auf AAPS | info | yes |
| W2 | conn.phone_lost_stale | Telefon getrennt · 14′ | alert | yes |
| W3 | conn.phone_silent / age.stale+ | Keine Daten · 17′ | alert | yes |
| W3 | **age.none** (no BG ever, status present) | **Kein BG** (`face_no_bg`) | warn | yes |
| **W3.5** | **secondary urgent low** | **Tom TIEF 2,8** (`face_secondary_low`) | alert | yes |
| W4 | conn.no_profile | Kein Profil | warn | yes |
| W5 | loop.off | Loop aus | warn | yes |
| W6 | loop.bad 15–29′ | Loop 23′ | **warn** | yes |
| W6 | loop.bad ≥ 30′ | Loop 34′ | alert | yes |
| **W6.5** | **secondary very stale ≥ 30′** | **Mia: Keine Daten · 42′** (`face_secondary_stale`) | warn | no |
| W7 | pump.res_critical | Reservoir 5 U | alert | no |
| W8 | batt.phone_low | Tel 18 % | warn (< 10 %: alert) | no |
| W9 | pump.res_low | Reservoir 18 U | warn | no |
| W10 | conn.phone_lost_fresh | glyph only (§4.4) | txt2 | glyph kept |

1. **BG urgency lives in the hero** (badge, digits, corona, track, glyph). It never takes the pill.
2. **Fresh low or urgent low:** only **W7–W9 are suppressed.** **W1–W6 always show, even during urgent low**, together with W3.5. The W10 glyph is never suppressed.
   - Test row: urgent low + loop.bad 34′ → pill "Loop 34′" (`prio urgent low + loop.bad`).
   - Test row: low + res critical → no pill (`suppression (low + res crit)`).
   - Test row: low + phone lost fresh → glyph visible (`conn.phone_lost_fresh + bg.low`).
3. **Stale beats range:** once STALE+, no range hue appears anywhere.
   - The corona and the badge go.
   - The digits turn txt2 with a strike.
   - The glyph and the delta go.
   - The sparkline turns grey.
   - The predictions go, and the ball is disabled.
4. **Red budget:** see §3.1. When the slot owns W2, W3 or W6, the LOOP ball is neutral.
5. **Loop age is amber from 15′ and red from 30′.** Loop age comes from `openApsStatus`, which is the time of the last temp basal, so it can over-report while the loop is running (G7). Red is kept for ≥ 30′ so that it stays meaningful.
6. **Toggle exceptions that ignore their pref:** the aging/stale age, pump.res_critical (pill), batt.phone_low < 10 % (pill), and the W10 glyph.

## 6. Ambient variants (states.md §4.5)

- **Kept:**
  - time (no seconds)
  - date
  - BG digits and trend glyph (outlined stroke 2)
  - **range badge (TIEF / HOCH)**
  - meta row, including the W10 glyph
  - pill ranks ≤ 6 (including W3.5)
  - LOOP, IOB and TT balls (outlines)
  - secondary patients (name + BG + age, doubled outline when low, mini-badge when urgent)
  - sparkline history
- **Dropped:**
  - corona and glows
  - moon
  - bolus button (no drawing, no hit test)
  - COB and BASAL balls
  - IOB arc
  - targets, predictions and captions
  - toasts
  - secondary loop dots
  - W6.5–W9
- **Range shape** (colour is never the only signal):

| Range | Track | Badge |
|---|---|---|
| In range / stale | Solid 1 px ambLine (dropped in low-bit) | none |
| HIGH / VERY_HIGH | **Dashed** 6/6 in amb2, full track | HOCH outlined for sentinel only |
| LOW | **Doubled** (r 166 + r 171) in amb2 | TIEF outlined |
| URGENT_LOW | Doubled, plus Regular digits | TIEF filled (burn-in: doubled outline) |

- **Stale:** strike + "Keine Daten · 17′" pill text.

| Variant | Rules | Tiles |
|---|---|---|
| AMBIENT | Greys amb1/2/3/ambLine only, AA on | `mode.ambient *` |
| LOW_BIT | amb* → white, ambLine track dropped (only a dashed/doubled range track remains), AA off, 1-bit | `mode.ambient_low_bit *` |
| BURN_IN | BG digits outlined (no fill > 4x4), pause bars hollow, now dot hollow, urgent badge as a doubled outline, strokes ≤ 2 (urgent BG 3). The whole layout shifts by (dx,dy) ∈ [−4,4], with a new offset every minute. Pixel-on budget < 10 %. | `mode.ambient_burn_in *` |
| LOW_BIT_BURN_IN | The union of the two | `mode.ambient_low_bit_burn_in` |

- **Transition:**
  - Entering ambient hides the ball and calls `gesture.reset()`. Captions and toasts are dropped.
  - Leaving ambient recomputes the ages before the first frame.
- **Cadence:** ambient updates on `onTimeTick` and on new data.

### 6.1 `sys.simplified`: the shared SimpleUi reference (family; no Orbit variant)
Used while charging, or by simplify_ui (which can be active in ambient). Only the BG font may vary per face. Tiles: the `sys.simplified *` rows on the ambient sheet.

| Element | Position | Rule |
|---|---|---|
| Time | (225,112), 44 px Regular | txt1 / amb1 |
| Range badge | (225,160) | The same TIEF badge as §4.2: outlined for low, filled for urgent low |
| BG digits | (225 − 22, 226), 112 px (face font, Regular; Light in ambient) | **txt1, never in the range hue.** STALE+: txt2 with a 3 px strike (2 px in ambient). "---" from 60′. |
| Trend glyph | right of the digits + 26 | The **only** range-hue element. Ambient: amb1. Removed when stale. |
| Info line | (225,318), 22 px Medium | W2 or W3 text in alert ("Telefon getrennt · 14′", "Keine Daten · 17′"), or "Kein BG"; otherwise the age (txt2, warn when AGING, always shown). Add the W10 glyph after the age when phone lost fresh. Ambient: amb1/amb2. |

Low-bit: everything white, 1-bit. Burn-in: digits outlined as on the face, and the badge never filled.

## 7. Flavours (effects only, never labelled)

| Flavour | Orbit effect | Tile |
|---|---|---|
| full | Every state | most tiles |
| aapsclient | loop.unknown_mode. Loop age comes from NS, so WARN is common and adds no warning. Predictions often empty. Ball usually hidden (wearControl off) or refused by the phone. Multi-patient as in §4.13. | `flavor.client`, multi sheet |
| pumpcontrol | loop.no_aps ("Pumpe" in a dim ball, never red), no predictions, TT ball never level 1, ball possible | `loop.no_aps (pumpcontrol)` |

## 8. Toggles (on / on-without-data / off)

| Pref | On | On, no data | Off |
|---|---|---|---|
| key_show_delta | delta in the meta row | "--" txt3 | removed, age only |
| key_show_ago | age txt2 | "--" | hidden while FRESH; AGING+ is always shown |
| key_show_direction | glyph | no glyph (NONE) | no glyph, BG re-centred |
| key_show_iob | IOB ball | "--" dim | ball absent |
| key_show_cob | COB ball | "--" dim | absent |
| key_show_temp_basal | BASAL ball | "--" dim | absent |
| key_show_temp_target | TT ball when active | absent | absent |
| key_show_external_status | LOOP ball | "--" dashed | absent (loop.bad still shows the W6 pill) |
| key_show_date / week | date / " · KW 42" | n/a | removed, track closed |
| key_show_seconds | ":07" in interactive | n/a | none |
| key_show_reservoir_level, key_show_uploader_battery, key_show_rig_battery, key_show_bgi, key_show_detailed_iob | Not drawn: Orbit has no slot. Reservoir and phone battery appear only through W7–W9. | | |
| key_show_bg, key_dark, key_match_divider, graph layer prefs, key_show_ring_history, key_show_big_numbers | Ignored (documented) | | |
| halo_bolus_double_tap / amount | §4.12 | | |

- `pref.all_on` = all balls + TT + ball + KW + seconds. It fits at 384 (`round 384 all-on stale`).
- `pref.minimal` = BG + glyph + time, with track and sparkline. The track carries the face.

## 9. Taps (tile `tap zones`)

| Zone | Area | Single tap | Double tap |
|---|---|---|---|
| Graph | x 143..299, y 268..340 | MainMenuActivity | Cycle the time frame (800 ms) + caption |
| Time | x 150..300, y 35..80 | MainMenuActivity | |
| Bolus | r 44 at (57,225) | §4.12 | |
| LOOP ball | r 36 (optional) | ActionLoopStatusDetailed | |

Ambient does no hit testing.

## 10. Preview (`sys.preview`)

- **Sample:** "6,4", FLAT, "+0,1", 1′, loop 2′, IOB 1,25, COB 0 g, basal 0,85, 10:08, "Fr 17. Okt".
- **Excluded:** no ball and no services.
- **Config preview:** the same with the ball idle when the pref is on.

## 11. Update cadence

- **New BG:** hero, badge, meta row, sparkline, corona, track shape, ball verdict.
- **New Status:** only the changed ball, plus a pill and ball-verdict re-evaluation.
- **Every minute:**
  - time and moon (with the occluded rim)
  - age-driven states: AGING, STALE, VERY_STALE, ≥ 60′ "---", secondary W6.5
  - phoneLink, W10 glyph, ball verdict
  - burn-in offset
- **Midnight:** date.
- **Armed:** about 10 fps for WINDOW_MS only.
- **Pre-render:**
  - track with gaps: per gap width, for the urgent double track, and per range shape in ambient
  - corona (6)
  - ball shells (neutral, dim, dashed dim, each hue, doubled low)
  - bolus shells (idle hollow, disabled dashed + slash, sent with arc, armed fill)

## 12. Changes

### 12.1 v2 → v3 (critique applied)
1. **Low has a word.** A fresh low gets red digits plus an outlined "TIEF" badge. Urgent low gets a filled badge and a doubled red track. #FFD6DC is gone from digits.
2. **Sentinel high** shows an outlined "HOCH".
3. **Bolus guard:** LOW_BG (urgent, or low and falling), NO_PROFILE and LOOP_OFF disable the ball, each with a toast. Max-bolus is disabled (not idle + toast). Phone lost or stale is always disabled, never hidden.
4. **Bolus shapes:** idle is hollow, disabled is dashed with a slash, sent shows "…" with a static arc, armed is a solid fill. The amount string is "+0,5" over "U". The drop glyph is removed.
5. **multi.switched:** every dataset-0-only element is hidden. The hero name becomes a header chip, and the graph says "Kein Verlauf".
6. **Secondary low:** doubled block outline and a down arrow. Urgent low escalates to pill W3.5 and very stale to W6.5. Ambient blocks keep the shape cue.
7. **Suppression** is W7–W9 only. W1–W6 always show during urgent low, and the W10 glyph is never suppressed and is kept in ambient.
8. **age.none** gets "Kein BG" (warn, rank W3).
9. **Red budget:** the LOOP ball is neutral whenever the slot owns W2/W3/W6. The v2 alpha-110 "muted red" is removed, so the `loop.bad + bg.low` tile now matches the spec.
10. **Loop age** is amber 15–29′ and red ≥ 30′, and the forced-on LOOP ball exception is removed.
11. **SimpleUi** has one reference layout with age, W2/W3 text, the badge and the glyph. The range hue appears only on the arrow.
12. **Strings:** loop off is "aus" (lower case). German spacing gives "120 %", "45 g", "Reservoir 18 U" and "Tel 18 %".
13. **French:** truncation keeps " · N′", and the ring labels step down to fit 50 px.
14. **Zero basal during a low** is neutral.
15. **units.phone_locale** now really shows English phone strings on a German watch.

### 12.2 v1 → v2 (unchanged, summary)
- BG digits are txt1, and the strike starts at 12′.
- The hierarchy pill replaces "⚠ 17′ alt", "✕ Handy" and the date-line warnings.
- Decimal comma from the phone; COB is not amber.
- The TT ball is shown only for an active TT.
- One loop.off state; loop.never and no_aps added.
- The tap window is 800 ms (`WINDOW_MS`), the cooldown is `COOLDOWN_MS`, the hit radius is 44, and the prefs use `halo_bolus_*`.
- The trend glyph is a vector.
- Ambient range shapes; the date obeys its prefs; the IOB arc is added.

## 13. States not designed well, or not applicable

- **multi.three trade-off:** in AAPSClient with 3 patients, the primary's COB is not shown and its TT lives only in the meta row, because there are only 6 orbit slots.
- **Secondary loop dot:** r 4 at the bezel edge, dropped below s 0.9. A secondary loop problem is weakly signalled; only urgent low and very stale escalate.
- **Long locales:** the age is now preserved, but the words are cut ("Téléphone déconn… · 14′"). Shorter translations are still needed for fr/nl/ru.
- **Three reds in `multi.secondary urgent + primary low`:** the primary BG group, the W3.5 pill and the secondary block. The block and the pill are counted as one group (§3.1). Accepted, because two patients are low at once.
- **Not drawn by Orbit (no slot):** iob.detailed, status.bgi, batt.rig, batt.phone_ok, pump.res_ok, status.external free text, graph.layers.
- **Not visible in the push data:**
  - TT reason and high vs low TT (G3)
  - pump battery (G5)
  - sensor warm-up (G2; it shows as stale)
  - loop-off reason (G6)
  - real last-loop-run time (G7, see §15)
- **Other:**
  - locale.rtl is not mirrored (P2).
  - pref.dark, pref.match_divider and sys.font_scale are ignored.
  - age.clock_skew is clamped to fresh.
- **Mockup-only approximations:**
  - Low-bit is a threshold of the AA render.
  - The toast is a stand-in for the system Toast.
  - SF Rounded stands in for the final font.

## 14. Open questions for the user

- The ball unit in German: "U" (current values-de) or "IE".
- Whether 54 / 250 mg/dL (urgent low / very high) become watch prefs.
- Whether a plain LOW that is flat should also disable the ball. The current design keeps it armable; only urgent low or a falling low disables it.

## 15. Family amendments that `states.md` must take over (shared by Halo and Glass)

| § in states.md | Amendment |
|---|---|
| §2.2 bg.low | A fresh low has digits in range.low and an **outlined "TIEF" badge** near the digits. |
| §2.2 bg.urgent_low | A **filled "TIEF" badge** (range.low fill, text #14060A) and the face's heavier ring or track. **Replace "suppresses everything below W4" with "suppresses W7–W9 only; W1–W6 always show, even during urgent low".** Drop #FFD6DC as a digit tint (pill text only). |
| §2.2 bg.sentinel_high | An outlined "HOCH" badge (face_high is now used). |
| §2.3 age.none | Slot text "Kein BG" (warn, rank W3). |
| §2.10 multi.switched | Hide every dataset-0-only element. Show the hero name as a header chip, and show "Kein Verlauf" in the graph. |
| §3 tokens | Add `on_red` #14060A. Rename range.low.text to `on_red_soft` (pill text only). |
| §4.2 hierarchy | Add W3 "Kein BG", **W3.5 secondary urgent low** (alert, ambient yes) and **W6.5 secondary very stale ≥ 30′** (warn, ambient no). W6 is warn from 15′ and alert from 30′. W10 is a glyph that is never suppressed by a low and is kept in ambient. Low suppression is W7–W9. Add the three test rows from §5. |
| §4.2 red budget | The exact rule from §3.1: the BG accent group plus the slot. The loop element is neutral grey whenever the slot owns W2/W3/W6. |
| §4.4 strings | `face_low` TIEF, `face_high` HOCH, `face_no_bg` "Kein BG" (No BG), `face_secondary_low` "%1$s TIEF %2$s", `face_secondary_stale` "%1$s: Keine Daten · %2$s", `face_no_graph` "Kein Verlauf", `face_loop_off_short` "aus", `halo_bolus_again_short` "2×", `halo_bolus_refused_low_bg` "BG zu niedrig. Keine Anfrage gesendet.", `halo_bolus_refused_no_profile` "Kein Profil. Keine Anfrage gesendet.", `halo_bolus_refused_loop_off` "Loop aus. Keine Anfrage gesendet." German spacing: "0,85 U/h", "120 %", "18 U", "45 g", "Tel 18 %". Use "Reservoir" in both tables. Status text is truncated in its words, never in the trailing " · N′". |
| §4.4 IOB colour | The value is txt1; the label and arc or accent are ins. |
| §4.5 ambient | The range badge stays. Urgent is a filled badge, or a doubled outline in burn-in. Secondary blocks keep the doubled outline when low. |
| §4.5 / §2.13 sys.simplified | The SimpleUi reference in §6.1. |
| §4.6 ball | Add the verdicts LOW_BG, NO_PROFILE and LOOP_OFF, with order and toasts as in §4.12. MAX_BOLUS is disabled. Phone lost or stale is disabled, never hidden. The visuals are the shape table in §4.12: idle hollow, disabled dashed with a slash, sent "…" with a static arc, armed solid fill. The amount is "+0,5" over the unit, and armed shows "nochmal" (or "2×"). |
| §4.9 multi | The secondary low shape cue (doubled outline + down arrow; urgent mini-badge) and the two escalations. |
| §2.16 gaps | **G7 (priority):** the phone should send a real last-loop-run timestamp. Until then, loop age = openApsStatus = last TBR, amber 15–29′. |

## 16. Implementation amendments, Glass/Orbit UX round 3

These override the sections above where they differ (code: `OrbitFaceLogic`, `OrbitWatchface`).

- **Hero:** 118 px (about an 84 px cap). At 128 px the LOOP satellite sat 5-8 px from the first digit. `heroClear` now measures 14 px at the digits' box corner, 0.04 em inside. The arrow sits 0.12 em from the digits, and its box never goes below 36 px. The digits shrink first, and the stroke never goes below 7 px.
- **Satellites:** hairline rings (1 px #465064) on true black. Only an alarm (amber, red) keeps a 2 px coloured rim. **No fill arcs:** the IOB 6 U / COB 80 g fractions had no legend and read as progress or alarm cues. A known basal or KH value shows without a label ("0,85", "12g"). "--" keeps its label. LOOP and IOB keep theirs at 19 px.
- **Track:** a constant 6 px break before every satellite and the bolus button.
- **Sparkline:** no box. It uses Glass's adaptive log range: at least 60-220 mg/dL, widened to the visible values, clamped at 40/400. Values sit 8 px inside y 300..356. The target band is a 10 % tint with no edges.
- **Corona:** non-urgent alpha 150 (was 200).
- **Date:** 22 px in TXT2. A W4-W9 pill at 6 o'clock keeps the short date ("Sa 17.", 18 px) to its left, so the date never disappears.
- **Bolus button:** idle is the family's filled indigo sphere with white text. Armed is solid #5B63F5 with the white countdown rim. Disabled has a dashed rim and a faint slash under the text, with no text halo. Its 48 px hit circle never overlaps LOOP or KH (unit test).
- **Ambient:** all four satellites show as values (no discs). Under burn-in protection the in-range or stale ring is not drawn, and the urgent low ring is long-dashed.
