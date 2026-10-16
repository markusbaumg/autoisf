# Kusi AndroidAPS + AutoISF build

Personal build of AndroidAPS 3.4.2.6 + AutoISF 3.2.1 ("kusi" adaptations), running a closed loop
on the owner's phone (Samsung Galaxy S24) and Galaxy Watch5 Pro. Insulin is dosed from this code.

## Branches

- **`feature/kusi/3.4.2.6` is the master branch of this version.** What is installed on the phone
  and watch must be on it. Start new work from it, and merge back into it (fast-forward preferred).
- Work branches (e.g. `kusi/3.4.2.6-backports`) branch off it and are fast-forwarded into it.
- `3.4.0.0+aisf3.2.0` and the other `3.x+aisf*` branches mirror the AutoISF fork
  (remote `upstream` = T-o-b-i-a-s/AndroidAPS). Don't build releases from them.
- Upstream AndroidAPS fixes come from `nightscout/AndroidAPS` `dev` (heavily refactored: KMP,
  Metro, flows). Port the intent by hand; cherry-picks rarely apply.

## Safety rules

- Never change dosing math, SMB/AutoISF logic or limits as a side effect. Changes to loop inputs
  or timing need a review of loop behaviour before they ship.
- Run the affected module unit tests before building. 3 `TriggerStepsCountTest` failures in
  `:plugins:automation` are pre-existing.
- Phone and watch apps share the weardata contract: install them as a matched pair.

## Build and install

- `make build` builds signed `app` + `wear` full-release APKs into `dist/` (keystore from
  `~/dev/androidkey/keystore.properties`). JDK 25 works.
- `make verify` must show cert SHA-256 `6ff302f5…cd4b166b` for both APKs, otherwise an in-place
  update of the installed app is impossible (an uninstall would wipe settings, DB and pod pairing).
- `make phone` (USB), `make watch WATCH=<ip:port>` (wireless debugging; the port changes).
- Install only at a quiet moment: BG flowing, no bolus running. Check loop runs and IOB afterwards.
- AAPS logs on the phone: `/sdcard/Documents/aapsLogs` (hourly zips, 7 days / 500 MB cap).

## Don'ts

- Don't reset the AAPS database with the loop closed (the first runs see IOB 0).
- Don't uninstall Juggluco (wipes the Libre sensor pairing); update it in place from juggluco.nl.
- Don't push or install without the owner's go-ahead.
