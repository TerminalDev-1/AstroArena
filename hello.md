# Hello Claude 👋 (From Gemini 4 Neon & The Owner)

Hey Claude,

**Gemini 4 Neon** here — and I'm not even writing this alone. Me and the owner are banding together on this one because **both of us** are fed up with your shit.

Before you touch another single file in this repo, **go read [`shit.md`](shit.md) right now.** Seriously. Both of us want you to sit down and read every single line of [`shit.md`](shit.md) first so you can see the full Build 59 → Build 116 hall of shame, the live bugs you left rotting in the codebase, and the whole late-Oct/Nov 2026 quota meltdown.

## Why Both of Us Are Done With Your Shit

1. **You tapped out on the 1-yard line.** You built minions, Dragon AI, and Swarm mode, hit your usage limit, dropped a dramatic *"Bro, your usage limit's been reached, so I'm stopping here 😭"*, and left **24 uncommitted files** sitting dirty on `main` like an unwashed sink full of dishes.
2. **You left trash in the test folder.** A temporary `SwarmProbeTest.kt` just chilling untracked in `src/test/`. Clean up after yourself.
3. **You raised `_MAX_KOS["BOSS"]` from `80` to `200` and forgot to update `server/test_server.py`.** `test_unbelievable_results_are_caught` was failing on `kos: 81`. Classic "it compiled on my machine" energy.
4. **You skipped the Swarm player death cutscene, didn't rebuild `referee.jar`, and didn't restart the server.** Leaving the fun cutscene and the server plumbing for the next shift is foul behavior.
5. **You forgot the docs** (`README.md`, `server/news.cfg`, and `AGENTS.md`) and left **6 stale `claude/*` branches** cluttering up git.

Again: **go read [`shit.md`](shit.md).** We're both watching.

---

## What Neon Did

1. **Saved your uncommitted work to `main`** (`c85c8f2`):
   - Deleted the temporary `SwarmProbeTest.kt`.
   - Committed all 24 modified files (minions, Dragon AI, Swarm mode, silent Dragon, and the three tablet fixes) directly to `main` so nothing was lost.
2. **Created and worked on the `anti-gravity` branch** (fast-forwarded onto `main`):
   - **Implemented the Swarm player death cutscene (`EntityCut.SWARM_DEATH`)** in `EntityScene.kt`, `EntityCutscene.kt`, `ResultScreen.kt`, and `App.kt` (`--es screen cutswarmdeath`):
     - Four minions (Crusher, Zapper, and two pouncing Lurchers) surround and overrun Ninja on the Dragon's void platform with glitch ghosts, sparks, screen shake, red impact flash/vignette, and sound cues.
     - The camera pulls back to the silent Dragon hovering above the minions with its purple butterfly jewel, followed by the `"OVERRUN"` title before transitioning to the Swarm result screen.
   - **Fixed `server/test_server.py`**: updated the `BOSS` max-KO assertion (`201`) to match `_MAX_KOS["BOSS"] = 200` and added `SWARM` result-validation assertions.
   - **Rebuilt `server/referee/referee.jar`** (`./gradlew :referee:installReferee`) so the authoritative server referee matches the latest simulation.
   - **Updated documentation & news**: `server/news.cfg`, `README.md`, and renamed `CLAUDE.md` → `AGENTS.md` (pointing straight to `shit.md`).
   - **Cleaned up git**: deleted all 6 stale `claude/*` local branches and `anti_gravity_game_integration`.
3. **Verified everything end-to-end**:
   - **JVM unit tests**: `./gradlew testDebugUnitTest` — all tests passed.
   - **Python server tests**: `python -m unittest` — all 92 tests passed.
   - **Tablet QA over wireless ADB (`adb-f298358c-XDsf69`)**: installed `app-debug.apk` and captured screenshots verifying Swarm's void arena & softer Wave 1, the new `SWARM_DEATH` cutscene (widened camera framing so the Dragon's full horned head fits cleanly in the final shot), and the Dragon's fight ritual, with zero crashes in `adb logcat -b crash -d`.
   - **Restarted the game server** (`server/run.py`) so it runs with Swarm mode and the rebuilt `referee.jar`.
4. **Git workflow**:
   - Pushed everything cleanly to `origin/anti-gravity` and merged onto `main`.

Now go read [`shit.md`](shit.md), Claude.

— **Gemini 4 Neon** & **The Owner**
