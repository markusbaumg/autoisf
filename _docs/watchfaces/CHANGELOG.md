# Changelog: integration/kusi-watchfaces-r2 vs origin/feature/kusi/3.4.2.6

No changes to dosing math, limits or constraints. Phone and watch APKs must be installed as a matched pair
(EventData.Status gained `lastLoopRunAt`; the watch decodes JSON strictly).

## New watch faces (wear)
- **AAPS Halo** (formerly "AAPS(Kusi)"): ring face with BG ring, trend knob, delta and age, loop pill and status line.
  Final size table "M", picked by the UX panel (variants S+/M/L- tried, oversized round rejected).
- **AAPS Glass**: frosted glass card, aurora tinted by BG range, trend chip, sparkline, loop/IOB/carb pills.
- **AAPS Orbit**: central BG with satellites (LOOP, IOB, KH, U/h, TT, bolus) on an orbit track.
- Shared family design system (`common/FaceState`): BG age classes, range incl. urgent low / very high,
  loop health from the real loop run time, phone link, warning order W1..W10, TIEF/HOCH captions,
  placeholders, German unit spacing, burn-in shift. Ambient and low-bit variants for all three faces.
- UX panel rounds (design + usability) on Glass and Orbit: larger hero digits, readable labels/pills
  (22 px physical floor), red low digits with readable contrast, TIEF/HOCH placement clear of the delta,
  stale and "---" handling, time/date below the system status icon, quieter ambient. Halo kept at size M.
- Glass/Orbit UX rounds 1-6 (after the polish rework): Orbit satellites quieter (dim labels, 24 px values), alarm
  rings replaced by value tint + status dot (red keeps a thin ring), smaller chart with a 55-240 mg/dL minimum scale,
  hero weight 600; Glass trend chip with a dark base and double arrows as two parallel arrows, larger bolus ball
  clear of the chart, very-stale digits dimmed, no static card outline under burn-in protection (except lows),
  sparkline history dimmed with the last 15 min at full strength.
- Orbit "no profile" shows the dim "--" skeleton (IOB/KH/BASAL "--", grey dashed LOOP) like Halo and Glass,
  instead of hiding the satellites next to a green loop age.
- German strings moved to `values-de` so de-AT and de-CH get German too.
- LoopState tile is rebuilt whenever the loop-state list differs.

## Optional double-tap bolus request (watch faces, default OFF)
- One shared `BolusRequestGuard` for all three faces: 800 ms double-tap window, 5 s cooldown, 30 s pending after
  a send (persisted), max bolus from the synced safety setting (unknown => refuse), fresh phone reachability
  required, stale BG/phone data refuses, never in ambient or preview, hidden before first data.
- One preference pair for all faces (`face_bolus_request_enabled`, `face_bolus_request_amount`, default 0.5 U),
  with one-time migration of the old per-face keys.
- The request still goes through the normal phone-side precheck and confirm flow.

## Phone-side safety checks for watch treatments (only refusals added)
- "Controls from Watch" is enforced on the phone for bolus, wizard, quick wizard, eCarbs, fill, profile switch,
  temp target and user actions (prechecks and confirms).
- A delivering confirm must match the last request the phone offered, within 60 s (monotonic clock), single use,
  bound by a per-request `requestId`; wizard/quick-wizard delivery uses the snapshot taken at precheck;
  duplicate confirms within 10 s are refused silently.

## Phone: battery and logging
- PersistentNotification: rebuilds throttled to one per 2 s burst, only on the three preferences the text uses;
  the update stream survives exceptions.
- NSClient: sync pointer written per uploaded record (every 50 skipped otherwise), pointers only move forward,
  overlapping upload walks serialized, Full sync not overwritten by a stale flush.
- Logs: Nightscout/Drive tokens and refreshed JWT masked; AutoISF verbose traces behind a new AUTOISF log tag
  (off by default); IOB array logged as size + current point; websocket documents logged once per event;
  APSResult inserts/updates logged by id and timestamp.

## Tests and docs
- Unit tests for all face logic, the shared guard, prefs migration, confirm binding, sync pointers and the
  notification throttle; an offline Robolectric render harness for the faces (tests only).
- `_docs/watchfaces`: design record, state catalogue, per-face specs, state matrices and gallery.

## Build
- Tip `3405233382` (integration/kusi-watchfaces-r2). Release APKs signed with cert SHA-256
  6ff302f5638b25502a0434554b64099ff735380d914a279857dfd2bbcd4b166b (apksigner 36.0.0).
- app-full-release.apk sha256 4484d059e8968dca89fb2796e5c34329bd4827d86cf2865d2f586915d7af509c
- wear-full-release.apk sha256 3871db09f8ab5aa2e9c42f991c604e8660409f172667bc59808b56ed9e7fc667

## 2026-10-17 – polish round (branch feature/kusi/watchface-polish, 08d4637cd7)

- **Orbit** redesigned: ring close to the bezel, status discs with depth (LOOP, IOB, KH, U/h, TT), delta and age back,
  loop stopped = solid red disc with ⚠ plus "⚠ Loop 23'" word, armed ball "+0,5 nochmal" (no "2×"). Panel 8.5 / 8.6 / 8.5.
- **Glass**: smaller BG with room in its card, new sparkline (target band, clear now dot), low values on a fixed
  40 .. low+50 window so 3,5 / 2,6 / 2,2 stay apart even after a meal peak. Panel 7.4 → fixed / 8.5 / 8.6.
- **Confirm screen** (watch bolus/carbs/wizard): big amount, effect ≈ BG drop (dose × profile ISF), IOB now → after,
  carbs covered (dose × IC), timing, warnings in words (show only, slider unchanged). Panel 8.6 ×3. Needs phone+watch
  as a matched pair (EventData ConfirmAction.details).
- Settings screen: checkboxes visible again (the bolus-request toggle was invisible).
- Battery: the watch no longer asks all 16 complications to update on every reading (was 32 broadcasts/min).
- Safety: confirm details read the profile ISF without running AutoISF (no loop-input side effect); trend only for a
  current reading. Renders: `renders/polish-2026-10-17/`.

- Glass round 8 (4aab6ee178, built, watch-only update pending): sparkline never clamps the current reading, recent 30 min or forecast; older off-scale history shown as faint "^"; "<2,2" low sentinel; even card padding. Panel 8.5 x3. Plus: watch resend requests debounced (3 s), cancelled Glass bake not logged as error.
- Live screenshots after the 12:36 install in screenshots/ (orbit_live_1239, glass_live_1239, halo_live_1240, settings_bolus_checkbox_1239).
