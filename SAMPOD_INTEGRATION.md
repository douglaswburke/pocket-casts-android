# SamPod × Pocket Casts — integration plan (v0.4)

This fork of `Automattic/pocket-casts-android` is the SamPod v0.4 base (decision: Option A,
2026-07-25). The ad-skip is **server-side** (SamPod ingest server detects at queue-time and
serves a sidecar + cached audio), so the app-side work is a **thin layer**, not an engine
rewrite. Full design: the "SamPod — Design & v0.4 Plan" doc.

**Debug build works with no Pocket Casts secrets** (README: secrets only for signed release) —
so this compiles/runs out of the box before any SamPod wiring.

---

## What's already in the fork (self-contained, ⚠️ authored headless — compile-check tonight)

`app/src/main/java/au/com/shiftyjelly/pocketcasts/sampod/`
- **`Sidecar.kt`** — Moshi models for the server's skip-sidecar (`/sampod/sidecar/<id>`).
- **`AdSkipController.kt`** — pure skip-decision logic (confidence gate + per-ad re-fire
  guard) + the ±10s/±30s offset constants. No Android deps → unit-testable.
- **`SamPodApi.kt`** — OkHttp client: `fetchSidecar(id)` + `cachedAudioUrl(id)`.

These reuse Pocket Casts' existing deps (OkHttp + Moshi). If the `app` module doesn't get
them transitively, add okhttp + moshi to `app/build.gradle.kts` (they're used elsewhere in
the tree, e.g. `modules/services/servers`).

---

## The 5 wiring points (Doug + compiler, tonight)

### 1. BuildConfig — server URL + token
`app/build.gradle.kts`: read `sampod.server` / `sampod.token` from `local.properties`
(gitignored) into `BuildConfig` (same pattern as the custom MVP). Then:
`SamPodApi(BuildConfig.SAMPOD_SERVER, BuildConfig.SAMPOD_TOKEN)`.

### 2. Play the CACHED copy — the DAI-dissolver (one field, already supported)
Pocket Casts already has the exact hook: `BaseEpisode.overrideStreamUrl`
(`modules/services/model/.../entity/BaseEpisode.kt:152` → `get() = overrideStreamUrl ?: downloadUrl`).
When an episode has a SamPod sidecar, set:
```kotlin
episode.overrideStreamUrl = samPodApi.cachedAudioUrl(sampodId)
```
Now the player streams the server's analyzed bytes → sidecar timestamps match exactly. No
custom playback source needed.

### 3. Ad-skip hook — observe position, seek past ads
`PlaybackManager` (`modules/services/repositories/.../playback/PlaybackManager.kt`) exposes:
- `playbackStateFlow: Flow<PlaybackState>` (:224) — `PlaybackState.positionMs` (:16) + `.episodeUuid` (:18)
- `seekToTimeMs(positionMs: Int)` (:1069)

Wire a collector (in PlaybackManager, or a small SamPodSkipCoordinator it owns):
```kotlin
val controller = AdSkipController()
playbackStateFlow.collect { state ->
    val skips = currentSidecar?.skips ?: return@collect
    controller.check(state.positionMs, skips)?.let { d ->
        seekToTimeMs(d.seekToMs)      // jump to ad end
        // emit a "skipped <advertiser>" toast/notice for undo (design doc)
    }
}
```
Load `currentSidecar` when the episode becomes current (via `getCurrentEpisode()` (:359) →
`SamPodApi.fetchSidecar(id)`, off-main). Call `controller.reset()` on episode change.

### 4. Timeline ad-bars + ±10/30 buttons — the UI
`PlayerViewModel` (`modules/features/player/.../viewmodel/PlayerViewModel.kt`):
- Expose `controller.timelineWindows(skips)` → draw ad-bars on the seek bar (all windows;
  sub-confidence ones are manual-skip-only).
- Add intents for the four jump buttons using the offset constants:
  `seekToTimeMs((state.positionMs + AdSkipController.SKIP_FWD_30_MS).coerceIn(0, durationMs))`
  (and −10s / +10s / −30s). "Skip to next content" = seek to the current/next ad's `endMs`.

### 5. Sidecar id ↔ episode mapping
Simplest: when Doug queues an episode into SamPod, the **server returns the id** (sha1 of
the enclosure URL). Store it on/next to the episode (a tiny Room table `sampod_episode(uuid,
sampod_id)` or a settings map). Then steps 2–4 look it up by episode uuid. (A later nicety:
have the server resolve id from the enclosure URL so no store is needed.)

---

## AntennaPod best-of to fold in (design doc §Feature superset)
OPML import/export, no-account local-first (Pocket Casts leans account — keep its local mode),
flexible per-feed auto-download rules. Most already exist in Pocket Casts; OPML + the SamPod
queue integration are the net-new bits.

## Build order
1 (BuildConfig) → 2 (overrideStreamUrl) → 3 (skip hook) → 4 (UI bars + buttons) → 5 (id map)
→ then OPML/sharing/polish. Get 2+3 working first = "ads auto-skip on the cached copy" = the
core win; 4 is the visible polish.

## Non-goals (v0.4)
Multi-user/crowdsource ad-DB (P6), differential-download DAI detection (product scale),
on-device real-time detection.
