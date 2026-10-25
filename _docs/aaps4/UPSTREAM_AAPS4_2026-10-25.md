# AAPS 4 readiness research for the kusi build (read-only)

The repo was not changed. Only `git log/show/diff/ls-tree` with explicit refs, `gh` and web lookups were used. One scratch file list was written to `/private/tmp/claude-501/-Users-markus-dev-autoisf/06075335-24b5-436d-9c0f-d8e1e19949ba/scratchpad/`.

## 1. Base used for the kusi branch

| Candidate upstream branch (T-o-b-i-a-s) | merge-base with `feature/kusi/3.4.2.6` (aa16883b84) | kusi commits after base | upstream commits not merged |
|---|---|---|---|
| `3.4.0.0+aisf3.2.0` (4ccf50db80) | 4ccf50db80 (tip, 2026-02-04) | 378 | 0 |
| `3.4.1.0+aisf3.2.0` (6057ff016f) | 0f35b6fc2d | 306 | 2 |
| `3.4.2.1+aisf3.2.0` (bc0cce207e) | abdedc1e6c | 280 | 1 |
| **`3.4.2.6+aisf3.2.1` (91e9e71a94)** | **a14b8c7663** (2026-08-10, "Fix Quick Guide link in README.md") | **192** | 3 |

- **The base I used is `a14b8c7663`, on upstream `3.4.2.6+aisf3.2.1`.** It is the closest merge-base.
- The 3 upstream commits that are missing are only merges plus "updated AutoISF version string for its tab" (c5df8a6d1a, d3519d59b2, 91e9e71a94). Nothing functional is missing.
- T-o-b-i-a-s has **no 4.x branch**. Its newest branch is `3.4.2.6+aisf3.2.1`, last pushed 2026-10-04.

### How much kusi changes the core (a14b8c7663 → aa16883b84)

- 396 files: **280 added**, **115 modified**, 1 deleted (`wear/.../fragment_wizard_confirm.xml`).
  - The added files are mostly `_docs/watchfaces/*` (≈150), new watch faces, tests and helpers.
  - Line counts: +40 599 / −1 320.
- Modified core files per area: wear 23, ui 11 (widget), core/interfaces 11, plugins/sync 10, app 9, implementation 8, plugins/main 7, plugins/aps 7, workflow 5, omnipod 3, automation 3, database 3+2, core/keys 3, smoothing 2.
- The largest edits inside core files are these:
  - `ui/widget/Widget.kt` (+627/−213) and `widget_layout.xml` (+609/−246)
  - `sync/wear/wearintegration/DataHandlerMobile.kt` (+424/−83)
  - `nsclientV3/DataSyncSelectorV3.kt` (+321/−126)
  - `workflow/CalculationWorkflowImpl.kt` (+300/−90)
  - `IobCobCalculatorPlugin.kt` (+137/−86)
  - `rx/weardata/EventData.kt` (+116/−6)
  - `WearPlugin.kt`, `AutomationPlugin.kt`, `PersistentNotificationPlugin.kt`, `XdripPlugin.kt`, `UnscentedKalmanFilterPlugin.kt`
  - `DetermineBasalAutoISF.kt` (+40/−40) and `OpenAPSAutoISFPlugin.kt` (+26/−13): these touch dosing code.
- **These edits will not carry over to AAPS 4 as they are.** I matched each modified `.kt` file to its path in `v4.0.0-rc1`:
  - Almost every file moved from `src/main` to `src/commonMain` or `src/androidMain`, and was heavily rewritten.
  - Examples of base → rc1 churn (lines): `DataHandlerMobile` 2342, `PersistenceLayerImpl` 4034, `UnscentedKalmanFilterPlugin` 1471, `LoopPlugin` 1156, `OpenAPSAutoISFPlugin` 841, `AcceptActivity` 803, `DataSyncSelectorV3` 615, `IobCobCalculatorPlugin` 543, `PersistentNotificationPlugin` 495 (moved to `app/src/main/kotlin/app/aaps/persistentNotification/`), `KeepAliveWorker` 180 (moved to `implementation/androidMain`), `ImportExportPrefsImpl` 1341 (moved to `implementation`).
  - These files no longer exist in rc1: `MainActivity.kt` (replaced by `ComposeMainActivity`), `MyPreferenceFragment.kt`, `Notification.kt`, `CompatDBHelper.kt`, `NetworkStackBuilder.kt`, `StepService.kt`.

