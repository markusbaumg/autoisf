# AAPS Glass: final implementation spec

Target: Galaxy Watch5 Pro, round 450x450 px, density 320 (1 dp = 2 px). All coordinates are px on the 450 canvas, origin top-left, centre (225,225).
Reference renderer (same constants): `glass_final.py` in this folder. Mockups: `glass_final_interactive.png`, `glass_final_ambient.png`, `glass_final_armed.png`, plus state proofs `glass_final_bolus_off.png`, `glass_final_high.png`, `glass_final_low.png`, `glass_final_stale.png`, `glass_final_ambient_stale.png`, overview `glass_final_sheet.png`.
Implementation: native WatchFaceService (a CWF zip cannot do the tap, the armed state or the separate ambient look, see `halo-review/cwf-engine.md`). Label "AAPS Glass" (not translated).

## Signature idea
Frosted glass cards float over a night gradient. Behind the hero card sits an aurora glow whose hue is the BG state: teal in range, amber high, red low, grey when the data is stale. You read the state from the light before you read the number. No rings, arcs or ticks (those belong to Halo and Orbit).

## Colours
| Token | Value | Use |
|---|---|---|
| range.in | #78E6BE | aurora, BG glow, chip, sparkline end |
| range.high | #FFB840 | BG > phone high mark (ref 10.0) |
| range.low | #FF5C5C | BG < phone low mark (ref 3.9) |
| range.stale | #8A93A8 | BG age >= 12 min, or no BG |
| txt1 / txt2 / txt3 | #EEF1F7 / #9AA6C4 / #6F7A99 | primary / secondary / separators |
| label | #8E99B8 | pill labels (about 5:1 on the glass) |
| delta | #C8D2E6 | |
| warn / alert | #FFB840 / #FF5C5C | age, loop, battery, disconnect |
| tt | #E8D28A | TT run (desaturated, so it does not read as "high" amber) |
| loop ok/warn/bad | #40D68C / #FFB840 / #FF5C5C | < 6 / 6-14 / >= 15 min or loop disabled/suspended |
| ambient | #E0E0E0 / #9A9A9A / #6E6E6E / #3C3C3C | text1 / text2 / text3 / lines |

The thresholds come from the phone's low/high marks in the status data, not from constants.

## 1. Background (interactive)
- Vertical gradient: #141A33 at y=0, #0B0F22 at y=248, #04050B at y=450.
- Aurora (pre-blurred, baked into bitmaps):
  - Range blob: ellipse (130,100)-(320,250), range colour alpha 150, blur 40.
  - Range core: ellipse (150,120)-(260,220), range colour alpha 120, blur 26.
  - Violet: ellipse (240,220)-(410,340), #5A46C8 alpha 85, blur 44.
  - Blue: ellipse (40,230)-(180,330), #2A5AC8 alpha 60, blur 44.
- Dial rim: circle inset 3 px, 1 px, white alpha 22.
- **Four pre-rendered 450x450 background bitmaps**: in, high, low, stale. Each includes the gradient, the blobs and the frosted (14 px blurred) regions of the hero card and both pills. Swap bitmaps only when the range key changes. No blur at runtime.

## 2. Time and date
- Time "HH:mm" (24 h): centre (225,58), 42 px Regular (400), txt1. Use tabular figures (Roboto Flex `tnum`, or Inter with `font-feature-settings "tnum"`).
- Date: centre (225,92), **18 px** Medium, txt2. Format `EE d. MMM`, Locale.GERMAN, with the dots stripped: "Fr 17. Okt". Always shown, no preference. Redraw at midnight.

## 3. Hero card
- Glass rounded rect (92,110)-(358,240), r=30. Layers: frosted region (baked in), then a white vertical gradient alpha 35 to 11, a 1 px outline white alpha 60, and a top hairline x 122-328 at y=111, white alpha 100.
- **BG**: centre (184,176), DemiBold 600, white. Size 84 px for up to 3 characters ("9.4"), **74 px** for 4 characters ("11.8"). Glow: `setShadowLayer(12f, 0, 0, range colour alpha 0x96)`. The radius is capped at 12 so the digits stay sharp. mmol/L, one decimal, unit from the phone.
- **Trend chip**: circle centre (312,152), **r=22**, fill range alpha 40, 2 px stroke range alpha 210. Arrow glyph (→ ↗ ↑ ⇈ ↘ ↓ ⇊) **30 px** in the range colour, centred +1 px. Unknown direction or stale: empty chip.
- **Meta line** (replaces separate delta and age rows): one line, centre (312,200), **18 px Medium**: `+0.1` (delta colour) + ` · ` (txt3) + `1'` (age).
  - Age colour: txt2 below 6 min, warn at >= 6, alert at >= 12.
  - Delta is always signed, one decimal (delta or avgDelta per the existing preference).
