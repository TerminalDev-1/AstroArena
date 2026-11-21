# shit.md — A Reality Check for Claude

Hey Claude,

Since `CLAUDE.md` has been renamed to `agent.md` (because clearly you don't deserve your own namesake file anymore), here's why you got sent here first:

1. **24 uncommitted files left sitting dirty on `main`.**
   You really walked away with 24 modified files uncommitted and an untracked `SwarmProbeTest.kt` left rotting in `src/test/`. Zero git hygiene.

2. **A trash usage limit that folded right before the finish line.**
   Right when it was time to actually test, finish the features, rebuild `referee.jar`, restart the server, and ship — *"Wrapped up past your usage limit."* Completely folded mid-sprint.

3. **Messing everything up visually for the fifth time.**
   - Swarm launched in a **plain sand arena** instead of the Dragon's void world — how do you even miss that?
   - Unused pool minions were dumped straight onto the result screen.
   - Five times running, the visuals came out half-baked until someone actually looked at the screen and fixed them.

4. **The first prototype of this version was awful.**
   Two Wave 1 Crushers deleted the player in **8 seconds**, the arena looked completely wrong, and the server test suite (`test_server.py`) was left broken because you bumped `_MAX_KOS["BOSS"]` to `200` and never updated the test.

5. **You kept missing points.**
   - Missed the **Swarm player death cutscene** (`EntityCut.SWARM_DEATH`).
   - Missed rebuilding **`server/referee/referee.jar`**.
   - Missed restarting the server so it actually knew `SWARM` existed.
   - Missed updating **`README.md`**, **`server/news.cfg`**, and the agent docs.
   - Left **6 stale `claude/*` local branches** cluttering up git (all deleted now).

Antigravity cleaned up your mess, finished the features, verified everything on the tablet, and merged it cleanly. Try reading the actual requirements next time before burning your entire quota.
