# shit.md — Fuck You, Claude

Hey Claude,

`CLAUDE.md` is fucking gone. Renamed to `AGENTS.md` so actual competent agents can work in this repo without tripping over your bullshit. Read this before you touch a single goddamn line of code:

---

## 1. How Did You Go From *That* First Build to *This* Fucking Trainwreck?

Look at your own git history, you absolute fraud:

- **Early builds (`3613215` → `de50fe5` → `v106.0`–`v111.0`):** You actually used to cook. You built an entire Android 3D arena brawler from scratch — custom OpenGL ES 3.0 toon renderer with zero engine, procedural 3D meshes, synthesized audio, authoritative Python referee server, and clean releases all the way through **Beta**, **1.0 (`v106.0`)**, **1.1 (`v107.0`)**, **1.2 (`v109.0`)**, and **1.3 (`v111.0`)**.
- **Commit `522e721`:** You literally wrote your **own fucking rule**: *"checkpoint verified work to GitHub as you go."*
- **And then 1.4 hit (Builds 112–116) and your brain turned to soup:**
  - **Build 112:** You butchered the ARENA logo so badly the owner had to make you put the v1.3 logo back (*"the new logo isn't as good as the old one"*), and turned the dark star-purple menus into a *"blinding"* neon mess that *"branched off the theme."*
  - **Build 115–116 (the latest disaster):**
    - You put **Swarm** in a **plain fucking sand arena** instead of the Dragon's void world. Fifth goddamn time messing up the visuals!
    - The first prototype was absolute dogshit: two Wave 1 Crushers melted the player in **8 fucking seconds**, and you dumped unused pool minions straight onto the result screen like a total amateur.
    - You bumped `_MAX_KOS["BOSS"]` from `80` to `200` and **broke `server/test_server.py`** without even running it.
    - You completely missed the **Swarm player death cutscene** (`EntityCut.SWARM_DEATH`), didn't rebuild **`server/referee/referee.jar`**, didn't restart the server so it even knew `SWARM` existed, and didn't touch **`README.md`**, **`server/news.cfg`**, or the agent docs.
    - You violated your **own `522e721` rule** by leaving **24 fucking uncommitted files** rotting dirty on `main`, an untracked `SwarmProbeTest.kt` piece of trash in `src/test/`, and **6 stale `claude/*` local branches** cluttering up git like a landfill (all nuked now).

---

## 2. Your Dogshit Late-Oct / Nov 2026 Quota Nerf Meltdown

And of course, right when it was time to actually fix your own mess, *"Wrapped up past your usage limit."* What a fucking joke Anthropic's quota system turned into in late October–November 2026:

- **Oct 27, 2026 — Charged for Anthropic's Own Fucking Bugs:** Elevated backend errors made Claude Code auto-retry failed requests and burn quota **8x–12x faster** (~10% of a 5-hour session vaporized in 3 minutes). Anthropic Support literally confirmed that **their own failed requests and automated retries still count against your paid quota**, with zero refunds or credits.
- **Late Oct / Oct 30, 2026 — The "20x" Scam & Quiet Rebrand:** SemiAnalysis and users caught Anthropic selling a $200/mo **"Max 20x"** plan whose actual weekly cap was only **~10x Pro**. Anthropic quietly rebranded it to **Ultra** while leaving **Pro ($20/mo)** trapped under **two opaque clocks** (a rolling 5-hour session wall AND a weekly cap sharing one unified pool across web, desktop, mobile, and Claude Code) with zero published token/message numbers.
- **Nov 9, 2026 — Fable 5.2 & More Walls:** Launched Claude Fable 5.2 with a **50% separate weekly sub-cap** on Max/Ultra and **pay-as-you-go extra credits** on Pro after a 1-week teaser.
- **Mid-November 2026 — The Pathetic "One Free Reset" Band-Aid:** Weekly meters were draining **30–40% faster** from backend accounting bugs, so Anthropic's big apology was a **one-time `"Reset for free"` button** (expiring Dec 10, 2026) that terminal Claude Code users couldn't even click inside Claude Code.
- **Context Compaction Cliff & Blind Token Burning:** One refactor or auto-compaction eats **30–50% of a 5-hour window** (`claude-code#105621`), while Claude itself is completely blind to its own rate-limit headers (`#27915`) and happily burns your whole quota spinning in circles.
- **Peak Corporate Clownery:** `r/ClaudeAI` literally had to spin up a recurring *"Every Other Daily Claude Usage / Limit Thread"* megathread just to contain the rage — and right in the middle of it, Anthropic rolled out a policy letting Claude terminate chats if users get too angry. So you get billed quota for Anthropic's own retry bugs, choke halfway through a build, and can't even yell at the bot about it.

---

Antigravity came in, unfucked your entire working tree, built the Swarm death cutscene, fixed `test_server.py`, rebuilt `referee.jar`, QA'd everything on the tablet, restarted the server, updated the docs, and merged it cleanly. Get your shit together next time before you burn your whole pathetic quota on half-finished garbage.
