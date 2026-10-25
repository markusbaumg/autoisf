# Kusi → AAPS 4 migration

**Status: backlog (owner decision 25 Oct 2026).** No migration branch yet. The move from 3.4.2.6 +
AutoISF 3.2.1 to AAPS 4 starts only when AutoISF has a 4.x line (`make upstream-status`). Until then
these notes keep what we know, and new kusi work follows the rules below so the move stays cheap.

Background, same folder:
- `AUDIT_2026-10-25.md`: every kusi change (192 commits on base `a14b8c7663`, 3.4.2.6+aisf3.2.1),
  additive vs core edit, dosing relevance, AAPS 4 effort, strategy and open decisions.
- `UPSTREAM_AAPS4_2026-10-25.md`: state of AAPS 4 (nightscout dev / 4.0.0-rc1) and AutoISF at that date.

## Gate

| Condition | State (25 Oct 2026) |
|---|---|
| AAPS 4.0 final released (nightscout) | no: 4.0.0-beta1 (8 Oct), 4.0.0-rc1 (16 Oct) |
| AutoISF 3.2.x on 4.x (T-o-b-i-a-s) | no 4.x branch. AAPS 4 core ships AutoISF 3.0.1, engineering mode only: a downgrade for us |
| Second test device / emulator for the first 4.x build | open |
| Watch5 Pro Wear OS version checked | open |

Do not install anything 4.x on the loop phone before all four are met. The 3.4 → 4 DB migration is one-way:
export settings and keep the 3.4.2.6 APKs first.

## Rules (apply now, on 3.4.2.6)

1. New kusi behaviour lives in new files, preferably under `kusi/` modules. A core file only gets a hook:
   one call, one enum entry, one DI line, one manifest entry, marked `// KUSI: <topic>` (XML
   `<!-- KUSI: <topic> -->`).
2. Every core touch has a row in `KUSI_PATCHES.md` (file, symbols, kind, why, guarding test, AAPS 4 action).
   No row, no merge.
3. Hand-ports from upstream are listed in `KUSI_BACKPORTS.md` and are **dropped**, not ported, on 4.
4. Dosing, limit and loop-timing patches are each one small commit, reviewed for loop behaviour, never
   bundled with refactors.
5. No more in-place rewrites of stock classes, deleted stock resources or commented-out stock code; no
   fields added in the middle of the phone↔watch contract (`EventData`). New watch messages go through
   one kusi envelope message added at the end.
6. Master stays a linear patch stack on the upstream base: no merge commits with hand-resolved conflicts.

## Preparation steps, any time before the gate opens (no change to the installed app)

1. ✅ This plan and `make upstream-status` (25 Oct 2026). When the gate opens: create the migration branch.
2. Ledger `KUSI_PATCHES.md` and `KUSI_BACKPORTS.md` (written from the audit, verified against the diff).
3. Restack: rebuild the history from `a14b8c7663` as topic commits (build/tooling → kusi additions →
   hooks → backports → dosing patches last). Acceptance: `git diff aa16883b84 <tip>` is empty. The old
   history stays as tag `kusi-3.4.2.6-history`.
4. Move signing out of `app/` and `wear/build.gradle.kts` into the Makefile (`android.injected.signing.*`).
   Acceptance: `make verify` shows the same cert `6ff302f5…cd4b166b`.
5. Extract the watch faces into `:kusi:wear-faces`, then the widget (own provider) into `:kusi:widget`,
   then loop watchdog / log definer into `:kusi:phone`. Each step: tests green, then a normal quiet-moment
   install of the matched pair.

## Steps when the gate opens

1. Mirror the AutoISF 4.x branch to `kusi/base-4.x`; build it stock with our cert, run it on the test device.
2. Walk the `drop` rows of the ledger: confirm dev covers each one (code + our test scenario). Gaps become
   small re-apply rows.
3. Port the kusi modules (Dagger → Metro, RemoteViews widget → Glance where needed), tests green.
4. Re-add the hooks, one commit each.
5. Dosing/limit patches last, one commit each, line-by-line compare with 3.4, loop-behaviour review.
6. versionCode ≥ installed, same cert, matched-pair install at a quiet moment, watch loop runs and IOB.

## Open owner decisions

- AutoISF base on 4: wait for T-o-b-i-a-s 4.x (recommended), 3.0.1 from core, or own port.
- Keep SMB-minutes max 600 and the AutoISF div-by-zero fallbacks?
- Restack approval; upstream issues (AI-written PRs are not accepted upstream).
- Kusi widget as its own provider (additive) instead of replacing the stock widget.
