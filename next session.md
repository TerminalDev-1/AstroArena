# Next session (2026-11-14, from 4 PM)

Read CLAUDE.md first (the new "boss-fight game about the Entity" bullet at the top of "Rules of the codebase").
The user's working rules for this repo: QA on the tablet before any release, then ask them questions and iterate
until they say it's right; nothing is released until it's perfect. The last release is still AstroArena 1.3 build 110:
everything below (and the committed rework in 8ad91c6) is unreleased. The next release is build 111.

## Where things stand

Committed and pushed (main):
- 8ad91c6: the game is a boss-fight game about the Entity. Knockout Rush, Last Spark and Dimension removed, the old
  bosses deleted, Boss Mode = the Entity's fight (ritual with SKIP, its world, 3:30 limit, strikes/rings/sweeps/
  corruption flares), teams of three, server updated. 105 client tests and 94 server tests passed at that commit.
- Before it: the cinematic ritual + the Entity's voice + the new-player opening (2eec916), PLAY INTRO on the loading
  screen and smoother, uncharged bots in the Entity fight (3ad34c2).

**Not committed yet (in the working tree, unverified as a whole):**
1. The Entity's cutscenes (done and checked on the tablet; only the whole build needs the test run):
   `render3d/EntityScene.kt`, `ui/screens/EntityCutscene.kt`; wired in `MatchRunner` (`corruptions`), `MatchView`,
   `MatchScreen` (corruption cutscene pauses the match, solo only), `ResultScreen` (VICTORY / DEFEAT cutscene instead of
   the reveal in the Entity's fight), `App.kt` (debug `--es screen cutvictory|cutdefeat|cutcorrupt`).
2. Cups removal, half done:
   - Client UI done: home top bar is now name + wins (opens the leaderboard), Cup Track screen deleted, the CUPS
     section tab gone, fighter rank removed from the fighter page, result screen shows no Cups/rank (fighter on a
     pedestal + Coins/Credits/Crystals/unlocks/MVP), Chaos Cup buttons gone, Cup wording removed from Settings/1v1.
     This compiled.
   - Client data NOT done: `Economy.settleMatch` still computes Cups via `Trophies`; `Trophies`, `CupTrack`,
     `FighterRanks`, `Milestone`, `Economy.claimMilestone`, `Progression.claimable`, `MatchRewards` cup fields,
     `LocalGame/GameServer.claimMilestone`, `devGrant(cups)`, the leaderboard (`RemotePlayer.cups`,
     `LeaderboardEntry.cups`, `LeaderboardScreen` "ranked by Cups") still exist. Make the leaderboard read `wins`.
   - Server done but its tests NOT updated/run: `rules.cup_delta` returns 0, trophies.cfg deleted (and its parsing in
     config.py), everyone's Cups wiped at start (`store.py`), leaderboard and `rank()` by wins (`WINS` in store.py),
     `/v1/track/claim` answers 410. `test_server.py` still expects Cups: fix it, run `python -m unittest` in `server/`.
   - Then: client tests (`./gradlew testDebugUnitTest`, EconomyTest still checks Cups), rebuild the referee jar
     (`./gradlew :referee:installReferee`), commit file by file.

## The user's new asks (2026-11-14, before leaving)

1. **The loading screen becomes a cutscene.** Instead of the current loading screen, a cutscene: the "ASTROARENA"
   title from the end of the intro cutscene, animated (rising out of the void, glitching like the Entity, its
   streaks, the Entity's eyes behind it...). Loading progress and the server status still need to be shown somewhere
   small, and PLAY INTRO / PLAY OFFLINE (DEV) stay. `ui/screens/LoadingScreen.kt`.
2. **A new menu theme: "voidy neon purple".** Still purple, but the menus less light purple and more void-dark with
   neon purple (darker panels, neon violet/magenta/cyan edges and glows). Menus only (`ui/Theme.kt` `Palette`,
   `ui/Components.kt` plates); don't change the 3D stage/lobby colours.
3. **"May the trophy road looking like cup track be removed."** Most likely the Cup Track (removal above); it may
   mean the Spark Road too. Ask the user in one line which they meant before removing the Spark Road (it is how
   fighters are unlocked).

## QA still owed for the release
- Play a full Entity fight on the tablet and win it (VICTORY cutscene → result), lose one (DEFEAT cutscene), get
  corrupted (corruption cutscene, match resumes), SKIP the ritual; home screen without Cups; leaderboard by wins.
- Then ask the user the QA questions (Entity voice, bow length, intro flight pose were asked and not answered yet),
  iterate, release build 111 (replace the "AstroArena 1.3" release per CLAUDE.md).

## Why the tests are slow (the user asked)
Client: ~3 min; whole matches are simulated in game time, and each Entity fight is a 72 s ritual plus up to 3:30.
Server: ~80 s; the 1v1/team lobby tests use real sockets and timeouts. Run single tests while iterating
(`--tests '*Name*'`, `python -m unittest test_server.Api.test_x`) and the full suites once before committing.
