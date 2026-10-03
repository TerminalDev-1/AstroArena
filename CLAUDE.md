# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Project WIP-Preview: an original, mobile-first 3D arena brawler for Android (landscape, touch, bots as a
first-class way to play). Preview software. Kotlin + Jetpack Compose (menus) + a custom OpenGL ES 3.0
renderer — no game engine. All art/sound is generated in code; keep it original (no Brawl Stars/Supercell
assets, names, icons or UI copies).

## Toolchain & commands

- JDK **17 or 21** (Gradle 8.11 cannot run on Java 24+). On the author's machine the system Java is 25, so use
  the portable JDK: `export JAVA_HOME="/c/Users/gamer/AppData/Local/Android/tools/jdk-21.0.12.1+1"`.
- `local.properties` (gitignored) must contain `sdk.dir=...`; SDK needs `platforms;android-35`, `build-tools;35.0.0`.
- Pinned: Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01 (`gradle/libs.versions.toml`).

```bash
./gradlew assembleDebug                      # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug
./gradlew testDebugUnitTest                  # JVM tests (sim, AI, progression)
./gradlew testDebugUnitTest --tests '*freeForAllEndsWithUniquePlacements*'   # single test
./gradlew testDebugUnitTest --tests '*BalanceReport*'   # prints per-fighter stats & match lengths (not assertions)
```

Test output printed with `println` lands in `app/build/test-results/testDebugUnitTest/*.xml`.

### Device testing

- Test device is usually a tablet on **wireless debugging**: `adb mdns services` then `adb connect <ip:port>`
  (port changes; if both an IP and an mDNS entry show up, pass `-s <ip:port>`). On HyperOS, installs may show
  an on-device "Install via USB" prompt the user must accept.
- On Windows Git Bash set `MSYS_NO_PATHCONV=1` before `adb shell` commands.
- Debug builds accept a start screen: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match`
  (`match|fighters|shop|track|settings`).
- Inspect the save: `adb shell run-as io.github.projectwip cat files/save.json`.
- Phone-size check on a tablet: `adb shell wm size 1080x2400 && adb shell wm density 420`, then **always**
  `wm size reset` / `wm density reset`.
- Verify UI changes with `adb exec-out screencap -p > file.png` and `adb logcat -b crash -d`; compiling is not enough.
- The tablet is also the user's everyday device. Never send `adb shell input ...` blind: check
  `dumpsys window | grep mCurrentFocus` shows `io.github.projectwip` immediately before every tap, and skip it otherwise.

## Architecture (big picture)

Package root: `app/src/main/java/io/github/projectwip/`.

**Pure-Kotlin core (no Android imports — keep it that way; it runs in JVM tests and is the basis for a future
authoritative server):**
- `sim/` — `World` is the fixed-step (60 Hz, `Match.STEP`) authoritative simulation; `Arena` is a tile grid
  (`FLOOR/WALL/THICKET/WATER/CRATE`) built from an ASCII **quadrant mirrored both ways** (`Arenas`); `Match`
  assembles fighters + bot brains for a `MatchConfig` and produces a `MatchReport`.
- Modes via `MatchRules`: team mode (Knockout Rush: 2 teams, respawns, vertical map, player's team at the
  **bottom**) vs free-for-all (Last Spark: each fighter its own team, one life, placements, `Storm`, crates →
  Power Cell `Pickup`s). Team code must not assume exactly two teams when `rules.freeForAll`.
- Humans and bots drive fighters through the **same `Control` struct**; never give bots extra stats/vision.
  Visibility (thickets) goes through `World.isVisibleTo`. The sim emits `GameEvent`s that renderer/audio/HUD consume.
- `ai/` — `BotBrain` layers: `think()` (target, intent incl. `SEEK_ZONE`, goal, A* path via `Pathfinder`) →
  `steer()` → `dodge()` → `combat()`. Difficulty = `BotProfile` behaviour knobs only, never health/damage
  (a test asserts Elite beats Easy with identical stats).
- `audio/SfxSynth.kt` — all sound design as pure-Kotlin synthesis (also runs in JVM tests). `Sfx` caches the rendered
  WAVs; bump its `CACHE` name whenever a sound changes or devices keep playing the old files.
- `data/` — **all balance numbers** in `Balance.kt` (linear `StatLine(base, perLevel)` stats so upgrades show
  exact deltas), Cup Track & shop catalog in `Catalog.kt`, `Progression` = pure functions `(SaveData) -> SaveData`.
  `GameRepository` (StateFlow) applies them and persists via `SaveStore` (JSON + `AtomicFile`). When adding a
  save field, update both `toJson` and `fromJson` (use `opt*` defaults so old saves load).

**Android side:**
- `gl/` — `MeshBuilder` (procedural primitives; triangle winding matters because outlines use inverted hulls
  with front-face culling), `Shaders` (one toon "lit" shader with `uMode`: 0 lit, 1 flat/outline/decal,
  2 silhouette, 3 unlit vertex colour), `GlThread`/`Egl` (own EGL context per surface, 4× MSAA).
- `render3d/` — `MatchRenderer` (shadow pass → env → x-ray ally silhouettes → fighters → outlines → crates,
  cells, bushes, water, storm, decals → additive sprites; then publishes a `HudSnapshot`), `FighterModels`
  (rigged fighters; meshes are white, colour comes from skin slots per draw), `ArenaModel` (static scene;
  crates are drawn dynamically), `Lobby` (persistent 3D menu backdrop), `Stage`/`Portraits` (offscreen
  portrait bitmaps), `Toon` (shared light/shadow setup). Sim (x, y) maps to world (x, 0, y).
- `match/` — `MatchView` = `SurfaceView` (GL thread steps the sim via `MatchRunner` and renders) + `HudView`
  (2D Canvas overlay on the UI thread, fed by double-buffered `HudChannel`, also receives touch). Touch →
  `TouchControls` (synchronized) → `MatchRunner` maps it to the player's `Control`, does auto-aim
  (`World.nearestVisibleEnemy` + `World.leadAim`) and aim assist.
- `ui/` — Compose menus with a custom design system (no Material): `ChunkyButton`, `Panel`, `GameText`,
  vector `GameIcon`s. `App.kt` holds navigation (`Screen`), UI scaling (density scaled by screen height;
  `UiMetrics.roomy/wide` add content on tablets) and the persistent `LobbyView` behind every non-match screen.
  Screens control the lobby with `LobbyShotEffect(...)` and place the 3D fighter with `Modifier.lobbyAnchor()`.
  The lobby `TextureView` must be removed during matches (it would cover the match `SurfaceView`).

See `README.md` (features, build, where to change rules) and `docs/ARCHITECTURE.md` (deeper rationale).

## Conventions

- Commit as the repo-local git identity (already configured); end commit messages with the co-author line used
  in history. Remote: `https://github.com/TerminalDev-1/ProjectWIP-Preview` (public); releases are tagged
  `vX.Y.Z-preview`. Ask before tagging a release unless the user asked for it.
- **Checkpoint to GitHub while you work.** As soon as a piece of code or logic is verified (tests pass, or it was
  checked on the device), commit just that piece and `git push origin main`, so there is always a known-good
  version to fall back to. One checkpoint per verified piece — don't batch a whole session into one commit, and
  never checkpoint something unverified or a build that doesn't compile. Checkpoint pushes need no extra
  confirmation; tagging a release still does.
- `screenshots/` is gitignored scratch space for device captures.
