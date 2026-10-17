# CWF engine review for "AAPS Halo" (source: wear/.../watchfaces/CustomWatchface.kt @ db89b0c208)

Need vs CWF (pure zip):
- Radial backdrop tinted by range: YES (Background/BackgroundHigh/BackgroundLow png/svg/jpg, keyed on sgvLevel)
- Static ring track + target band + "IOB" label: YES (bake into Background/cover_chart)
- Filled BG arc: PARTIAL, quantized (freetext1 dynData valueKey sgv, image1..N steps; prefer SVG: PNG steps are all decoded at load, ~0.6-0.8 MB heap each at 400-450 px)
- Knob at value: YES, continuous (freetext + dynData rotationOffset minValue/maxValue, as SteamPunk does)
- Knob/arc colour by range: PARTIAL (one valueKey per view; colour steps are uniform bins over sgv in mg/dL, not the phone's low/high; colour filter desaturates, PNG only)
- Glow: static PNG only (no text shadow key; AndroidSVG ignores filters/blur)
- Comet tail (3 h history): NO (dynData only sees current values; chart is rectangular)
- Outer IOB arc: NO (ValueMap has no IOB/COB/basal; iobSum is a locale-formatted string, may use comma)
- Hero BG, arrow, delta, time, IOB value, TT, basal, loop pill: YES (sgv/direction/delta/time/iob2/tempTarget/basalRate/loop + dynData loop images)
- German date "Fr 17. Okt": YES with caveats (watch locale is German: dayName "E" -> "Fr.", stripped to "Fr"; month "MMM" -> "Okt"; day is "dd" zero-padded "07"; day/day_name/month hidden unless the SHOW_DATE watch pref is on). No-pref, locale-independent route: 3 freetext views with dynData valueKey day_name textvalue1..7, day dynValue "%.0f.", month textvalue1..12. Only 3 fixed boxes, no single line.
- Custom font: YES (ttf/otf in the zip, referenced by file name in "font")
- Tappable bolus ball: NO (taps hard-coded: chart double-tap = timeframe, sgv double-tap = main menu)
- Armed state visual: NO (no gesture state exposed)
- Ambient variants: NO (setColorLowRes == setColorDark; same full-colour images drawn in ambient; only "simplify UI" fallback)
- Install without activating: NO (watch has ONE user CWF slot; an import replaces the active CWF immediately)

Minimal approach C engine extension (all in CustomWatchface.kt plus a small BaseWatchFace hook):
1. ValueMap IOB/COB/BASAL (+ext): parse status strings with ',' -> '.', strip units. ~10 lines.
2. Ambient switch: pseudo prefKey "watch_ambient" usable in dynPref + checkPref sees the mode change (re-init on onWatchModeChanged). ~20 lines.
3. Tap action on a freetext view: "tapAction":"bolusRequest". Reuse kusi/BolusTapGesture; amount and enable come from WATCH prefs (default OFF) plus WearControl, never from the zip JSON; send ActionBolusPreCheck(amount,0); valueKey "bolusArmed" for the armed images; disabled in ambient. ~50 lines plus tests.
4. Optional: shadowColor/shadowRadius on TextView (glow) ~6 lines; "dateFormat" (+ optional "locale") on a TextView ~8 lines.
5. Separate slot ("install but do not activate"): a second WatchFaceService "AAPS Halo" sharing the CWF renderer, with its own DataStore field and a bundled asset zip. It must not write key_last_selected_watchface or overwrite customWatchface. This is the largest change; without it, pure B or C replaces the user's current CWF.