## 2. State of AAPS 4 (nightscout/AndroidAPS)

- Tags and releases:
  - `4.0.0-beta1`: 2026-10-08, 28a341e4cc.
  - `4.0.0-rc1`: 2026-10-16, d5e7f5bdbb, versionCode 1500. Pre-release: https://github.com/nightscout/AndroidAPS/releases/tag/4.0.0-rc1
  - `master` is still 3.4.2.6, released 2026-08-02. `dev` reports `4.0.0-dev-e` and was 83 commits past rc1 on 2026-10-23 (553586f918).
- **Release date:** I found no announced 4.0 final date. There is no GitHub milestone, and the wiki release notes stop at 3.4.2.6: https://wiki.aaps.app/en/latest/Maintenance/ReleaseNotes.html. Moving from beta1 to rc1 in 8 days suggests a final release is close, but that is my inference.
- Platform (from `buildSrc/.../Versions.kt` at rc1):
  - Phone: minSdk 31, targetSdk 35, compileSdk 37.
  - Wear: minSdk and targetSdk 30.
  - Libraries: Kotlin 2.4.21, Metro 1.4.5, Compose 1.12.1, Room 2.8.5.
- Main points of the rc1 release notes:
  - The phone UI was rebuilt in **Jetpack Compose**.
  - **Kotlin Multiplatform**, with clients for desktop (Windows, macOS, Linux) and iOS. There are new top-level `desktop/`, `ios/`, `appshell/` and `shared/{clientbindings,impl,tests}` modules.
  - The **watch app was rebuilt in Compose**, with new tiles and complications, and **Watch Face Push** on Wear OS 6+.
  - **New widgets**: Glance-based `ui/src/androidMain/.../widget/glance/*`, plus `CompactBgWidget`, `BgGraphWidget`, `GlucoseCircleWidget` and `SmallWidget`. The old `Widget.kt` still exists.
  - Scenes, master–client control, insulin concentration and catalogue, site rotation, a calibration plugin, **UKF smoothing**, Omnipod Dash PDM ref codes and drift compensation, and a persistent notification that uses Android live updates and the Samsung Now Bar.
  - The UKF smoothing and Dash changes overlap with kusi backports.
- **DI:** Dagger is gone from `app/`, `plugins/` and `wear/` (0 files import dagger). It is replaced by **Metro**, which uses graphs such as `AppRootGraph`, `MetroGraphs`, `HistoryWindowGraph` and `WearGraph`.
- **Events:** RxBus is still there (375 `.kt` files use it), alongside many StateFlow/SharedFlow interfaces in `core/interfaces`. The move is to flows step by step, not all at once.
- **Wear contract:** `core/interfaces/src/commonMain/.../rx/weardata/EventData.kt` is still a `@Serializable sealed class`, and it gained new messages (e.g. `WatchFacePushStatus`). Two consequences:
  - A 3.x phone app and a 4.x watch app cannot be mixed, so the matched-pair rule still holds.
  - Adding a new watch message still means editing that core sealed class. There is no extension point for it.
- **AutoISF is in AAPS 4 core, but at version 3.0.1.**
  - Code: `plugins/aps/src/commonMain/.../openAPSAutoISF/OpenAPSAutoISFPlugin.kt` sets `autoIsfVersion = "3.0.1"`.
  - Line 125 forces it off unless the build is in engineering mode and is a dev build: `.enforce(EnforcedState.Disabled) { !(config.isEngineeringMode() && config.isDev()) }`.
  - Our base runs 3.2.0/3.2.1, so AAPS 4 would mean a **downgrade of the dosing algorithm** unless AutoISF 3.2.1 is ported again.
