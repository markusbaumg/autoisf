# AAPS Halo: face spec (family version 2)

17 Oct 2026 · design only. Nothing in the repo, on the phone or on the watch was changed.

This spec says **where and in which shape** Halo draws each state. **What** state applies, its colour token and what wins come from `states.md` (the family contract); ids are the `states.md` ids. Version 2 applies the cross-face critique. Where a v2 rule is family-wide but `states.md` does not say it yet, it is marked **[family v2]** and listed in §14 so the same text can go into `states.md` and the Glass/Orbit specs. Where this spec and the current code (`wear/.../watchfaces/HaloWatchface.kt`, `halo/HaloFaceLogic.kt`, `halo/HaloLayout.kt`) differ, §12 lists the change.

Mockups (174 tiles, each 450 px, labelled with the state id and what to look at):

| Sheet | Tiles | Contents |
|---|---|---|
| `halo_states_data.png` | 34 | bg.* (incl. **bg.low vs bg.urgent_low side by side**, sentinels with TIEF / HOCH), age.* (incl. age.none "Kein BG"), conn.first_start, trend.*, delta.*, graph.empty, iob.*, cob, basal.* (incl. zero basal during a low), tt.*, conn.no_profile, sys.preview |
| `halo_states_warnings.png` | 29 | conn.phone_* (W10 glyph, also during a low), loop.* (ok / warn / **late 15–29′ amber** / **bad ≥ 30′ red** / never / open / lgs / off "aus" / no_aps / unknown_mode), pump.res_*, batt.phone_*, priority cases with red counts (urgent low + loop.bad / loop.late / loop.off), flavor.pumpcontrol |
| `halo_states_ball.png` | 24 | ball.off / hidden / idle / armed / sent / disabled, **idle · armed · sent · disabled side by side**, every guard verdict (NO_PHONE, STALE_DATA, MAX_BOLUS, **LOW_BG**, **NO_PROFILE**, **LOOP_OFF**), toasts, P2 delivering / delivered, ambient, 384 px variants |
| `halo_states_ambient.png` | 28 | mode.ambient (in / high dashed track / very high / **low doubled track + TIEF** / **urgent low boxed TIEF** / low vs urgent / stale / lost / loop.bad / loop.off / first start / TT / multi), low_bit, burn_in, low_bit_burn_in, mode.transition, **sys.simplified reference** (in / aging / stale / phone lost / low / urgent low / ambient low / ambient urgent) |
| `halo_states_units_locale.png` | 21 | units.mmol / mgdl (sentinels with TIEF / HOCH, mg/dL low), phone locale, locale.de_24h / en_12h / en low "LOW" / long, pref.date / week / seconds / all_on / minimal (age kept), toggle reflow |
| `halo_states_multi.png` | 18 | multi.one / two / three, **secondary_low**, **secondary_urgent_low** (escalated), secondary_stale, **secondary very stale ≥ 30′** (escalated), primary stale vs escalation, patient_name empty, **switched (chip, dataset-0 items hidden)**, @384, client primary, ambient with low / urgent, long primary name |
| `halo_states_shapes.png` | 20 | round 384 / 396 / 416 / 456 / 480, square 450 / 360, rect 368x448, flat tyre 360x330 (default, **pref.all_on**, W3, **multi.three**), ambient and burn-in on small screens, armed ball and simplified low at 384 |

Generator: `halo_render.py` (parametric renderer; the Python `FaceState` mirror is the block "FaceState" near the top: `classify`, `loop_health`, `status_slot`, `escalation`, `ball_guard`) and `halo_states.py` (sheet list). Run `uv run --with pillow python3 halo_states.py [sheet ...]`. The renderer draws with real alpha blending (RGB canvas + RGBA draw), so every "x %" in this spec is visible as such in the mockups.

---

## 1. Concept

One ring, one number. The BG is a 270° gauge ring (gap at the bottom), the knob sits at the current value, and the ring fill grows from the low end up to the knob in the range colour. A short grey trail inside the ring shows the last 30 minutes. Everything else is small and quiet: time and date above the number, trend · delta · age below it, IOB on a thin outer arc at 3 o'clock, one status line + loop pill + info line in the bottom gap, the optional bolus ball at 9 o'clock.

Calm rules specific to Halo:
- At most one glowing element group: ring fill + knob + digit halo, all in the same range hue. The ball has its own small glow only while idle or armed.
- **Red budget [family v2]**: at most two red element groups at once. Group 1 = the BG accent (ring fill, knob, digits, arrow, TIEF word/badge, chevrons count as one). Group 2 = the status line. Whenever the status line owns W2, W3 or W6, the loop pill is a neutral grey outline (`#4A5160`, hollow txt3 dot, number txt2), whatever the loop age. Each priority tile on the warnings sheet states its red count.
- Colour is never the only signal: every low has the word "TIEF", every urgent low a filled badge, every sentinel high "HOCH", every stale value a strike, every ambient range a shape (§6).
- Nothing moves except the armed countdown, a 150 ms rim flash on send, and the 200 ms value cross-fade on new data.

## 2. Geometry (450 x 450 design canvas)

