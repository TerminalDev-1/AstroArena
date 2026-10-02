package io.github.projectwip

import io.github.projectwip.ai.BotBrain
import io.github.projectwip.ai.BotProfile
import io.github.projectwip.ai.Pathfinder
import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.sim.Arenas
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.MatchConfig
import io.github.projectwip.sim.Phase
import io.github.projectwip.sim.Tile
import io.github.projectwip.sim.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimulationTest {

    @Test fun spawnsAreOnOpenGround() {
        val a = Arenas.foundryYard()
        for (team in a.spawns) for (s in team) assertFalse("spawn $s blocked", a.circleBlocked(s.x, s.y, 0.5f))
    }

    @Test fun circleNeverEntersWalls() {
        val a = Arenas.foundryYard()
        val out = FloatArray(2)
        val rng = Random(1)
        var x = 1.5f
        var y = 10f
        repeat(5000) {
            a.moveCircle(x, y, 0.45f, (rng.nextFloat() - 0.5f) * 0.4f, (rng.nextFloat() - 0.5f) * 0.4f, out)
            x = out[0]; y = out[1]
            assertFalse("inside wall at $x,$y", a.circleBlocked(x, y, 0.44f))
        }
    }

    @Test fun shotsStopAtWallsButCrossWater() {
        val a = Arenas.foundryYard()
        // Find a wall and a pool tile to shoot across.
        var wall: Pair<Int, Int>? = null
        var water: Pair<Int, Int>? = null
        for (y in 0 until a.height) for (x in 1 until a.width - 1) {
            if (a[x, y] == Tile.WALL && a[x - 1, y] == Tile.FLOOR && a[x + 1, y] != Tile.WALL && wall == null) wall = x to y
            if (a[x, y] == Tile.WATER && water == null) water = x to y
        }
        val (wx, wy) = wall!!
        assertFalse(a.shotClear(wx - 0.5f, wy + 0.5f, wx + 1.5f, wy + 0.5f))
        val (px, py) = water!!
        assertTrue(a[px, py].blocksMove)
        assertFalse(a[px, py].blocksShots)
    }

    @Test fun fullBotMatchFinishes() {
        for (d in BotDifficulty.entries) {
            val m = Match(MatchConfig(FighterId.JUNO, 3, 0, "Test", d, humanPlayer = false, seed = 42L + d.ordinal))
            var t = 0f
            while (!m.isOver && t < 400f) { m.step(Match.STEP); t += Match.STEP }
            println("$d: ${"%.1f".format(t)}s score=${m.world.score.toList()} " +
                m.world.fighters.joinToString { "${it.def.id}:${it.kos}/${it.deaths}/${it.damageDealt}" })
            assertEquals("match ($d) must end", Phase.ENDED, m.world.phase)
            val kos = m.world.score.sum()
            assertTrue("bots on $d should actually fight (KOs=$kos)", kos >= 4)
            for (f in m.world.fighters) assertFalse(m.world.arena.circleBlocked(f.x, f.y, f.radius * 0.9f))
        }
    }

    /** Difficulty must come from behaviour: Elite bots should beat Easy bots with identical stats. */
    @Test fun eliteBeatsEasyWithSameStats() {
        var eliteWins = 0
        var easyWins = 0
        repeat(8) { seed ->
            val rng = Random(seed)
            val fighters = (0 until 6).map { i ->
                val def = Balance.fighters[i % 3]
                Fighter(i, def, 5, 0, if (i < 3) 0 else 1, "B$i", isBot = true)
            }
            val world = World(Arenas.foundryYard(), fighters, random = Random(seed))
            val pf = Pathfinder(world.arena)
            val brains = fighters.map {
                val p = BotProfile.of(if (it.team == 0) BotDifficulty.ELITE else BotDifficulty.EASY)
                BotBrain(it, p, world, pf, Random(rng.nextLong()))
            }
            var t = 0f
            while (world.phase != Phase.ENDED && t < 400f) {
                brains.forEach { it.update(Match.STEP) }
                world.step(Match.STEP)
                t += Match.STEP
            }
            when (world.winningTeam) { 0 -> eliteWins++; 1 -> easyWins++ }
        }
        println("Elite $eliteWins – Easy $easyWins")
        assertTrue("Elite should dominate Easy ($eliteWins vs $easyWins)", eliteWins >= 7)
    }
}