- Contribution policy (`CONTRIBUTING.md` at rc1):
  - **"AI-generated pull requests are not welcome."** AI findings should go into an issue instead.
  - Upstream PRs should branch from the latest `dev` and be rebased onto `dev`.
  - So we can propose extension points through issues, but we should not send AI-written PRs.

## 3. Extension points in AAPS 4 for additive work

- **Plugins: these can be added without touching core.**
  - A plugin class can register itself into the plugin map with Metro annotations. Example at rc1, `pump/virtual/.../VirtualPumpPlugin.kt:81`:
    ```kotlin
    @ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
    @IntKey(1000)
    @SingleIn(AppScope::class)
    @Inject
    open class VirtualPumpPlugin(...)
    ```
  - `PersistentNotificationPlugin` registers itself the same way with `@IntKey(0)`. About 164 files use `@ContributesIntoMap`.
  - So a new `plugins/kusi` (or `kusi/*`) Gradle module can add plugins, workers, receivers and bindings through `@ContributesTo(AppScope::class)` and `@BindingContainer`. Only `settings.gradle` and the `app` dependency list need a one-line edit.
- **An APS (AutoISF 3.2.1):**
  - It can be its own plugin with its own `@IntKey`, built like `OpenAPSAutoISFPlugin` in `plugins/aps/commonMain`.
  - It must still go through Objectives, constraints and the `APS` interface. The engineering-mode gate is in the upstream plugin, so a separate plugin class avoids patching it.
- **Widgets:**
  - Each Glance widget is its own class plus a manifest receiver, and `core/interfaces/.../widget/WidgetUpdater.kt` is the update hook.
  - So the kusi widget can become **a separate widget class and receiver in its own module**, instead of a rewrite of `Widget.kt` and `widget_layout.xml`.
- **Watch faces and wear:**
  - Custom watch faces (CWF zip, documented in `_docs/CWF_*.md`) and Watch Face Push let faces ship as data, not code.
  - Extra watch messages still need either a change to `EventData` (core) or a separate DataLayer path in our own phone and wear module. The second option is additive, but it duplicates the transport code.
- **Things with no extension point:** changes inside `CalculationWorkflowImpl`, `IobCobCalculatorPlugin`, `DataSyncSelectorV3`, `PersistenceLayer`/DAOs and `DataHandlerMobile`. These must stay as small, isolated patches, or be dropped if 4.0 already fixes the problem.

## 4. AutoISF and AAPS 4

- **T-o-b-i-a-s/AndroidAPS:** no 4.x branch and no releases. Branches go up to `3.4.2.6+aisf3.2.1`. Repo: https://github.com/T-o-b-i-a-s/AndroidAPS
- **ga-zelle/autoISF** (documentation and the AutoISF reference): branches end at `A3.4.2.6_ai3.2.1`, with no AAPS 4 branch. Repo: https://github.com/ga-zelle/autoISF
- **RSC-RJCgit/AndroidAPS-v4-autoisf321**, described as "AndroidAPS 4.0.0-rc1 (KMP) with AutoISF 3.2.1 by ga-zelle, ported to v4":
  - Created 2026-10-18, last pushed 2026-10-20, 0 stars, commit messages like "firstgroup019next".
  - It is unofficial and unreviewed. It is only useful for comparing how someone else did the port, not as a base. Repo: https://github.com/RSC-RJCgit/AndroidAPS-v4-autoisf321
- AutoISF itself has been in AAPS since 3.3, but only in dev/engineering mode (stated in the README at 91e9e71a94). That stays true in 4 (§2).
- **Expect a `4.0.x+aisf3.2.x` branch from T-o-b-i-a-s some time after 4.0 final.** That is an inference; I found no announcement.

## 5. How forks usually stay close to upstream

