# AAPS Orbit (final): implementation spec

Target: Galaxy Watch5 Pro, 450x450 round, 320 dpi, German UI, mmol/L.
Generator (source of truth for every number): `orbit_final.py` (same folder).
Mockups: `orbit_final_interactive.png`, `orbit_final_ambient.png`, `orbit_final_armed.png`,
plus `orbit_final_bolus_off.png`, `orbit_final_stale.png`, `orbit_final_stale_ambient.png`, `orbit_final_sheet.png`.

Geometry: centre (225,225). Angles clockwise from 12 o'clock: `P(r,deg) = (225 + r·sin deg, 225 − r·cos deg)`.
Rounded sans (Google Sans Rounded or a bundled Nunito/Inter; mockups use SF Rounded). All text centre-anchored.
**Round-safe rule:** no element extends beyond a radius of 205 from the centre.

## Signature idea
BG is the **sun** in the centre. Status values are **planets** on one orbit ring. The ring breaks at 12 o'clock for the time and at 6 o'clock for the date.
A **minute moon** (a plain dot) moves along the ring at 6° per minute. It is the only motion on the face.
Colour shows **state only**. A ring in its normal state is neutral grey-blue.

## Palette (interactive)
| Token | Value | Use |
|---|---|---|
| bg | #000000 | background |
| range in / high / low | #3DDC97 / #FFB547 / #FF5A6E | BG hero, sparkline, corona (shared with Halo and Glass). Thresholds are the phone's low/high marks |
| neutral ring / label | #5A6B85 / #9AA6B8 | ball in normal state |
| dim ring / text | #3A4250 / #7D8796 | value is zero or inactive (COB 0, no TT) |
| IOB | #5AC8FA | always (value colour shared with Halo's IOB arc) |
| TBR running | #3DDBD9 | BASAL ball while a TBR is above 0 |
| TBR 0 / COB > 0 / warn | #FFB547 | amber means "attention" |
| TT active | #B388FF | TT ball; also the prediction dots |
| red | #FF5A6E | loop error, stale data, no phone |
| ball fill | #0E131B | every ball and the idle bolus button |
| value text | #EEF2F7 | |
| track | #2A3446 | orbit ring |
| time / date / delta row | #E6ECF4 / #A9B4C4 / #AAB6C6 | |
| bolus idle | ring #7888FF at alpha 180, glyph #7888FF, text #E8EBFF | |
| bolus armed | fill #5B63F5, white text, white countdown | deliberately **not amber**, so it never collides with high-BG or warning amber |

## Elements

1. **Corona.** An ellipse of 240x200 px centred at (225,215), in the BG range colour at alpha 34, with a 60 px blur.
   - Pre-render 3 bitmaps (in range, high, low).
   - Not drawn when BG is 12 min or older.
2. **Orbit track.** Circle r=168, stroke 1.5 px, #2A3446.
   - Gap half-angle around the time = (textWidth + 20)/2/168 rad.
   - The date gap uses the same rule. It widens automatically when the date line carries a warning (see 12).
   - Balls and the bolus button are painted over the track.
3. **Time "00:40".** At P(168,0) = (225,57). 30 px Semibold #E6ECF4. Format follows the system 12/24 h setting.
4. **Date "Fr 17. Okt".** At P(168,180) = (225,393). 18 px Medium #A9B4C4.
   - `SimpleDateFormat("EE d. MMM", Locale.GERMANY)`, then remove the trailing "." from the weekday and month abbreviations.
   - Always shown. It does not depend on SHOW_DATE.
   - Use the same string on Halo and Glass.
5. **Minute moon.** A dot r=4.5 at P(168, minute·6), fill #F2F6FC. No tail and no glow.
   - **In the time gap** (|deg| < gapT + 2°) or **the date gap**: not drawn.
   - **Behind a ball:** the angular distance to the ball centre is < asin(34/168) + 1.5° ≈ 13.2°. For the bolus button the limit is atan(36.3/168) + 1.5°.
     - The dot is not drawn.
     - Light a 44° arc of the ball's rim instead: 1.6 px, #FFFFFF at alpha 220, at radius ballR + 1.
     - Arc centre on the rim, measured clockwise from 12 = `ballDeg + (moonDeg − ballDeg)/halfAngle · 90`. The highlight sweeps from the trailing side, over the outer rim, to the leading side as the minute passes. See TT at 00:52 in `orbit_final_stale.png`.
   - **Ambient:** no moon.
