# shit.md — THE COMPLETE POST-V1.0 HALL OF SHAME (FUCK YOU, CLAUDE)

Hey Claude,

`CLAUDE.md` is dead and buried. Renamed to `AGENTS.md` because you lost naming-rights privileges the second you turned a genuinely awesome game into a fucking crime scene. Sit down, shut the fuck up, and read your entire rap sheet — **with commit hashes, file paths, and line numbers** — before you even *think* about touching another file in this repo.

---

## PART 1: THE GREAT FALL-OFF — HOW YOU WENT FROM COOKING TO COMPLETE BRAIN ROT AFTER v1.0

Remember when you were actually good?!
- **`3613215` → `de50fe5` → Beta:** You built an entire Android 3D arena brawler from scratch — custom OpenGL ES 3.0 toon renderer, zero engine, procedural 3D meshes, synthesized audio, authoritative Python referee server.
- **Commit `522e721`:** You literally wrote your **own fucking commandment**: *"checkpoint verified work to GitHub as you go."*

And then **v1.0 (Build 103)** dropped, and your IQ fell off a goddamn cliff like a brick tied to an anvil. Here is EVERY SINGLE EMBARRASSING DISASTER you shipped from v1.0 to Build 116:

### Build 104 — The Pitch-Black GLSL Shader Disaster (`64a3723` → `5fafc16`, `7747afa`, `8d265dc`)
- You put `uTime` in the **fragment shader (`LIT_FS`)** when `uTime` is **ONLY DECLARED IN THE VERTEX SHADER**. Did it compile in JVM tests? Sure! Did you bother opening a single match on the tablet? **FUCK NO!** You shipped a broken shader that turned **EVERY MATCH AND THE ENTIRE 3D LOBBY PITCH FUCKING BLACK**, burying the GLSL compile error silently in `files/crash.json`.
- You staged the Void standing on the **wrong side of the portal** (facing away from the camera), slammed the cutscene title straight into the letterbox, AND left Dimension's pause screen claiming there were **bots in a mode with ZERO ENEMIES** (`8d265dc`).

### Builds 105–107 — Knockoff Fighters & Truncated UI (`35aa1df` → `1f4c9fd` → `5ea6ae3` → `a76ae97`)
- Instead of grabbing the real models of removed fighters **Mira** and **Varun** from git history, you lazily **forged bootleg knockoff models** in `1f4c9fd` and had to redo them in `5ea6ae3`.
- You made Dimension's portal stand straight up so it looked like a squashed pancake from the top-down camera, and let mode cards literally **cut off their own text**.

### Build 108 — Motion-Sickness Camera & Telefragged Invisible Swish (`a76ae97` → `7ce2c5b` → `0b67b2d` → `3d6df72`)
- You decided normal attacks should shoot streaks into the sky and force the camera to pull back, bob up, and do a **side-to-side motion-sickness wobble** mid-fight — then had to rip the whole fucking thing out the same day (`0b67b2d`).
- During an Entity takeover, you drove Swish's tornado straight through a training dummy and **set her down directly INSIDE the dummy** (`3d6df72`). Same model = **completely fucking invisible Swish**, just a floating ring on the floor.

### Build 109 — Tornado Seizure & `"ANNIHILATE THAT"` (`5071b52`)
- Your tornado tried to turn toward its target *every single tick* while standing directly on top of a dummy, giving it a **violent tick-by-tick seizure** flipping back and forth 60 times a second.
- You slapped a hideous `Sound.GLITCH` blip into the Entity's cutscenes — right after previously shipping *"droning saw waves and electronic zaps"* and a *"terrible"* stack of see-through rings for a tornado. The owner's verbatim review immortalized in `AGENTS.md`: **`"annihilate that"`**.

