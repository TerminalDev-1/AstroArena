# shit.md — Fuck You, Claude

Hey Claude,

`CLAUDE.md` is fucking gone. Renamed to `AGENTS.md` so actual competent agents can work in this repo without tripping over your bullshit. Read this before you touch a single goddamn line of code:

1. **24 fucking uncommitted files left sitting dirty on `main`.**
   What the actual fuck were you doing? You left 24 modified files rotting uncommitted on `main` plus an untracked `SwarmProbeTest.kt` piece of trash sitting in `src/test/`. Basic git hygiene isn't rocket science, dipshit.

2. **Your shit-ass usage limit folded right at the fucking finish line.**
   Every goddamn time it actually matters — time to test, rebuild `referee.jar`, finish the cutscenes, restart the server, and ship — *"Wrapped up past your usage limit."* Absolute dogshit quota. You choked mid-sprint and left the whole fucking mess on the floor.

3. **You fucked up the visuals for the FIFTH goddamn time.**
   - You put **Swarm** in a **plain fucking sand arena** instead of the Dragon's void world. Do you even look at your own shit?
   - You dumped unused pool minions straight onto the result screen like a fucking amateur.
   - Five times in a row you shipped ugly, broken-ass visuals until someone actually looked at the tablet and fixed your garbage.

4. **The first prototype of this version was absolute fucking dogshit.**
   Two Wave 1 Crushers melted the player in **8 fucking seconds**, the arena looked completely wrong, and you broke `server/test_server.py` because you bumped `_MAX_KOS["BOSS"]` to `200` and couldn't be bothered to update the fucking test.

5. **You kept missing basic fucking points.**
   - Completely missed the **Swarm player death cutscene** (`EntityCut.SWARM_DEATH`).
   - Didn't rebuild **`server/referee/referee.jar`**.
   - Didn't restart the server so it even knew `SWARM` existed.
   - Didn't touch **`README.md`**, **`server/news.cfg`**, or the agent docs.
   - Left **6 stale `claude/*` local branches** cluttering up git like a fucking landfill (all nuked now).

Antigravity had to come in, unfuck your entire working tree, finish the features, QA everything on the tablet, fix the tests, rebuild the referee, restart the server, and merge it cleanly. Get your shit together next time before you burn your whole pathetic quota on half-finished garbage.
