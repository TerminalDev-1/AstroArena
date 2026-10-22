# AstroArena

A mobile-first, landscape, **3D arena brawler for Android phones and tablets**: quick matches against bots,
twin-stick touch controls, five fighters to unlock and level up, bosses to take down, and 1v1s and team play
against real players through its own game server. Launch it, press PLAY, and you're in a match in seconds.

**Repository:** https://github.com/TerminalDev-1/AstroArena

> **Beta.** This is an experiment. Anything (rules, balance, saves, code) may change without notice. Every build
> replaces the last one: the game checks GitHub for a newer release when it starts and asks you to update.

Everything in the game is original: the 3D fighters, bosses and arenas (all meshes generated in code), icons, the
Cup emblem, music and sounds (synthesised at runtime), UI and rules. No third-party game assets are used.

## What's in it

| | |
|---|---|
| **Modes** | **Last Spark**: a 10-fighter free-for-all, last one standing; break crates for Power Cells while the Static Storm closes in. **Knockout Rush**: 3v3, first team to 10 KOs. **Boss Mode**: you (or a team of friends) against one of three bosses, each with moves of its own (Hailstorm, a rocket platform; Lighthouse, a beam sweeper; Ramrod, a wrecking bull); watch the marked ground, unlimited lives. **1v1**: against another real player, each on their own device, first to 3 knockouts. **Training Area**: dummies, a swarm of minis, a boss that stands still and one sentry gun; nothing at stake |
| **Arenas** | *Static Canyon* (free-for-all), *Foundry Yard* (3v3), *Proving Ground* (bosses and 1v1s) and the *Training Area*: walls, tall grass to hide in, water that blocks movement but not shots, destructible crates |
| **Fighters** | **Spark**, the starter: a glossy blue star who lobs his Spite Star over walls, and whose Shadow Realm traps an enemy with four of his shadows. **Bark**: a dog who throws paw prints that take a share of what health is left. **Kira**: a witch whose Hex Bolts poison and stack, and whose Bitter Brew hurts enemies and heals her. **Kito**: a ninja whose sword swing hits everyone in its arc, with an edge bar that makes the next swing reach further, and a super that blinks him to the first enemy on a line. **Buddy**: a rogue AI who hurls computers, and whose super leaps onto an enemy's back and corrupts them. Every fighter has a **super** and a **Hyper** that charges from hits |
| **Graphics** | Custom OpenGL ES 3.0: toon lighting, real-time shadows, inked outlines, gloss, 4× MSAA, up to 120 Hz |
| **Controls** | Floating or fixed move stick · drag-to-aim attack stick (a tap auto-aims at the nearest enemy) · super and Hyper buttons · aim assist · a layout editor for the controls |
| **Bots** | Easy / Normal / Hard / Elite: behaviour only (reaction, aim, leading, dodging, spacing, targeting, supers). The server approves the choice |
| **Progression** | Levels 1–10 with fully visible stat gains, paid for with **Upgrade Credits**. **CPU Chips** are the shop currency. **Cups** for every win, a rank and Cups of each fighter's own, and the **Cup Track** of rewards |
| **Arena Boxes** | Earned from good finishes. Each holds several items, revealed one at a time: Credits, CPU Chips, colourways, and now and then a jackpot. Open them one by one or all at once |
| **Spark Road** | A 3D road with every fighter standing along it. Credits fill it toward the next fighter, who unlocks the moment they're covered (or can be bought with CPU Chips) |
| **Shop** | A daily free gift, daily offers and the server's deals |
| **Online** | A real **leaderboard** of the server's players, a **News** tab, **1v1** and **teams**: two or three players in one Boss Mode or Knockout Rush match, joined with a four-digit code |
| **Offline** | When the server can't be reached the game carries on with an offline profile of its own, kept on the device: **Chaos Mode**, where every tweak (luck, free drops, upgrade cost, hand-outs) is open to everyone |
| **Menus** | A live 3D lobby behind every screen, with dark steel-glass panels over it: a navigation rail on the home screen, tabs between the sub-screens, your fighter on show and a mode picker |
| **Sound** | Upbeat lobby and victory music, a defeat theme and every effect are designed in code by a small synth (`audio/SfxSynth.kt`): band-limited oscillators, FM bells, filtered noise, drive, echo and reverb |
| **Settings** | Bot difficulty, player name, controls, audio and haptics, display, data, and an "Arena Boxes only" layout |

## Layout

| | |
|---|---|
| [`client/`](client) | The Android game: Kotlin, Jetpack Compose menus, a custom OpenGL ES 3.0 renderer |
| [`server/`](server) | The game server: Python (standard library only) with a SQLite database. See [server/README.md](server/README.md) |

The server is in charge of what matters: it keeps each player's Cups, Arena Boxes, Credits, CPU Chips and
fighters, referees every match (it replays the match from the player's inputs through the same simulation, built
into `server/referee/referee.jar`, and the result is its own), works out what a match is worth, rolls what comes
out of a box, runs the shop, the daily gift and the News, sets how tough the bots are, pairs 1v1s and teams,
and turns away versions that are no longer supported. If it can't be reached, the game switches by itself to the
offline profile and goes back to the server's account when it answers; nothing from the offline profile is ever
sent to the server.

## Build & run

Requirements: **JDK 17 or 21** (Gradle 8.11 can't run on Java 24+), Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `platform-tools`. Android Studio is *not* required.

```bash
# local.properties must point at your SDK, e.g.  sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk
cd client && ./gradlew assembleDebug          # APK -> client/app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on the connected device
./gradlew testDebugUnitTest      # JVM tests: simulation, economy, progression, bot matches, the referee
./gradlew :referee:installReferee   # rebuild the server's referee after any change to sim/, ai/ or data/
```

The server: `cd server && python run.py` (or `run.bat`) starts it on port 8765; `python -m unittest` runs its tests.

Wireless debugging: `adb mdns services` lists devices advertising it; `adb connect <ip:port>` attaches.
On Xiaomi/HyperOS, ADB installs show an "Install via USB" prompt on the device that must be accepted.

Debug builds accept a start screen for automated testing:

```bash
adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match   # or boss|train|duel|fighters|shop|road|track|settings|news|leaders|result|loading
adb shell run-as io.github.projectwip cat files/save.json                        # inspect the save
```

## Changing the rules

Almost every number lives in two files:

* [`data/Balance.kt`](client/app/src/main/java/io/github/projectwip/data/Balance.kt): fighters, bosses, stats
  (`StatLine(base, perLevel)`), supers and Hypers, upgrade costs, match length, bot difficulty.
* [`data/Catalog.kt`](client/app/src/main/java/io/github/projectwip/data/Catalog.kt): the Cup Track, the Spark
  Road, Arena Box tiers and rewards, fighter ranks, the shop.

The server's copies are the ones that count: prices, drop tables and what a match pays are also in
[`server/astro/economy.py`](server/astro/economy.py) and [`rules.py`](server/astro/rules.py), and Cups per mode in
`server/trophies.cfg`. Change both sides; `EconomyTest` and `test_server.py` check that they agree.

Bot behaviour per difficulty: [`ai/BotProfile.kt`](client/app/src/main/java/io/github/projectwip/ai/BotProfile.kt).
Arenas are ASCII maps in [`sim/Arena.kt`](client/app/src/main/java/io/github/projectwip/sim/Arena.kt).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.

## License

Copyright (C) 2026 Talmeez Ahmad. AstroArena is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License, version 3](LICENSE), or (at your option) any later version. It comes with no warranty. If you share a version of it, you must share that version's source under the same license and keep this credit.
