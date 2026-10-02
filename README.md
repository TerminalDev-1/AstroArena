# Project WIP-Preview

A mobile-first, landscape, top-down **arena brawler for Android phones and tablets** — quick 3v3 matches,
twin-stick touch controls, fighters you level up, Cups, a Cup Track, a Shop — and **bots as a first-class
way to play**. Launch it, pick a fighter, press PLAY, and you're in a match against bots in seconds. No
account, no server, no matchmaking.

> **Preview software.** This is an experiment. Anything — rules, balance, saves, code — may change without
> notice. There is no promise of maintenance.

Everything in the game is original: characters, arena, icons, the Cup emblem, sounds (synthesised at
runtime), UI and rules. No third-party game assets are used.

## What's in the first vertical slice

| | |
|---|---|
| **Mode** | *Knockout Rush* — 3v3, first team to 10 KOs (or most KOs after 2:30) |
| **Arena** | *Foundry Yard* — walls, tall-grass thickets (hide inside), coolant pools (block movement, not shots) |
| **Fighters** | **Juno Flint** (burst skirmisher, starter) · **Brakk** (shotgun tank, ram super) · **Mira Vale** (sniper, piercing super) |
| **Controls** | Floating/fixed move stick · drag-to-aim attack stick (tap = auto-aim, drag back to centre = cancel) · super stick |
| **Bots** | Easy / Normal / Hard / Elite — behaviour only (reaction, aim, leading, dodging, spacing, targeting, supers) |
| **Progression** | Levels 1–10 with linear, fully visible stat gains · Bolts (upgrades) · Prisms (shop) · Cups · Cup Track rewards |
| **Shop** | Daily free gift · fighter unlocks · Bolt supplies · colourways |
| **Settings** | Bot difficulty, player name, control size/opacity/mode, auto-aim, volume/mute, haptics, frame rate, damage numbers, FPS, reset |
| **Persistence** | Everything above is saved to a JSON file and survives restarts |

## Build & run

Requirements: **JDK 17 or 21** (Gradle 8.11 can't run on Java 24+), Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `platform-tools`. Android Studio is *not* required.

```bash
# local.properties must point at your SDK, e.g.  sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on the connected device
./gradlew testDebugUnitTest      # JVM tests: progression, economy, collision, full bot matches
```

Wireless debugging: `adb mdns services` lists devices advertising it; `adb connect <ip:port>` attaches.
On Xiaomi/HyperOS, ADB installs show an "Install via USB" prompt on the device that must be accepted.

Debug builds accept a start screen for automated testing:

```bash
adb shell am start -S -n io.github.projectwip/.MainActivity --es screen match   # or fighters|shop|track|settings
adb shell run-as io.github.projectwip cat files/save.json                        # inspect the save
```

## Changing the rules

Almost every number lives in two files:

* [`data/Balance.kt`](app/src/main/java/io/github/projectwip/data/Balance.kt) — fighters, stats
  (`StatLine(base, perLevel)`: e.g. damage `100 + 5/level`), upgrade costs, match length, reward formulas,
  bot difficulty reward bonuses.
* [`data/Catalog.kt`](app/src/main/java/io/github/projectwip/data/Catalog.kt) — Cup Track milestones, Shop
  items, daily gift.

Bot behaviour per difficulty: [`ai/BotProfile.kt`](app/src/main/java/io/github/projectwip/ai/BotProfile.kt).
The arena is ASCII: edit the quadrant in [`sim/Arena.kt`](app/src/main/java/io/github/projectwip/sim/Arena.kt).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.

## License

Not yet chosen. Until a license file is added, all rights are reserved by the author.
