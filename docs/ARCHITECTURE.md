# Architecture

## Why this stack

* **Kotlin + native Android + a small custom OpenGL ES 3.0 renderer, no engine.** Gameplay is top-down on a
  2D ground plane; presentation is stylised 3D (tilted perspective camera, toon lighting, real-time shadows,
  inked outlines). A purpose-built renderer keeps the APK tiny, needs no engine export pipeline or asset
  store, and gives full control over timing and touch latency. All meshes are generated in code.
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
gl/       Mesh/MeshBuilder (procedural primitives), Shaders (toon lit, depth, water, sprites), GlThread/Egl
render3d/ FighterModels (rigged 3D fighters), ArenaModel (3D arena + surroundings), MatchRenderer (match),
          Stage (menu 3D stage, offscreen portraits), Toon (shadow map + shared lighting), Effects (particles)
match/    MatchView (SurfaceView + HUD), MatchRunner (fixed-step sim, input, aim assist, feedback),
          HudView/HudSnapshot (2D overlay fed by the render thread), TouchControls (twin-stick input)
audio/    Sfx (runtime-synthesised effects + haptics)
ui/       Theme, Components, Icons, App (navigation), screens/*
```

## Game loop & rendering

`MatchView` stacks a `SurfaceView` (3D) and a `HudView` (2D). A `GlThread` owns the EGL context; each frame
`MatchRunner` polls touch input and steps the `Match` at a **fixed 60 Hz**, then `MatchRenderer` draws with
**interpolation** (`prev → current`), so motion is smooth at 90/120/144 Hz. Passes: shadow map → ground &
walls → x-ray silhouettes (allies behind walls) → fighters → inverted-hull outlines → projectiles → bushes
(sway + fade near friendlies) → coolant → ground decals (team rings, aim indicator, auto-aim marker) →
additive particles. After drawing, the renderer publishes a `HudSnapshot` (screen positions, health, score…)
that `HudView` draws on the UI thread; touch goes `HudView → TouchControls` (synchronized).

Fighters are rigged from rounded primitives (body, head, legs, free arm, weapon, floating crystal) and
animated procedurally (walk, lean, recoil, breathe, hit flash). Meshes are white; skins colour them per draw.

Menus reuse the same models and lighting. One persistent `LobbyView` (a `TextureView` with its own GL thread)
sits behind every menu screen and renders a 3D lobby — sky dome, glowing floor, neon pillars, emblem, floating
props — plus the selected fighter on a pedestal. Screens describe what they want with `LobbyShotEffect`
(HOME / FIGHTER / BACKDROP) and `Modifier.lobbyAnchor()` (where the fighter should stand); the camera glides
between shots and uses a lens shift so the fighter lands exactly in the layout's hero column. The lobby is
removed during matches. `Portraits` renders every fighter/skin once offscreen (MSAA FBO → bitmap) for cards.

## Shop & Offer Creator

Fixed catalog items live in `data/Catalog.kt`. Player-made offers are `CustomOffer`s stored in the save:
contents (Bolts, Prisms, a fighter, a colourway — bundled as `Reward.Bundle`), price currency (free/Bolts/
Prisms), optional "was" price shown as a discount, expiry, purchase limit and colour theme. Purchases go
through `Progression.buyOffer`; already-owned items in a bundle are compensated rather than wasted.

Humans and bots drive fighters through the **same `Control` struct** — move vector, aim vector, one-shot
attack/super triggers — so bots can't cheat on stats or speed, and visibility (thickets) is enforced for
both via `World.isVisibleTo`.

## Modes

`MatchRules` switches between **teams** (Knockout Rush: two teams, respawns, KO race, vertical map with the
player's team at the bottom) and **free-for-all** (Last Spark: every fighter is its own team, one life,
placements). Free-for-all adds:

* **Static Storm** — a circle around the arena centre that waits, then shrinks; damage outside grows over time.
* **Spark Crates** — `Tile.CRATE` tiles with health (`World.crateHp`); breaking one turns it into floor and drops
  a **Power Cell** pickup (+10% max health and damage each, stacking). Knocked-out fighters drop their cells.

Bots read the same state: they seek safety from the storm first, loot crates/cells when no fight is near, and
early in the match only engage enemies close by (otherwise ten fighters stampede into each other). A
`BalanceReport` test measures match lengths and per-fighter performance headlessly.

## Auto-aim

Aim sticks measure from where the thumb lands, so any tap on them is an auto-aim tap. Auto-aim locks onto
`World.nearestVisibleEnemy` and **leads** it with `World.leadAim` (solves the projectile/target intercept), so
shots meet moving targets. That target is always marked in the arena (gold dashed ring + bobbing arrow + HUD
reticle) so players see who a tap will hit. With **Aim Assist**
(Settings → Controls), dragged shots within 12° of a visible enemy snap onto it.

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
cards) rather than a stretched phone layout. In matches the camera sits at a fixed distance and pitch, so
every device sees the same depth of field; wider phones see more horizontally. Touch controls are sized in dp and scaled by a setting.
