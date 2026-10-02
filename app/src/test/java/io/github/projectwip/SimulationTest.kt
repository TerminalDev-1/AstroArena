package io.github.projectwip

import io.github.projectwip.ai.BotBrain
import io.github.projectwip.ai.BotProfile
import io.github.projectwip.ai.Pathfinder
import io.github.projectwip.data.Balance
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
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
        // Vertical layout: the player's team starts at the bottom.
        assertTrue(a.height > a.width)
        assertTrue(a.spawns[0].all { it.y > a.height / 2f })
        val c = Arenas.staticCanyon()
        assertEquals(10, c.ffaSpawns.size)
        for (s in c.ffaSpawns) assertFalse("ffa spawn $s blocked", c.circleBlocked(s.x, s.y, 0.5f))
        assertEquals("spawns must be distinct", 10, c.ffaSpawns.toSet().size)
    }

    @Test fun freeForAllEndsWithUniquePlacements() {
        repeat(3) { seed ->
            val m = Match(MatchConfig(FighterId.MIRA, 4, 0, "Test", if (seed == 0) BotDifficulty.NORMAL else BotDifficulty.HARD, mode = GameMode.LAST_SPARK, humanPlayer = false, seed = 7L + seed))
            var t = 0f
            while (m.world.phase != Phase.ENDED && t < 300f) { m.step(Match.STEP); t += Match.STEP }
            println("FFA seed $seed ended at ${"%.1f".format(t)}s storm r=${"%.1f".format(m.world.storm!!.radius)}")
            assertEquals(Phase.ENDED, m.world.phase)
            val places = m.world.fighters.map { it.placement }.sorted()
            assertEquals((1..10).toList(), places)
            assertEquals(1, m.world.aliveCount)
        }
    }

    @Test fun leadAimHitsAStrafingTarget() {
        val a = Arenas.staticCanyon()
        val def = Balance.fighter(FighterId.MIRA)
        val shooter = Fighter(0, def, 1, 0, 0, "S", true)
        val target = Fighter(1, def, 1, 0, 1, "T", true)
        val w = World(a, listOf(shooter, target), io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w.step(Match.STEP) } // past countdown
        shooter.x = 18f; shooter.y = 26f
        target.x = 18f; target.y = 19f
        target.vx = 3.5f; target.vy = 0f
        val out = FloatArray(2)
        w.leadAim(shooter, target, def.attack.speed, out)
        // Where will the projectile and target be when the shot covers the distance?
        val len = kotlin.math.hypot(out[0], out[1])
        val tHit = len / def.attack.speed
        val px = shooter.x + out[0] / len * def.attack.speed * tHit
        val py = shooter.y + out[1] / len * def.attack.speed * tHit
        val tx = target.x + target.vx * tHit
        val ty = target.y + target.vy * tHit
        assertTrue("lead should meet the target", kotlin.math.hypot(px - tx, py - ty) < 0.1f)
        assertTrue("lead must aim ahead of a target moving right", out[0] > 0.5f)
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
        var checkedWall = false
        var checkedWater = false
        for (y in 1 until a.height - 1) for (x in 1 until a.width - 1) {
            if (a[x, y] == Tile.WALL && a[x - 1, y] == Tile.FLOOR) {
                assertFalse(a.shotClear(x - 0.5f, y + 0.5f, x + 0.6f, y + 0.5f))
                checkedWall = true
            }
            if (a[x, y] == Tile.WATER && a[x - 1, y] == Tile.FLOOR) {
                assertTrue(a[x, y].blocksMove)
                assertTrue("shots fly over coolant", a.shotClear(x - 0.5f, y + 0.5f, x + 0.6f, y + 0.5f))
                checkedWater = true
            }
        }
        assertTrue(checkedWall && checkedWater)
    }

    @Test fun fullBotMatchFinishes() {
        for (d in BotDifficulty.entries) {
            val m = Match(MatchConfig(FighterId.JUNO, 3, 0, "Test", d, mode = GameMode.KNOCKOUT_RUSH, humanPlayer = false, seed = 42L + d.ordinal))
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