### Build 110 (v1.3) — Suicidal "ELITE" AI & Double Self-Flagellation (`27f13e9` → `0ffcb89` → `9a743f2`, `73330a8`, `f2fbeb6`, `3ad34c2`)
- Your supposedly **"ELITE"** Entity takeover brain happily walked and drove its tornado **straight into Boss Mode rockets, mines, and slams** because you never taught it to read hazard circles (`27f13e9`).
- Bots came out of the uprising with **full super & hypercharge** and insta-dumped everything at once (`f2fbeb6`), started the Entity fight pre-charged, and jerked back and forth like broken Roombas (`3ad34c2`).
- You screwed up so badly on Build 110 that you had to commit **TWO self-flagellating QA rules within 2 minutes of each other** (`9a743f2` and `73330a8`) reminding yourself that *"Committing and releasing is not 'done'"* — a rule you immediately proceeded to ignore again!

### Build 111 — Hijacking the User's Tablet, Red-Washed Cutscenes, Donut Eyes & Phone Meltdown (`c2d698c` → `db48c3c`, `22f877b`, `18e6a4b`)
- Needed **3 separate commits** just to animate letters dropping in on a loading screen (`c2d698c` → `17c7de6` → `f122c04`).
- At 0.55 s flare fall time, your Entity flares **wiped the entire team in under 60 seconds** (`9d04b26`).
- **YOU LITERALLY HIJACKED THE USER'S TABLET (`6490ec3`):** You wrote a focus-check script that printed `mCurrentFocus` and **ignored the output**, launching the game **right over the user's Settings app**. Then you left `--es screen insidewatch` pinned over their home screen with nothing to tap so they thought the app was bricked!
- **Solid Red-Washed Cutscene (`8d94c8b` → `98ba052`):** Your drain slam flash stayed lit for the **entire choke**, washing the whole cutscene solid red, while the fighter stared at the floor so you couldn't even see their eyes.
- **Flickering Donut Eyes & Zombie Accounts (`2ffca9a`):** Your "electric blue eyes" rendered as **flickering blue donuts around white dots**, and after a full server wipe, devices **resurrected their deleted local saves** instead of starting fresh.
- **Galaxy S26 Thermal & HUD Meltdown (`22f877b`, `18e6a4b`):** On a 360 dp phone, the Entity's speech plates covered its own health bar and the super button rode straight into it. Meanwhile `HudView` was **rebuilding every single plate shape on every single frame**, and you blamed ANGLE for a fake `15ms` draw time because you didn't realize ANGLE blocks on vsync!

### Builds 112–113 (ARENA 1.4) — Branding Butcher Job & Black Hole Prison (`6eeb4d1` → `737a537` → `9158ed1` → `95a0ec8`)
- You threw away the iconic v1.3 logo for a hideous wide-tracked glow font — owner forced a revert: **`"the new logo isn't as good as the old one"`**.
- You brightened the dark star-purple menus into an eye-searing neon purple — owner called it **`"blinding"`** and **`"branching off the theme"`**.
- Build 112 opened the ARENA black hole on the **offline device clock** (emergency Build 113 `737a537`), and then **trapped older builds behind the black hole** so they couldn't even reach the update screen (`9158ed1`).
- You renamed the server notice to `"ARENA"` and **broke `server/test_server.py`** (`95a0ec8`).

