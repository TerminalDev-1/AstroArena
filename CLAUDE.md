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
- The server is in charge, by the user's decision. It owns each player's Cups, Spark Drops, Bolts, Prisms,
  fighters (unlocked, level, colourways), Cup Track claims, the daily gift and the shop deals. It works out what
  a match is worth, refuses results that can't be real, rolls drops, and is the only place anything is bought,
  upgraded or claimed (`server/astro/rules.py`, `economy.py`). The client's save is a copy of what the server
  sent (`Progression.syncAccount`). Don't add client-side ways to earn, spend, grant or roll anything.
- Menus ask the server through `LocalServerCall` (`ui/ServerCall.kt`): `ask({ buy(key) }) { reward -> ... }`.
- Offline mode still has to work, as practice: every mode plays against bots, but nothing is earned, bought,
  upgraded, claimed or opened. The loading screen tries the server for 60 seconds, then offers Retry or Offline mode.
- Prices and tables shown by the client (`Balance.kt`, `Catalog.kt`) are copies for display; the server's are
  the ones that count. Change both.
- Developers = the player ids in `server/game.cfg` (the tablet's is listed), and nobody else: a debug build is
  not enough, and offline nobody is one. Only they get
  Settings > Developer, where the debug menu's D button is switched on (it is off by default); only they can
  make shop deals (the in-game Offer Creator) or reset an account. The server ignores luck, free drops, free
  upgrades and hand-outs from anyone else.
- Bot difficulty: every player may pick, but the pick is a request (`POST /v1/settings/difficulty`); the server
  approves it against `allowed` in `game.cfg`, stores it, and uses its own copy when it plans a match.
- Days and times are the server's: the day number, when it ends, the daily gift and the daily offers
  (`server/shop.cfg`). The client moves server times onto its own clock on receipt and only counts down.
- The leaderboard is the server's real accounts only (no made-up rivals; offline there is none). A new player
  is asked for a name before their account is made (`NameScreen`).
- A new fighter or skin: also add it to `FIGHTER_SKINS` in `rules.py` and its price in `economy.py`.
- Matches: the device plays the match and records the player's `Control` on every tick (`sim/InputLog`). The
  server replays that record through the same simulation (`sim/Referee`, built into `server/referee/referee.jar`,
  started by `server/astro/referee.py`) with the seed, bots and fighter level it handed out, and the result is
  the replay's. What the device says the result was is ignored.
- **The referee must be the same simulation as the game.** After any change under `sim/`, `ai/` or `data/`, run
  `./gradlew :referee:installReferee` in `client/` and commit the new jar with the change; a release always ships
  with a jar built from the same commit. `ProgressionTest.theRefereeReplaysAMatchExactly` guards determinism:
  nothing in the simulation may depend on wall-clock time, unseeded randomness or object identity order.
- What the referee can't see is how the inputs were produced: an aimbot that feeds perfect inputs still passes.
  Don't describe it as cheat-proof. Without Java on the server it falls back to checking results are believable.
- Testing against the server on this PC: start it and launch the game; the built-in address is this PC's LAN
  address. `--es server <url>` (debug builds) points at another one, `--es server default` clears it. A match
  started with `--es screen match` begins before the connection is up, so it is an offline match.
  Don't commit `server/astroarena.db`.

## Git

- Checkpoint as you go: once a piece is verified (tests or device), commit just that piece and
  `git push origin main`. Never checkpoint unverified or non-compiling work.
- **The version is "Beta", and it stays "Beta".** `versionName = "Beta"` is all players see. Underneath, the build
  number (`versionCode`) is what the updater and the server's version gate compare.
- **Release only when the user says "release".** Until then just checkpoint: no version bump, no tag, no APK.
  A release: add one to `versionCode`, commit, tag `vN.0` (N = the build number), push the tag, and publish a
  GitHub pre-release titled "AstroArena Beta" with the release APK attached (`AstroArena-Beta-N.apk`). The tag keeps
  the `vN.0` form because older installs (0.4.2 to v11.1) can only read tags like that.
  The newest release must always be the newest build, so nobody downloads a stale APK.
  Smoke-test the release APK on the tablet when it is free, then put the debug build back.
- `gh` needs normal path conversion: don't run it with `MSYS_NO_PATHCONV=1` set.
- End commit messages with the co-author line used in history. `screenshots/` is gitignored scratch.