- The upstream docs only say: branch from `dev`, rebase onto `dev`, and uncheck "copy master branch only" when forking (`CONTRIBUTING.md`).
- In practice, the AutoISF fork itself merges each AAPS release into a new `<aaps>+aisf<x>` branch (e.g. `3.4.2.6+aisf3.2.1`, made by merging `342x-master/GNTM3426dev_ai321`). That means one branch per upstream release.
- Recommended for kusi, so the move to 4 stays cheap:
  1. **A patch stack, not a merged history.** Keep kusi as a short, ordered series of topic commits on top of the AutoISF base. On each upstream release, rebase the series, using `git rerere` to replay resolved conflicts and `git range-diff` to review what changed. The 192 commits should first be squashed into about 10–20 themed commits: widget, watch faces, wear bolus safety, sync, wakelock, backports and so on.
  2. **New code goes in its own modules:**
     - `kusi/widget`, `kusi/wear-faces` and `kusi/plugins`, with Metro `@ContributesIntoMap` and `@ContributesTo`.
     - Kusi-only strings, resources and manifest entries in those modules. The kusi edits to core `strings.xml` and the ui/wear manifests move there.
  3. **Small hooks for core edits that cannot be avoided.** Add one interface in `core/interfaces` (or use an existing flow), call it from core in one line, and implement it in the kusi module. Tag every core edit with a `// KUSI:` marker, so `git grep KUSI` shows the whole core footprint.
  4. **A backport ledger.** For every upstream fix ported by hand (UKF, DST/Dash, NSClient sync, keep-alive), record the upstream commit. At the move to 4.0 those commits are dropped, not ported again; many are already in rc1.
  5. **Dosing code stays separate.** The `DetermineBasalAutoISF` and `OpenAPSAutoISFPlugin` changes should be one or two clearly reviewed commits, so they can be compared line by line with the 3.2.1 port onto 4.
  6. **Ask upstream for extension points through an issue, not a PR,** for example a pluggable wear message type or widget data provider. That follows the no-AI-PR rule.
- Suggested order:
  1. Wait for the 4.0 final and for T-o-b-i-a-s `4.0.x+aisf3.2.x`.
  2. Rebuild the kusi modules on that base. The widget becomes a Glance widget in its own module.
  3. Port the remaining core hooks one at a time and review loop behaviour for each.
  4. Install phone and watch as a matched pair.

## Sources

- https://github.com/nightscout/AndroidAPS/releases/tag/4.0.0-rc1 (rc1 notes; also tag 4.0.0-beta1, 2026-10-08)
- nightscout/AndroidAPS refs read locally: `v4.0.0-rc1` d5e7f5bdbb, `dev` 553586f918, `master` aa16db6419 (still 3.4.2.6); files `CONTRIBUTING.md`, `CLAUDE.md`, `buildSrc/src/main/kotlin/Versions.kt`, `app/src/main/kotlin/app/aaps/di/metro/MainPluginsBindings.kt`, `pump/virtual/src/commonMain/.../VirtualPumpPlugin.kt`, `plugins/aps/src/commonMain/.../openAPSAutoISF/OpenAPSAutoISFPlugin.kt`, `core/interfaces/src/commonMain/.../rx/weardata/EventData.kt`, `core/interfaces/src/commonMain/.../widget/WidgetUpdater.kt`
- https://wiki.aaps.app/en/latest/Maintenance/ReleaseNotes.html (no 4.0 entry yet)
- https://aaps.readthedocs.io/en/latest/EN/Installing-AndroidAPS/Releasenotes.html (search result, older notes only)
- https://github.com/T-o-b-i-a-s/AndroidAPS (branches; README at 91e9e71a94)
- https://github.com/ga-zelle/autoISF (branches up to A3.4.2.6_ai3.2.1)
- https://github.com/RSC-RJCgit/AndroidAPS-v4-autoisf321 (unofficial v4 port)
- https://www.diabettech.com/automating-isf-in-open-source-aid-systems-experiments-with-androidaps/ (background only)