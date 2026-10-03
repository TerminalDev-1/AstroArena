# CLAUDE.md

AstroArena (the package id is still `io.github.projectwip`, so saves carry over): original mobile 3D arena brawler for Android (landscape, touch, bots), with an optional Python game server. Kotlin + Compose
menus + custom OpenGL ES 3.0 renderer, no engine. All art and sound is generated in code and must stay
original: no Brawl Stars/Supercell assets, names, icons or UI copies. Fighters have first names only. Players see "Spark Drops"; the code still calls them capsules.

## Build

- Use the portable JDK (system Java 25 breaks Gradle 8.11):
  `export JAVA_HOME="/c/Users/gamer/AppData/Local/Android/tools/jdk-21.0.12.1+1"`
- The Android project lives in `client/`: run Gradle from there. `./gradlew testDebugUnitTest` (add `--tests '*Name*'` for one) · `./gradlew assembleDebug`.
  `println` from tests lands in `client/app/build/test-results/testDebugUnitTest/*.xml`.

## Device

- adb is at `/c/Users/gamer/AppData/Local/Android/Sdk/platform-tools/adb`; the tablet is on wireless debugging
  (`adb mdns services`, the port changes). Set `MSYS_NO_PATHCONV=1` for `adb shell`.
- Start a screen directly: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match`
  (`match|boss|train|fighters|kito|shop|track|settings|result|leaders`, or `capsule0`..`capsule5` to preview a capsule opening, suffix `s` splits into eight, `f` gives a fighter, `b` a bundle). Save file: `adb shell run-as io.github.projectwip cat files/save.json`.
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
- The game checks GitHub releases at start-up (`net/Updater.kt`) and blocks on an update screen if a newer one with
  an APK exists. So a release with a broken APK locks every player out: never publish one that wasn't built from
  the tagged commit with tests passing. Debug: `--es screen update` (fake) or `updatecheck` (real check as v0.0.1).

## Client and server

- `client/` is the whole Android game. `server/` is the game server: Python, standard library only, SQLite.
  `python run.py` (or `run.bat`) starts it on port 8765; `python -m unittest` in `server/` runs its tests.
- The server is in charge, by the user's decision: it owns each player's Cups and Spark Drops (it works out what
  a match is worth, refuses results that can't be real, and rolls what comes out of a drop), sets the bot
  difficulty, and says who is a developer. Those rules live in `server/astro/rules.py`. Don't add client-side
  ways to earn Cups or drops, or to roll a drop.
- Offline mode still has to work: every mode plays against bots and pays Bolts, with no Cups, no new drops and
  no opening drops. The loading screen tries the server for 60 seconds, then offers Retry or Offline mode.
- Developers = debug builds, plus the player ids in `server/game.cfg`. Only they see the debug menu and the
  difficulty choice; the server ignores luck, free drops, difficulty and hand-outs from anyone else. The tablet's
  id is listed there. Everyone else plays on the difficulty in `game.cfg` (Easy).
- The leaderboard is the server's real accounts only (no made-up rivals; offline there is none). A new player
  is asked for a name before their account is made (`NameScreen`).
- A new fighter or skin: also add it to `FIGHTER_SKINS` in `rules.py`. A change to the Cup table goes in
  `rules.py` (the client has no copy).
- The match itself runs on the device, so the server can't catch a client that plays with cheats, only one that
  claims results. Don't describe it as cheat-proof.
- Testing against the server on this PC: start it and launch the game; the built-in address is this PC's LAN
  address. `--es server <url>` (debug builds) points at another one, `--es server default` clears it. A match
  started with `--es screen match` begins before the connection is up, so it is an offline match.
  Don't commit `server/astroarena.db`.

## Git

- Checkpoint as you go: once a piece is verified (tests or device), commit just that piece and
  `git push origin main`. Never checkpoint unverified or non-compiling work.
- **Non-negotiable: every version is committed, tagged and released.** Versions are whole numbers from v6 on
  (`versionName = "6"`, then "7"). Whenever the version in `client/app/build.gradle.kts` changes, finish by
  committing, tagging `vN.0`, pushing the tag and publishing a GitHub pre-release titled "AstroArena vN" with the
  release APK attached (`AstroArena-vN.apk`), without waiting to be asked. The tag keeps the `.0` because installs
  of 0.4.2 to 0.5.1 can only read tags of the form `vX.Y`; a bare `v6` tag would be invisible to their updater.
  The newest release must always be the newest version, so nobody downloads a stale APK.
  Smoke-test the release APK on the tablet when it is free, then put the debug build back.
- `gh` needs normal path conversion: don't run it with `MSYS_NO_PATHCONV=1` set.
- End commit messages with the co-author line used in history. `screenshots/` is gitignored scratch.