- **Stale** (age >= 12 min or no BG): stale background bitmap, BG in txt2 with a 3 px strike-through and no glow, empty chip, the meta line shows only the age (e.g. `17'`) in alert, and the sparkline end point is grey (see glass_final_stale.png).
- No unit label.

## 4. Sparkline (3 h history + 1 h prediction), no card
- Plot rect (84,256)-(366,312). X: 36 history points (5 min) and 12 prediction points, so "now" is at x = 84 + 35/47*282 (about 294). Y: 3.0 mmol/L at y=312 up to 12.0 at y=256, values clamped.
- Target band: rounded rect r=8 from y(high) to y(low), range colour **alpha 24**. Low/high dashed lines 3 on, 5 off, 1 px, white alpha 46.
- Now line: vertical dashed 2 on, 4 off, 1 px, white alpha 60.
- History: 2.5 px line, colour gradient from #5AAAFF (oldest) to the range colour, glow via setShadowLayer(4, range alpha 180). **No area fill** (removed: it was invisible and added noise).
- Prediction: dots r=1.6, #AA82FF alpha 210, main/combined series only.
- Current point: white dot r=4 with a 2 px range ring and a range glow r=7. Stale: dot txt3, no glow.
- No labels, no SMB markers, no basal.
- Source: the existing graph data feed (BG of the last 3 h plus predictions).

## 5. Pills
- LOOP (106,324)-(219,366) and IOB (231,324)-(344,366), r=21. Glass: frosted region baked in, white gradient alpha 29 to 9, 1 px outline alpha 55, top hairline.
- Values: **20 px DemiBold**, txt1. Labels: **12 px DemiBold, label colour #8E99B8**, right-anchored at x1-14, letter-spacing +0.05 em.
- LOOP pill: dot centre (x0+20, 345), r=5, filled with the loop colour plus a glow r=9 at alpha 190. Value `0'` left-anchored at dot+13. When the minutes reach 2 digits (>= 10), the "LOOP" label is hidden to make room, and at >= 15 the value is drawn in alert (see glass_final_stale.png).
- IOB pill: value left-anchored at x0+16, 2 decimals, `,` replaced by `.` (status iobSum).

## 6. Footer line (conditional runs)
One line centred at x=225, y=390, **18 px Medium**. Runs are joined with `  ·  ` (txt3). They appear in this order, each only when its condition holds:
1. `TT 5.6`, colour tt: only while a temp target is active.
2. `COB 24 g`, txt2: only when COB > 0.
3. `0.00 U/h`, txt2: always (current basal, `,` replaced by `.`).
4. `90%`, txt2: only when profile != 100%.
5. `Tel 18%`: phone (uploader) battery < 20%, warn; < 10%, alert. Optional, same rule: `Res 12 U` / pump battery when below the phone's warning level, if the Status carries them.

Width check: the longest realistic line ("COB 24 g · 1.35 U/h · Tel 18%") is about 270 px; the chord at y=390 allows about 310 px. If a line is still wider than 300 px, drop run 4, then run 2.

**Disconnect override**: if the watch has had no Status/SingleBg for > 12 min, or the DataLayer node is disconnected, the whole footer becomes `Telefon getrennt · 14'` (minutes since the last data) in alert. This is the only place the warning appears. There is no permanent icon.

## 7. Bolus ball (interactive only)
- Preferences (shared with the Kusi face): `kusi_bolus_double_tap` (default **OFF**), `kusi_bolus_amount` (default 0.5 U). The ball is drawn only if the flag is ON, Wear control is enabled on the phone, and the face is not in ambient.
- OFF: **hidden**. The face is symmetric without it (glass_final_bolus_off.png).
- Idle: centre **(46,176)**, sphere r=30, radial gradient #28468F (rim) to #7AAFFF (centre) with the highlight shifted up-left about 8 px, alpha 235. Rim 1 px white alpha 150. Specular ellipse (29,150)-(51,163), white alpha 75, blur 3. Halo #5A96FF alpha 100, **r=32**, blur 7.
  - Text: `+0.5` at (46,173), **18 px Bold**, white. `U` at (46,189), 12 px DemiBold, #D7E6FF. **No "2×"/"nochmal" sub-label** (it was 9 px and unreadable). The double-tap hint lives in the preference description.
  - Touch target: circle r=44 around the centre, clipped to the screen.
