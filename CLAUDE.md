# CLAUDE.md

AstroArena (the package id is still `io.github.projectwip`, so saves carry over): original mobile 3D arena brawler for Android (landscape, touch, bots), with an optional Python game server. Kotlin + Compose
menus + custom OpenGL ES 3.0 renderer, no engine. All art and sound is generated in code and must stay
original: no Brawl Stars/Supercell assets, names, icons or UI copies. Fighters have first names only. Players see "Glitch Drops", "Upgrade Credits" and "CPU Chips"; the code (and the server's files and API) still call them capsules, bolts and prisms. (They were Spark Drops, Power Ups and Crystals: the user renamed them. Upgrade Credits pay for every fighter upgrade; their icon is the Credit card in amber. CPU Chips are the shop currency; their icon is a chip.)

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
  (`match|boss|train|duel|fighters|roster|kito|varun|buddy|shop|road|track|settings|result|leaders|news`; `roster` is the fighter grid with every model shown unlocked, `tryvarun` / `trybuddy` the Training Area as Varun / Buddy, or `haul` to preview an "open all", or `capsule0`..`capsule5` to preview a capsule opening, suffix `s` splits into eight, `f` gives a fighter, `b` a bundle). Save file: `adb shell run-as io.github.projectwip cat files/save.json`.
- UI changes must be checked with a screenshot (`adb exec-out screencap -p`) and `adb logcat -b crash -d`.
- The tablet is the user's everyday device. Before every `input tap` or `am start`, confirm
  `dumpsys window | grep mCurrentFocus` shows `io.github.projectwip` or the home screen (`com.miui.home`): on
  the home screen the tablet is free, so starting the game is fine. In any other app, skip it and say so.
- Phone-size check: `wm size 1080x2400 && wm density 420`, then always `wm size reset` / `wm density reset`.

## Rules of the codebase

- `sim/`, `ai/`, `data/` and `audio/SfxSynth.kt` are pure Kotlin (no Android imports); they run in JVM tests.
- Humans and bots drive fighters through the same `Control`. Visibility goes through `World.isVisibleTo`.
- The starter fighter is Byte (`FighterId.BYTE`, `STARTING_FIGHTER`); Juno was removed, by the user's decision, and
  accounts that had her lost her. Byte's kit is modelled on a familiar shotgun brawler at the user's request; her
  name, look and words are ours and must stay so.
- Boss Mode bosses are their own things (`BossKind`, `Balance.bosses`), not giant fighters: each fights through
  moves of its own in `sim/Boss.kt` (telegraphed ground hazards, sweeps, rings, charges) and has its own model.
  Keep their names, looks and moves original.
- Buddy (`FighterId.BUDDY`, Ultra, last on the Spark Road) is the user's own design: a rogue AI. His attack smashes a
  computer into whoever is close (`AttackShape.SMASH`: one short, heavy shot drawn as a computer, `ShotStyle.COMPUTER`).
  His super (`SuperKind.CORRUPT`) needs no aiming: it picks the nearest enemy in sight and poisons them (`Fighter.poisonBy`,
  `World.stepPoison`): no healing, and it only ends with a knockout (a boss shakes it off after `POISON_GIANT_SECONDS`).
  With nobody in sight the super isn't spent. His face is a hologram: a flat lit screen over a projector ring, no head.
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
- The server is in charge, by the user's decision. It owns each player's Cups, Glitch Drops, Bolts, Prisms,
  fighters (unlocked, level, colourways), Cup Track claims, the daily gift and the shop deals. It works out what
  a match is worth, refuses results that can't be real, rolls drops, and is the only place anything is bought,
  upgraded or claimed (`server/astro/rules.py`, `economy.py`). The client's online save is a copy of what the server
  sent (`Progression.syncAccount`). Don't add client-side ways to earn, spend, grant or roll anything in it: the
  offline profile (below) is the only thing the device decides for itself.
- Menus ask the server through `LocalServerCall` (`ui/ServerCall.kt`): `ask({ buy(key) }) { reward -> ... }`.
- Offline mode is a profile of its own, by the user's decision. While the server can't be reached the game plays on
  an *offline profile* kept on the device (`files/offline.json`; `GameRepository.offlineMode`): its own Cups, drops,
  currencies and fighters, earned and spent by the same rules as the server's, run on the device (`data/Economy.kt`,
  through `net/LocalGame.kt`). It starts as a new account and is never sent to the server; the online save
  (`save.json`, a copy of the server's account) is untouched while offline. The switch is automatic and only ever
  made by the connection: offline when the server doesn't answer (at start-up after `CONNECT_PATIENCE_MS`, or when
  it stops answering later), back online by itself when it does, and never in the middle of a match (a match belongs
  to the profile it started on; one started online that can't be handed in pays nothing). There is no button for
  it. A disabled account or an unsupported version gets no offline play. Offline nobody is a developer, and there
  is no leaderboard, News, 1v1 against a real player or team play.
- `Economy.kt` must say what `server/astro/economy.py` and `rules.py` say: change a price, a cost, a drop table or
  what a match pays in both, and `EconomyTest` and `test_server.py` both check the numbers.
- Menus ask through `LocalServerCall` whichever profile is showing: the request is written against `GameActions`
  (`net/LocalGame.kt`), which the server connection and `LocalGame` both implement.
- Prices and tables shown by the client (`Balance.kt`, `Catalog.kt`) are copies for display; the server's are
  the ones that count. Change both.
- Developers = the player ids in `server/game.cfg` (the tablet's is listed), and nobody else: a debug build is
  not enough, and offline nobody is one. Only they get Settings > Chaos Command Center, which holds every tweak
  (drop luck, free drops, upgrade cost, no level cap, hand-outs) and is always on for them: there is no "D" button
  and no switch any more. Only they can make shop deals (the in-game Offer Creator) or reset an account. The server
  ignores luck, free drops, free upgrades and hand-outs from anyone else.
- Settings > Gameplay has "Glitch Drops only", for everyone: the home screen becomes just the Glitch Drop button, with
  no fights. It is a layout choice kept on the device; the drops are still the server's.
- `server/accounts.cfg` is the owner's hand on every account (`astro/accounts.py`): the server writes it from the
  database, leaderboard first, and forces whatever value the owner changes in it (`Store.force`). It holds player
  ids and names, so it is gitignored: never commit it. `disabled = yes` there marks an account (never deletes it),
  with an optional `disabled_reason` and `disabled_until`: the server answers it 403 everywhere and the game
  shows `DisabledScreen` (`--es screen disabled` previews it), which has nothing to press. `delete = yes`
  there deletes an account for good (`Store.delete`; what it held is appended to the gitignored
  `server/deleted_accounts.log`). A disabled account
  can do nothing, by the user's decision: no menus, no offline play, and no Jail mode (there was one in build 50;
  it was removed). Don't give it anything to play.
- There is no GUI for running the server, by the user's decision: one was built (`server_manager/`) and removed
  as clunky. The owner edits the `.cfg` files by hand and starts the server with `run.bat`. Don't build another.
- Bot difficulty: every player may pick, but the pick is a request (`POST /v1/settings/difficulty`); the server
  approves it against `allowed` in `game.cfg`, stores it, and uses its own copy when it plans a match.
- Days and times are the server's: the day number, when it ends, the daily gift and the daily offers
  (`server/shop.cfg`). The client moves server times onto its own clock on receipt and only counts down.
- The News tab shows `server/news.cfg` (`GET /v1/news`), newest first; add an item there when a release changes what
  players see. Offline there is none.
- Cups don't depend on bot difficulty. What each mode pays (Boss Mode included) is `server/trophies.cfg`, over
  `DEFAULT_CUPS` in `rules.py`; the client has no copy and shows what the verdict says (`cupDelta`, `mvpCups`). Each fighter has Cups and a rank of its own
  (`FIGHTER_RANK_CUPS` in `rules.py`, `FighterRanks` in `Catalog.kt`); only a judged match changes them. Ranks have no
  top: past the table every rank is another `FIGHTER_RANK_STEP` Cups.
- 1v1 (`GameMode.DUEL`) is two real players, each on their own device. Both run the same simulation from the same
  seed and only exchange inputs (`net/DuelLink.kt`, lockstep in `MatchRunner`); the server's lobby (`astro/duel.py`,
  the game port + 1) pairs them and passes the frames. It is played for Cups (`[DUEL]` in `trophies.cfg`): the lobby
  keeps both players' frames, replays the match through the referee (`Referee.judgeDuel`) and pays each by the
  replay; neither device is asked who won. A match that isn't played out is lost by whoever left, stalled, or
  disagreed with the replay. Without a referee a 1v1 pays nothing.
  A player waits for another real one for as long as it takes: there is no stand-in opponent, by the user's decision.
  Every 30 ticks the devices compare a checksum of the match and call it off (a draw) if they disagree.
  The lobby, not the devices, decides what happened when a match stops moving: the player whose inputs stopped
  first loses, and both are told (`DuelLobby.watch`). It only pairs players on the same build.
