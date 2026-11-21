# ARENA

*(Formerly AstroArena; renamed in 1.4, build 112.)*

A landscape **3D boss-fight game for Android phones and tablets**. Something called the Entity lives in every
fighter's mind and lends them their power, and in return it wants control. Team up (with friends or bots) and fight
it in its own world, where it gets more dangerous every time you face it. Launch it, press PLAY, and the Entity is
waiting.

**Repository:** https://github.com/TerminalDev-1/AstroArena

> Every build replaces the last one: the game checks GitHub for a newer release when it starts and asks you to
> update. Rules, balance and saves may change between versions.

**ARENA 1.4** is the rename: a new icon (black, with two white eyes looking to the right), menus in dark purple with
stars shining on every plate, and the Entity whispering cryptic warnings, silently, in the menu. The first build
of 1.4 opened on a white black hole and the ARENA title, held shut by the server until the reveal
(`[reveal]` in `server/game.cfg`), then opened onto the game.

Everything in the game is original: the 3D fighters, the Entity and its world (all meshes generated in code),
icons, music and sounds (synthesised at runtime), cutscenes, UI and rules. No third-party game assets are used.

## The Entity

| | |
|---|---|
| **Its ritual** | The sky goes dark, the Entity rises, and it calls every fighter to it. You walk to its circle, kneel, and it speaks to you (and to you, the one holding the screen). Then it opens a portal into its world. You can skip it |
| **Its world** | A platform in the void, where your team of three (bots fill any empty place) fights it until it falls. Your attacks hit the shadow beneath it |
| **Its moves** | A giant sword it spins like a tornado, a dash down a marked lane, marked strikes, rings, sweeps, spokes, soul rain, its hand slamming down, shockwaves, and a chase of strikes on your heels. Nothing it fires lands past the edge of its platform |
| **Its flares** | They rise out of it and hunt you down. A **blue** flare pulls you inside it. A **red** flare drains you: each drain leaves you weaker (softer hits, slower) and heals it, and the third takes you |
| **Inside it** | A ruin like a hell, where everyone it has taken is chained, their energy drawn up into its heart. It feeds on them: every fighter it holds heals it and makes it hit harder. Watch your team fight on, and beat it to set everyone free |
| **No second chances** | Nobody respawns in its world. Knocked out or taken, you're out until the match ends; if nobody is left standing, it wins |
| **It learns** | Every fight with it, won or lost, makes the next one harder: more health, harder hits, faster attacks |
| **Cutscenes** | Victory, defeat, being pulled in and being drained are staged scenes, and the Entity speaks in a voice of its own. New players see the opening, where Swish is taken by the Entity |

## The rest of the game

| | |
|---|---|
| **Modes** | **The Entity** (above). **1v1**: against another real player, each on their own device, first to 3 knockouts. **Training Area**: dummies, a swarm of minis, a giant and one sentry gun; nothing at stake |
| **Fighters** | **Swish**, the starter: a storm goddess whose Frost Gale knocks enemies back, and whose Thunderhead turns her into a tornado that brings lightning down. **Bark**: a dog who throws paw prints that take a share of what health is left. **Kira**: a witch whose Hex Bolts poison and stack, and whose Bitter Brew hurts enemies and heals her. **Kito**: a ninja whose sword swing hits everyone in its arc, and whose super blinks him to the first enemy on a line. **Buddy**: a rogue AI who hurls computers, and whose super leaps onto an enemy's back and corrupts them. Every fighter has a **super** and a **Hyper** that charges from hits |
| **Progression** | Levels 1–10 with fully visible stat gains, paid for with **Coins**. **Arena Crystals** are the shop currency. The **Spark Road**, a 3D road with every fighter standing along it, unlocks them in turn |
| **Shop** | A daily free gift, daily offers and the server's deals |
| **Online** | A **leaderboard** of the server's players by wins, a **News** tab, **1v1** and **teams**: two or three players fighting the Entity together, joined with a four-digit code |
| **Offline** | When the server can't be reached the game carries on with an offline profile of its own, kept on the device: **Chaos Mode**, where every tweak is open to everyone |
| **Graphics** | Custom OpenGL ES 3.0: toon lighting, real-time shadows, inked outlines, gloss, 4× MSAA, up to 144 Hz |
| **Controls** | Floating or fixed move stick · drag-to-aim attack stick (a tap auto-aims at the nearest enemy) · super and Hyper buttons · aim assist · a layout editor for the controls |
| **Menus** | A live 3D lobby behind every screen, with dark purple panels full of shining stars over it, and a loading screen with the ARENA title coming in letter by letter |
| **Sound** | Upbeat lobby and victory music, a defeat theme and every effect are designed in code by a small synth (`audio/SfxSynth.kt`) |
| **Starting over** | Reset Progress in Settings deletes your account and starts the game over as a new player: a new name, and the opening again |

