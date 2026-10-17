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
- Four UX panel rounds (design + usability) on Glass and Orbit: larger hero digits, readable labels/pills
  (22 px physical floor), red low digits with readable contrast, TIEF/HOCH placement clear of the delta,
  stale and "---" handling, time/date below the system status icon, quieter ambient. Halo kept at size M.
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
