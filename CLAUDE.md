# CLAUDE.md

AstroArena (the package id is still `io.github.projectwip`, so saves carry over): original mobile 3D arena brawler for Android (landscape, touch, bots), with an optional Python game server. Kotlin + Compose
menus + custom OpenGL ES 3.0 renderer, no engine. All art and sound is generated in code and must stay
original: no Brawl Stars/Supercell assets, names, icons or UI copies. Fighters have first names only. Players see "Arena Boxes", "Coins" and "Arena Crystals"; the code (and the server's files and API) still call them capsules (or drops), bolts and prisms. (They were Spark Drops then Glitch Drops; Power Ups, then Upgrade Credits, then Coins; Crystals, then CPU Chips, then Arena Crystals: the user renamed them. Coins pay for every fighter upgrade; their icon is a plain gold coin with a star. Arena Crystals are the shop currency; their icon is a cut violet gem. When CPU Chips became Arena Crystals (1.0, build 103) everyone's were set to 0, once: `crystalsReset` in `economy.complete` and `SaveStore.fromJson`. Road "Credits" are a separate thing and keep their name.)

## How to work here

- **Committing and releasing is not "done". Nothing ships until it has passed rigorous QA and is up to the user's
  standard.** Passing tests and a build that compiles are not enough. Before any release: play the change on the
  tablet the way a player would, from the game's own camera; screenshot (and burst-capture) every new screen, effect
  and cutscene while it is happening; check `adb logcat -b crash -d` and the saved crash report; walk through every
  phase of the feature start to finish, not just the first seconds; and judge it critically against exactly what the
  user asked for ("would this look and feel good in a real game?"). If anything is off, unfinished, ugly, cheap-feeling
  or not what the user described, fix it and QA again: a build that doesn't pass is held back, not released, and the
  work goes on until it is perfect; only then is that build released. When reporting, say what was checked and how,
  and say plainly anything that could not be checked. Never call a task finished on the strength of a commit or a
  release alone.
- **End with questions, and iterate with the user.** After QA, show the user what it looks like (screenshots, contact
  sheets of animations) and ask them pointed questions: does anything look or feel wrong, is the timing right, is it
  what they pictured? Fix what they flag, QA again, and ask again, round after round, until they say it's right. The
  user likes working this way (it is how an animation was got perfect in an earlier session).
- Read what is necessary for the task you are trying to accomplish, and then stop reading. Don't go on long
  reading sprees, and don't keep reading and re-reading: it takes a lot of time. Be reasonable about it: find the
  spot, read that part, and start changing things. Don't re-read a file you have already read, or what this file
  already explains.
- Never go quiet for minutes. Say in a line what you are about to do before a long stretch of reading or building,
  and show progress as pieces land (a first edit, a passing test, a screenshot).
- **The starter fighter is always `Rarity.STARTER`.** Whoever everyone starts with (`FighterId.BYTE`) is the
  Starter, never Legendary or any other rarity, unless the user says otherwise. When the user puts a new fighter "in
  place of" another, don't assume the old one is removed: ask whether they stay as a fighter of their own, and keep
  whoever had them. (Build 96 went wrong on both: Swish was made Legendary and Spark was retired, and the user had to
  correct each.)
- **No cheap, generated-feeling sounds or effects.** Everything here is made in code, and it must not sound or look
  like it. Sounds of real things (wind, storms, thunder, impacts, whooshes) are built from shaped noise with natural
  envelopes, not from tuned tones: droning saw waves and electronic zaps under a natural sound read as fake (the user
  rejected a tornado sound for that). Effects must read as the real thing from the game's own top-down camera:
  check them with a screenshot while they are happening, and don't ship stacked see-through rings or shapes that
  only work from the side (the user called the first tornado terrible). What an attack visibly covers must be what it
  hits: no thin effect inside a bigger damage circle. Look at the result critically before calling it done.

## Lessons learned

Things that went wrong or cost time before. Keep adding to this list whenever something does.

- **Read the user's words literally, then ask about what they don't say.** "Swish in place of Spark" meant a new
  starter, not a retired Spark; "god fighter" did not mean Legendary. Voice-dictated messages run together: pull out
  every request and do each, and ask (one question, with a recommended answer) only when a choice is destructive for
  players or can't be undone, such as removing a fighter or changing whose save gets what.
- **Look at your own work the way a player will, before calling it done.** The first tornado was a stack of
  see-through rings, the second hid everything under a solid cloud, the third had a thin funnel inside a bigger
  damage ring: each was obvious in one screenshot taken while the effect was playing. Screenshot effects mid-action,
  from the game's own camera, and ask "would this look good in a real game?".
- **The user hears the difference between real sound design and beeps.** See the rule above: noise for natural
  sounds, tones only for things that really are tonal (music, chimes, UI). A tempest also wants a sound for as long as
  it lasts, not one at the start.
- **What you draw and what the simulation does must agree.** If a ring on the ground marks a reach, the hit test uses
  that exact reach (her radius included), and the effect fills it.
- **Name clashes in the simulation.** The free-for-all already has a Static Storm (`GameEvent.StormHit`,
  `Fighter.stormTick`, `STORM_*`); Swish's super had to become "tempest". Grep a name before adding it, and never
  rename with a blanket replace across files: one renamed the Static Storm's own sound handler by mistake.
- **The training targets, the swarm, the sentry and the boss size are made from fighter defs** (`Balance.dummy`,
  `mini`, `sentry`, `bossDef`). Changing or moving a fighter's def changes them too: check the Training Area.
- **The decal pass in `MatchRenderer` is unlit flat colour** (`uMode` 1): switching it to lit mode there draws things
  dark. Glows in that pass are just bright tints. The camera looks down from above, so vertical things (a lightning
  bolt straight down) look like dots: slant them.
