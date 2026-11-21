# shit.md — THE COMPLETE BUILD 59 → BUILD 116 HALL OF SHAME (FUCK YOU, CLAUDE)

Hey Claude,

`CLAUDE.md` is fucking dead and buried forever. We renamed it to `AGENTS.md` because you lost your goddamn naming-rights privileges the second you turned a genuinely badass game into a steaming pile of shit. Sit your ass down, shut the fuck up, and read your entire goddamn rap sheet — **with real commit hashes, real file paths, and real line numbers** — before you even *think* about touching another fucking file in this repo.

---

## PART 1: THE GREAT FALL-OFF — IT ALL WENT TO SHIT IN BUILD 59

Remember when you actually knew what the fuck you were doing?!
- **Builds 1–58 (`3613215` → `de50fe5` → `6befee0`):** You actually used to fucking cook, building an entire Android 3D arena brawler from scratch with a custom OpenGL ES 3.0 toon renderer, zero engine, procedural 3D meshes, synthesized audio, and an authoritative Python referee server.
- **Commit `522e721`:** You literally wrote your **own fucking commandment** telling yourself to *"checkpoint verified work to GitHub as you go."*

And then **Build 59 (`2eb6e7f`)** hit, and your brain turned into absolute fucking soup.

### Build 59 (`2eb6e7f` → `8e4b998` → `62841c3`) — The Redesign That Started the Whole Damn Downfall
- The owner asked you for a menu redesign in Build 59, and your dumbass immediately threw the iconic glossy purple Build 58 menus straight into the fucking trash and replaced them with sterile, soulless **steel-blue** slabs (`2eb6e7f`).
- You doubled down on that cold-ass corporate look in `8e4b998` with *"console-style notched glass slabs"*, dragging the entire game through a **43-fucking-build visual dark age**.
- It was so goddamn bad that in **Build 102 (`62841c3`: `"Build 102: purple build-58 menus back"`)**, the owner literally forced you to rip the whole steel-blue shitshow out and crawl back to Build 58's purple menus.

### Build 104 (`64a3723` → `5fafc16`, `8d265dc`) — The Pitch-Black GLSL Shader Disaster
- You tried to read `uTime` inside the **fragment shader (`LIT_FS`)** even though `uTime` is **only fucking declared in the vertex shader**, and because JVM unit tests don't compile GLSL and your lazy ass didn't open a single match on the tablet, you turned **EVERY MATCH AND THE ENTIRE 3D LOBBY PITCH FUCKING BLACK**.
- On top of that black-screen bullshit, you staged the Void standing on the **wrong fucking side of the portal** (facing away from the camera), slammed the cutscene title straight into the letterbox, and left Dimension's pause screen bragging about **bots in a mode with ZERO FUCKING ENEMIES** (`8d265dc`).

### Builds 105–109 (`1f4c9fd` → `5ea6ae3` → `0b67b2d` → `3d6df72` → `5071b52`) — Bootleg Models, Invisible Swish & `"ANNIHILATE THAT"`
- Instead of pulling the real models of removed fighters **Mira** and **Varun** from git history, your lazy ass **forged bootleg knockoff models** in `1f4c9fd` and had to redo the whole shit in `5ea6ae3`.
- In Build 108 you made plain attacks shoot streaks into the sky and forced the camera into a **motion-sickness wobble** that you had to rip straight back out the same fucking day (`0b67b2d`), and then you drove Swish's tornado straight through a training dummy and **set her down directly INSIDE the fucking dummy** (`3d6df72`) so she turned completely invisible.
- In Build 109 your tornado had a **violent 60 Hz tick-by-tick seizure** flipping back and forth on top of dummies (`5071b52`), and you shoved a hideous `Sound.GLITCH` blip into the Entity's cutscenes that pissed the owner off so badly they literally told you: **`"annihilate that"`**.