- Teams are two or three real players in one Boss Mode or Knockout Rush match (`net/TeamLink.kt`, `astro/team.py`, on
  the 1v1 lobby's port). One makes a team and gets a four-digit code, the others join with it, the leader starts.
  It is the 1v1's lockstep with more players: every device runs the same match, bots included (`MatchConfig.team`;
  the real players are the first fighters, in slot order), and the lobby replays it (`Referee.judgeTeam`) and pays
  each player. Anything in `sim/` that reads `match.player` or `config.playerLevel` would put a team's devices out
  of step: use what every device shares. A player who leaves takes a defeat and their fighter stands still; the
  rest play on. Devices that disagree about a team match get it called off, unpaid (the 1v1 says who was wrong).
- The leaderboard is the server's real accounts only (no made-up rivals; offline there is none). A new player
  is asked for a name before their account is made (`NameScreen`).
- A new fighter or skin: also add it to `FIGHTER_SKINS` in `rules.py` and its price in `economy.py`; a new fighter
  also needs a place on the Spark Road (`SPARK_ROAD` in `economy.py`, `SparkRoad` in `Catalog.kt`).
- Fighters are unlocked on the Spark Road with Credits (or bought with CPU Chips): drops and the Cup Track pay
  Credits, never a fighter. Credits are not a wallet and must never be shown as one: they go straight onto the
  road toward the next fighter along it (a fixed order; rarity decides the cost: Rare 2,500, Epic 4,200, Mythic
  6,500, Legendary 9,000, Ultra 13,000), and the moment they cover it the server unlocks that fighter
  (`economy.fill_road`), with the leftover carried on: there is nothing to claim. A reward that unlocked someone
  comes back as a bundle (the Credits and the fighter), and a match's verdict lists them in `unlocked`. Once every
  fighter is unlocked Credits are paid as Upgrade Credits. Glitch Drops are the main source of Credits: every
  Credit amount a drop gives is multiplied by `CREDIT_BUFF` in `rules.py`.
- There is no Glory and no Spark Pass: both were removed on purpose (stored Glory was paid out as Upgrade Credits,
  `economy.complete`). Don't bring them back.
- The Spark Road screen is 3D: `LobbyShot.ROAD` draws a road in the lobby scene (`render3d/Lobby.kt`, off at
  `ROAD_Z`) with every fighter standing along it, lit as far as the Credits have reached; `RoadScreen` only lays the
  header, the summary and the card for the focused stop over it, and drags `LobbyParams.roadScroll`.
- The Spark Road is our own take on a familiar idea. Keep its names, art and layout original.
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
