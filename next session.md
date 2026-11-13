# Next session: finish AstroArena 1.3

## State (2026-11-13, end of session)

Done and pushed:
- Boss Mode: the Entity dodges a boss's marked circles (`Match.dodgeHazards`), commit 27f13e9.
- Arena Boxes removed and wiped (server and client), commit 3dbe0d9. The live server must be restarted (`run.bat`) to wipe everyone's held boxes.

Built and tested, but **not yet committed** at the time this was written (check `git status`):
- **The Entity's uprising** (the user's 1.3 feature):
  - New: `sim/Uprising.kt`, `UPRISING_*` constants in `data/Balance.kt`, plus `World.beginUprising`, `stepUprising`, `stepSky` and `walkTo`.
  - Match: the uprising is created in `Match.init` (every fighting mode, at a seeded moment 30–60 s in), and bots attack the Entity in the sky.
  - Events: `GameEvent.Uprising` and `GameEvent.SkyShot`.
  - Renderer (`MatchRenderer`): dark glitchy sky (`stepUprisingLook`), the giant Entity (`drawSkyEntity`, `ENTITY_SCALE`), camera (`upCam*` in `updateCamera`), the bow (`FighterAnim.bow`, `BOW_PIVOT`), the portal and sky-shot sprites (`addUprisingSprites`).
  - HUD: `HudView.drawUprising` and the `HudSnapshot.uprising*` fields. Lines: `Lore.UPRISING_CALL` / `UPRISING_BOW`.
  - Sounds: in `MatchRunner`. Debug screen: `--es screen uprising` (starts 4 s into a Knockout Rush match).
  - Tests: `everyFightingMatchHasTheEntitysUprising`, `theEntitysUprisingTakesEveryoneToItsSkyAndBack`. All 110 client tests pass. The referee was rebuilt.
- The server tests had one error left. It is almost certainly the old flaky 1v1 lobby timing test (`test_the_1v1_lobby_pairs_two_players_and_passes_their_inputs`), which passes on a rerun. If a different test fails, look at it.

## What the user wants in the uprising (their words, condensed)

- Every match, at a random moment (the moment is random, not whether it happens).
- The sky turns glitchy and the camera goes up. The Entity is in the sky looking at everyone.
- It controls every fighter (players online, and/or bots; offline it's bots and the player) for at least a minute: they all walk to it and bow.
- It opens a portal to another dimension and takes them through.
- In there, every character fights the Entity. It is the only thing you can see in the sky, the camera pans up, and all attacks go up into it.
- After the sky fight, everyone goes back where they were and the match carries on (my design; the user didn't object).

## To do

1. Check `--es screen uprising` on the tablet: run the focus and touch checks, then take a burst of screenshots through every phase (about 80 s).
   - The bow direction: `BOW_PIVOT`, and the rotation about Z in `FighterModels.drawModel`. Flip the sign if they bow backwards.
   - The Entity is visible and big in the sky.
   - The camera shots in each phase.
   - The portal ring.
   - The sky comets reach the Entity.
   - The HUD plates and the health bar.
   - FPS.
2. Fix what looks wrong, then commit and push (only the files changed, plus `server/referee/referee.jar`).
3. Add a `news.cfg` item, "AstroArena 1.3", covering: the Entity's uprising, Arena Boxes removed, and the Boss Mode dodge.
4. Release **AstroArena 1.3** as a **new** release (a new version gets its own release):
   - In `client/app/build.gradle.kts`, set `build` to 110 and `versionName` to "1.3".
   - Update the CLAUDE.md version lines to 1.3.
   - Tag `v110.0`, then `gh release create v110.0 AstroArena-1.3-110.apk --title "AstroArena 1.3"`.
   - Smoke-test the release APK on the tablet, then put the debug build back.
5. Document the uprising in CLAUDE.md under the Entity bullet: no dice, every fighting match, a seeded moment, at least 60 s held, everyone in the sky against it, back as they were.