Angles in canvas degrees (0 = 3 o'clock, clockwise). All values × `s` (§9).

| Element | Position | Size |
|---|---|---|
| Ring track | centre (225,225), r 133.5, 135° → 405° | stroke 13 (urgent low / very high: track and fill 19) |
| Target band | same arc, angle(low) → angle(high) | stroke 13, `#1C3E30` |
| Threshold cuts | at angle(low), angle(high), r 123.5 → 143.5 | 3 px black |
| BG scale | 40 → 300 mg/dL linear over the sweep (`HaloFaceLogic.angle`), clamped | |
| Knob | on the ring at angle(sgv) | r 12 white + r 6 range hue |
| Trend trail | r 120, from angle(sgv 30 min ago) to angle(sgv) | 3 px, `#AAB4C3`, alpha 12 % → 100 % |
| Fast-trend chevrons | on the ring, 17° and 24° ahead of the knob in travel direction | 6 px arms, 2.4 px (3.2 px triple) |
| Time | (225, 136) | 30 px Medium; 12 h "PM" or ":07" seconds 12 px right of it |
| Date / switched chip | (225, 165) | 15 px Medium / chip h 28, 17 px Bold |
| Hero BG | (225, 230) centre of the digits+caption group | 104 px Semibold, fit 196 px (no caption) / 220 px for digits + caption |
| BG caption (TIEF / HOCH) | left edge = digits right edge + 8, centre y 200 | 16 px Bold; badge = text + 8 px padding each side, h 26, radius 7 |
| Meta line (trend · delta · age · W10 glyph) | (225, 300) | 22 px Medium, arrow glyph 24 px, phone glyph 15 px |
| Status line | (225, 340) | 17 px Semibold, fit to 210 px (round) / 300 px (square), min 14 px |
| Loop pill | (225, 372) | h 28, w = max(74, 26 + text + 16) (compact 56), radius 14 |
| Info line (TT · basal · res · batt · rig) | (225, 404) | 14 px Medium, max 220 px (round) / 200 px (flat tyre) / 320 px (square), min 12 px |
| IOB arc | r 207, −50° → +50° (with patient blocks: −50° → +38°), fills from the lower end upward | stroke 5 |
| IOB / KH column | x 393; labels 13 px Semibold, values 22 px (IOB) / 19 px (KH), max width 50 | |
| Bolus ball | (46, 225) r 27; hit r 44 clipped to the screen | §7 |
| Patient blocks | centres (142, 372) and (308, 372) | 80 x 38, radius 10 |
| Toast | bottom edge y 330, centred | 14 px, max 2 lines, `#2E323C` @ 96 % |

The 12 o'clock rim (top 24 px) carries nothing essential (sys.notification_dot).

Clearances checked on the sheets: right block edge (348, 391) is ≥ 16 px from the round edge at 384 px and ≥ 40 px from the shortened IOB arc end (388, 352). The caption group never reaches the ring's inner edge (r 127): group half-width ≤ 110 at y 200.

## 3. Elements

Format per element: **Source** → **Formatting** → **Colour per state** → **Priority / override** → **Ambient** → **Scaling / fallback**.

### 3.1 Ring (BG gauge + trend trail)
- **Source**: `SingleBg[0].sgv` (mg/dL always, independent of units), `.low`/`.high` (fallback 70/180 when ≤ 0), `.timeStamp` for age, `GraphData.entries` for the 30-min trail (value ~30 min before now, first entry inside the window).
- **Formatting**: angle = `HaloFaceLogic.angle(sgv)`. Values above 300 or below 40 park the knob at the end stop (the digits carry "400+" / "39").
- **Colour per state**:
  - bg.in_range: fill `range.in` #3DDC97, alpha ramp 22 % at the start → 100 % at the knob, glow 6 px. Backdrop: soft radial tint r 147 in the same hue, max 9 % alpha.
  - bg.high: `range.high` #FFB547. bg.very_high (> 250 mg/dL): same hue, track and fill 19 px, backdrop 12 %.
  - bg.low: `range.low` #FF5A6E, 13 px. bg.urgent_low (< 54 mg/dL, or low with DOWN2/DOWN3): same hue, track and fill 19 px, backdrop 12 %.
  - age.stale / age.very_stale < 60′: no range hue. Fill flat `range.stale` #8A93A8 at 43 %, no glow, no backdrop, no trail, knob hollow (r 10 `#2A2E36` + 2 px stale outline + r 4 stale dot).
  - age.very_stale ≥ 60′, age.none, conn.first_start: track and band only, no fill, no knob.
- **Trail**: only when fresh/aging, key_show_direction on and GraphData has a point 25–35 min old; otherwise omitted (graph.empty, graph.gaps: no interpolation). Halo has no prediction drawing; graph.no_predictions has no visual.
- **Fast trends** (trend.fast): two chevrons on the ring ahead of the knob in the range hue; triple = thicker. Omitted outside the sweep.
- **Ambient**: §6 (track shape carries the range).
- **Scaling**: stroke min 1 px.

### 3.2 Time and date
- **Source**: system clock; key_show_date, key_show_week_number, key_show_seconds; system 24 h setting.
- **Formatting**: de "14:07"; en 12 h "2:07" + "PM" (12 px Semibold txt2, baseline +6). Date de "Fr 17. Okt" (`EE d. MMM`, dots stripped from day and month), other locales `getBestDateTimePattern(locale, "EEEdMMM")`. Week "KW 42" appended " · ".
- **Primary name**: with at least one patient block, the dataset-0 name prefixes the date ("Kusi · Fr 17. Okt"). **[family v2]** It is ellipsised (characters removed from the end, "…" appended) until the whole date row fits 220 px; the week goes first, then the name.
- **multi.switched**: the date row becomes a **header chip**: h 28, radius 14, fill `#262B36`, 1.2 px txt2 outline, a 4 px txt1 dot, the hero patient name 17 px Bold txt1 (ellipsised to 150 px). The date is not shown while switched. Ambient: chip outline 1.5 px `amb2`, name `amb1`.
- **Colour**: time txt1 #EEF1F7, date txt2. Never a state colour.
- **Override**: date off → time moves to y 150 (not while switched; the chip always shows).
- **Ambient**: time `amb1` Regular, date `amb2`; seconds never.

### 3.3 Hero BG and BG caption
- **Source**: `SingleBg[d].sgvString` (phone-formatted, units and decimal separator as sent), `sgv` for thresholds.
- **Formatting**: string unchanged ("6,4", "115", "6.4" from an English phone; never swap the separator). Sentinel high (sgv ≥ 400) appends "+". "---" for age.none, first start and ≥ 60′.
- **BG caption [family v2]** (`face_low` / `face_high`, non-optional, no pref):
  | State | Caption | Interactive | Ambient (all variants) |
  |---|---|---|---|
  | bg.low (fresh/aging) | "TIEF" / "LOW" word | 16 px Bold `range.low` | 16 px Bold `amb1` (low-bit white) |
  | bg.urgent_low (incl. sentinel ≤ 39) | "TIEF" badge | filled `range.low` box, white 16 px Bold text | 2 px `amb1` box outline + `amb1` text (no fill: burn-in safe) |
  | sentinel high (sgv ≥ 400) | "HOCH" / "HIGH" word | 16 px Bold `range.high` | `amb1` word |
  | everything else, stale, "---" | none | | |
  Digits and caption are centred as one group: the digits move left by half the caption width. The digits are fitted so the whole group is ≤ 220 px ("22,2+ HOCH" shrinks; "3,4 TIEF" does not).
- **Digit colour per state**: in range / high: white with a 9 px glow in the range hue at 47 %. **bg.low and bg.urgent_low: digits `range.low` #FF5A6E** with the same red glow. `range.low.text #FFD6DC` is no longer used on Halo. Stale: digits txt2, no glow, 3 px txt2 strike-through at y 228 across the measured width + 6 px each side. "---" in txt3 (none / first start) or txt2 (≥ 60′).
- **Low vs urgent low** (tile "bg.low vs bg.urgent_low"): word vs filled badge, ring 13 vs 19 px, single vs double-head arrow with ring chevrons (urgent by fast fall), backdrop 9 vs 12 %.
- **Ambient**: outline digits (black fill, stroke 2 px `amb1`; burn-in 1.5 px; urgent low +1 px). Stale: `amb2` + 2 px strike. Low-bit: white.
- **Scaling**: the ring never moves; the caption never goes below 11 px physical (at 384 px it is 13.6 px).

### 3.4 Meta line (trend · delta · age · W10)
- **Source**: `slopeArrow` → trend enum (states.md §1, drawn, never a font glyph), delta string per key_show_detailed_delta / key_show_avg_delta, age from `timeStamp`, phone reachability for W10.
- **Formatting**: `[arrow] [delta] · [age] [phone-off glyph]`, age "1′", "17′", "3 Std", "2 T". Arrow: 24 px, 3 px stroke, double head for UP2/DOWN2, +1 px for UP3/DOWN3.
- **Colour**: arrow range hue; delta `#B4BAC4`, "--" txt3 (delta.missing); age fresh `#B4BAC4`, aging warn, stale/very stale alert; phone glyph txt2.
- **Age is never optional [family v2]**: key_show_ago off does not remove the age; while fresh it is drawn 16 px txt3 (tile "pref.minimal"), from AGING on at full size in warn/alert.
- **W10 [family v2]**: conn.phone_lost_fresh is a 15 px phone-off glyph after the age, on every face glyph-only, never slot text. It is **not** suppressed by a low (tile "conn.phone_lost_fresh + bg.low").
- **Override**: trend.none → no glyph, line re-centres. trend.hidden_pref / delta.hidden_pref → part removed. While W2/W3 owns the status line, the meta line is removed (the status line carries the age). Stale: arrow and delta removed.
- **Ambient**: arrow `amb1` 2 px, delta `amb2`, age `amb2` fresh / `amb1` otherwise; no W10 glyph.

### 3.5 Status line (family status slot)
- **Source**: `FaceState` hierarchy (states.md §4.2 with the v2 additions below).

| Rank | Trigger | Text (de) | Colour | Ambient |
|---|---|---|---|---|
| W1 | conn.first_start | "Warte auf AAPS" | txt2 | yes |
| W2 | conn.phone_lost_stale | phone glyph + "Telefon getrennt · 14′" | alert | yes |
| W3 | conn.phone_silent / age.stale+ | "Keine Daten · 17′" | alert | yes |
| W3 (none) **[family v2]** | age.none (no BG ever, status present) | "Kein BG" | warn | yes |
| W3.5 **[family v2]** | a secondary dataset in bg.urgent_low, or its BG ≥ 30′ old | "Lea TIEF 2,8" / "Lea · Keine Daten · 42′" | alert | yes |
| W4 | conn.no_profile | "Kein Profil" | warn | yes |
| W5 | loop.off | pause glyph + "Loop aus" | warn | yes |
| W6 | loop.late 15–29′ **[family v2]** | "Loop 23′" | **warn** | yes |
| W6 | loop.bad ≥ 30′ | "Loop 34′" | alert | yes |
| W7 | pump.res_critical | "Reservoir 5 U" | alert | no |
| W8 | batt.phone_low | "Tel 18 %" | warn (< 10 %: alert) | no |
| W9 | pump.res_low | "Reservoir 18 U" | warn | no |

- **One line**; first true rank wins.
- **Low suppression [family v2, corrects states.md line 69]**: a fresh bg.low / bg.urgent_low suppresses **W7–W9 only**. W1–W6 always show, also during urgent low (tiles "prio: urgent low + loop.bad / loop.late / loop.off"). Test row: urgent low + loop age 34′ must show "Loop 34′".
- **Loop age honesty**: loop age is the last-TBR age (§13). 15–29′ is amber "look soon"; red only from 30′.
- Values shown in the slot (W7/W8/W9) are not repeated in the info line.
- **Ambient**: W1–W6 (incl. W3 none and W3.5), `amb1` for W2/W3/W3.5/W6, `amb2` otherwise; low-bit white.
- **Scaling**: fit to 210 px (round) down to 14 px; secondary names in W3.5 use the 7-character rule (§8).

### 3.6 Loop pill
- **Source**: `Status[0].openApsStatus` (age), `externalStatus` (loopRun), `LoopStatesList` (loopMode), flavour heuristic.
- **States** (label 17 px Semibold, dot at x0 + 16):

| State | Fill | Outline | Dot | Label |
|---|---|---|---|---|
| loop.ok ≤ 5′ | `#12281E` | `#285A40` | `#40D68C` glowing | "2′" `#D2F5E1` |
| loop.warn 6–14′ / loop.late 15–29′ | `#2A2210` | `#5A4A20` | warn | "9′" `#FFE6AA` |
| loop.bad ≥ 30′ | `#2E1218` | `#6A2632` | alert | "34′" `#FFD2D8` |
| **slot owns W2 / W3 / W6** (any of the above) | none | `#4A5160` | hollow txt3 | minutes txt2 |
| loop.lgs | as by age | | | "LGS 3′" |
| loop.open | none | `#285A40` | hollow green | "Offen" txt2, no age, never warn/red |
| loop.never / noprofile | none | `#2C313C` | hollow txt3 | "--" txt3 |
| loop.off | none | `#5A4A20` | warn pause bars | **"aus"** warn (lower case, family string) |
| loop.unknown_mode (client) | as by age | | | age only, no mode label |
| loop.no_aps (pumpcontrol) | pill removed | | | |
| multi.switched | pill removed (dataset-0 only) | | | |

- **Ambient**: 1.5 px outline `amb3` (low-bit white), hollow dot `amb1`, label `amb1`; loop.off = pause bars + "aus".
- **Scaling**: compact (min width 56, "LGS" dropped) when patient blocks share the row.

### 3.7 IOB arc and IOB / KH column
- **Source**: `Status[0].iobSum` (`iobDetail` ignored, iob.detailed N/A), `cob`, key_show_iob / key_show_cob.
- **Formatting**: phone strings through `display()`; arc fraction = parse (comma or dot) / 6 U, capped; ≤ 0 → empty. Label "IOB"; COB label "KH" (de) / "COB".
- **Colour [family v2 rule: only the label and the arc carry `ins`]**: arc `ins` #5AC8FA on track `#1A1C24` with a 3 px glow; label "IOB" `ins`; value txt1; iob.zero / iob.unknown value txt3; iob.negative txt1, arc empty. KH label txt3; COB "0 g" and "--" txt3, otherwise txt1, "20(30) g" as sent with German spacing.
- **Override**: COB off → IOB centres on y 221; IOB off → arc removed, KH centres. With patient blocks the arc ends at +38° instead of +50°. multi.switched → whole column and arc removed.
- **Ambient**: arc 1 px `ambLine` track + 2 px `amb2` fill (low-bit: white fill, no track), label `amb3`, value `amb2`, KH dropped.
- **Scaling**: values fit to 50 px ("12,40", "-0,35").

### 3.8 Info line (bottom runs)
- **Source**: TT (`tempTarget`, `tempTargetLevel`), `currentBasal`, `reservoirString` / `reservoirLevel`, `battery` / `batteryLevel`, `rigBattery`; each by its key_show_* toggle.
- **Formatting [family v2]**: German spacing between number and unit everywhere: "0,85 U/h", "120 %", "120 U", "Tel 64 %", "20 g", "Rig 88 %". The face inserts the space when the phone string has none (regex `(\d)(U/h|U|%|g)$` → `$1 $2`, narrow no-break space U+202F allowed).
- **Order and colour**: "TT 8,0" tt #B388FF (tt.active only) · basal txt2, "120 %" tbr #3DDBD9, zero basal ("0,00 U/h", "0 %") warn **but txt2 while a fresh bg.low / bg.urgent_low is active [family v2]** · reservoir txt2 / warn / alert · "Tel 64 %" txt2 / warn · "Rig 88 %" txt2. Separator " · " txt3.
- **Drop order** when the line does not fit at ≥ 12 px: rig, phone battery, reservoir, basal; TT is kept last. Flat tyre uses max width 200 (tile "flat tyre · pref.all_on").
- **Override**: values owned by the status line are not repeated. multi.switched → line removed (dataset-0 only).
- **Ambient**: TT only, `amb2`.

### 3.9 Toast
Transient (2 s) for ball refusals and "Eine Anfrage läuft bereits". Interactive only, dropped on ambient. Strings: existing `halo_bolus_*` plus the guard reasons in §7.

## 4. Warning order on this face (summary)
1. The hero (ring, digits, caption) always shows the BG state, including urgent low; it never yields.
2. Status line: first of W1–W9 (§3.5). A fresh low suppresses W7–W9 only; W1–W6 always show.
3. W10 is a glyph in the meta line and is never suppressed by a low.
4. Red budget: BG accent group + status line. Pill neutral whenever the slot owns W2/W3/W6. Counted per tile: "prio: W2 beats W6 + W7" = 1 red (slot; the BG is stale grey), "urgent low + loop.bad" = 2, "urgent low + loop.late" = 1 (slot amber), "urgent low + loop.off" = 1.
5. Patient blocks keep loop and stale warnings inside the block; a secondary urgent low or ≥ 30′ old BG escalates to W3.5 (§8).

## 5. States per group (where to look, what changes)

| id | Halo treatment | Sheet |
|---|---|---|
| conn.first_start | track only, "---" txt3, IOB/KH "--", pill "--", W1, ball hidden | data |
| conn.restored_old_data | = age.very_stale ≥ 60′ on the first frame ("---", "3 Std") | data |
| conn.no_profile | hero normal, IOB/KH/basal "--", pill "--", W4, ball disabled (NO_PROFILE) | data, warnings, ball |
| conn.phone_ok | nothing | — |
| conn.phone_lost_fresh | phone glyph after the age (also during a low), ball disabled | warnings, ball |
| conn.phone_lost_stale | stale ring/digits, W2, pill neutral, ball disabled | warnings |
| conn.phone_silent | stale ring/digits, W3, pill neutral, ball disabled (never hidden) | warnings |
| age.none | "---" txt3, status "Kein BG" warn, pill as by loop age, ball disabled | data, warnings |
| bg.* / age.* / trend.* / delta.* | §3.1, 3.3, 3.4 | data |
| units.mmol / units.mgdl | same layout; strings from the phone; "400+ HOCH" and "39 TIEF" fit | units_locale |
| units.phone_locale | en phone sends "6.4": shown as sent; ball amount follows the watch locale | units_locale |
| graph.normal / empty / gaps | 30′ trail present / absent / absent | data |
| graph.no_predictions, graph.layers, graph.timeframe_changed | not applicable (no chart). Single tap outside the ball opens the menu | — |
| iob.* / cob / basal.* / tt.* | §3.7, 3.8 | data |
| loop.* | §3.6 | warnings |
| pump.* / batt.* | status line W7–W9 or info line | warnings |
| status.external, status.bgi | not drawn (meaning mapped to loop.off / no_profile) | — |
| multi.* | §8 | multi |
| flavor.full / client / pumpcontrol | client → unknown_mode pill, ball usually hidden; pumpcontrol → pill removed, ball allowed | warnings, multi |
| mode.* | §6 | ambient |
| pref.* | §10 | units_locale |
| sys.simplified | family SimpleUi reference, §6.1 | ambient |
| sys.preview | 6,4 → +0,1 1′, loop 2′, IOB 1,25, 10:08, "Fr 17. Okt", no ball | data |
| locale.* | §3.2 | units_locale |
| tap.* | ball zone → §7; everything else single tap → MainMenuActivity; loop pill tap → ActionLoopStatusDetailed (P2); ambient no hit test | — |

## 6. Ambient (all four variants)

Kept: ring outline + ticks + hollow knob (r 8, 2 px), time, date / chip, hero outline digits + caption, meta line, IOB arc + value, loop pill outline, status line W1–W6, TT run, patient blocks reduced to name + BG + age. Dropped: glow, backdrop, ring fill, trail, chevrons, KH, basal / reservoir / battery runs, W10 glyph, ball (never drawn, never hit-tested), toasts.

**Range by shape [family v2: the whole track carries it, like Orbit's track and Glass's card]**:

| Range | Ring track | Caption |
|---|---|---|
| in range | one solid 2 px `amb3` arc (low-bit 1 px white) | — |
| high | whole track dashed: 4° on / 3° off, 2 px `amb2` | — |
| very high | dashed, 3 px | — |
| low | **doubled track**: two 2 px `amb2` arcs at r 129 and r 138 (low-bit 1 px white) | "TIEF" word `amb1` |
| urgent low | doubled track | "TIEF" in a 2 px outlined box, digits +1 px outline |
| stale | single track, knob `amb2` | digits `amb2` + strike, "Keine Daten · 17′" |

- **AMBIENT**: greys amb1–amb3 / ambLine only, AA on.
- **LOW_BIT**: everything white or off, AA off, strike 2 px, IOB track dropped. The mockups threshold an AA render; on the device draw the 1 px arcs with AA off so they stay continuous.
- **BURN_IN**: no filled area > 4x4 px (outline digits, hollow knob and dot, pill outline, boxed TIEF outline only), strokes ≤ 2 px (urgent digits 2.5 px), whole layout shifted by `HaloFaceLogic.burnInOffsetX/Y(minute)` (±5 px design × s).
- **LOW_BIT_BURN_IN**: union.
- **Transition**: entering ambient calls `gesture.reset()` (armed → idle, invisible), drops toasts; leaving recomputes ages before the first frame.

### 6.1 sys.simplified: family SimpleUi reference [family v2]
One layout for all three faces; only the BG font differs per face (Halo: SF-Rounded-like Semibold / Light outline in ambient). Used while charging, in ambient or both (`simplify_ui`), so it carries the safety cues itself.

| Element | Position (450 canvas) | Interactive | Ambient |
|---|---|---|---|
| Time | (225, 112), 30 px | txt1 | `amb1` Regular |
| BG digits | (225, 212), 110 px, fit 230 (260 with caption) | **txt1 white** in every range; stale txt2 + 3 px strike; "---" txt2 | outline 2 px `amb1` (burn-in 1.5, urgent +1) |
| Caption | right of digits, centre y 182 | TIEF word / filled badge / HOCH, as §3.3 | word / outlined box, as §3.3 |
| Arrow | (225 − group/2, 290), 30 px drawn arrow | **the only element in the range hue** | `amb1` |
| Age | after the arrow, 22 px | fresh txt2, aging warn | fresh `amb2`, else `amb1` |
| Status line | (225, 340) | W1–W3.5 only ("Telefon getrennt · 14′", "Keine Daten · 17′", "Kein BG", "Lea TIEF 2,8") | `amb1` |

Rules: arrow and age are removed when W2/W3 owns the line (the line carries the age); no ring, no IOB, no ball. Tiles: in / aging / stale / phone lost / low / urgent low / ambient low / ambient urgent, and 384 px low on the shapes sheet.

## 7. Bolus ball

Pref `halo_bolus_double_tap` (default off), amount `halo_bolus_amount` ∈ {0.5, 1.0}, user's choice 0,5 U. Request only: a double tap sends `ActionBolusPreCheck(amount, 0)`; the phone checks and the confirm screen opens on the watch. Always dataset 0's pump.

**Guard verdicts [family v2]** (`BolusRequestGuard.evaluate()`, first match wins; any verdict turns idle into disabled, a tap shows the toast and never arms):

| Verdict | Condition | Toast (de, 2 lines) |
|---|---|---|
| NO_PHONE | phone unreachable (lost fresh / lost stale / silent) | "Telefon nicht verbunden. / Keine Anfrage gesendet." |
| STALE_DATA | BG age ≥ 12′ or no BG | "Keine aktuellen Daten. / Keine Anfrage gesendet." |
| **LOW_BG** (new) | bgRange URGENT_LOW, or LOW with a falling trend (DOWN45, DOWN, DOWN2, DOWN3) | "BG zu niedrig. / Keine Anfrage gesendet." |
| **NO_PROFILE** (new) | loopRun noprofile | "Kein Profil. / Keine Anfrage gesendet." |
| **LOOP_OFF** (new) | loopRun OFF | "Loop aus. / Keine Anfrage gesendet." |
| MAX_BOLUS | amount > phone max bolus (known from the last refusal) | "1,0 U liegt über dem / Max-Bolus (0,8 U)" |

A low that is flat or rising stays idle (tile "ball + bg.low FLAT"): the phone still checks it.

**Shapes [family v2: fill state is the shared signal; the same four shapes on Glass's sphere and Orbit's rounded square]** (tile "ball.* side by side"):

| State | When | Visual at (46,225) r 27 |
|---|---|---|
| ball.off | pref off | nothing, no hit area |
| ball.hidden | ambient (any), headless/preview, wearControl off, amount not allowed, multi.switched, first start | nothing, no hit area. Phone lost / stale is **never** hidden, always disabled |
| ball.idle | no verdict | **outline**: fill `#141628`, solid 2.5 px `#7888FF`, 4 px glow; "+0,5" 14 px Bold `#7888FF`, "U" 10 px `#96A0DC` |
| ball.armed | first tap in the hit zone | **solid fill** `#5B63F5` with 6 px glow; "+0,5" and "nochmal" white; "2×" when the ball is < 50 px physical (384 px screens); white 2.2 px countdown ring r 32, 360° → 0° over `BolusTapGesture.WINDOW_MS` (800 ms), clockwise from 12 o'clock |
| ball.sent | second tap, verdict SEND | 150 ms white rim flash, then **solid outline at 55 %**, amount replaced by a 22 px "…" `#C8CEF5`, **static partial ring** r 32 (300°, gap at the top) 2 px `#7888FF` at 50 %, for `BolusRequestGuard.PENDING_MS` (30 s); tap → "Eine Anfrage läuft bereits." |
| ball.disabled | any guard verdict | **dashed outline** (20 dashes, 55 % duty) 2.2 px `#7888FF` at 40 %, no fill, no glow, **diagonal slash** 2.2 px txt3 from 7:30 to 1:30, "+0,5" / "U" txt3 with a 2.5 px black knock-out over the slash |
| ball.delivering (P2) | BolusProgress while sent | as sent, the static ring replaced by a progress ring `#7888FF` |
| ball.delivered (P2) | delivery done | white check (never a range hue) for 2 s, then idle |

Amount string family-wide: "+0,5" (watch locale) above a unit line "U"; armed: "+0,5" above "nochmal" / "2×". Transitions: armed → idle on timeout, tap elsewhere, ambient or screen off (silent). Cooldown `COOLDOWN_MS` 5 s after a send. Hit area r 44 (design), clipped to the screen; the ring's outer edge is at x 85, so the hit circle (x 2–90) overlaps only ring pixels without a tap action. The ball never takes a range hue.

## 8. AAPSClient multi-patient
- Dataset 0 owns ring, hero, meta line, IOB, pill, status line, info line and ball.
- multi.two: one block at (142, 372) left of the compact pill; multi.three: second block at (308, 372). The info line stays below.
- **Block** (80 x 38, radius 10, fill `#0E1016`): name 13 px Semibold txt2 at (x0+7, y0+11), BG 16 px Bold in its range hue + 11 px arrow at y0+27, age 11 px right-aligned (txt2 / warn / alert), loop dot r 3 top-right (dropped below 420 px and when a TIEF tag is shown). Normal outline 1 px range hue at 43 %.
- **Name rule [family v2]**: at most 7 characters + "…" ("Annabel…"), then shortened further until it fits the block (13 px design = 11.1 px physical at 384, above the 11 px floor). Empty name → "P2" / "P3".
- **Secondary low / urgent low [family v2, shape not only colour]**: bg.low → doubled outline (2 px `range.low` + 1 px inner outline 4 px inset at 60 %) and a "TIEF" tag 11 px Bold `range.low` on the name row. bg.urgent_low → additionally the block is backed with `range.low` at 24 % and the tag is a filled `range.low` box with white text, **and** the face status line shows W3.5 "Lea TIEF 2,8" in alert.
- **Secondary stale**: BG txt2 with a strike, outline stale, age alert; nothing else on the face changes. ≥ 30′ escalates to W3.5 "Lea · Keine Daten · 42′". The primary's own W1–W3 still win over the escalation (tile "multi.three + primary stale").
- **multi.switched (P2)**: the hero shows dataset 1; the header chip names the patient (§3.2); **IOB arc and column, KH, loop pill, info line (TT, basal, reservoir, phone battery, rig), W4–W9 and the ball are hidden**, because they belong to dataset 0. Dataset 0 moves into a block (with its own loop dot). The status line keeps W1–W3 and W3.5.
- **Ambient**: block outline 1 px `amb3`; low → doubled `amb1` outline + "TIEF" word; urgent → doubled outline + "TIEF" in an outlined box; high → dashed `amb2` outline; name + BG + age in `amb1`/`amb2`, no dot, no arrow.

## 9. Scaling and shapes
- `s = min(w, h_visible) / 450`. All coordinates, fonts, strokes × s; strokes ≥ 1 px; text ≥ 11 px physical (labels hit the floor first; values keep their size ratio).
- Round 384 (s 0,853): all elements fit; the floor enlarges "IOB", "KH" and block ages; blocks drop the loop dot; armed label "2×". Round 396/416/450/456/480: straight scaling.
- Square / rect: face drawn in the inscribed circle (diameter = min side), centred; corners black. Status line and info line may widen to 300 / 320 px. 368x448 centres vertically.
- **Flat tyre** (bottom inset > 0, e.g. 360x330 visible): **8 px safe band above the chin [v2]**. The bottom stack (status line, pill / blocks, info line) is re-spaced linearly from y 334 so its last row ends at the safe band; the info line uses max width 200 and the drop order of §3.8. Ring, hero and ball do not move. Rendered: default, pref.all_on, W3, multi.three.
- Safe radius: text inside 0.86 R, rims up to 0.96 R. The ball (x 19–73) is inside the circle at every size.

## 10. Content toggles (on / on-without-data / off)

| Toggle | On | On, no data | Off |
|---|---|---|---|
| key_show_date | date line | — | time moves to y 150 |
| key_show_week_number | " · KW 42" | — | removed |
| key_show_seconds | ":07" 12 px txt2 (interactive) | — | removed |
| key_show_direction | arrow + trail + chevrons | no glyph | removed, line re-centres |
| key_show_delta | delta | "--" txt3 | removed |
| key_show_avg_delta / detailed_delta | chooses the string | "--" | default delta |
| key_show_ago | age 22 px | "--" | **age kept, 16 px txt3 while fresh**; full size from AGING |
| key_show_iob | arc + value | "--", arc empty | arc and value removed |
| key_show_cob | KH value | "--" | removed, IOB centres |
| key_show_basal | run | removed (no "--" in runs) | removed |
| key_show_temp_target | TT run (tt.active only) | hidden | removed |
| key_show_reservoir_level | run | hidden | removed; critical still in W7 |
| key_show_uploader_battery | run | hidden ("0" = unknown) | removed; < 10 % still in W8 |
| key_show_rig_battery | run | hidden | removed |
| key_show_bg, key_dark, key_match_divider, graph layer prefs, chart time frame | ignored (documented) | | |
| loop pill, BG caption, W10 glyph | no toggle (safety) | "--" | — |

pref.minimal: ring + hero + arrow + small age + time + pill. pref.all_on at 384 px and on flat tyre fits (info line drops rig first).

## 11. Strings
Family keys from states.md §4.4: face_waiting, face_phone_lost, face_no_data, face_no_profile, face_loop_off, face_loop_age, face_loop_open, face_loop_lgs, face_reservoir ("Reservoir %1$s", value "5 U"), face_phone_batt, face_tt, face_cob "KH"/"COB", face_low "TIEF"/"LOW", face_high "HOCH"/"HIGH", face_minutes "%1$d′", face_hours "%1$d Std", face_days "%1$d T", face_week "KW %1$d".

New in v2 (propose for states.md §4.4):

| Key | de | en |
|---|---|---|
| face_no_bg | Kein BG | No BG |
| face_loop_off_short | aus | off |
| face_secondary_low | %1$s TIEF %2$s | %1$s LOW %2$s |
| face_secondary_no_data | %1$s · Keine Daten · %2$s | %1$s · No data · %2$s |
| face_bolus_amount | +%1$s | +%1$s |
| face_bolus_unit | U (open: "IE") | U |
| face_bolus_again / face_bolus_again_short | nochmal / 2× | again / 2× |
| face_guard_no_phone | Telefon nicht verbunden.\nKeine Anfrage gesendet. | Phone not connected.\nNo request sent. |
| face_guard_stale | Keine aktuellen Daten.\nKeine Anfrage gesendet. | No current data.\nNo request sent. |
| face_guard_low_bg | BG zu niedrig.\nKeine Anfrage gesendet. | BG too low.\nNo request sent. |
| face_guard_no_profile | Kein Profil.\nKeine Anfrage gesendet. | No profile.\nNo request sent. |
| face_guard_loop_off | Loop aus.\nKeine Anfrage gesendet. | Loop off.\nNo request sent. |

Existing Halo strings kept: `halo_bolus_sent`, `halo_bolus_refused_*` (max bolus text). `halo_bolus_sub` "U 2×" is replaced by face_bolus_unit.

## 12. Changes against the current code (for the implementer)
1. Armed ball is amber in code (`0xFFFFE6AA` amount, `0xFFFFC85A` label, amber ring): becomes solid `#5B63F5` with white text and a white countdown ring. Idle text `#7888FF`.
2. `BTN_HIT_R` 34 → 44.
3. Ball sub-label `halo_bolus_sub` "U 2×" → "U" (idle); armed "nochmal", "2×" when 2·r·s < 50 px.
4. Ball amount in watch locale ("+0,5"); `arrays_halo.xml` entry names locale-formatted.
5. Disabled / sent shapes (dashed outline + slash; 55 % outline + "…" + static ring) instead of alpha only.
6. `BolusRequestGuard`: add verdicts LOW_BG, NO_PROFILE, LOOP_OFF; map MAX_BOLUS to disabled (not idle); never hide the ball for NO_PHONE / STALE_DATA.
7. `HaloFaceLogic.ageText`: "′" instead of ASCII "'", hours from 60′ ("3 Std"), days from 48 h, "---" BG from 60′. The age is drawn even with key_show_ago off (16 px txt3).
8. Layout: TIME_Y 138 → 136, DATE_Y 168 → 165, HERO_Y 229 → 226 (centre 230), DELTA_Y 291 → 300, PILL_Y 375 → 372, status line 340 (new), info line 404, blocks at x 142 / 308, 80 x 38.
9. Ring fill: drop the `FILL_START` blue (#286EBE) gradient start; fill = range hue with an alpha ramp. Stale fill `#8A93A8` at 43 %. Urgent low / very high widen track and fill to 19.
10. Hero: low digits `#FF5A6E` (no `#FFD6DC`); TIEF word / badge, HOCH word, group centring (§3.3).
11. Loop: thresholds ≤ 5 OK, 6–14 warn, **15–29 late (amber)**, **≥ 30 bad (red)**; neutral pill while the slot owns W2/W3/W6; loop.off pill label "aus"; loop.never / open / no_aps handling; LGS label.
12. Status line W1–W9 + "Kein BG" + W3.5 escalation; low suppresses W7–W9 only; W10 moves to a meta-line glyph.
13. Info line: German spacing normaliser; zero basal txt2 during a low; drop order; flat tyre 8 px band and max width 200.
14. IOB: label `ins`, value txt1; arc end +38° with blocks; hidden while switched.
15. Ambient: track shapes (dashed high, doubled low), TIEF word / box; knob r 8 single ring (the doubled knob and the short dashed outer arc are removed). TT run `amb2`.
16. SimpleUi: replace the per-face simplified drawing with §6.1.
17. Secondary blocks: name rule, low / urgent shapes, W3.5 escalation, switched behaviour.

## 13. States not designed well / open points
- **Loop age is not loop-run age.** `openApsStatus` is the last TBR enact time. Until the phone sends a real last-loop-run timestamp, 15–29′ is amber and red starts at 30′; a loop that runs but keeps the TBR can still show "Loop 34′" falsely on very stable nights. Contract gap with priority (needs a new field in `EventData.Status`).
- **loop.off reasons** (suspended, disconnected pump, superbolus) all look the same; push data cannot tell them apart (G6).
- **loopMode for AAPSClient** is unknown: no LGS/open label on clients; loop.warn appears often. **flavour** is a heuristic (G8): a client without devicestatus looks like pumpcontrol (pill removed). Both neutral, never red.
- **LOW_BG guard** uses the watch's view of the BG (≤ 5′ old). The phone remains the authority; the guard only avoids offering the button.
- **MAX_BOLUS** is only known after the phone refused once (the watch does not receive the max-bolus pref). Until then a too-large amount stays idle and the phone refuses.
- **graph.timeframe_changed / graph.layers / predictions**: Halo has no chart; documented, not designed. **iob.detailed, status.bgi, status.external**: no slot.
- **Ball confirmation** (ConfirmAction screen) is a system activity; ball.delivering / delivered (P2) depend on BolusProgress reaching the face, which is not wired today.
- **multi.switched** (P2): rendered with the v2 hiding rules; the behaviour of W4–W6 for dataset 1 (its own loop) is not available on the watch today.
- **W3.5 escalation** needs `SingleBg[d].sgv` and `timeStamp` for datasets 1/2 (present) and a stable patient name (`Status[d].patientName`, present on clients).
- **locale.rtl** (P2) and **sys.font_scale** not rendered; Halo is symmetric and canvas-drawn.
- **Low-bit** mockups are a threshold of the AA render; draw 1 px arcs with AA off on the device.
- **Decisions for the user**: German ball unit "U" vs "IE"; whether 54 / 250 mg/dL become watch settings.

## 14. Family-wide v2 rules this spec assumes (to copy into states.md, Glass and Orbit)
1. Fresh low: digits `range.low` + "TIEF" word; urgent low: filled "TIEF" badge + emphasis step; `#FFD6DC` dropped. Add a "bg.low vs bg.urgent_low" tile to every sheet.
2. Ambient: low = doubled track/outline + "TIEF" word, urgent = outlined "TIEF" box, high = whole track/outline dashed. No "!" suffix on loop ages.
3. Ball: guard verdicts LOW_BG / NO_PROFILE / LOOP_OFF; disabled = dashed + slash, sent = 55 % outline + "…" + static partial ring, idle = outline + indigo text, armed = solid `#5B63F5` + white text + countdown; "+0,5" / "U"; "nochmal" with "2×" below 50 px; MAX_BOLUS = disabled; phone lost / stale = disabled, never hidden.
4. Status slot: states.md line 69 corrected to "suppresses W7–W9 only; W1–W6 always show"; W6 amber 15–29′, red ≥ 30′; "Kein BG" for age.none; W3.5 secondary escalation; W10 glyph-only, never suppressed.
5. Red budget: BG accent group + status slot; loop element neutral grey whenever the slot owns W2/W3/W6.
6. multi.switched hides all dataset-0-only items and shows the hero name as a header chip.
7. Secondary low: doubled outline + TIEF tag; urgent: filled backing + filled tag + W3.5. Block names ≤ 7 chars + "…"; primary name ellipsised to the date-row width.
8. SimpleUi reference §6.1 (time on top, white digits, hue only on the arrow, age, W1–W3.5 line, caption).
9. Strings: pill "aus", German unit spacing everywhere, "Reservoir 5 U" in both tables, IOB value txt1 with `ins` only on label and arc, zero basal neutral during a low, "HOCH" used for sentinel high.
10. Age never optional (key_show_ago off demotes it while fresh).
