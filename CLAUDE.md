# CLAUDE.md

AstroArena (the package id is still `io.github.projectwip`, so saves carry over): original mobile 3D arena brawler for Android (landscape, touch, bots). Kotlin + Compose
menus + custom OpenGL ES 3.0 renderer, no engine. All art and sound is generated in code and must stay
original: no Brawl Stars/Supercell assets, names, icons or UI copies. Fighters have first names only. Players see "Spark Drops"; the code still calls them capsules.

## Build

- Use the portable JDK (system Java 25 breaks Gradle 8.11):
  `export JAVA_HOME="/c/Users/gamer/AppData/Local/Android/tools/jdk-21.0.12.1+1"`
- `./gradlew testDebugUnitTest` (add `--tests '*Name*'` for one) · `./gradlew assembleDebug`.
  `println` from tests lands in `app/build/test-results/testDebugUnitTest/*.xml`.

## Device

- adb is at `/c/Users/gamer/AppData/Local/Android/Sdk/platform-tools/adb`; the tablet is on wireless debugging
  (`adb mdns services`, the port changes). Set `MSYS_NO_PATHCONV=1` for `adb shell`.
- Start a screen directly: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match`
  (`match|boss|fighters|shop|track|settings|result|leaders`, or `capsule0`..`capsule5` to preview a capsule opening, suffix `s` splits into eight, `f` gives a fighter, `b` a bundle). Save file: `adb shell run-as io.github.projectwip cat files/save.json`.
- UI changes must be checked with a screenshot (`adb exec-out screencap -p`) and `adb logcat -b crash -d`.
- The tablet is the user's everyday device. Before every `input tap` or `am start`, confirm
  `dumpsys window | grep mCurrentFocus` shows `io.github.projectwip`; otherwise skip it and say so.
- Phone-size check: `wm size 1080x2400 && wm density 420`, then always `wm size reset` / `wm density reset`.

## Rules of the codebase

- `sim/`, `ai/`, `data/` and `audio/SfxSynth.kt` are pure Kotlin (no Android imports); they run in JVM tests.
- Humans and bots drive fighters through the same `Control`; bot difficulty is behaviour only, never stats or
  vision. Visibility goes through `World.isVisibleTo`.
- Team code must not assume two teams when `rules.freeForAll`.
- All balance numbers live in `data/Balance.kt` and `data/Catalog.kt`; progression is pure functions in `Progression`.
- New save field: update both `toJson` and `fromJson` in `SaveStore`, with an `opt*` default.
- Changed a sound: bump `CACHE` in `audio/Sfx.kt`, or devices keep the old WAVs.
- Mesh triangle winding matters (outlines are inverted hulls). Sim (x, y) maps to world (x, 0, y).
- The lobby `TextureView` must be removed during matches.

## Git

- Checkpoint as you go: once a piece is verified (tests or device), commit just that piece and
  `git push origin main`. Never checkpoint unverified or non-compiling work.
- **Non-negotiable: every version is committed, tagged and released.** Whenever the version in
  `app/build.gradle.kts` changes, finish by committing, tagging `vX.Y.Z-preview`, pushing the tag and
  publishing a GitHub pre-release with the release APK attached (`AstroArena-X.Y.Z-preview.apk`), without
  waiting to be asked. The newest release must always be the newest version, so nobody downloads a stale APK.
  Smoke-test the release APK on the tablet when it is free, then put the debug build back.
- `gh` needs normal path conversion: don't run it with `MSYS_NO_PATHCONV=1` set.
- End commit messages with the co-author line used in history. `screenshots/` is gitignored scratch.
