# AAPS face family: state catalogue and shared rules (Halo, Glass, Orbit)

Version 1 · 17 Oct 2026 · design only, nothing in the repo was changed.

This file is the shared contract for all three faces. Each face spec (halo / glass_spec.md / orbit_spec.md) only decides *where* and *in which shape* something is drawn. *What* state is shown, *which colour* it gets and *what wins* when several apply is decided here.

Sources: the two catalogue passes (data-states, mode-states), merged and deduplicated; EventData.kt, DataHandlerMobile.kt (DHM), BaseWatchFace.kt (BWF), ComplicationStore.kt (CS), CustomWatchface.kt (CWF), wear/.../watchfaces/halo/* (HaloFaceLogic, BolusTapGesture, BolusRequestGuard), wear res values/values-de strings_halo.xml, glass_spec.md, orbit_spec.md, halo.py.

---

## 0. How to read this

- **id**: stable, `group.name`. Renderers, tests and mockup file names use it (`<face>_<id>.png`). Never renumber or reuse an id. A new state gets a new id.
- **prio**: `P0` must be designed and rendered for every face before release. `P1` should be designed (may share a mockup with a neighbour). `P2` nice to have, or documented as "not applicable".
- **merged from**: ids from the two input lists that this entry replaces (d: = data-states, m: = mode-states).
- **Trigger** always uses the numeric fields (sgv, deltaMgdl, timeStamp, openApsStatus, levels) and never parses display strings, except where the contract only offers a string (noted).

## 1. Derived signals (one shared Kotlin helper, `FaceState`, used by all three faces)

All faces compute the same signals in one place, so they can never disagree. HaloFaceLogic is the seed for this helper.

| Signal | Rule | Values |
|---|---|---|
| `bgAgeMin` | `max(0, now - singleBg[d].timeStamp) / 60 000`; undefined when timeStamp == 0 | int |
| `bgAge` | FRESH < 6 · AGING 6-11 · STALE 12-29 · VERY_STALE >= 30 · NONE when timeStamp == 0 | enum |
| `bgRange` | from `sgv` (mg/dL): URGENT_LOW sgv < 54 · LOW sgvLevel == -1 · IN sgvLevel == 0 · HIGH sgvLevel == 1 · VERY_HIGH sgv > 250. Only computed for FRESH/AGING; STALE+ forces `STALE` | enum |
| `lowLine/highLine` | SingleBg.low/high; if <= 0, fall back to 70 / 180 mg/dL | mg/dL |
| `sentinel` | LOW_SENTINEL sgv <= 39 · HIGH_SENTINEL sgv >= 400 | enum? |
| `trend` | map slopeArrow: "⇈" UP2, "↑" UP, "↗" UP45, "→" FLAT, "↘" DOWN45, "↓" DOWN, "⇊" DOWN2, "X" + deltaMgdl sign → UP3/DOWN3, anything else ("??", "--", unknown) → NONE | enum |
| `fastFall` / `fastRise` | trend in {DOWN2, DOWN3} or deltaMgdl <= -10 · trend in {UP2, UP3} or deltaMgdl >= +10 | bool |
| `loopAgeMin` | `now - status[d].openApsStatus`; undefined when openApsStatus == -1 | int |
| `loopRun` | RUNNING when externalStatus trimmed == "" · OFF when it starts with "Loop Disabled" · NO_PROFILE when it starts with "No profile" · UNKNOWN otherwise (incl. "no status") | enum |
| `loopMode` (dataset 0 only, best effort) | CLOSED / OPEN / LGS = the one of {LOOP_CLOSED, LOOP_OPEN, LOOP_LGS} missing from LoopStatesList.states; UNKNOWN if the list is missing, older than the last Status by > 15 min, or ambiguous; SUSPENDED when loopRun == OFF and the list is stale | enum |
| `loopHealth` | NEVER (-1) · OK <= 5 · WARN 6-14 · BAD >= 15. Forced to NEUTRAL when loopMode == OPEN or flavour is pumpcontrol | enum |
| `phoneReachable` | CapabilityClient FILTER_REACHABLE has a node (HaloWatchface.refreshPhoneReachable) | bool |
| `phoneLink` | OK reachable and last Status/SingleBg < 12 min · LOST_FRESH unreachable, data < 12 min · LOST_STALE unreachable, data >= 12 min · SILENT reachable but data >= 12 min | enum |
| `firstStart` | singleBg[0].timeStamp == 0 and status[0].openApsStatus == -1 and iobSum == "IOB" | bool |
| `noProfile` | loopRun == NO_PROFILE or iobSum == "" | bool |
| `flavour` | not sent. Heuristic: LoopStatesList ever received → FULL; else openApsStatus > 0 → CLIENT; else UNKNOWN_NOAPS (pumpcontrol or client without devicestatus). Faces must never show the flavour, only its effects. | enum |
| `tbrActive` | currentBasal ends with "%" → true; otherwise unknown (contract gap G4), optionally hinted by a TreatmentData.temps entry covering now | bool? |
| `display(s)` | `s.trim()`; "" , "IOB", "-.--", "--g", "-.--U/h", "no status" → "--" | string |

Width test strings (every face must fit them in its slots at 384 px): BG "400", "39", "22,2", "2,2", "---"; delta "+45", "-12,5", "+0,28"; IOB "-0,35", "12,40"; COB "20(30)g"; basal "0,85 U/h", "250%"; TT "4,4 - 5,0"; reservoir "120U"; age "47′", "3 Std"; patient "Annabelle-Sophie" (ellipsised).

---

## 2. State catalogue

### 2.1 Connectivity and lifecycle (`conn`)

| id | prio | Trigger | Treatment (all faces) | merged from |
|---|---|---|---|---|
| conn.first_start | P0 | `firstStart` | Skeleton face: time + date normal, BG "---" in txt2, no arrow, no delta, pills/balls show "--" in dim, status slot "Warte auf AAPS". No placeholder strings ("IOB", "no status") ever visible. Ball hidden. | d:conn.first_start, m:data.none_first_launch |
| conn.restored_old_data | P0 | DataStore restored after watch reboot; data age computed as usual | No own visual: the age rules (bg.age.*) must apply from the first frame. Test: render with a 3 h old timeStamp. | d:conn.restored_old_data |
| conn.no_profile | P0 | `noProfile` | Hero BG normal if BG fresh. IOB/COB/basal/BGI "--". Status slot "Kein Profil". Loop indicator grey. | d:conn.no_profile, m:flavor.full.no_profile |
| conn.phone_ok | P0 | phoneLink OK | Nothing. No permanent "connected" icon. | (baseline) |
| conn.phone_lost_fresh | P1 | phoneLink LOST_FRESH | Small phone-off glyph (12 px, txt2) next to the age or in the status slot only if the slot is otherwise empty. Data unchanged. Ball disabled. | d:conn.phone_unreachable_fresh |
| conn.phone_lost_stale | P0 | phoneLink LOST_STALE | BG stale treatment + status slot "Telefon getrennt · 14′" in alert. Ball disabled. This is "disconnected". | d:conn.phone_unreachable_stale, m:data.phone_disconnected |
| conn.phone_silent | P1 | phoneLink SILENT (AAPS killed, no CGM, NS down for client) | BG stale treatment + status slot "Keine Daten · 17′" in alert. The watch keeps its ActionResendData timer (16′, then every 5′). | d:conn.reachable_no_new_data |

### 2.2 Glucose value (`bg`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| bg.in_range | P0 | bgRange IN | range.in hue on the face's BG accent (Halo ring, Glass aurora, Orbit corona). Digits white. | d:bg.in_range, m:bg.in_range |
| bg.high | P0 | bgRange HIGH | range.high hue. No other change. | d:bg.high, m:bg.high |
| bg.very_high | P1 | bgRange VERY_HIGH (> 250 mg/dL / 13,9) | range.high hue + the face's "emphasis" step (Halo: ring 1.5x stroke; Glass: aurora alpha +20 %; Orbit: corona alpha +20 %). No new hue, no motion. | d:bg.very_high |
| bg.low | P0 | bgRange LOW | range.low hue. | d:bg.low, d:bg.very_low (folded in), m:bg.low |
| bg.urgent_low | P0 | bgRange URGENT_LOW, or LOW with fastFall | range.low hue + emphasis step + BG digits in range.low tint (#FFD6DC) + status slot suppresses everything below W4 (§4.2). Legible in every ambient variant (digits stay at full size, outline weight +1 px). Never flashing. | d:bg.urgent_low |
| bg.sentinel_low | P1 | sgv <= 39 | Show the phone string ("39" / "2,2") plus a small "LOW" caption under/next to the digits; treatment = bg.urgent_low. | d:bg.sentinel_low, m:bg.extreme_text |
| bg.sentinel_high | P1 | sgv >= 400 | Show "400+" in mg/dL or "22,2+" in mmol (append "+" to the phone string); treatment = bg.very_high. | d:bg.sentinel_high, m:bg.extreme_text |
| bg.hidden_pref | P2 | key_show_bg off | Not applicable to these faces: the BG is the face. Documented: pref ignored. | m:pref.each_toggle |

### 2.3 Data age (`age`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| age.fresh | P0 | bgAge FRESH | Age label txt2 (or hidden if key_show_ago off). | d:age.fresh, m:data.fresh |
| age.aging | P0 | bgAge AGING | Age label in warn. Everything else unchanged, also shown when key_show_ago is off (the warning is not optional). | d:age.aging, m:data.aging |
| age.stale | P0 | bgAge STALE | Range hue replaced by range.stale everywhere (ring/aurora/corona, sparkline end). BG digits txt2 with a strike-through (3 px at 450). Arrow/trend chip empty. Delta hidden. Age label in alert. | d:age.stale, m:data.stale |
| age.very_stale | P1 | bgAge VERY_STALE | As stale, plus the BG digits are replaced by "---" from 60 min on; the age label switches to hours from 60 min ("1 Std", "3 Std"). Graph shows its empty right side honestly. | d:age.very_stale, m:data.very_stale |
| age.none | P0 | bgAge NONE | BG "---", age "--", no strike-through. (Part of conn.first_start when the rest is empty too.) | d:age.no_bg_ever |
| age.clock_skew | P2 | timeStamp > now | Clamp to 0 (shown as fresh). | d:age.negative |

### 2.4 Trend and delta (`trend`, `delta`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| trend.normal | P0 | trend in UP/UP45/FLAT/DOWN45/DOWN | Face's own glyph or angle (no system arrow font). Range hue. | d:trend.arrows, m:bg.arrows |
| trend.fast | P1 | trend UP2/DOWN2/UP3/DOWN3 | Double glyph (Glass/Orbit) or double chevron on the ring (Halo). Triple uses the same double glyph + a thicker stroke. Direction for "X" from deltaMgdl sign. Emphasise only the arrow. | d:trend.triple, d:trend.rising_fast, d:trend.falling_fast |
| trend.none | P0 | trend NONE | Empty chip / no pointer. Never "??" or a tofu glyph. | d:trend.none |
| trend.hidden_pref | P1 | key_show_direction off | Chip/pointer removed, layout reflows (§4.8). | m:pref.each_toggle |
| delta.normal | P0 | delta strings present | Signed phone string, unchanged ("+0,1", "+3"). Which string: key_show_detailed_delta → deltaDetailed / avgDeltaDetailed; key_show_avg_delta → avgDelta; else delta. | d:delta.normal, m:bg.delta_variants |
| delta.missing | P0 | deltaMgdl == null or string "--" | "--" in txt3 if the delta is enabled; no delta-driven geometry. | d:delta.missing |
| delta.wide | P1 | string length >= 5 | Must fit; slot sized for "-12,5". | d:delta.large |
| delta.hidden_pref | P1 | key_show_delta off | Removed, the meta line joins only age. | m:pref.each_toggle |

### 2.5 Units and locale of numbers (`units`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| units.mmol | P0 | glucoseUnits "mmol" | One decimal, phone separator (German: comma). Graph y range 2,5-14 (clamp). Target labels in mmol. | d:bg.units_mmol, m:units.mmol |
| units.mgdl | P0 | glucoseUnits "mg/dl" | Integers up to 3 digits ("400"). Graph y range 45-252. Same layout, no overflow. | d:bg.units_mgdl, m:units.mgdl |
| units.unknown | P2 | glucoseUnits "-" | No unit label. (No face shows a unit label anyway.) | d:bg.units_unknown |
| units.phone_locale | P0 | always | Never replace "," by "." and never parse strings for geometry (Glass/Orbit specs currently do: see §6). Watch-generated numbers (ball amount, graph labels) use the watch locale: "0,5" in German. | d:bg.locale_comma |

### 2.6 Graph (`graph`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| graph.normal | P0 | entries present | Face-specific (Halo ring trail / Glass sparkline / Orbit sparkline). Window = key_chart_time_frame. | — |
| graph.empty | P0 | entries empty | Plot area with target band only. No crash, no fake line. | d:graph.empty, m:chart.empty_sparse |
| graph.gaps | P1 | gap > 15 min between points | Line breaks; no interpolation. | d:graph.gaps, m:chart.empty_sparse |
| graph.no_predictions | P1 | predictions empty (open loop, client, pumpcontrol) or key_prediction_lines off | Right side of "now" stays empty; layout unchanged. | d:graph.treatments, m:pref.chart_layers |
| graph.layers | P2 | key_show_graph_bolus/carbs/basal/temp_basal/grid, key_highlight_basals | Family decision: the custom faces show BG + predictions only; these prefs are documented as "only in the classic faces". isValid == false treatments are never drawn. | d:graph.treatments, m:pref.chart_layers |
| graph.timeframe_changed | P1 | key_chart_time_frame changed (double-tap on the graph area) | Rescale; show "3 Std" as a txt2 caption in the graph area for 1,5 s (interactive only). | m:pref.chart_timeframe |

### 2.7 Insulin and carbs (`iob`, `cob`, `basal`, `tt`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| iob.normal | P0 | iobSum numeric | Phone string, ins colour on value/arc. Arc fraction = value / 6 U, parse with comma support. | — |
| iob.zero | P0 | iobSum "0,00" | Value in dim text, arc empty. | d:iob.zero |
| iob.negative | P0 | value < 0 | Minus sign shown, arc empty (no reverse arc), value in txt1. | d:iob.negative |
| iob.large | P1 | value >= 6 | Arc full (capped); text fits 5 chars. | d:iob.large |
| iob.detailed | P2 | key_show_detailed_iob | iobDetail "(1,20\|-0,35)" replaces the value only where a face has a text slot of that width (Glass footer, Orbit none). Otherwise ignored, documented. | d:iob.detailed, m:status.iob_detail |
| iob.unknown | P0 | display(iobSum) == "--" | "--", arc empty, dim. | d:iob.unknown |
| cob.values | P0 | cob string | "0g" → dim or omitted (face rule); "45g" normal; "20(30)g" shown as is; "--g"/"" → "--". | d:cob.values, m:status.cob_variants |
| basal.normal | P1 | currentBasal present, tbrActive not true | Phone string, txt2. | d:basal.profile, d:basal.temp_absolute, d:basal.extended |
| basal.tbr_percent | P1 | tbrActive (ends with "%") | Value in the tbr accent (#3DDBD9); "0%" in warn. | d:basal.temp_percent, m:status.temp_basal |
| basal.zero_abs | P1 | currentBasal starts with "0,00"/"0.00" | Value in warn (zero temp or zero profile; both deserve attention). | d:basal.temp_absolute |
| basal.unknown | P0 | display() == "--" | "--". | d:basal.unknown |
| tt.profile | P0 | tempTargetLevel 0 | Hidden (face default) — the profile target is not news. | d:tt.profile_range |
| tt.aps_adjusted | P1 | tempTargetLevel 1 | Hidden by default; optionally the target value in txt2 with a small tt-dot. Never violet chip. | d:tt.aps_adjusted |
| tt.active | P0 | tempTargetLevel 2 | Chip/run "TT 8,0" (or range "4,4 - 5,0") in tt colour. Type (eating soon/activity/hypo) not available (G3). | d:tt.active, m:status.temp_target |
| tt.unknown | P0 | display() == "--" | Hidden. | d:tt.unknown |

### 2.8 Loop (`loop`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| loop.ok | P0 | loopRun RUNNING, loopHealth OK | Loop dot/pill in loop.ok, minutes in txt1 ("2′"). | d:loop.fresh, m:flavor.full.closed |
| loop.warn | P0 | loopHealth WARN | Dot in warn, minutes in warn. | d:loop.aging |
| loop.bad | P0 | loopHealth BAD (closed/LGS/unknown mode) | Dot in alert, minutes in alert, status slot "Loop 23′" (W6). | d:loop.old, m:data.loop_stale |
| loop.never | P0 | openApsStatus -1 | Dot hollow grey, value "--". Never red. | d:loop.never |
| loop.open | P1 | loopMode OPEN | Dot hollow loop.ok outline, label "Offen" instead of minutes. Age is not an alarm. | d:loop.mode_open, m:flavor.full.open_lgs |
| loop.lgs | P1 | loopMode LGS | As loop.ok + small "LGS" label. Age rules apply. | d:loop.mode_lgs, m:flavor.full.open_lgs |
| loop.closed | P1 | loopMode CLOSED | Baseline; no label. | d:loop.mode_closed |
| loop.off | P0 | loopRun OFF (disabled, suspended, pump suspended, disconnected, superbolus, DST) | Dot hollow warn ring with a pause bar, label "Loop aus", status slot (W5). The finer reason is shown only if the face requested LoopStatusData on tap; push data cannot tell them apart. | d:loop.not_running, d:loop.mode_suspended, d:loop.mode_disconnected, d:loop.mode_superbolus, d:loop.mode_disabled, m:flavor.full.loop_disabled |
| loop.unknown_mode | P0 | loopMode UNKNOWN (client, pumpcontrol, stale list) | Only the age-based dot; no mode label. | d:loop.mode_unknown |
| loop.no_aps | P0 | flavour UNKNOWN_NOAPS and openApsStatus -1 | Loop element replaced by neutral grey "Pumpe" dot or hidden (face decides, consistently). Never red. | d:flavor.pumpcontrol, m:flavor.pumpcontrol |

### 2.9 Pump, batteries, misc status (`pump`, `batt`, `status`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| pump.res_ok | P2 | reservoirLevel 0, string non-empty | Shown only if key_show_reservoir_level and the face has a slot; txt2. | d:pump.reservoir_ok |
| pump.res_low | P1 | reservoirLevel 1 | "Res 18U" run in warn (W9). | d:pump.reservoir_low, m:status.reservoir |
| pump.res_critical | P0 | reservoirLevel 2 and string non-empty | "Res 5U" in alert (W7). Shown even when the pref is off. | d:pump.reservoir_critical |
| pump.res_unknown | P0 | reservoirString "" or "--" | Hidden. Never red (level 2 is meaningless here). | d:pump.reservoir_unknown_or_empty |
| pump.res_patch_clamped | P2 | patch pump at max | Not detectable reliably; show the string as is. | d:pump.patch_clamped |
| batt.phone_ok | P2 | batteryLevel 1 | Hidden unless key_show_uploader_battery; then "Tel 64 %" txt2. | d:phone.battery_ok |
| batt.phone_low | P1 | batteryLevel 0 and battery not "0" | "Tel 18 %" in warn, < 10 % in alert (W8). battery "0" = unknown → hidden. | d:phone.battery_low, m:status.batteries |
| batt.rig | P2 | key_show_rig_battery | rigBattery as is (has its own %); "100%" with no uploaders is shown as is (G9). | d:rig.battery |
| batt.watch_charging | P2 | watch charging | No face glyph; simplify_ui may take over (sys.simplified). | m:system.charging |
| status.external | P2 | key_show_external_status | Not drawn as free text. Its meaning is mapped to loop.off / conn.no_profile / conn.first_start. | d:status.external, m:status.external_text |
| status.bgi | P2 | key_show_bgi | Glass footer run "BGI -1,2" in txt2; Halo/Orbit: not shown (documented). | d:status.bgi, m:status.bgi |

### 2.10 Patients / datasets (`multi`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| multi.one | P0 | only dataset 0 has timeStamp > 0 (or key_include_external off) | Normal face. patientName hidden. | d:multi.dataset_0, m:patients.one |
| multi.two | P1 | dataset 1 present and key_include_external on | One compact secondary block (§4.9). | d:multi.dataset_1_2, m:patients.two |
| multi.three | P1 | datasets 1 and 2 present | Two compact blocks, must fit at 384 px. | m:patients.three |
| multi.secondary_stale | P1 | a secondary block STALE+ | Only that block greys and strikes; it never affects the primary's colours or the status slot. | m:patients.secondary_stale |
| multi.switched | P2 | key_switch_external | Hero shows dataset 1, dataset 0 goes into a block. Graph, treatments, loop mode and the ball stay dataset 0 only and are hidden while switched (ball hidden too). | m:patients.switch_external |
| multi.patient_name | P1 | patientName non-empty | Name above/next to its BG, max 10 chars + "…". Primary name shown only when a secondary block exists. | d:multi.patient_name |

### 2.11 Flavours (`flavor`) — effects only, never a label

| id | prio | Effective states | merged from |
|---|---|---|---|
| flavor.full | P0 | everything; loop.* by enact time; loopMode known | d:flavor.full, m:flavor.full.closed |
| flavor.client | P0 | loop.unknown_mode; loop age from NS (minutes late is normal, so loop.warn is common: no extra warning); predictions maybe; ball usually hidden (wearControl off) or refused by phone | d:flavor.aapsclient, m:flavor.aapsclient.primary |
| flavor.pumpcontrol | P0 | loop.no_aps; graph.no_predictions; tt.aps_adjusted never; ball possible | d:flavor.pumpcontrol, m:flavor.pumpcontrol |

### 2.12 Display modes (`mode`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| mode.interactive | P0 | WatchMode.INTERACTIVE | Full colour, glow, ball, seconds if enabled. | m:mode.interactive |
| mode.ambient | P0 | AMBIENT | §4.5: greys only, outlines, AA on, minute updates. | m:mode.ambient |
| mode.ambient_low_bit | P0 | LOW_BIT | §4.5: pure black/white, AA off, no greys. | m:mode.ambient_low_bit |
| mode.ambient_burn_in | P0 | BURN_IN | §4.5: no fills, strokes <= 2 px, per-minute shift. | m:mode.ambient_burn_in |
| mode.ambient_low_bit_burn_in | P1 | LOW_BIT_BURN_IN | Union of the two above. | m:mode.ambient_low_bit_burn_in |
| mode.ambient_warning | P0 | any ambient + age.stale / conn.phone_lost_stale / loop.bad / bg.urgent_low | Warning word stays as text ("Keine Daten · 17′"), strike-through for stale; never only a colour or a "!". | m:mode.ambient_stale |
| mode.transition | P0 | enter ambient | ball → hidden, armed cleared (gesture.reset()), toasts dropped, graph caption dropped. On exit: recompute ages before the first frame. | m:mode.transition, m:system.theater_mode |

### 2.13 Watch preferences and system (`pref`, `sys`)

| id | prio | Trigger | Treatment | merged from |
|---|---|---|---|---|
| pref.all_on | P0 | every display_preferences toggle on | Densest layout; verified at 384 px round. | m:pref.content_toggles_all_on |
| pref.minimal | P0 | only BG + direction + time | Reflow, no holes, no "--" (§4.8). | m:pref.content_toggles_minimal, m:pref.each_toggle |
| pref.date | P1 | key_show_date / key_show_week_number | Date "Fr 17. Okt"; week "KW 42" appended with " · ". Off → line removed, time moves to the optical centre of its zone. | m:pref.date_seconds_week |
| pref.seconds | P2 | key_show_seconds | Interactive only, small ":07" after the minutes in txt2; never ambient. | m:pref.date_seconds_week |
| pref.dark | P2 | key_dark | Ignored: all three faces are dark only (OLED). Documented. | m:pref.dark_light |
| pref.match_divider | P2 | key_match_divider | Not applicable. Documented. | m:pref.match_divider |
| pref.vibrate_hourly | P2 | key_vibrate_hourly | No visual change. | m:pref.vibrate_hourly |
| sys.simplified | P0 | simplify_ui active (charging/ambient/both) | Family uses the shared SimpleUi (BG + arrow + time, strike when stale), but in family colours and with the face's BG font. No per-face variant. | m:pref.simplify_ui |
| sys.preview | P0 | isHeadless (picker, companion) | Deterministic sample: 6,4 mmol (or 115 mg/dL), "→", "+0,1", 1′, loop 2′, IOB 1,25, time 10:08, "Fr 17. Okt". No ball, no services. | m:preview.picker |
| sys.config_preview | P2 | config activity preview | As sys.preview but with the ball idle when the pref is on. | m:preview.config_screen |
| sys.notification_dot | P2 | system unread dot | Keep the 12 o'clock rim (top 24 px) free of essential content. | m:system.notification_overlay |
| sys.font_scale | P2 | system font scale | Ignored by the canvas faces; toasts follow it. Documented. | m:a11y.font_scale |
| locale.de_24h | P0 | German, 24 h | "14:07", "Fr 17. Okt" (`EE d. MMM`, dots stripped). | m:locale.de_24h |
| locale.en_12h | P0 | English, 12 h | "2:07" + "PM" in txt2 at 40 % of the time size, right of the minutes; "Fri, Oct 17". | m:locale.en_12h |
| locale.long | P1 | fr/nl/ru/pl | Labels ellipsise; status slot strings must fit 300 px at 18 px. | m:locale.long_strings |
| locale.rtl | P2 | ar, iw | Layout not mirrored (circular faces); numbers LTR; text runs right-aligned only where they are left-aligned in LTR. | m:locale.rtl |

### 2.14 Tap targets (`tap`)

| id | prio | Rule | merged from |
|---|---|---|---|
| tap.graph | P0 | Graph zone: single tap → MainMenuActivity; double tap (800 ms) → cycle time frame. Zone may not overlap the ball zone (min 8 px gap at 450). | m:tap.chart |
| tap.menu | P0 | Time zone: single tap → MainMenuActivity (same as stock mainMenuTap). | m:tap.main_menu |
| tap.loop | P2 | Loop element: tap → ActionLoopStatusDetailed (shows the system dialog). Optional. | d:loop.* note |
| tap.ambient | P0 | Ambient: no hit testing at all; tap only wakes. | m:tap.ambient |
| tap.complications | P2 | complication_tap_action: not applicable (no complication slots). | m:tap.complication_action |

### 2.15 Bolus ball (`ball`) — see the state machine in §4.6

| id | prio | merged from |
|---|---|---|
| ball.off | P0 | m:ball.off_pref |
| ball.hidden | P0 | m:ball.hidden_ambient_preview, m:ball.hidden_wear_control_off, m:ball.refused_amount, m:ball.multi_patient (switched) |
| ball.idle | P0 | m:ball.idle, m:ball.disarmed, m:ball.amount_variants, d:control.bolus_ball |
| ball.disabled | P0 | m:ball.refused_no_phone, m:ball.refused_stale_data, m:ball.refused_max_bolus |
| ball.armed | P0 | m:ball.armed |
| ball.sent | P0 | m:ball.sending, m:ball.awaiting_confirm, m:ball.refused_pending_cooldown, m:ball.phone_rejected, m:ball.user_cancel |
| ball.delivering | P2 | m:ball.delivering, d:control.bolus_progress |
| ball.delivered | P2 | m:ball.delivered |

### 2.16 Known gaps (no state can be designed without a contract change)

| id | Gap | Design answer now |
|---|---|---|
| gap.G1 | Urgent/very levels | Derived on the watch (§1), constants 54 / 250 mg/dL. Future: watch prefs. |
| gap.G2 | Sensor warm-up, calibration, sensor age | Appears only as age.stale. |
| gap.G3 | TT reason, high vs low TT, profile name, profile % | TT shows the value only; no profile % run (Glass spec run 4 removed). |
| gap.G4 | TBR-active flag for absolute TBRs | basal.tbr_percent only; absolute shown neutral. |
| gap.G5 | Pump battery, pod/cannula age, pump connection | Not shown. |
| gap.G6 | Exact loop mode for client / suspended reason | loop.off / loop.unknown_mode. |
| gap.G7 | "Phone connected" flag | CapabilityClient + data age (phoneLink). |
| gap.G8 | Flavour flag | Heuristic, effects only. |
| gap.G9 | rigBattery "100%" with no uploaders | Shown as is, off by default. |

---

## 3. Colour tokens (one table for all three faces)

Faces may vary *material* (glass, flat, glow) and alpha, never the hue meaning. Values are on black.

| Token | Hex | Meaning |
|---|---|---|
| range.in | #3DDC97 | BG in range; loop ok (loop.ok uses the same green family #40D68C, allowed) |
| range.high | #FFB547 | BG high / very high |
| range.low | #FF5A6E | BG low / urgent low |
| range.low.text | #FFD6DC | digits tint in urgent low |
| range.stale | #8A93A8 | any BG >= 12 min, unknown |
| warn | #FFB547 | attention (aging, loop 6-14, low battery, res low, 0 basal) — same as high on purpose: "look soon" |
| alert | #FF5A6E | act (stale/disconnected text, loop >= 15, res critical) — same as low: "look now" |
| ins | #5AC8FA | IOB value/arc |
| tbr | #3DDBD9 | percent TBR running |
| tt | #B388FF | active TT, prediction dots (Glass #AA82FF is within tolerance) |
| ball.idle | #7888FF | bolus ball (indigo, deliberately not amber/red/green) |
| ball.armed | #5B63F5 fill, white text/countdown | armed |
| txt1 / txt2 / txt3 | #EEF1F7 / #9AA6C4 / #6F7A99 | primary / secondary / separators + dim |
| amb1 / amb2 / amb3 / ambLine | #E0E0E0 / #9A9A9A / #6E6E6E / #3C3C3C | ambient greys (AMBIENT only) |

Semantic rules:
1. **Red means "act now", amber means "look soon", grey means "unknown / not applicable", never anything else.** A missing value is grey, never red (loop.never, pump.res_unknown, flavour gaps).
2. Colour is never the only carrier: every amber/red state has a word, a number or a shape change (strike, hollow dot, pause bar) too, so it works in ambient and for colour-blind users.
3. At most **two** red elements at once: the BG accent and the status slot. Element-level red (loop dot) yields to grey-red outline when the status slot already says the same thing.
4. Stale beats range: once bgAge >= STALE, no range hue anywhere (an old low must not look current, an old in-range must not look safe).
5. The ball never uses range/warn/alert hues.
6. Glow only in interactive, only in range hue or ball hue, radius <= 12 px at 450.

## 4. Family rules

### 4.1 Channels
Every face has exactly these channels; the face spec says where each lives.
- **Hero**: BG digits + range accent + trend + delta/age meta line.
- **Status slot**: one line of text, max ~300 px at 18 px (Glass footer override, Orbit pill under the delta row, Halo text under the BG). Empty most of the time.
- **Indicators**: loop dot/pill, IOB, COB, basal, TT, reservoir, batteries (each colours itself by its own rules).
- **Ball**.
- **Secondary patients**.

### 4.2 Warning hierarchy (what owns the status slot)
Evaluate top-down for dataset 0; the first true one owns the slot. Lower ones still colour their own indicator if it is visible, but never get text in the slot.

| Rank | State | Slot text (de) | Colour |
|---|---|---|---|
| W1 | conn.first_start | Warte auf AAPS | txt2 |
| W2 | conn.phone_lost_stale | Telefon getrennt · 14′ | alert |
| W3 | conn.phone_silent / age.stale+ (phone reachable) | Keine Daten · 17′ | alert |
| W4 | conn.no_profile | Kein Profil | warn |
| W5 | loop.off | Loop aus | warn |
| W6 | loop.bad (not open, not no_aps) | Loop 23′ | alert |
| W7 | pump.res_critical | Reservoir 5U | alert |
| W8 | batt.phone_low | Tel 18 % | warn (<10 %: alert) |
| W9 | pump.res_low | Reservoir 18U | warn |
| W10 | conn.phone_lost_fresh | (glyph only) Telefon getrennt | txt2 |
| — | otherwise | face's normal content (Glass: footer runs, Orbit/Halo: empty) | |

Overrides:
- **BG urgency is not in the slot**; it lives in the hero and always shows. While bg.low/bg.urgent_low is active and fresh, ranks W7-W10 are suppressed entirely (no slot text, indicator colour only) so the user sees one thing.
- If W2/W3 owns the slot, the loop dot also shows its own colour (usually red) but the face must not add a second red word.
- Ambient keeps W1-W6 only.
- Secondary datasets never enter the hierarchy; they show warnings inside their own block (§4.9).

### 4.3 Text fallbacks
- Enabled element with no data → "--" in txt3. Disabled element → absent (no "--", no hole).
- Map through `display()` (§1): "", "IOB", "-.--", "--g", "-.--U/h", "no status", "??" are never drawn.
- BG with no value → "---" (three dashes, the stock convention).
- Age → "--" when unknown, minutes with prime "17′", hours from 60 min "3 Std", days from 48 h "2 T".
- Never show 0 for unknown (IOB "0,00" only when the phone says so).
- Trim externalStatus' trailing newline before comparing.

### 4.4 Strings (German first; add to strings_face.xml + values-de; English in brackets)
| key | de | en |
|---|---|---|
| face_waiting | Warte auf AAPS | Waiting for AAPS |
| face_phone_lost | Telefon getrennt · %1$s | Phone disconnected · %1$s |
| face_no_data | Keine Daten · %1$s | No data · %1$s |
| face_no_profile | Kein Profil | No profile |
| face_loop_off | Loop aus | Loop off |
| face_loop_age | Loop %1$s | Loop %1$s |
| face_loop_open | Offen | Open |
| face_loop_lgs | LGS | LGS |
| face_pump_only | Pumpe | Pump |
| face_reservoir | Reservoir %1$s | Reservoir %1$s |
| face_phone_batt | Tel %1$s %% | Phone %1$s %% |
| face_tt | TT %1$s | TT %1$s |
| face_cob | KH %1$s | COB %1$s |
| face_iob | IOB | IOB |
| face_bgi | BGI %1$s | BGI %1$s |
| face_low / face_high | TIEF / HOCH | LOW / HIGH |
| face_minutes | %1$d′ | %1$d′ |
| face_hours | %1$d Std | %1$d h |
| face_days | %1$d T | %1$d d |
| face_week | KW %1$d | W%1$d |
| face_chart_hours | %1$d Std | %1$d h |
| ball refusal toasts | existing halo_bolus_refused_* (values-de already present) | |
| ball unit | U (as in existing values-de strings; switch to "IE" family-wide only if the user decides) | U |

Date: `DateTimeFormatter.ofPattern("EE d. MMM", Locale.GERMAN)` with "." removed from the weekday and month abbreviations → "Fr 17. Okt"; other locales use `DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`. Time: system 12/24 h. COB label "KH" in German (watch already uses it), "COB" elsewhere.

### 4.5 Ambient rules (all four variants)
Kept: time (no seconds), date, BG digits, trend glyph (outline), meta line age, loop as text/outline, status slot W1-W6, TT text, IOB value. Dropped: ball (never drawn, never hit-tested), glow, aurora/gradients, prediction dots, sparkline fill, graph captions, toasts, secondary blocks reduce to name + BG + age.
- **AMBIENT**: only amb1-amb3/ambLine on black; AA on. Range state is expressed by a shape: high = outline ring/accent dashed, low = accent doubled, stale = strike-through + "Keine Daten" text. No hue.
- **LOW_BIT**: amb1 → white, amb2/amb3 → white or dropped, ambLine dropped; AA off; strike-through 2 px; no greys.
- **BURN_IN**: no filled area > 4x4 px (BG digits outlined, dots hollow), strokes <= 2 px, whole layout shifted by (dx,dy) ∈ [-4,4] px, new offset every minute; pixel-on budget < 10 %.
- **LOW_BIT_BURN_IN**: both.
- Updates once per minute; ages computed at draw time.

### 4.6 Bolus ball state machine
Pref `halo_bolus_double_tap` (family-wide key, default off; the Glass spec's `kusi_*` keys are superseded), amount `halo_bolus_amount` ∈ ALLOWED_AMOUNTS {0.5, 1.0}, user default 0,5 U. Timings from code: WINDOW_MS 800, COOLDOWN_MS 5000, PENDING_MS 30 000, DATA_FRESH_MS 12 min.

```
OFF ──pref on──▶ (visibility check) ──▶ HIDDEN | DISABLED | IDLE
visibility: HIDDEN if ambient (any) | headless | !wearControl | amount not allowed | multi.switched
            DISABLED if guard.evaluate() ∈ {NO_PHONE, STALE_DATA, MAX_BOLUS}
            else IDLE
IDLE ──tap in ball──▶ ARMED (haptic tick; countdown ring 360°→0° over WINDOW_MS)
ARMED ──2nd tap in window, verdict SEND──▶ SENT (ActionBolusPreCheck(amount, 0); white rim flash 150 ms)
ARMED ──2nd tap, verdict REFUSED_*──▶ IDLE/DISABLED + toast (halo_bolus_refused_*)
ARMED ──timeout | tap elsewhere | ambient | screen off──▶ IDLE (silent)
SENT  ──(phone ConfirmAction screen opens, user confirms/cancels, or phone rejects)──▶ stays SENT until PENDING_MS
SENT  ──PENDING_MS elapsed──▶ IDLE   (taps in SENT → toast "Eine Anfrage läuft bereits")
DISABLED ──tap──▶ toast with the reason, no arming
any ──enter ambient──▶ HIDDEN (gesture.reset())
```
Visuals (same for all faces, materials may differ): IDLE = ball.idle ring/sphere, "+0,5" + "U". DISABLED = same at 40 % alpha, text txt3, no glow. ARMED = ball.armed fill, white text, white countdown ring. SENT = idle colours at 55 % alpha with a static small "…" under the amount (no spinner). Optional P2: ball.delivering = thin progress ring from BolusProgress.percent while SENT; ball.delivered = check mark for 2 s. Hit area r=44 at 450, clipped to the screen; >= 8 px from the graph zone; never covering BG digits. The ball always means dataset 0's pump.

### 4.7 Scaling and shapes
- Design canvas 450x450. Scale `s = min(w, h_visible) / 450`; all geometry, fonts and strokes × s; strokes min 1 px, text min 11 px physical (about 12 sp).
- Round 384-416 (Pixel Watch 1/2 384, GW 40 mm 396, 416): s 0.85-0.92. Font floor kicks in for labels; hide pill labels before shrinking values. Multi-three must still fit (blocks shrink to name+BG only).
- Round 450-480 (GW 44-47 mm 450/480, Pixel Watch 3 456, TicWatch 454/466): s 1.0-1.07.
- Square/rect (320, 360, 368x448): the face is drawn into the inscribed circle (diameter = min side) centered; corners stay black except: status slot may widen to the full width; ball may move to the corner zone if that keeps it >= 8 px from content. For 368x448 centre vertically.
- Flat tyre (WindowInsets bottom > 0, e.g. 360x330 visible): circle diameter = width, everything below `h - insetBottom - 6 px` moves up by inset; the date/footer row moves above the chin; nothing essential below the chin.
- Safe radius: essential text inside r = 0.86 × R; dots/rims up to 0.96 × R.
- Density: draw in px from the scaled canvas; bitmaps (Glass backgrounds) are generated at the real size at onSurfaceChanged, never upscaled.

### 4.8 Content toggles
Each indicator has: on (value), on (no data → "--"), off (absent). When off, its neighbours reflow (pills centre as a group; footer runs join with " · "). Exceptions that ignore their toggle: age.aging/stale label, loop.bad, pump.res_critical, batt.phone_low < 10 % (safety). key_show_bg ignored.

### 4.9 Multi-dataset rule
- Dataset 0 owns the hero, graph, treatments, loop mode, status slot and ball.
- Datasets 1/2 (SingleBg + Status only) are shown only when timeStamp > 0 and key_include_external is on, as compact blocks: name (or "P2"/"P3" when empty), BG in its range hue, small arrow, age, loop dot. Their stale/disconnected state stays inside the block (grey + strike + age).
- Block placement per face: Halo bottom-left/right under the ring, Glass replaces the footer run area with two chips, Orbit uses two small balls on the orbit at 225°/315°.
- Block size at 450: 100x40; at 384 it drops the loop dot.

### 4.10 Motion
Allowed: armed countdown, ball rim flash, 1,5 s graph caption fade, value cross-fade 200 ms on new data. Not allowed: pulsing, flashing, rotating, colour cycling, animated warnings. Redraw only on data/minute/tap.

## 5. Minimum mockup matrix per face (P0)
interactive in/high/low/urgent_low · stale · very_stale · first_start · phone_lost_stale · no_profile · loop.off · loop.never (pumpcontrol) · loop.open · mg/dL variant of interactive · mmol 4-char ("11,8") · all-on prefs · minimal prefs · multi.two · multi.three @384 · ball idle/armed/disabled/sent · ambient (in, stale) · low_bit · burn_in · round 384 · square 360 · flat tyre 360x330 · en 12 h · sys.preview. File names `<face>_<id>.png`.

## 6. Conformance deltas found in the current face specs (to fix in each face spec)
1. Glass: range hues #78E6BE/#FFB840/#FF5C5C → family #3DDC97/#FFB547/#FF5A6E (or accept as "glass tint" only if the hue stays within ±6°; current in-range teal is noticeably bluer, align).
2. Glass: armed ball is amber → must be ball.armed indigo (rule 3.5; amber means "look soon").
3. Glass + Orbit: replace "," by "." in IOB/basal/delta → forbidden (units.phone_locale); German shows commas.
4. Glass: countdown "WINDOW_MS (1500 ms)" → code is 800 ms; specs must reference the constant, not a number.
5. Glass: pref keys `kusi_bolus_*` → `halo_bolus_*` (family-wide), string `kusi_bolus_again` → `halo_bolus_again`, and no sub-label anywhere.
6. Glass footer run 4 "90 %" (profile %) → remove (G3, not in the contract).
7. Glass TT colour #E8D28A → tt #B388FF.
8. Glass/Orbit loop thresholds (<6 / 6-14 / >=15) match; Halo HaloFaceLogic uses <=5 OK, which is the same boundary. Keep. Add loop.never/open/no_aps neutral handling to all three (none of the specs has it).
9. Orbit "age turns amber at over 5 min" → 6 min (same as `>5`, keep wording aligned with AGING 6-11).
10. All three: add the status slot (§4.1) and the hierarchy; today only Glass has an override line.