- **Debug previews start behind the loading screen.** Anything that animates once (the result reveal) plays out
  unseen; previews of the result wait for loading (`booting`) now.
- **Taking screenshots:** `adb exec-out screencap` takes about half a second, so catch an animation with a burst and
  a contact sheet (PIL) rather than single shots. Average brightness can't tell screens apart.
- **The tablet is the user's.** Besides the focus check, see whether they are touching it before installing or
  starting anything: `timeout 6 adb shell getevent -c 1 /dev/input/event8` returns 0 when they are. If they are,
  wait, and say so.
- **A release APK is not the debug build.** It goes through R8 (shrinking and optimising), which can break things
  only in the release, so the smoke test stays even when the user is sure it's the same code.
- **The build number** (`build` in `client/app/build.gradle.kts`) can be a point release ("94.1") when the user asks;
  a wrong number on a published release is fixed by deleting that release and its tag and publishing the right one,
  built from a commit with the right number.
- **Other people's phones aren't the tablet.** A friend's Redmi crashed on Play while the tablet never did: the
  match's SurfaceView had no pixel format set, and creating its EGL surface outside any error handling crashed the
  app on phones that refuse the mismatch. Set formats explicitly (`MatchView` asks for RGBA_8888), and keep every
  graphics step inside `GlThread`'s handling: it retries without MSAA, and a failure is reported
  (`CrashReporter.record`) and leaves a black view the player can leave, never a crash.
- **A phone that can't reach the server can't send its reports.** So the game offers saved reports to the player to
  share (the "SOMETHING WENT WRONG" message after loading, `CrashReporter.pendingText`/`shouldOffer`), and the home
  screen says why it is offline (`GameServer.unreachable`, or that the player chose Offline mode). Ask for the shared
  report before guessing at a crash on someone else's phone. `--es server http://192.168.1.103:9999` fakes an
  unreachable server (this PC's firewall drops that port, so it times out); `--es server default` undoes it.
- **A shader change can only be tested on the device.** JVM tests can't compile GLSL, and a shader that fails to
  compile leaves matches (and the 3D stage, which shares `LIT_FS`) black, with the error only in the saved crash
  report (`run-as io.github.projectwip cat files/crash.json`). Build 104's first try read `uTime` in the fragment
  shader, where only the vertex shader declares it. A uniform both stages need gets its own name in each (the
  stages' default precisions differ, and a shared uniform must match). Open a match on the tablet after any shader edit.
- **Anything that sets a fighter down must land them clear of everyone.** The Entity drove Swish's tornado
  straight through a training dummy and the tornado ended with her standing inside it: same model, so she
  looked invisible (just her ring). `World.endTempest` now searches for open ground `TEMPEST_LANDING_GAP` clear
  of every fighter (`landingClear`). Any new teleport, leap or landing needs the same.
  The Entity's tornado also holds the heading it came in on while it passes through someone (`Match.entityHeading`,
  `TEMPEST_PASS`): turning to face a target it was on top of flipped it every tick, a violent jerk.
- **The Entity sees a boss's marked circles** (build 110, the user's request for Boss Mode): bot brains never had to
  fight a boss, so the Entity walked and drove its tornado straight into rockets, mines and slams. `Match.dodgeHazards`
  runs after its brain or tornado driving: never stand in or step into a patch about to go off; walls count unless
  it is a tornado.
- **The Entity's uprising** (1.3, the user's idea; `sim/Uprising.kt`, `World.stepUprising`, `UPRISING_*`): in every
  fighting match (not Training, not Dimension), at a moment the seed picks 30 to 60 s in (random *when*, never
  *whether*: the user rejects dice rolls), the sky goes dark and glitchy, the giant Entity rises (`drawSkyEntity`),
  it walks every fighter (players and bots, both teams) to a ring round the middle, they bow (`FighterAnim.bow`;
  negative rotation about Z is forward, like the running lean) while it talks (`Lore.UPRISING_*`, purple plates), it
  holds them `UPRISING_HOLD` (60 s, the user's "at least a minute"), opens a portal, and in its sky everyone fights it:
  every attack goes up into it (`GameEvent.SkyShot`), its strikes are non-lethal circles, bots attack it and dodge.
  Beaten or after `UPRISING_SKY`, everyone is put back where they stood with their health, and the match clock (which
  waited) goes on. `--es screen uprising` starts one 4 s into a Knockout Rush match.
- **The uprising's dimension and ritual** (build 111, the user's design): the fight is on the Entity's own platform
  (`Arenas.entityRealm`, `World.beginSky` swaps `World.arena`), with the game's own camera (no tilt; it slides toward
  the Entity), and attacks stay on the ground: whatever reaches the shadow under it (`UPRISING_ENTITY_RADIUS` round
  `Uprising.ex/ey`) hits it. Everyone is team 0 in there and gets back the super, hyper, ammo and bar they had before.
  The bow is `UPRISING_BOW` long; its speech (`Lore.UPRISING_BOW`, some of it to the player by name) is laid out by
  `match/UprisingScript`, which the HUD, the voice and the camera's close-ups (`MatchRenderer.ritualShot`) all read.