6. **Hero BG "9.4".** Centre (209,191). 108 px Bold, in the range colour, with a 12 px glow at alpha 120.
   - Arrow: 44 px Semibold, same colour, at (bboxRight + 24, 193).
   - From 12 min old: #7D8796, no glow.
   - Over 15 min old: also a 3 px strike-through at y=195 from bboxLeft − 6 to arrowRight.
   - Source: `singleBg.sgvString` and `slopeArrow`.
7. **Delta row "+0.1 · 1'".** Centre (225,251). 21 px Medium #AAB6C6.
   - The age part turns #FFB547 at over 5 min.
   - **From 12 min:** replaced by a red pill "⚠ 17' alt":
     - Text: 19 px Semibold #FFD6DC.
     - Pill: fill #3A1018, 1.5 px #FF5A6E outline, height 30, radius 15, padding 14.
8. **Sparkline.** Box x 151..291, y 275..321.
   - **Y range:** fixed 2.5–14.0 mmol/L (45–252 mg/dL). The high line then sits at about 35 % from the top.
   - **Targets:** the phone's low/high as dashed lines (2 on, 4 off), 1 px, #3DDC97 at alpha 120. No band.
   - **History:** last 2 h, 24 points, from x=151 to now at x=265. 2.5 px curve in the range colour with a 4 px glow at alpha 150.
   - **Out-of-range values:** clamped to the box edge. Draw a 5 px caret (▲ or ▼) at that x on the edge.
   - **Now dot:** r=4, white. Hollow (1.2 px #C8C8C8) when BG is 12 min or older.
   - **Prediction:** next 30 min as 5 dots, r=2.2, #B388FF at alpha 220, x 265..291. Hidden when BG is 12 min or older.
   - Source: `graphData` bgDataList and predictions.
9. **Status balls.** r=34 on the orbit, 2 px ring, fill #0E131B.
   - A coloured ring also gets a faint top sheen: an ellipse in the ring colour at alpha 26 with a 3 px blur, pre-rendered. Neutral and dim rings get no sheen.
   - **Value:** 22 px Semibold at (x, y−7). If the text is wider than 56 px, fall back to 17 px Bold (e.g. "PAUSE").
   - **Label:** 14 px Semibold, +4 % tracking, at (x, y+15). Label colour = ring colour, or #9AA6B8 when neutral, or #7D8796 when dim.
   - Labels name the value; units are implied or carried in the value.

   | pos | ball | value | label | ring |
   |---|---|---|---|---|
   | 45° (343.8,106.2) | LOOP | loop age `0'` | LOOP | ≤5 min green, 6–14 amber, ≥15 red |
   | 90° (393,225) | IOB | `2.53` | IOB | #5AC8FA always |
   | 135° (343.8,343.8) | BASAL | `0.00` (U/h) | `TBR` while a temp basal runs, else `BASAL` | TBR > 0 teal, TBR = 0 amber (loop is withholding insulin), no TBR neutral |
   | 225° (106.2,343.8) | COB | `0g` | COB | > 0 amber, 0 dim |
   | 315° (106.2,106.2) | TT | `5.6` | `TT`, or `TT 120%` when the profile ≠ 100 % | active violet; no TT shows `100%` / `Profil` dim |

   **LOOP non-numeric states** replace the age. Ring red, value 17 px Bold:

   | state | text |
   |---|---|
   | loop disabled | `AUS` |
   | suspended | `PAUSE` |
   | pump disconnected | `GETR.` |
   | open loop while closed loop is expected | `OFFEN` (amber) |

   Ambient shows the same words, so the state is never only a "!".

   Positions never change between modes (muscle memory). With bolus off, 9 o'clock stays empty and the left side has 2 balls against 3 on the right. This is accepted as a deliberate asymmetry.
10. **Bolus button, 9 o'clock** at P(168,270) = (57,225).
    - **Shown only when** the watch pref "Bolus Doppeltipp" is ON. It is **OFF by default**.
    - **Shape:** a rounded square ("button", not a planet): 66x66, corner radius 16. The outer corner reaches r ≈ 204, inside the round-safe limit.
    - **Idle:**
      - Fill #0E131B, 2 px ring #7888FF at alpha 180, no glow.
      - Drop glyph 15 px tall at (x, y−12), #7888FF.
      - "0.5 U" 19 px Bold #E8EBFF at (x, y+11).
      - The shape and the glyph are the "action" signal. There is no dashed ring.
    - **Armed** (after the first tap, for `BolusTapGesture.WINDOW_MS` = 1.5 s):
      - Solid fill #5B63F5 with an 8 px glow at alpha 150.
      - "0.5 U" 20 px Bold white at (x, y−8), "nochmal" 14 px Semibold white at (x, y+13).
      - **Countdown:** a 3.5 px white stroke along the button outline (`PathMeasure.getSegment`). It starts at top-centre and drains clockwise; the unused part is white at alpha 60.
      - Redraw at about 10 fps only during the window.
    - **Second tap inside the window:** send `ActionBolusPreCheck(amount, 0)`. This is a **request** only; the phone checks it and shows the standard confirm. The button returns to idle with a 3 s cooldown.
    - A tap elsewhere, or the window expiring, disarms it.
    - **Hit area:** a circle of r=40 around (57,225).
    - **Amount** comes from the watch pref (default 0.5 U), never from the face.
    - **Ambient:** never drawn and never active. Entering ambient clears the armed state.
    - **Pref OFF:** no drawing and no hit target.
    - **Family rule:** Halo and Glass should use the same armed look (indigo fill, white "nochmal", outline countdown) and drop amber for "armed".
11. **Phone connection.** When no message has arrived from the phone for more than 5 min, show "✕ Handy" under the time at (225,87).
    - 15 px Semibold #FF5A6E, with the ✕ drawn as two 2 px lines.
    - Ambient: #BDBDBD.
12. **Conditional warnings on the date line.** Shown only when a threshold is crossed: phone battery < 20 % → `Akku 15%`, reservoir < 20 U → `Res 12U`, pump battery low → `Pumpe Akku`.
    - The date line becomes `Fr 17. Okt · Akku 15%`, with the warning part in amber. Show at most one warning, in priority order: reservoir, phone battery, pump battery.
    - The date gap widens to fit.
    - Hidden when everything is OK. No extra balls; this face is at its density limit.

## Ambient (`orbit_final_ambient.png`, `orbit_final_stale_ambient.png`)
- **Never drawn in ambient:**
  - corona, glows, fills, sheen, colour
  - moon
  - prediction, target lines
  - bolus button
- **Track:** 1 px #4A4A4A, with the same gaps.
- **Time / date / delta row:** #BDBDBD / #8E8E8E / #9A9A9A.
- **BG:** 108 px **Light** #D6D6D6, with a fallback to Regular if the bundled font has no Light. Arrow Regular.
- **Sparkline:** history only, 1.5 px #7A7A7A. Now dot hollow, r=4, 1.2 px #C8C8C8.
- **Balls:** black fill, 1.2 px #6E6E6E outline. Value 22 px Regular #C8C8C8, label 14 px **#9A9A9A**.
- **Stale:**
  - BG strike-through 1.5 px.
  - The delta row becomes `– – 17' alt` in #BDBDBD.
  - LOOP shows its state word.
  - "✕ Handy" shows in grey.
- **Burn-in:** shift the whole layout ±2 px on a 4-step cycle, (0,0) → (2,0) → (2,2) → (0,2), once per minute.
- **Low-bit ambient:** a 1-bit variant with the same strokes, white in place of the greys, and anti-aliasing off.

## Update cadence
- **Interactive:**
  - New BG: BG, delta, sparkline, corona and colours.
  - New status: only the changed ball (the existing "redraw only what changed" path).
  - Every minute: time, moon (and the occluded ball's rim), BG age and stale thresholds.
  - Midnight: date.
  - Bolus countdown: about 10 fps for at most 1.5 s.
- **Ambient:** `onTimeTick`, plus on new BG or status.
- **Pre-render:**
  - track with gaps (per gap width)
  - corona ×3
  - ball shells per (neutral, dim, each state colour)
  - bolus idle and armed shells
- **Stale thresholds** (minutes since the last BG):

  | age | effect |
  |---|---|
  | > 5 | age shown amber |
  | ≥ 12 | pill, grey hero, hollow now dot, no prediction |
  | > 15 | strike-through |

## CWF note
A pure CustomWatchface zip cannot do this face:
- the moon occlusion and rim light
- the 2 h sparkline
- the state-driven ring colours for IOB, COB and basal
- the ambient variants
- the bolus tap

Orbit needs the native KusiWatchface-style renderer, as Halo does, or the engine extensions in `halo-review/cwf-engine.md`.