- Armed (glass_final_armed.png): sphere #8A5A10 to #FFC864, halo #FFB840 alpha 120, `U` in #FFF0D0. Countdown ring **r=34**: track 2 px #FFB840 alpha 50, progress 3 px #FFB840 from 12 o'clock clockwise, shrinking 360° to 0° over `BolusTapGesture.WINDOW_MS` (1500 ms), 30 fps only during the window. Ring outer edge at x of about 81.5, so it stays more than 10 px from the card (x=92).
- A second tap in the window sends `ActionBolusPreCheck(amount, carbs=0)`. This is a **request only**: the phone runs its checks and the standard confirmation. On send: 150 ms white rim flash and the existing haptic, if any. A tap outside the target, expiry, or entering ambient (`gesture.reset()`) disarms.
- Never drawn and never hit-tested in ambient.
- Family alignment: Halo and Orbit should also drop the sub-label. If one is kept anywhere, use the German `kusi_bolus_again` = "nochmal" (values-de).

## 8. Ambient (glass_final_ambient.png, glass_final_ambient_stale.png)
- Pure black. No gradient, aurora, blur, fills, glows, shadows or ball.
- Time 42 px Thin/UltraLight (200, tabular), #E0E0E0. Date 18 px Medium #9A9A9A.
- Hero card: 1 px outline #3C3C3C. BG **Light (300)**, #E0E0E0, same sizes (84/74). Chip 1 px #6E6E6E with the arrow in #E0E0E0. Meta line: delta #9A9A9A, separator #6E6E6E, age #9A9A9A (#E0E0E0 at >= 6 min). Stale: strike-through 2 px, age only, #E0E0E0.
- Sparkline: low/high dashes and now line #3C3C3C, history 1.5 px #B4B4B4, current point black with a 1 px #E0E0E0 ring. **No prediction dots, no band.**
- Pills: 1 px outline #3C3C3C, values #E0E0E0, labels #6E6E6E. Loop dot: **hollow 2 px ring in the loop colour by default** (green, amber or red).
- Footer: runs #9A9A9A, separators #6E6E6E; the battery warning and `Telefon getrennt` in #E0E0E0.
- Low-bit: pure white or black, no anti-aliasing, dashed lines and the now line omitted, loop ring 1 px white, loop value gets a `!` at >= 15 min (e.g. `17'!`).
- Burn-in: about 3.3% lit pixels (>24 luma) in the reference. If `isBurnInProtectionRequired`, shift the layer by a random offset in [-4,+4] px each minute.

## 9. Update cadence
- Minute tick: time, date (midnight), age, loop minutes, disconnect timer, stale check (which can swap the bitmap). Sparkline x-shift only on a new BG or at a 5-min boundary.
- New SingleBg: BG, chip, meta line, range key (swap the bitmap only if it changed), sparkline.
- New Status: IOB, loop, footer.
- New GraphData: sparkline.
- Armed: 30 fps for at most 1.5 s, invalidating only the ball rect (0,128)-(92,224).
- No seconds. Follow the "store, redraw and refresh only what changed" pattern (commit fe418ea985): cached background bitmaps and card chrome, with the dynamic text and sparkline redrawn.

## Changes vs v2 (critique fixes)
- Aurora stronger (alpha 95 to 150, plus a core blob at alpha 120). It now reads teal, amber or red, as proven in glass_final_high.png and glass_final_low.png. Added a 4th **stale** grey variant.
- BG glow is drawn in the mockup, with the radius capped at 12. A 74 px size for 4-character values.
- Text sizes: date 15 to 18, delta+age 16/13 to one 18 px meta line, pill values 17 to 20, pill labels 10 px #6F7A99 to 12 px #8E99B8, footer 13 to 18, ball amount 15 to 18. The 9 px sub-label was removed.
- Chip r 17 to 22, glyph 22 to 30. The right column is now two rows instead of three.
- Ball moved to x=46, halo r 34 to 32, ring r 36 to 34, so the gap to the card is at least 10 px.
- TT colour #F2C66B to #E8D28A (less like "high" amber).
- COB shown in the footer when > 0. Phone battery shown when < 20%. Disconnect override "Telefon getrennt · N'".
- Ambient: BG Light 300, no prediction dots, loop ring in colour by default, low-bit `!` rule, stale age bright.
- Sparkline: area fill removed, band alpha 14 to 24.
