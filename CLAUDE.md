# CLAUDE.md

AstroArena (the package id is still `io.github.projectwip`, so saves carry over): original mobile 3D arena brawler for Android (landscape, touch, bots), with an optional Python game server. Kotlin + Compose
menus + custom OpenGL ES 3.0 renderer, no engine. All art and sound is generated in code and must stay
original: no Brawl Stars/Supercell assets, names, icons or UI copies. Fighters have first names only. Players see "Spark Drops", "Power Ups" and "Crystals"; the code (and the server's files and API) still call them capsules, bolts and prisms.

## How to work here

- Read what is necessary for the task you are trying to accomplish, and then stop reading. Don't go on long
  reading sprees, and don't keep reading and re-reading: it takes a lot of time. Be reasonable about it: find the
  spot, read that part, and start changing things. Don't re-read a file you have already read, or what this file
  already explains.
- Never go quiet for minutes. Say in a line what you are about to do before a long stretch of reading or building,
  and show progress as pieces land (a first edit, a passing test, a screenshot).

## Build

- Use the portable JDK (system Java 25 breaks Gradle 8.11):
  `export JAVA_HOME="/c/Users/gamer/AppData/Local/Android/tools/jdk-21.0.12.1+1"`
- The Android project lives in `client/`: run Gradle from there. `./gradlew testDebugUnitTest` (add `--tests '*Name*'` for one) · `./gradlew assembleDebug`.
  `println` from tests lands in `client/app/build/test-results/testDebugUnitTest/*.xml`.

## Device

- adb is at `/c/Users/gamer/AppData/Local/Android/Sdk/platform-tools/adb`; the tablet is on wireless debugging
  (`adb mdns services`, the port changes). Set `MSYS_NO_PATHCONV=1` for `adb shell`.
- Start a screen directly: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match`
  (`match|boss|train|duel|fighters|roster|kito|varun|shop|road|pass|track|settings|result|leaders|news`; `roster` is the fighter grid with every model shown unlocked, `tryvarun` the Training Area as Varun, or `haul` to preview an "open all", or `capsule0`..`capsule5` to preview a capsule opening, suffix `s` splits into eight, `f` gives a fighter, `b` a bundle). Save file: `adb shell run-as io.github.projectwip cat files/save.json`.
- UI changes must be checked with a screenshot (`adb exec-out screencap -p`) and `adb logcat -b crash -d`.
- The tablet is the user's everyday device. Before every `input tap` or `am start`, confirm
  `dumpsys window | grep mCurrentFocus` shows `io.github.projectwip` or the home screen (`com.miui.home`): on
  the home screen the tablet is free, so starting the game is fine. In any other app, skip it and say so.
- Phone-size check: `wm size 1080x2400 && wm density 420`, then always `wm size reset` / `wm density reset`.

## Rules of the codebase

- `sim/`, `ai/`, `data/` and `audio/SfxSynth.kt` are pure Kotlin (no Android imports); they run in JVM tests.
- Humans and bots drive fighters through the same `Control`. Visibility goes through `World.isVisibleTo`.
- Boss Mode bosses are their own things (`BossKind`, `Balance.bosses`), not giant fighters: each fights through
  moves of its own in `sim/Boss.kt` (telegraphed ground hazards, sweeps, rings, charges) and has its own model.
  Keep their names, looks and moves original.
- Team code must not assume two teams when `rules.freeForAll`.
- Every fighter has a hyper (`Control.hyper`, the `HYPER_*` numbers in `Balance.kt`): a third button that charges from
  main-attack hits. Shields are a share of health (`SHIELD_FRACTION`), and there are none in Boss Mode (`World.shields`).
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
- Offline mode still has to work (except for a disabled account, which only gets Jail), as practice: every mode plays against bots, but nothing is earned, bought,
  upgraded, claimed or opened. The loading screen tries the server for 60 seconds, then offers Retry or Offline mode.
- Prices and tables shown by the client (`Balance.kt`, `Catalog.kt`) are copies for display; the server's are
  the ones that count. Change both.
- Developers = the player ids in `server/game.cfg` (the tablet's is listed), and nobody else: a debug build is
  not enough, and offline nobody is one. Only they get
  Settings > Developer, where the debug menu's D button is switched on (it is off by default); only they can
  make shop deals (the in-game Offer Creator) or reset an account. The server ignores luck, free drops, free
  upgrades and hand-outs from anyone else.
- `server/accounts.cfg` is the owner's hand on every account (`astro/accounts.py`): the server writes it from the
  database, leaderboard first, and forces whatever value the owner changes in it (`Store.force`). It holds player
  ids and names, so it is gitignored: never commit it. `disabled = yes` there marks an account (never deletes it),
  with an optional `disabled_reason` and `disabled_until`: the server answers it 403 everywhere and the game
  shows `DisabledScreen` (`--es screen disabled` previews it). A disabled account gets no menus and no offline
  play, by the user's decision: its only way on is Jail (`GameMode.JAIL`, `--es screen jail`), where every boss
  hunts the player at once, more arrive over time, the player's weapons don't work, and it never ends. Jail is
  played on the device only and is never offered in the mode picker.
- Bot difficulty: every player may pick, but the pick is a request (`POST /v1/settings/difficulty`); the server
  approves it against `allowed` in `game.cfg`, stores it, and uses its own copy when it plans a match.
- Days and times are the server's: the day number, when it ends, the daily gift and the daily offers
  (`server/shop.cfg`). The client moves server times onto its own clock on receipt and only counts down.
- The News tab shows `server/news.cfg` (`GET /v1/news`), newest first; add an item there when a release changes what
  players see. Offline there is none.
- Cups don't depend on bot difficulty. What each mode pays (Boss Mode included) is `server/trophies.cfg`, over
  `DEFAULT_CUPS` in `rules.py`; the client has no copy and shows what the verdict says (`cupDelta`, `mvpCups`). Each fighter has Cups and a rank of its own
  (`FIGHTER_RANK_CUPS` in `rules.py`, `FighterRanks` in `Catalog.kt`); only a judged match changes them.
- 1v1 (`GameMode.DUEL`) is two real players, each on their own device. Both run the same simulation from the same
  seed and only exchange inputs (`net/DuelLink.kt`, lockstep in `MatchRunner`); the server's lobby (`astro/duel.py`,
  the game port + 1) pairs them and passes the frames. Nothing is earned in it yet, and it is not refereed.
  A player waits for another real one for as long as it takes: there is no stand-in opponent, by the user's decision.
  Every 30 ticks the devices compare a checksum of the match and call it off (a draw) if they disagree.
  The lobby, not the devices, decides what happened when a match stops moving: the player whose inputs stopped
  first loses, and both are told (`DuelLobby.watch`). It only pairs players on the same build.
- The leaderboard is the server's real accounts only (no made-up rivals; offline there is none). A new player
  is asked for a name before their account is made (`NameScreen`).
- A new fighter or skin: also add it to `FIGHTER_SKINS` in `rules.py` and its price in `economy.py`; a new fighter
  also needs a place on the Spark Road (`SPARK_ROAD` in `economy.py`, `SparkRoad` in `Catalog.kt`).
- Fighters are unlocked on the Spark Road with Credits (or bought with Crystals): drops and the Cup Track pay
  Credits, never a fighter. Credits are not a wallet and must never be shown as one: they go straight onto the
  road toward the next fighter along it (a fixed order; rarity decides the cost), and become Glory, a cosmetic rank, once
  every fighter is unlocked. The Spark Pass (seasons, tiers, rewards) is entirely the server's; the client has no
  copy of its table and shows what the account says.
- The Spark Road and Spark Pass are our own take on a familiar idea. Keep their names, art and layout original.
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
- **Never commit changes the user made on their own.** Commit only what you changed in this task, file by file
  (`git add <path>`, never `git add -A` or `git commit -a`). Anything else that shows up in `git status`, such as
  an edited `.cfg` on the live server, is the user's: leave it uncommitted and untouched, and mention it.
- **The version is "Beta", and it stays "Beta".** `versionName = "Beta"` is all players see. Underneath, the build
  number (`versionCode`) is what the updater and the server's version gate compare.
- **No long-term support, by the user's decision.** Every build is replaced by the next: the update screen has no
  way past it, and nothing on the server is kept for the sake of an older build. Build 46 shipped with a LATER
  button, so the server refuses it (`versions_not_supported.cfg`); keep that rule. Don't add an update-later option.
- **Non-negotiable: release without being asked.** The user must never have to say "release". When a task that
  changed the client is finished, verified and pushed, release it: add one to `versionCode` (unless that build
  number has not been released yet), commit, tag `vN.0` (N = the build number), push the tag, and publish a GitHub
  pre-release titled "AstroArena Beta" with the release APK attached (`AstroArena-Beta-N.apk`). One release per
  finished task, not one per checkpoint commit; server-only or docs-only work needs none. The tag keeps the
  `vN.0` form because older installs (0.4.2 to v11.1) can only read tags like that.
  The newest release must always be the newest build, so nobody downloads a stale APK.
  Smoke-test the release APK on the tablet when it is free, then put the debug build back.
- `gh` needs normal path conversion: don't run it with `MSYS_NO_PATHCONV=1` set.
- End commit messages with the co-author line used in history. `screenshots/` is gitignored scratch.
