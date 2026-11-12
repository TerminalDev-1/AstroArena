# Next task: Dimension becomes a lore mode

Dimension (`GameMode.PORTAL` in the code) shipped in 1.0 as a fighting mode with cutscenes. The user wants it to be
**primarily a lore mode, not a fighting mode.**

## What to change

1. **Not a fighting mode.** The story comes first. Waves of enemies are not the point of the mode: cut them back
   or remove them (ask the user which), so the run is about entering dimensions, exploring, and the Void.
2. **Every dimension is weirder than the last.** Dimension 1 is close to normal. Each portal after it makes the world
   stranger: colours, sky, floor, shapes, sounds, the arena itself bending.
3. **Glitching artifacts that grow with depth.** From portal one on, the screen and the world start to break up:
   torn scanlines, colour splitting, flickering geometry, things out of place, more of it the deeper you go.
   Keep it eerie, never gory.
4. **The Void taunts the character all the way, and is at every portal after portal one.** It waits there each
   time: present in the world at the portal, not just in the cutscene, and it mocks the fighter as they go.
5. **Richer cutscene visuals.** `DimensionCutscene` (the black cut, the blinding light, the TV switch-off and the
   dialogue scene) must look richer: more depth, motion, lighting and detail on the Void (`VoidPortrait`), the
   backdrop and the dialogue panel. It should feel like a real game's story scene, not a flat overlay.

## Where things are

- Cutscene: `client/app/src/main/java/io/github/projectwip/ui/screens/DimensionCutscene.kt`
- Dialogue: `client/app/src/main/java/io/github/projectwip/data/Lore.kt` (Void lines, each fighter's replies)
- Mode rules: `World.stepPortal`, `PortalRun`, the `PORTAL_*` constants in `data/Balance.kt`
- Dimension lighting: `DIMENSION_LIGHT` and `drawPortal` in `render3d/MatchRenderer.kt`
- Server pays by depth: `per_portal` in `server/trophies.cfg` and `rules.py`, mirrored in `data/Economy.kt`
- Debug: `adb shell am start -S -n io.github.projectwip/.MainActivity --es screen portal`

Any change under `sim/`, `ai/` or `data/` needs the referee jar rebuilt (`./gradlew :referee:installReferee`).