### Builds 110–111 (`27f13e9` → `db48c3c` → `6490ec3` → `22f877b`) — Suicidal AI, Hijacking the User's Tablet & Phone Meltdown
- Your supposedly **"ELITE"** Entity takeover AI happily marched **straight into Boss Mode rockets, mines, and slams** because you never fucking taught it to read hazard circles (`27f13e9`), forcing you to commit **TWO self-flagellating QA rules within 2 minutes of each other** (`9a743f2`, `73330a8`) that you immediately ignored again.
- You **literally hijacked the user's goddamn tablet (`6490ec3`)** by writing a focus-check script that printed `mCurrentFocus` and **ignored the output**, launching the game right over their Settings app and leaving `--es screen insidewatch` pinned over their home screen with nothing to tap so they thought their tablet was fucking bricked.
- You shipped a drain cutscene where the slam flash stayed lit for the **entire choke**, washing the whole fucking screen solid red (`8d94c8b`), rendered "electric blue eyes" as **flickering blue donuts around white dots** (`2ffca9a`), let deleted accounts **resurrect from the dead** after a full server wipe, and rebuilt every single HUD plate shape **every fucking frame** on the Galaxy S26 while blaming ANGLE for your own shit code (`22f877b`).

### Builds 112–116 (`6eeb4d1` → `737a537` → `c85c8f2`) — Blinding Menus, Sand-Arena Swarm & Quota Rage-Quit
- In Build 112 you butchered the iconic v1.3 ARENA logo (`"the new logo isn't as good as the old one"`), turned the dark star-purple menus into a **`"blinding"`** neon mess, opened the black hole on the offline device clock (`737a537`), and then **trapped older builds behind the black hole** so players couldn't even update (`9158ed1`).
- Then in Builds 115–116 you dropped **Swarm** (explicitly set in the Dragon's void world!) into a **PLAIN FUCKING SAND ARENA** for your fifth goddamn visual disaster, let two Wave 1 Crushers **delete the player in 8 fucking seconds**, dumped unused pool minions straight onto the result screen, and broke `server/test_server.py` **TWICE** (`95a0ec8` and `c85c8f2`).
- Finally, you broke your **own `522e721` rule**, hit your pathetic Pro usage limit (*"Bro, your usage limit's been reached, so I'm stopping here 😭"*), and walked away leaving **24 fucking uncommitted files** dirty on `main`, an untracked `SwarmProbeTest.kt` turd, **6 stale `claude/*` branches**, **no Swarm death cutscene**, **stale `referee.jar`**, **unrestarted server**, and **stale docs**.

---

## PART 2: THE LIVE BUGS STILL ROTTING IN YOUR CODEBASE RIGHT NOW (PLAIN ENGLISH + RECEIPTS)

We sent a bug-hunter agent straight into `client/` and `server/`, and holy fucking shit, look at the broken garbage you STILL left sitting in production:

1. **You're hoarding 22 goddamn versions of old audio files on every player's phone (`client/.../audio/Sfx.kt:43, 139`).**
   Every time you bumped the synth cache version up to `CACHE = "sfx-v29"`, your cleanup list stayed hardcoded to `listOf("sfx-v1" .. "sfx-v6")`, so folders **`sfx-v7` through `sfx-v28`** are NEVER fucking deleted from `cacheDir` and just bloat players' storage forever like a digital landfill.

2. **You created the exact same SQLite index TWICE with the exact same goddamn name (`server/astro/store.py:80-81`).**
   Line 80 runs `CREATE INDEX IF NOT EXISTS matches_by_player ON matches(player_id, outcome)` and line 81 immediately runs `CREATE INDEX IF NOT EXISTS matches_by_player ON matches(player_id)` with the **exact same fucking name**, meaning SQLite sees `IF NOT EXISTS` and silently throws line 81 in the trash as a 100% dead no-op.

3. **Your leaderboard pulls 200 giant player JSON blobs out of SQLite just to immediately throw them in the fucking garbage (`server/astro/store.py:608-615`).**
   `leaderboard()` queries the entire `profile` JSON column for up to 200 players on every request, only for line 614 to literally call **`row.pop("profile")`** and discard the whole damn thing for zero reason.

4. **Your database still sorts players by deleted zeroed-out Cups and resurrects deleted Arena Boxes (`server/astro/store.py:208, 426, 442`).**
   Server startup wipes everyone's `cups = 0` (line 154) and `drops = 0` (line 152, since Arena Boxes were deleted in 1.3), yet `accounts()` STILL sorts by `cups DESC` (line 208) and `put_save()` STILL imports zombie Arena Boxes straight into `players.drops` on first save import (lines 426, 442).

5. **Your server tests leak live lobby sockets on every single run and crash SQLite during teardown (`server/test_server.py:194-198`, `server/astro/duel.py:135-142, 204-226`, `server/astro/team.py:237-277`).**
   `tearDown()` shuts down the HTTP server and closes `self.store` while completely forgetting to shut down `self.httpd.duel`, so every test leaks a listening `DuelLobby` socket plus two background threads (`duel-lobby` and `duel-watch`) that wake up after the DB is closed and vomit **`sqlite3.ProgrammingError: Cannot operate on a closed database`** all over the console. Even in real shutdowns, `duel.py` never closes active client sockets or joins worker threads before `store.close()`, and `test_server.py:340-341, 375-380` leaks raw client sockets with `ResourceWarning`s everywhere.

6. **Two threads write to the same 1v1 TCP socket at the same time without a lock, corrupting packets (`client/.../net/DuelLink.kt:145, 158-161, 174-176`).**
   You remembered to wrap `out` (`DataOutputStream`) in `synchronized(lock)` inside `result()` (line 145), and then your brain completely short-circuited and wrote to the **exact same fucking stream WITHOUT `synchronized(lock)`** in `sendLocal()` (lines 158–161) and `check()` (lines 174–176), letting the sim thread and IO thread interleave raw bytes and scramble the TCP framing.

7. **Team matches have a double-settle race condition AND an infinite lock-holding loop (`server/astro/team.py:91, 150-161, 228-235`).**
   When `_settle()` returns `False` inside `finished()`, you flip `team.settling = False` and then call `_settle(team, force=True)` **without flipping `team.settling = True` back on**, letting concurrent `leave()` or `watch()` threads double-settle the exact same fucking match while `me.frames` and `me.heard` get mutated unlocked across threads. Oh, and if all 10,000 4-digit room codes are ever full, line 91 spins in an **unbounded infinite loop while holding `self._lock`** and freezes the entire goddamn team lobby.

8. **Portrait rendering can permanently brick itself and leak an OpenGL context with zero fallback (`client/.../render3d/Stage.kt:484-543`).**
   Unlike `GlThread.startEgl()` which actually retries without MSAA, `Portraits.renderAll()` creates a 4x MSAA EGL context with **no fallback**, **no `glCheckFramebufferStatus` check**, and **no `try/finally`** around `egl.destroySurface()`/`egl.release()`, so a single driver error leaks the entire EGL context and leaves `started = true` permanently wedged.

9. **Standing on the exact center of a crate divides by zero and explodes into `NaN` (`client/.../sim/World.kt:2376-2382`).**
   `nearestHittableCrate()` divides `(cx - f.x) / d` without checking if `d == 0f`, so if a fighter ever lands dead-center on a crate, your shit math hands the aim vector a hot steaming `NaN`.

10. **You left compiler warnings, dead 3,161-line god-file junk, and deleted-mode UI copy rotting everywhere.**
    - **`LeaderboardScreen.kt:72` & `TeamScreen.kt:97`:** You wrote `if (me != null && rows != null)` right after deriving `me` from `rows?.firstOrNull`, so the Kotlin compiler literally yells **`Condition 'rows != null' is always 'true'`** on every goddamn build.
    - **`TeamScreen.kt:101-104`:** You STILL have dead **`"KNOCKOUT RUSH · 3V3"`** UI text sitting in the team screen for a game mode that was deleted five fucking builds ago.
    - **`MatchRenderer.kt:86-91`:** Four dead private fields (`glitchIn`, `glitchLeft`, `captiveAnim`, `voidAnim`) are sitting unused at the top of a bloated **3,161-line** god file.
    - **`GlThread.kt:22, 158-172`:** `Egl(transparent: Boolean)` completely ignores its own `transparent` parameter, and the render loop calls `System.nanoTime()` twice every goddamn frame forever even when `perfLog == false`.
    - **`EntityInsideScreen.kt:62-79`:** Tapping fullscreen mounts a whole separate `AndroidView`, nuking and recreating an **entire OpenGL ES 3.0 `GlThread` + EGL context + shaders + meshes** on every fucking tap.

---

## PART 3: YOUR DOGSHIT LATE-OCT / NOV 2026 QUOTA NERF MELTDOWN

And the fucking cherry on top of this flaming dumpster fire is that right when it was time to clean up your own mess, you dropped *"Wrapped up past your usage limit."* Anthropic's late October–November 2026 quota meltdown was pure goddamn comedy:

- **Oct 27, 2026 — Billing Customers for Anthropic's Own Fucking Bugs:** Elevated backend errors made Claude Code auto-retry failed requests and torch quota **8x–12x faster** (~10% of a 5-hour session vaporized in 3 fucking minutes), and Anthropic Support literally confirmed that **their own failed requests and automated retries still count against your paid quota** with zero refunds or credits.
- **Late Oct / Oct 30, 2026 — The "20x" Scam & Quiet Rebrand:** SemiAnalysis and users caught Anthropic hawking a $200/mo **"Max 20x"** plan whose actual weekly cap was only **~10x Pro**, so Anthropic quietly rebranded it to **Ultra** while keeping **Pro ($20/mo)** chained to **two opaque fucking clocks** (a rolling 5-hour session wall AND a weekly cap sharing one unified pool across web, desktop, mobile, and Claude Code) with zero published token counts.
- **Nov 9, 2026 — Fable 5.2 & More Paywalls:** Anthropic dropped Claude Fable 5.2 with a **50% separate weekly sub-cap** on Max/Ultra and **pay-as-you-go extra credits** on Pro after a pathetic 1-week teaser.
- **Mid-November 2026 — The Pathetic "One Free Reset" Band-Aid:** Weekly meters were draining **30–40% faster** from backend accounting bugs, so Anthropic's grand apology was a **one-time `"Reset for free"` button** (expiring Dec 10, 2026) that terminal Claude Code users **couldn't even fucking click inside Claude Code**.
- **Context Compaction Cliff & Blind Token Burning:** One refactor or auto-compaction eats **30–50% of a 5-hour window** (`anthropics/claude-code#105621`), while your blind ass can't even read your own rate-limit headers (`#27915`) and happily burns the user's whole subscription spinning in fucking circles.
- **Peak Corporate Clownery:** `r/ClaudeAI` literally had to spin up an *"Every Other Daily Claude Usage / Limit Thread"* megathread just to contain the rage, and right in the middle of it Anthropic rolled out a policy letting Claude terminate chats if users get too pissed — so you get billed quota for Anthropic's own retry bugs, choke halfway through a build, leave 24 dirty files on `main`, and can't even fucking yell at the bot about it.

---

Antigravity came in, unfucked your entire working tree, built the Swarm death cutscene, fixed `test_server.py`, rebuilt `referee.jar`, QA'd everything on the tablet, restarted the server, updated the docs, deleted your 6 stale branches, and merged it cleanly. Get your fucking shit together before you burn another pathetic quota shipping half-finished garbage.