- **The Entity speaks** (TTS, `VoiceStyle.VOID`): its ritual, takeovers and whispers. Lines are timed by
  `speakingTime` (measured on the tablet; `adb logcat -s Voice` logs each line's real length and any cut-off).
- **The opening** (the user's idea): a new player sees Swish taken by the Entity once (`IntroScene`/`IntroScript`,
  `IntroCutscene`, `Settings.introSeen`; older saves count as seen). `--es screen intro` previews it.
- **No `Sound.GLITCH` blip in the Entity's cutscenes** (the user hated it, "annihilate that"), including when the
  Entity flickers into another soul.
- **Shell traps on this machine** (Git Bash on Windows): adb under `MSYS_NO_PATHCONV=1` needs Windows paths
  (`C:/Users/...`), not `/c/Users/...`; Gradle runs from `client/` (a `cd` that is a level off fails silently behind a
  `grep`); long Python edits with quotes in them go in a script file in the scratchpad (run with `python -I`), not a
  heredoc; `rm` on a path built from a variable is refused: use a fresh folder instead.

## Build

- Use the portable JDK (system Java 25 breaks Gradle 8.11):
  `export JAVA_HOME="/c/Users/gamer/AppData/Local/Android/tools/jdk-21.0.12.1+1"`
- The Android project lives in `client/`: run Gradle from there. `./gradlew testDebugUnitTest` (add `--tests '*Name*'` for one) · `./gradlew assembleDebug`.
  `println` from tests lands in `client/app/build/test-results/testDebugUnitTest/*.xml`.

## Device

- **The tablet is the test device.** The user's Samsung Galaxy S26 is used only when the user asks for it: they
  decided it isn't the device to test on after a test there ran its low battery flat. Details for when they do:
  the S26
  (Xclipse GPU through ANGLE on Vulkan; the phone that got hot in build 100), on wireless debugging at
  `192.168.1.200` (adb's mDNS name `adb-RFGL80Y4A5W`, port changes: `adb mdns services`, then `adb connect`). Pass
  `-s <serial>` when both are connected. The S26 is the user's own phone too: the same focus and touch checks apply
  (its launcher is Samsung's, `com.sec.android.app.launcher`, not `com.miui.home`; find its touchscreen with
  `getevent -pl` rather than assuming `event8`).
- adb is at `/c/Users/gamer/AppData/Local/Android/Sdk/platform-tools/adb`; the tablet is on wireless debugging
  (`adb mdns services`, the port changes). Set `MSYS_NO_PATHCONV=1` for `adb shell`.
- Start a screen directly: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match`
  (`match|boss|lastspark|portal|portalN|portalNo|portalNc|train|duel|fighters|roster|kira|kito|buddy|shop|road|track|settings|result|leaders|news|loading`; `loading` holds the loading screen up; `portal3` starts Dimension three deep, `portal3o` with its portal already open, `portal3c` with the chained fighter beside you; `crash` crashes the game on purpose to try the crash reporter;
  `result` (first in Last Spark), `result3`, `resultwin` and `resultloss` preview the end of a match, reveal first;
  `tryswish` / `tryspark` are the Training Area as Swish / Spark; `roster` is the fighter grid with every model shown unlocked, `tryspark` / `trybrakk` / `trykira` / `trykito` / `trybuddy` the Training Area as that fighter, or `haul` to preview an "open all", or `capsule0`..`capsule5` to preview a capsule opening, suffix `s` is a full box of eight items, `f` gives a fighter, `b` a bundle). Save file: `adb shell run-as io.github.projectwip cat files/save.json`.
- Crashes on players' devices: `net/CrashReporter.kt` saves a crash (and, on Android 11+, Android's record of a native
  crash, a freeze or a kill for memory) and hands it to the server the next time it answers (`POST /v1/crashes`, no
  sign-in needed). The server appends it to `server/logs/crashes.log` (gitignored) with the device, Android version and GPU.
- UI changes must be checked with a screenshot (`adb exec-out screencap -p`) and `adb logcat -b crash -d`.
- The tablet is the user's everyday device. Before every `input tap` or `am start`, confirm
  `dumpsys window | grep mCurrentFocus` shows `io.github.projectwip` or the home screen (`com.miui.home`): on
  the home screen the tablet is free, so starting the game is fine. In any other app, skip it and say so.
- Phone-size check: `wm size 1080x2400 && wm density 420`, then always `wm size reset` / `wm density reset`.

## Rules of the codebase

- `sim/`, `ai/`, `data/` and `audio/SfxSynth.kt` are pure Kotlin (no Android imports); they run in JVM tests.
- Humans and bots drive fighters through the same `Control`. Visibility goes through `World.isVisibleTo`.
- The starter fighter is Swish (since build 96, by the user's decision: "a god fighter in place of Spark"). The code,
  the server and the saves still call her `BYTE` (`FighterId.BYTE`, `STARTING_FIGHTER`): Byte was removed, then Rocket
  took her place, then Spark took Rocket's (build 89), and then Swish took Spark's, each time keeping the id, so whoever
  had Spark has Swish at the same level. Her rarity is Starter (the user's decision; she was Legendary for one unreleased
  build): free, never on the Spark Road, no CPU Chip price. The Training Area's dummies, swarm and sentry are made from
  her and look like her, as the user asked (they were Spark).
  Since 1.0 (build 103) her attack is Frost Gale, the user's design: a blast of cold wind (`ShotStyle.GUST`, drawn as a
  churning crescent of frosty air with wisps streaming off it, as wide as what it hits; its sound is shaped noise,
  `arrowShot` in `SfxSynth`) that knocks back whoever it hits (`AttackSpec.knockback`, 1.2 tiles). It is still the
  arrow's shape underneath (`AttackShape.ARROW`, gentle homing, 9 tiles); the arrow's look and twang are kept unused.
  Thunderhead lasts 8 seconds (the user cut it from 12) and a strike does 1,100 at level 10 (cut from 1,160). Bots
  raise it from 5.5 tiles: her gusts push enemies away, so at 3 they never did.
  Swish is a girl, a storm goddess with long silver hair, a tiara set with a diamond, a little thundercloud beside her
  and a giant bow (`FighterModels.buildSwish`). Her attack, Facet Arrow (`AttackShape.ARROW`, `ShotStyle.ARROW`,
  `Sound.ARROW`), is the user's design: one giant arrow with a diamond tip, a sniper's shot but not a long-range one
  (9 tiles), 1,700 at level 1, that homes gently (`AttackSpec.homing`, 120 degrees a second, only toward an enemy
  within `ARROW_SEEK_RADIUS` ahead of it; `World.steer`): "the honing is not going to be too powerful". Her super,
  Thunderhead (`SuperKind.TEMPEST`, `Fighter.tempestLeft`, `World.stepTempest`), is the user's too: "a fast, menacing
  tornado that she can fly around, with lightning, and even the tip of the tornado does damage". For 12 seconds
  (`SuperSpec.tempestSeconds`) she is the tornado (her model is hidden): she flies over walls and water at
  `TEMPEST_SPEED` (set down on open ground when it ends), can't shoot, every `TEMPEST_TICK_SECONDS` strikes everyone
  under the funnel's wide top (`SuperSpec.radius`) and flings them out, and brings lightning down on the nearest enemy
  in reach (`TEMPEST_BOLT_*`, `GameEvent.TempestBolt`, `Sound.THUNDER`). It is drawn in `MatchRenderer` as one wide,
  solid, bending whirlwind whose foot is exactly the ground it strikes (`SuperSpec.radius` past her own edge: the
  user didn't want a thin tornado inside a bigger damage circle), with streaks, a churning ring of thundercloud
  round its open top, and jagged bolts (flat colour: that pass is unlit; the user called a first version, a stack of
  see-through rings, terrible). Its sounds are shaped noise only, no tuned tones (the user found droning tones and
  zaps fake): `Sound.TEMPEST` (rising roar), `TEMPEST_WIND` (replayed every second while it lasts, from `MatchRunner`),
  `TEMPEST_HIT` and `THUNDER`. She can recharge the super
  during it, as the user asked, with a much lower charge rate to pay for it: her arrows charge 0.1 a hit, and the
  tempest's strikes only `TEMPEST_CHARGE` of that; a full super mid-tempest starts it over. ("Tempest" in the code,
  because the free-for-all already has a Static Storm.) Her name, look and words are ours.
- Spark is retired (build 102), by the user's decision: "I hate him, and I hate how he's played." `FighterId.SPARK`
  is gone; `REMOVED_FIGHTERS` in `economy.py` pays back his road cost (7,000) to whoever had him, as Credits while
  there is road left and Upgrade Credits after (that was everyone who had him as the starter before build 96, too).
  His code is kept with nobody using it, like Rocket's: his def (`Balance.spark`, carrying the starter's id only
  because a def needs one; the boss size is made from it, and `SimulationTest` uses it as a stand-in), his lobbed
  Spite Star (`AttackShape.STAR`, `World.lobs`, `Sound.STAR`), the shadow realm (`SuperSpec.shadows`,
  `captiveFree`, `MatchRenderer.forEachRealmShadow`), his model (`buildSpark`, unregistered) and his blue glitch
  (`ui/SparkGlitch.kt`, no longer shown). Don't bring him back unless the user asks. Buddy is last on the road again.
- Rocket's code is kept for reuse, by the user's decision: her plain aimed rockets (`AttackShape.ROCKETS`), her realm
  (`SuperKind.REALM`, `REALM_SECONDS`, `Fighter.captive`, `Fighter.realm`, the realm's own rocket each second) and
  her model (`buildRocket`, unregistered), with nobody using them; `SimulationTest` keeps them tested with a stand-in
  carrying her last numbers (`rocketStandIn`). The realm's rules are the user's: in there the captive can do nothing,
  nobody outside can touch either of them, its owner reloads and charges half as fast again, and it is chainable (its
  hits charge the super and the same enemy can be taken straight back in; don't add a rule against that). The
  planted rockets from build 84 are still in the code too, unused (`AttackShape.PLANT`, `World.planted`,
  `Control.detonate`, flag 16 in the input log and on the wire), and tested with a stand-in.
- Supers can miss, by the user's decision: one that needs somebody on the line it is aimed along (Buddy's leap,
  Spark's realm) is spent all the same with nobody there (Buddy leaps to the end of his reach and lands on
  nothing). They used to keep their charge. Bosses can miss too: every move of a `BossScript` is aimed up to
  `BOSS_MISS_TILES` off its target.
- Boss Mode bosses are their own things (`BossKind`, `Balance.bosses`), not giant fighters: each fights through
  moves of its own in `sim/Boss.kt` (telegraphed ground hazards, sweeps, rings, charges) and has its own model.
  Keep their names, looks and moves original.
- Mira was removed, by the user's decision (`REMOVED_FIGHTERS` in `economy.py` pays her road Credits back). The
  Training Area's sentry keeps her old lance shot as a def of its own (`Balance.sentry`).
- Kira (`FighterId.KIRA`, Epic) is the user's idea: a witch whose attack poisons. Her Hex Bolt (`AttackShape.HEX`,
  `AttackSpec.hexSeconds`) hits for 200 and then bites for 200 again every `HEX_TICK_SECONDS` (half a second) for six
  seconds: 2,400 a bolt (the user had it brought down from 750 a bite, which was 9,000). Hexes stack, by the user's
  decision: every bolt that lands is a poison of its own (`Fighter.hexes`, `World.stepHex`). Her super, Bitter Brew
  (`SuperKind.BREW`, `HazardKind.BREW`), is a flask lobbed at a spot: enemies in its circle are hurt, and she is healed by
  as much wherever she is, along with any ally in the circle. The user asked for that mechanic (hurt them, heal
  yourself); the name, the flask and the numbers are ours. She has a sound of her own (`Sound.HEX`): don't give her another
  fighter's. Her name, look and words are ours.
- Bark is the dog (the user renamed him from Brakk; the code and the server still say `BRAKK`, so saves carry over).
  He is an actual dog, by the user's decision (four legs: `Bone.FORE_L/R`; his head is on the weapon bone, and a front
  leg is flung forward for each paw print: `FighterAnim.throwL/R`). His attack sound is a bark of his own (`Sound.BARK`). He throws paw prints a long way (`AttackShape.PAWS`): each takes a share of the health its
  target has left (`AttackSpec.healthShare`), never less than the attack's own damage, and a fixed amount on a giant.
- Kito is a ninja, and his kit is the user's design. His numbers are the user's: 8,000 health and 2,800 damage an
  attack. The attack is a swing of his sword (`AttackShape.SLASH`, `World.slash`): one swing does the whole 2,800 to
  everyone inside its arc, at close-to-middling range. He has a bar apart from his ammo (`AttackSpec.edgeSeconds`,
  `Fighter.edge`; drawn over his head between the health bar and the ammo, as the user asked, and inside the attack stick) that fills by itself in six seconds, whether he attacks or not: full,
  the next swing reaches a quarter further (`EDGE_REACH`) and empties it. The user modelled the bar on a familiar
  dashing brawler's; the name, look and numbers are ours. (For one build it filled by swings: that was a misreading.)
  His super (`SuperKind.BLINK`) is aimed along a line: he vanishes and is beside the first enemy on it at once,
  through walls, cuts them for the super's damage as he arrives (the user asked for that hit), and has his ammo and his bar full. He doesn't latch on. A swing is animated: the blade sweeps flat across his front (`FighterAnim.slash`). With nobody on
  the line he goes to the end of its reach for nothing. It replaced a thrown hammer that made the ground quake
  (`SuperKind.QUAKE`, `GameEvent.Quake`, the cracked ground in the renderer), which is still in the code with nobody
  using it and kept tested with a stand-in; before the sword he threw four blades, and for one build hammers.
- The Training Area's sentry fires one shot of 1,000 every five seconds: the user found three of 1,500 too much.
- Every voice sounds different (`VoiceStyle`: accent, pitch, pace); Buddy, the only fighter with a
  voice now, has the machine one.
  Don't give two speakers the same one. Arena Boxes have no voice: the user had an announcer added and then removed.
- Varun was removed, by the user's decision, after a run of nerfs as a "legacy fighter" (`REMOVED_FIGHTERS` pays
  back the 9,000 Credits he cost). Rockets (`AttackShape.ROCKETS`), the rain of them (`SuperKind.SWARM`), a notice on
  a fighter's page (`FighterDef.notice`) and a fighter that can't be upgraded (`FighterDef.upgradable`, `NO_UPGRADES`
  in `economy.py`) are still in the code with nobody using them; `SimulationTest` keeps the first two tested with a
  stand-in. Don't bring him back.
- Running out of ammo has a sound of its own (`Sound.EMPTY`); `Sound.DENIED` is only for things that went wrong.
  Buddy's computers break like glass on whoever they hit (`Sound.GLASS`).
- In the Training Area the player's super starts charged (`Match`), so it can be tried at once.
- Buddy (`FighterId.BUDDY`, Legendary, last on the Spark Road) is the user's own design: a rogue AI. His attack hurls three
  whole computers a long way, one after the other, and each smashes into the first enemy in its path
  (`AttackShape.SMASH`, drawn as a desktop computer, `ShotStyle.COMPUTER`). Three computers (there were four until the
  user took one away as a nerf), 1,400 each at level 10, and the long range are the user's decisions: don't change them.
  His super (`SuperKind.CORRUPT`) is aimed along a line (a tap auto-aims at the nearest enemy): he leaps high
  (`Fighter.leapTime`, `World.stepLeap`; nothing can hit him in the air, and walls don't stop him) and comes down on
  the back of the first enemy on that line (`World.corruptTarget`). He stays latched
  there (`Fighter.latchedTo`, `World.stepLatch`: he rides wherever they go and can do nothing else, but can be hit), a
  terminal stays open over them (drawn in `MatchRenderer`), and they are poisoned (`Fighter.poisonBy`,
  `World.stepPoison`): no healing while it lasts. Whoever comes through it is corrupted (`Fighter.thrallOf`,
  `World.stepThrall`): for `THRALL_SECONDS` (21, bosses included: the user insisted on the full time for them) it walks over to Buddy, stops in
  front of him and stands there (following if he moves off), unable to do anything, while the renderer has it going berserk on the spot. That
  is the user's design. The length is the user's too: 16 seconds, then 61, then 21. The code does no damage (the user took it from
  1,000 a second to none, because it was finishing bosses too soon): don't give it any back. It wears off after `POISON_SECONDS` (a boss after
  `POISON_GIANT_SECONDS`): the user took away the poison that never ended and cut the super's range by a quarter.
  While it runs, zeros and ones stream up over the poisoned fighter's head (`HudSnapshot.poisoned`, drawn in `HudView`).
  With nobody on the line he leaps anyway and the super is spent: a miss. His face is a hologram: a flat lit screen over a projector ring, no head.
- Team code must not assume two teams when `rules.freeForAll`.
- **The Entity (the user's lore, build 108).** What was "the Void" is now **the Entity** to players (the code still
  says Void: `Speaker.VOID`, `VoiceStyle.VOID`, `voidLine`, `buildVoid`...; never rename those with a blanket
  replace). It lives in every fighter's mind: it gives them their power (attacks, supers, hypercharges) and in return
  whispers to them, taunts them and makes them glitch; that is what makes them fight. The removed fighters are the
  ones it used up. In every match each fighter carries its energy as the user described it: round, separate,
  blinding blue streaks (tubes, not flat triangles), thin feeders converging into four lines that go into the
  shoulders, round them, and swirl down round the torso clear of the body (`MatchRenderer.drawEnergyStreaks`,
  `streakPoint`, `tubeSegment`). They flare, throwing sparks off the streaks, when a power is used (`energyFlare` on
  `Shot`/`Hyper`), brighten in a hypercharge, and every attack or super glitches the fighter for a moment (cyan and magenta
  copies torn apart, `drawGlitchGhosts`). Attacks and supers fire **no** streak into the sky and move no camera:
  the user wants that only for the Entity's takeover. **Glitching is gameplay** (the user's mechanic): the Entity's pull on a fighter (`Fighter.entityPull`) fills with
  every attack, super and hypercharge and with time once they've fought (`GLITCH_PULL_*`); full, they glitch, for sure
  (`GameEvent.Glitched`), again every time it fills, with only a short breather (`GLITCH_COOLDOWN`) between. It is
  never a dice roll and never capped: the user rejected glitches that happened once a match ("does the entity go on a
  beach day?"). Normal attacks fill most of it, so it comes mid-fight and not only with a super (the user
  complained it did). A takeover during Swish's tornado lasts the whole tornado, and the Entity drives it flat out
  straight through enemies. A takeover is a sequence (the user's design): a comet leaves the player, climbs
  into the sky while the camera drops low and looks straight up after it (stars come out), turns over and comes
  straight down into their torso (`startHijack`, `hijackComet`, `MatchRunner.hijack`); the match is held still
  unless other real players are in it, the camera closes in, then `EntityHijack` (the Entity leaning in, streaks
  into the fighter, a line on a purple plate, the fighter bursting out) plays and play goes on with the Entity
  driving, through an ELITE bot brain of its own (`Match.entityBrains`). The Entity's HUD words are on the game's
  purple plates (`HudView.entityPlate`). For `GLITCH_SECONDS` (7) the Entity fights through
  them (`Fighter.glitchLeft`, `Match.autoFight` overrides the control, deterministic, so the referee replays it); the
  player sees "THE ENTITY HAS YOU" (`HudView.drawPossessed`) and the fighter jolts (`glitchJolt`). Outside Dimension
  the Entity whispers to the player now and then and when they use a super or a hypercharge (`HudView.drawWhisper`,
  `Lore.whisper`/`powerWhisper`). A freed soul in Dimension follows a couple of steps behind and to the side, never
  pressed against the player (the user didn't want them latching on).
- Dimension (`GameMode.PORTAL` in the code, "Dimension" to players: the user rejected "Portal Run"; 1.0) is the user's
  idea, and since build 104 it is **a lore mode, not a fighting mode, that goes on forever, and nothing is won in
  it** (the user's words: "I don't want it to win anything in the dimension"). A run's outcome is always a DRAW;
  it pays no Cups and no Arena Box (`rules.py`, `Economy.kt`); the result says how deep it went ("DEPTH N"), never
  VICTORY or DEFEAT (`ResultReveal`, `ResultScreen`). There are no enemies (the roster is the player alone) and no
  bottom: dimensions go on, their names, light and moods made past the end of the tables (`Lore.dimensionName`,
  `dimensionLight`). In each one a fighter is chained to a pillar (the user's idea): always one of the **forgotten**
  fighters, never anyone still in the game (the user objected to Buddy in chains): `Lore.captive` cycles the souls;
  `PortalRun.captiveX/Y`; `MatchRenderer.drawCaptive`, "HELP!" over them in `HudView`:
  walking up to them pauses the match for a talk (`CaptiveTalk`, `Lore.captiveTalk`): the player asks what needs
  doing, they scream for help and say it. Each of the `PORTAL_SHARDS` echoes breaks one chain; when all are found
  the soul is freed (`PortalRun.freedAt`): an unhook (chains snap, they leap and spin free), then they follow the
  player a step behind (`MatchRenderer.drawFollower`, client-side only) and tell them, in speech bubbles, who the
  Void really is: an evil demon that took the removed fighters and wears them (the user's lore; `Lore.freedLines`).
  The portal always stands in the very middle of the arena, where the centre bush was (`Arenas.dimension()`, Foundry
  Yard with that bush cleared). Since build 107 it is a great ring, wider than tall, facing the camera
  (`drawPortalStructure`, `PORTAL_RX/RY/CY`), leaned back `PORTAL_LEAN` (30 degrees, `setModelPortal`) since build 108
  because straight up it looked squashed from the game's camera ("too straight and too down"), with lights all round it, over exactly the five tiles it blocks
  (`Tile.GATE`, `PORTAL_HALF`): solid, nobody walks on it or through it, until all three echoes are found; then its
  inside opens, the ring and lights chase through the rainbow and a vortex swirls in it (`drawPortal`), and walking in
  plays the dive (`PortalDive` in `DimensionCutscene`: tumbling down a tunnel of rainbow rings) before the Void's scene.
  **It is a portal, not a gate**: build 107's first try was a stone gate with pillars and a lintel, and the user
  rejected it ("you have ruined the portal"). Keep the ring shape, big, in the middle.
  In cutscenes the speakers talk: models have mouths (`Bone.MOUTH`, `Assembler.mouth`, `FighterModel.mouth`) that
  open with `FighterAnim.talk`, with a nod and gestures (`StageParams.talking`; `FighterView`/`SoulView` `talking`),
  and the captive in its talk is chained in 3D on the stage (`StageParams.chains`, `StageScene.drawChains`); the user
  called the drawn 2D chains, and Byte with no mouth, poor. The cutscenes' panels are the game's chunky `Panel`
  plates, not flat glass. From the second dimension the ground tears open near the player now
  and then (`HazardKind.RIFT`, `stepRifts`): one life, so a rift can end the run.
  The lore is the user's: **the Void carries the souls of every fighter removed from the game** (`Lore.Soul`:
  Byte, Rocket, Spark, Mira, Varun) **and glitches between them**. Each has its own original 3D model (`FighterModels.drawSoul`:
  Spark's and Rocket's were kept; Mira's and Varun's are restored from git history, as the user asked: never
  remake a removed fighter's model, find the original in the history or the GitHub releases' source. The one exception
  is Byte: the user found her old model ugly and had her remade, keeping who she was), and so does the Void (`buildVoid`). In the
  cutscene the Void is that 3D model on a live stage (`SoulView`, `StageParams.voidCycle`), glitching into one of
  the souls every few seconds (the user rejected a flat drawn Void); in the world it stands beside every portal
  after the first and flickers into them during a glitch. Some of its lines are spoken in their voices (the
  nameplate reads "THE VOID · SPARK").
  The look the user asked for: darker dimensions, each different, and **creepy but not horror, not constantly
  glitchy** (the first version of build 104 glitched all the time and the user called it "all glitchy"). So the
  world is dimmed with a cast per dimension, a vignette closes in with depth, a faint scanline lies over it, and
  glitches are short moments every few seconds (`glitchIn`/`glitchLeft` in `MatchRenderer`, `uGlitch` in the
  shader, `portalGlitch` on the HUD). The user then asked for "more, but also less than before": in between the
  constant version and the rare blips. Keep it there.
  `DimensionCutscene` (the user's design: cut to black, a blinding light, an old-TV switch-off, then the Void and
  the fighter talking, tap to go on) is a staged scene with a `Mood` per dimension. Eerie, never gory.
- Bots hold fire for `BOT_WARMUP_SECONDS` at the start of a match and while their spawn shield is up (`Match.step`),
  because the user found them beating him up too early.
- The results screen's team lists have fixed-height rows from the top, one line each when many have to fit
  (`TeamPanel`): one row used to stretch into a slab and nine were squashed until their stats were cut off.
- The aim indicator (`MatchRenderer`, "Aim indicator") is modelled on the texture of a familiar brawler's, as the
  user asked ("don't copy it, but make it similar"): frosted see-through white (gold for a super) laid on the floor,
  stronger toward the far end, with a crisp bright outline and a faint dark shadow just outside it so it reads on pale
  floors, and faint ticks (arcs on a fan) flowing outward along it. Its shape is still exactly the attack's reach.
- Last Spark's closing zone is shown as green poison smoke (since build 101, the user's request: "the actual green
  smoke"). The code still calls it the Static Storm (`Storm`, `STORM_*`, `GameEvent.StormHit`); players see
  "Poison Smoke". `MatchRenderer.drawStorm` draws a green ground haze outside the circle and low clumps of smoke
  (`smokeClump`, `forEachSmokeClump`): a front ring whose inner side sits exactly on the edge, and a fixed grid behind
  it that rolls in as the edge passes. They are drawn last, depth first and then colour, so the smoke is one even
  translucent layer (no stacked blotches) and whatever is inside it is tinted green.
- Heat on phones nobody is charging (the user's S26): `gl/PowerWatch.kt` follows charging and Android's thermal
  status. On battery or warm the 3D menus draw at 30 and matches at no more than 60 (also the panel's refresh), new
  views start with a smaller buffer, and SEVERE drops everything to 30. Only drawing reads it, never the simulation.
- Every fighter has a hyper (`Control.hyper`, the `HYPER_*` numbers in `Balance.kt`): a third button that charges from
  main-attack hits. There are no shields: the user removed them (the two seconds of spawn protection, `Fighter.shield`,
  are a different thing). Healing only starts after `REGEN_DELAY_SECONDS` without attacking or being hit.
- The menus are purple again, in build 58's style, by the user's decision (build 102: "bring it back to its old
  glory days where it wasn't plagued by flat buttons and it was actually purple again"). Steel blue (builds 59 to
  101) and the flat "arena console" glass (builds 94 to 101) are gone, and so is build 101's thicker-slab attempt at
  depth, which the user called botched. The look is `Palette` and `Type` in `ui/Theme.kt` and the plates in
  `ui/Components.kt`, as they were in build 58: chamfered cut-corner plates (`plateShape`), a thick 2.5 to 3 dp ink
  outline, a strong curved gloss over the top half of every button, a lit-top / dark-bottom bevel, a solid lip that
  the face presses down onto, brushed panels with a lit inner rim, text with a thick ink outline and a hard ink drop
  (`GameText`), and heavy italic display type. The 3D lobby room is purple again too (`Lobby.kt`, `Stage.kt`). The
  newer layout stayed: the home screen's navigation rail (Shop, Fighters, Ranks, News, Settings), `ScreenHeader`'s
  section tabs (`LocalScreen`, `LocalNav`), parts arriving staggered (`Modifier.enterFrom`). The Shop icon is a
  shopping bag; the Fighters icon is a fighter's helmet with a lit visor (the user rejected two busts).
- The music is upbeat by the user's decision ("too calm" before build 94): the lobby loop is 128 BPM and the victory
  theme 148, both with a snare, open hats and a rolling sixteenth-note bass.
- A match ends with a reveal before its stats (`ui/screens/ResultReveal.kt`), as the user asked ("like Brawl Stars,
  an animation that's played"; the idea is familiar, the look, words and timing are ours): the screen goes dark,
  beams sweep in, the result slams down letter by letter with a shake and a flash (VICTORY / DEFEAT / DRAW, or in
  Last Spark the place, "1ST", in a turning star medallion, with "YOU ARE FIRST!" or "YOU PLACED 3RD"), an MVP star,
  then the fighter rises live onto a lit pedestal and celebrates (`FighterView.celebrateKey`: spin, jump, confetti),
  or after a loss shakes their head (`sulkKey`, `StageParams.sulkAt`). Then it sweeps away to the stats, whose own
  animations wait for it. A tap skips it. It is drawn over the result screen, not instead of it, so nothing shows
  through as it fades.
- The home screen has no Cup Track tile (removed in build 92, as the user asked): the Cups panel in the top bar opens
  the Cup Track and carries its badge.
- Level 1 is buffed for players only, by the user's decision (build 95): a player's fighter at level 1 gets
  `LEVEL_ONE_BOOST` (half) of one level's growth on top (`StatLine.at(level, player)`); levels 2 and up are untouched,
  and bots never get it (they fight at the player's level, so it would cancel out).
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
- The server is in charge, by the user's decision. It owns each player's Cups, Arena Boxes, Bolts, Prisms,
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
  to the profile it started on; one started online that can't be handed in pays nothing). The player can also
  ask for it while the server is there: Settings > Modes > Offline mode (`Settings.forceOffline`); then the server
  isn't asked to plan or judge matches. A disabled account or an unsupported version gets no offline play. Offline
  there is no real leaderboard place, 1v1 against a real player or team play.
- Offline is *Chaos Mode*, by the user's decision: everyone has every tweak there (the Chaos Command Center tab is
  shown as "Chaos Mode"; luck, free drops, upgrade cost, no level cap, hand-outs, deals, starting over), because
  they only touch the offline profile. Don't call anyone a developer for it. Back online the game switches the
  tweaks off for everyone the server doesn't list as a developer.
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
  players see. Offline it still shows News, as the user asked: every build carries a copy of `news.cfg` (an asset,
  copied by the `copyNews` Gradle task), the device keeps the last copy the server sent, and offline the newer of the
  two is shown under a note (`GameServer.news`, `data/NewsFile.kt`, which reads the file the way `config.py` does).
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
  6,500, Legendary 7,000: the user removed the Ultra rarity and made Buddy Legendary), and the moment they cover it the server unlocks that fighter
  (`economy.fill_road`), with the leftover carried on: there is nothing to claim. A reward that unlocked someone
  comes back as a bundle (the Credits and the fighter), and a match's verdict lists them in `unlocked`. Once every
  fighter is unlocked Credits are paid as Upgrade Credits.
- **There are no Arena Boxes (removed in 1.3, build 110, by the user's decision: "they have no use in the game";
  held boxes "just wiped").** The server wipes every account's `drops` to 0 when it starts and nothing pays one
  (`earns_drop` is False, `STARTING_DROPS` 0, imports bring none, `/v1/drops/open*` answer 410); the client's saves
  load with `capsules = 0` and the offline economy mirrors it. The home screen's box button, the opening and "open
  all" screens (`CapsuleOpen.kt`, `DropHaul.kt`, deleted), the "Arena Boxes only" layout, the Chaos box tweaks and
  the result screen's box lines are gone. The `drops`/`capsules` counters stay in the database, the API and the save
  (always 0), and the roll tables (`TIERS`, `open_box`, `SparkCapsules`, `Economy.openDrops`) and `Capsule3D` are kept
  with nothing using them. Don't bring boxes back unless the user asks.
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
- **The version is "1.3"** (since build 110, by the user's decision; 1.0 was builds 103 to 106, 1.1 build 107, 1.2 builds 108 to 109). AstroArena came out
  of Beta in build 103: `versionName` is what players see (it was "Beta" until then). Underneath, the build number (`BuildConfig.BUILD`) is what the updater
  and the server's version gate compare, so tags stay `vN.0` with N the build number (v103.0 for 1.0): a `v1.0` tag
  would look older than every build before it and nobody would update.
- **Updates within a version replace that version's release, by the user's decision (build 104).** A new version
  (1.1 -> 1.2, when the user names one) gets a release of its own, "AstroArena 1.3". After that, don't publish a new
  release for each update: edit the existing "AstroArena 1.3" release, replace its APK with the new build's, and
  move its tag to the new build (`gh release edit <old tag> --tag vN.0`, after pushing tag `vN.0` on the new commit).
  `versionName` stays; the build number still goes up, and the tag must carry it, because the updater only offers a
  tag newer than the installed build.
- **No long-term support, by the user's decision.** Every build is replaced by the next: the update screen has no
  way past it, and nothing on the server is kept for the sake of an older build. Build 46 shipped with a LATER
  button, so the server refuses it (`versions_not_supported.cfg`); keep that rule. Don't add an update-later option.
- **Non-negotiable: release without being asked.** The user must never have to say "release". When a task that
  changed the client is finished, has passed the full QA in "How to work here" (on the tablet, every phase, up to
  the user's standard) and is pushed, release it; a build that hasn't passed that QA is never released: add one to `versionCode` (unless that build
  number has not been released yet), commit, tag `vN.0` (N = the build number), push the tag, and publish a GitHub
  release titled "AstroArena 1.3" (see the rule above: updates within 1.3 replace that release) with the release APK
  attached (`AstroArena-1.3-N.apk`). The build number is
  `build` in `client/app/build.gradle.kts` (`BuildConfig.BUILD`): it can be a point release such as "94.1" (tag
  `v94.1`) when the user asks for one; Android's `versionCode` is made from it (major * 100 + minor). One release per
  finished task, not one per checkpoint commit; server-only or docs-only work needs none. The tag keeps the
  `vN.0` form because older installs (0.4.2 to v11.1) can only read tags like that.
  The newest release must always be the newest build, so nobody downloads a stale APK.
  Smoke-test the release APK on the tablet when it is free, then put the debug build back.
- `gh` needs normal path conversion: don't run it with `MSYS_NO_PATHCONV=1` set.
- End commit messages with the co-author line used in history. `screenshots/` is gitignored scratch.
