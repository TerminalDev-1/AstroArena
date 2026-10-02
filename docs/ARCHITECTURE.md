# Architecture

## Why this stack

* **Kotlin + native Android, no engine.** The game is 2D, top-down and vector-styled. A custom loop on a
  `SurfaceView` gives full control over timing, touch latency and rendering with a tiny APK and no engine
  licensing or export pipeline. The JDK/SDK toolchain is all command-line.
* **Jetpack Compose for menus**, fully re-skinned (no Material components). Compose makes chunky animated
  game UI (pressable plates, counting numbers, bursts) cheap to build and adapts well to phones vs tablets.
* **A pure-Kotlin simulation** (`sim/`, `ai/`, `data/` minus `SaveStore`) with no Android imports. It runs in
  JVM unit tests — including full bot-vs-bot matches — and could later run on a server.

## Layout

```
data/     Balance (all numbers), Catalog (Cup Track, Shop), SaveData, Progression (pure rules),
          SaveStore (JSON + AtomicFile), GameRepository (StateFlow the UI observes)
sim/      Arena (tiles, collision, raycasts), Entities (Fighter, Projectile, Control, events),
          World (fixed-step simulation + match rules), Match (world + bots + report)
ai/       BotProfile (difficulty knobs), BotBrain (perception → intent → path/steer → dodge → aim),
          Pathfinder (A* + smoothing)
match/    GameView (SurfaceView + game thread), TouchControls (twin-stick input + drawing)
render/   GameRenderer (arena, effects, HUD), FighterArt (shared character art)
audio/    Sfx (runtime-synthesised effects + haptics)
ui/       Theme, Components, Icons, App (navigation), screens/*
```

## Game loop

`GameView` owns a thread: input is polled, the `Match` is stepped at a **fixed 60 Hz**, then a frame is
rendered with **interpolation** (`prev → current` by the leftover accumulator), so motion is smooth on
90/120/144 Hz panels. Touch events arrive on the UI thread and are handed over through a synchronized
`TouchControls`; everything else (world, renderer) is touched only by the game thread.

Humans and bots drive fighters through the **same `Control` struct** — move vector, aim vector, one-shot
attack/super triggers — so bots can't cheat on stats or speed, and visibility (thickets) is enforced for
both via `World.isVisibleTo`.

## Bot AI

`BotBrain` runs in layers at different rates:

1. **think()** every `thinkInterval`: perceive visible enemies (fair, team-shared vision), pick a target by
   score (distance, health if `focusWeakest`, line of fire, spawn shields), choose an intent
   (`ENGAGE` / `RETREAT` / `ADVANCE`), compute a goal (ideal weapon range + strafe offset), plan an A* path.
2. **steer()** every tick: follow the path, keep spacing from allies, add `wander` noise, unstick.
3. **dodge()** every tick: predict closest approach of enemy projectiles; side-step with `dodgeChance`.
4. **combat()** every tick: wait `reactionTime` after acquiring a target, lead the shot by `leadFactor`,
   add Gaussian aim error, respect range/walls if `shotDiscipline`, time supers by `superSkill`.

New behaviours plug in as intents or as extra terms in target/goal scoring. A unit test checks that Elite
beats Easy with identical stats, so difficulty truly comes from behaviour.

## Progression

`Progression` is a set of pure functions `(SaveData, …) -> SaveData`. `GameRepository` applies them and
persists every change on a background thread. Cup Track rewards unlock against **best** Cups so losing Cups
never takes away a reached reward; owned duplicates are compensated (`CupTrack.duplicateCompensation`).

## Responsive UI

`App` scales Compose density by screen height (`0.92×–1.4×`) so controls stay thumb-sized and legible, and
exposes `UiMetrics.roomy`/`wide` so tablets get *more content* (arena minimap, fighter lore, larger
cards) rather than a stretched phone layout. In matches the camera always shows at least
22×13.5 tiles; wider phones see more horizontally. Touch controls are sized in dp and scaled by a setting.