## Layout

| | |
|---|---|
| [`client/`](client) | The Android game: Kotlin, Jetpack Compose menus, a custom OpenGL ES 3.0 renderer |
| [`server/`](server) | The game server: Python (standard library only) with a SQLite database. See [server/README.md](server/README.md) |

The server is in charge of what matters: it keeps each player's Coins, Arena Crystals, fighters and Entity tier,
referees every match (it replays the match from the player's inputs through the same simulation, built into
`server/referee/referee.jar`, and the result is its own), works out what a match is worth, runs the shop, the daily
gift and the News, sets how the bots behave, pairs 1v1s and teams, and turns away versions that are no longer
supported. If it can't be reached, the game switches by itself to the offline profile and goes back to the server's
account when it answers; nothing from the offline profile is ever sent to the server.

`python reset_game.py` in `server/` (with the server stopped) is a full reset of the game: it backs up the database,
deletes every account, and every device starts over as a new player the next time it connects.

## Build & run

Requirements: **JDK 17 or 21** (Gradle 8.11 can't run on Java 24+), Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `platform-tools`. Android Studio is *not* required.

```bash
# local.properties must point at your SDK, e.g.  sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk
cd client && ./gradlew assembleDebug          # APK -> client/app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on the connected device
./gradlew testDebugUnitTest      # JVM tests: simulation, the Entity, economy, progression, bot matches, the referee
./gradlew :referee:installReferee   # rebuild the server's referee after any change to sim/, ai/ or data/
```

The server: `cd server && python run.py` (or `run.bat`) starts it on port 8765; `python -m unittest` runs its tests.

Wireless debugging: `adb mdns services` lists devices advertising it; `adb connect <ip:port>` attaches.
On Xiaomi/HyperOS, ADB installs show an "Install via USB" prompt on the device that must be accepted.

Debug builds accept a start screen for automated testing:

```bash
adb shell am start -S -n io.github.projectwip/.MainActivity --es screen boss   # or intro|train|duel|fighters|shop|road|settings|news|leaders|result|loading|cutvictory|cutdefeat|cutcorrupt|cutdrain|cutobey|inside
adb shell run-as io.github.projectwip cat files/save.json                       # inspect the save
```

## Changing the rules

Almost every number lives in two files:

* [`data/Balance.kt`](client/app/src/main/java/io/github/projectwip/data/Balance.kt): fighters, stats
  (`StatLine(base, perLevel)`), supers and Hypers, the Entity (its health, moves, flares, drains and tiers), upgrade
  costs, bot difficulty.
* [`data/Catalog.kt`](client/app/src/main/java/io/github/projectwip/data/Catalog.kt): the Spark Road and the shop.

The Entity's moves and the order it uses them are in
[`sim/World.kt`](client/app/src/main/java/io/github/projectwip/sim/World.kt) (`entityMoves`, `ENTITY_MOVES`).
The server's copies of prices and payouts are the ones that count: they are also in
[`server/astro/economy.py`](server/astro/economy.py) and [`rules.py`](server/astro/rules.py). Change both sides;
`EconomyTest` and `test_server.py` check that they agree.

Bot behaviour per difficulty: [`ai/BotProfile.kt`](client/app/src/main/java/io/github/projectwip/ai/BotProfile.kt).
Arenas are ASCII maps in [`sim/Arena.kt`](client/app/src/main/java/io/github/projectwip/sim/Arena.kt).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.

## License

Copyright (C) 2026 Talmeez Ahmad. ARENA is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License, version 3](LICENSE), or (at your option) any later version. It comes with no warranty. If you share a version of it, you must share that version's source under the same license and keep this credit.