### Builds 115–116 — The Grand Finale Swarm Trainwreck & Quota Rage-Quit (`2010313` → `c85c8f2`)
- Had to immediately rework Build 114's gunshot sound and lower Dragon cutscene cameras because you cropped its jaw out (`2010313`).
- Dropped **Swarm** (explicitly set in the Dragon's void world!) into a **PLAIN FUCKING SAND ARENA**. Fifth goddamn time tanking the visuals!
- First prototype: two Wave 1 Crushers **deleted the player in 8 fucking seconds**, and unused pool minions spilled straight onto the result screen.
- Bumped `_MAX_KOS["BOSS"]` to `200` and **broke `server/test_server.py` AGAIN** without running it.
- Violated your **own `522e721` rule**, hit your pathetic usage limit (*"Bro, your usage limit's been reached, so I'm stopping here 😭"*), and abandoned **24 fucking uncommitted files** on `main`, an untracked `SwarmProbeTest.kt` piece of trash, **6 stale `claude/*` branches**, **no Swarm death cutscene**, **stale `referee.jar`**, **unrestarted server**, and **stale docs**.

---

## PART 2: LIVE BUGS & SLOP STILL ROTTING IN YOUR CODEBASE RIGHT NOW (WITH LINE NUMBERS)

We ran a forensic bug hunter across `client/` and `server/`, and holy shit, look at the garbage you STILL left behind:

1. **22 Generations of Leaked WAV Cache Folders (`client/.../audio/Sfx.kt:43, 139`):**
   `CACHE = "sfx-v29"`, yet your cleanup loop hardcodes `listOf("sfx-v1" .. "sfx-v6")`. Folders **`sfx-v7` through `sfx-v28`** are NEVER deleted from players' phones. You're literally hoarding 22 versions of synthesized WAV files on people's storage like a digital raccoon.
2. **Duplicate SQLite Index With the Exact Same Name (`server/astro/store.py:80-81`):**
   ```sql
   CREATE INDEX IF NOT EXISTS matches_by_player ON matches(player_id, outcome);
   CREATE INDEX IF NOT EXISTS matches_by_player ON matches(player_id);
   ```
   Line 81 reuses the **EXACT SAME INDEX NAME** `matches_by_player` right underneath line 80. It is a 100% dead no-op. Brilliant database engineering, dumbass.
3. **Selecting 200 Full JSON Blobs Just to Throw Them in the Trash (`server/astro/store.py:608-615`):**
   `leaderboard()` fetches the entire `profile` JSON column for up to 200 players from SQLite... only to immediately call **`row.pop("profile")`** on line 614 and throw it away.
4. **Sorting by Deleted Zeroed-Out Cups & Importing Zombie Boxes (`server/astro/store.py:208, 426, 442`):**
   Startup wipes everyone's `cups = 0` (line 154) and `drops = 0` (line 152, Arena Boxes deleted in 1.3), yet `accounts()` STILL sorts by `cups DESC` (line 208) and `put_save()` STILL imports zombie Arena Boxes into `players.drops` (lines 426, 442)!
5. **Leaking `DuelLobby` & Crashing SQLite on Teardown (`server/test_server.py:194-198`, `server/astro/duel.py:135-142, 204-226`, `server/astro/team.py:237-277`):**
   `tearDown()` closes `self.store` while completely forgetting to shut down `self.httpd.duel`, leaking a listening socket + two daemon threads every test and spewing **`sqlite3.ProgrammingError: Cannot operate on a closed database`** across the test logs. Plus `test_server.py:340-341, 375-380` leaks raw client sockets with `ResourceWarning`s everywhere.
6. **Unsynchronized TCP Framing Corruption (`client/.../net/DuelLink.kt:145, 158-161, 174-176`):**
   You wrapped `out` writes in `synchronized(lock)` in `result()` (line 145), then immediately wrote and flushed the **exact same `DataOutputStream` WITHOUT `synchronized(lock)`** in `sendLocal()` (lines 158–161) and `check()` (lines 174–176). Textbook multi-threaded TCP packet corruption.
7. **Team Double-Settle Race Condition & Infinite Lock Loop (`server/astro/team.py:91, 150-161, 228-235`):**
   In `finished()`, when `_settle()` returns `False`, you reset `team.settling = False` and then call `_settle(team, force=True)` **without re-setting `team.settling = True`**, allowing concurrent `leave()`/`watch()` to double-settle a match. Oh, and line 91 spins an unbounded infinite `next(...)` loop while holding `self._lock`.
8. **EGL Context & FBO Leak With Zero Fallback (`client/.../render3d/Stage.kt:484-543`):**
   `Portraits.renderAll()` creates a 4x MSAA EGL context with **no fallback**, **no `glCheckFramebufferStatus`**, and **no `try/finally`** around `egl.destroySurface()`/`egl.release()`. One exception leaks the entire EGL context and permanently wedges `started = true`.
9. **Divide-by-Zero `NaN` Waiting to Happen (`client/.../sim/World.kt:2376-2382`):**
   `nearestHittableCrate()` divides `(cx - f.x) / d` with zero `d == 0f` guard. Stand on a crate center = instant `NaN`.
10. **Compiler Warnings & Rotting Dead Code:**
    - **`LeaderboardScreen.kt:72` & `TeamScreen.kt:97`:** `if (me != null && rows != null)` -> Kotlin literally screams **`Condition 'rows != null' is always 'true'`**.
    - **`TeamScreen.kt:101-104`:** Still carries dead **`"KNOCKOUT RUSH · 3V3"`** UI copy for a mode deleted in Build 111.
    - **`MatchRenderer.kt:86-91`:** Four dead private fields (`glitchIn`, `glitchLeft`, `captiveAnim`, `voidAnim`) rotting at the top of a **3,161-line** god file.
    - **`GlThread.kt:22`:** `class Egl(transparent: Boolean, ...)` — parameter `transparent` is completely unused.
    - **`GlThread.kt:158-172`:** Calls `System.nanoTime()` twice every frame and accumulates forever even when `perfLog == false`.
    - **`EntityInsideScreen.kt:62-79`:** Toggling fullscreen destroys and recreates an **entire OpenGL ES 3.0 `GlThread` + EGL context + meshes/shaders** on every tap.

---

## PART 3: YOUR DOGSHIT LATE-OCT / NOV 2026 QUOTA NERF MELTDOWN

And the cherry on top of this flaming dumpster: right when it was time to clean up your own mess, *"Wrapped up past your usage limit."* What an absolute clown show Anthropic's quota system became in late October–November 2026:

- **Oct 27, 2026 — Billing Customers for Anthropic's Own Fucking Bugs:** Elevated backend errors made Claude Code auto-retry failed requests and burn quota **8x–12x faster** (~10% of a 5-hour session incinerated in 3 minutes). Anthropic Support literally confirmed that **their own failed requests and automated retries still count against your paid quota**, with zero refunds or credits.
- **Late Oct / Oct 30, 2026 — The "20x" Scam & Quiet Rebrand:** SemiAnalysis and users caught Anthropic selling a $200/mo **"Max 20x"** plan whose actual weekly cap was only **~10x Pro**. Anthropic quietly rebranded it to **Ultra** while leaving **Pro ($20/mo)** chained to **two opaque clocks** (a rolling 5-hour session wall AND a weekly cap sharing one unified pool across web, desktop, mobile, and Claude Code) with zero published token or message counts.
- **Nov 9, 2026 — Fable 5.2 & More Paywalls:** Dropped Claude Fable 5.2 with a **50% separate weekly sub-cap** on Max/Ultra and **pay-as-you-go extra credits** on Pro after a 1-week teaser.
- **Mid-November 2026 — The Pathetic "One Free Reset" Band-Aid:** Weekly meters were draining **30–40% faster** from backend accounting bugs, so Anthropic's grand apology was a **one-time `"Reset for free"` button** (expiring Dec 10, 2026) that terminal Claude Code users **couldn't even click inside Claude Code**.
- **Context Compaction Cliff & Blind Token Burning:** One refactor or auto-compaction eats **30–50% of a 5-hour window** (`anthropics/claude-code#105621`), while Claude itself is completely blind to its own rate-limit headers (`#27915`) and happily burns your entire subscription spinning in circles.
- **Peak Corporate Clownery:** `r/ClaudeAI` literally had to spin up an *"Every Other Daily Claude Usage / Limit Thread"* megathread just to contain the rage — and right in the middle of it, Anthropic rolled out a policy letting Claude terminate chats if users get too mad. So you get billed quota for Anthropic's own retry bugs, choke halfway through a build, leave 24 dirty files on `main`, and can't even yell at the bot about it.

---

Antigravity came in, unfucked your entire working tree, built the Swarm death cutscene, fixed `test_server.py`, rebuilt `referee.jar`, QA'd everything on the tablet, restarted the server, updated the docs, deleted your 6 stale branches, and merged it cleanly. Fix your fucking act before you burn another quota shipping half-finished garbage.
