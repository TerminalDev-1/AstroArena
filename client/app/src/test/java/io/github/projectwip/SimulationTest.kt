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

    /** Thickets hide a fighter only after it settles in, and a spotter doesn't lose it the instant it backs off. */
    @Test fun thicketConcealmentIsStable() {
        val a = Arenas.staticCanyon()
        val def = Balance.fighter(FighterId.JUNO)
        val hider = Fighter(0, def, 1, 0, 0, "H", true)
        val seeker = Fighter(1, def, 1, 0, 1, "S", true)
        val w = World(a, listOf(hider, seeker), io.github.projectwip.sim.MatchRules.lastSpark())
        fun run(seconds: Float) = repeat((seconds / Match.STEP).toInt()) { w.step(Match.STEP) }
        run(3.1f) // past countdown
        assertEquals(Tile.THICKET, a.tileAt(2f, 1f))
        hider.x = 2f; hider.y = 1f
        seeker.x = 12.5f; seeker.y = 12.5f
        run(Balance.THICKET_CONCEAL_SECONDS - 0.2f)
        assertTrue("brushing through grass must not hide a fighter", w.isVisibleTo(hider, seeker.team))
        run(0.4f)
        assertFalse("settled in the thicket: hidden", w.isVisibleTo(hider, seeker.team))
        assertTrue("always visible to its own team", w.isVisibleTo(hider, hider.team))

        seeker.x = 3.5f; seeker.y = 1f
        run(0.05f)
        assertTrue("spotted up close", w.isVisibleTo(hider, seeker.team))
        seeker.x = 12.5f; seeker.y = 12.5f
        run(Balance.THICKET_SPOT_LINGER_SECONDS - 0.2f)
        assertTrue("still seen right after losing contact", w.isVisibleTo(hider, seeker.team))
        run(0.4f)
        assertFalse("hidden again once the spotter is gone", w.isVisibleTo(hider, seeker.team))

        hider.revealTimer = 1f // as if it had just attacked
        run(1.05f)
        assertTrue("attacking breaks cover until the fighter settles again", w.isVisibleTo(hider, seeker.team))
        run(Balance.THICKET_CONCEAL_SECONDS)
        assertFalse(w.isVisibleTo(hider, seeker.team))
    }

    /** Regression: enemies used to blink out for a few frames whenever they clipped a thicket tile. */
    @Test fun enemiesDoNotFlickerInAndOutOfView() {
        var shortHides = 0
        var shortShows = 0
        for (mode in GameMode.entries) repeat(3) { seed ->
            val m = Match(MatchConfig(FighterId.JUNO, 3, 0, "T", BotDifficulty.NORMAL, mode = mode, humanPlayer = false, seed = 100L + seed))
            val seen = HashMap<Int, Boolean>()
            val since = HashMap<Int, Float>()
            var t = 0f
            while (!m.isOver && t < 200f) {
                m.step(Match.STEP); t += Match.STEP
                for (f in m.world.fighters) {
                    if (f.team == m.player.team) continue
                    if (!f.alive) { seen.remove(f.id); continue }
                    val v = m.world.isVisibleTo(f, m.player.team)
                    val prev = seen[f.id]
                    if (prev == null) { seen[f.id] = v; since[f.id] = -1f; continue }
                    if (prev == v) continue
                    val start = since.getValue(f.id)
                    if (start >= 0f && t - start < 0.3f) { if (prev) shortShows++ else shortHides++ }
                    seen[f.id] = v; since[f.id] = t
                }
            }
        }
        println("visibility blips over 6 matches: hides=$shortHides shows=$shortShows")
        assertEquals("an enemy that reappears must stay visible for a moment", 0, shortShows)
        assertTrue("too many sub-0.3s disappearances ($shortHides)", shortHides <= 40)
    }

    /** Auto-aim falls back to a Spark Crate it can actually hit, never one behind a wall. */
    @Test fun autoAimFindsHittableCrates() {
        val a = Arenas.staticCanyon()
        val def = Balance.fighter(FighterId.JUNO)
        val f = Fighter(0, def, 1, 0, 0, "A", true)
        val w = World(a, listOf(f, Fighter(1, def, 1, 0, 1, "B", true)), io.github.projectwip.sim.MatchRules.lastSpark())
        // Quadrant row 1 is "gggg.c##..": a crate at (5,1) with open floor to its left and wall to its right.
        assertEquals(Tile.CRATE, a[5, 1])
        f.x = 2.5f; f.y = 1.5f
        assertEquals(1 * a.width + 5, w.nearestHittableCrate(f, 6f))
        assertEquals("out of range", -1, w.nearestHittableCrate(f, 2f))
        f.x = 9.5f; f.y = 1.5f // the wall at (6..7, 1) is in the way now
        assertTrue(w.nearestHittableCrate(f, 6f) != 1 * a.width + 5)
    }

    /** Free-for-all bots shouldn't pile onto one fighter while the field is still crowded. */
    @Test fun botsDoNotGangUp() {
        var samples = 0
        var mobbed = 0
        repeat(3) { seed ->
            val m = Match(MatchConfig(FighterId.JUNO, 3, 0, "T", BotDifficulty.HARD, mode = GameMode.LAST_SPARK, humanPlayer = false, seed = 300L + seed))
            var t = 0f
            while (!m.isOver && t < 200f) {
                m.step(Match.STEP); t += Match.STEP
                if (m.world.aliveCount <= 3) break
                for (f in m.world.fighters) {
                    if (!f.alive) continue
                    samples++
                    if (m.brains.count { it.target === f } > 3) mobbed++
                }
            }
        }
        println("mobbed samples: $mobbed of $samples")
        assertTrue("fighters were mobbed by 4+ bots in $mobbed of $samples samples", mobbed < samples / 200)
    }

    /** Boss Mode: one giant with fixed stats; it ends when the boss falls or the player runs out of lives. */
    @Test fun bossModeIsOneFixedGiant() {
        val a = Arenas.provingGround()
        assertEquals(1, a.spawns[0].size)
        assertEquals(1, a.spawns[1].size)
        assertFalse("the boss needs room", a.circleBlocked(a.spawns[1][0].x, a.spawns[1][0].y, 1.25f))

        // Any fighter can be the boss, and its strength ignores the player's level.
        for (id in FighterId.entries) {
            val low = Match(MatchConfig(FighterId.JUNO, 1, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 1L, boss = id))
            val high = Match(MatchConfig(FighterId.JUNO, 60, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 1L, boss = id))
            val b1 = low.world.fighters.single { it.team != low.player.team }
            val b2 = high.world.fighters.single { it.team != high.player.team }
            assertEquals(id, b1.def.id)
            assertEquals("the boss does not scale with the player", b1.maxHp, b2.maxHp)
            assertEquals(b1.attackDamage, b2.attackDamage)
            assertTrue("drawn at two and a half times normal size", b1.scale > 2.4f)
            assertTrue(high.player.maxHp > low.player.maxHp)
        }
        // Unlimited lives: the fight can only end with the boss down, however often the player falls.
        var wins = 0
        for (seed in 0 until 6) {
            val m = Match(MatchConfig(FighterId.entries[seed % 3], if (seed % 2 == 0) 3 else 40, 0, "T", BotDifficulty.HARD, mode = GameMode.BOSS, humanPlayer = false, seed = 50L + seed))
            assertEquals(2, m.world.fighters.size)
            var t = 0f
            while (!m.isOver && t < 240f) { m.step(Match.STEP); t += Match.STEP }
            if (m.world.phase == Phase.ENDED) { wins++; assertEquals("only the player can win", m.player.team, m.world.winningTeam) }
            for (f in m.world.fighters) assertFalse(m.world.arena.circleBlocked(f.x, f.y, f.radius * 0.9f))
        }
        println("boss fights won inside four minutes: $wins of 6")
        assertTrue("a strong fighter should be able to beat it", wins >= 1)
    }

    /** Training Area: nineteen fighters, the targets never leave their spots, and it never ends by itself. */
    @Test fun trainingAreaIsAnEndlessPracticeGround() {
        val a = Arenas.trainingArea()
        assertEquals(1, a.spawns[0].size)
        assertEquals(4 + 1 + 1 + Match.TRAINING_MINIS, a.spawns[1].size)
        for (s in a.spawns[1]) assertEquals("targets stand on open floor", Tile.FLOOR, a.tileAt(s.x, s.y))
        val sentrySpot = a.spawns[1][5]
        assertTrue("the sentry's island can't be walked onto", a.circleBlocked(sentrySpot.x + 1f, sentrySpot.y, 0.4f))
        assertTrue("but it can shoot out over the coolant", a.shotClear(sentrySpot.x, sentrySpot.y, sentrySpot.x + 4f, sentrySpot.y))

        val m = Match(MatchConfig(FighterId.JUNO, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.TRAINING, humanPlayer = false, seed = 9L))
        assertEquals(19, m.world.fighters.size)
        assertTrue(m.world.fighters.size <= io.github.projectwip.match.HudSnapshot.MAX)
        val targets = m.world.fighters.filter { it.team != m.player.team }
        assertTrue(targets.all { it.rooted })
        val dummies = targets.filter { it.name.startsWith("Dummy") }
        val minis = targets.filter { it.name.startsWith("Mini") }
        assertEquals(4, dummies.size)
        assertEquals(Match.TRAINING_MINIS, minis.size)
        assertTrue("minis are small", minis.all { it.scale < 0.7f })
        assertTrue("one giant", targets.count { it.scale > 2.4f } == 1)
        val start = targets.map { it.x to it.y }
        var t = 0f
        while (t < 90f) { m.step(Match.STEP); t += Match.STEP }
        assertEquals("no clock and no score target: it keeps going", Phase.PLAYING, m.world.phase)
        assertFalse(m.isOver)
        assertEquals("targets never leave their spots", start, targets.map { it.x to it.y })
        val giant = targets.single { it.scale > 2.4f }
        assertTrue("dummies, the swarm and the boss never attack", (dummies + minis + giant).all { it.damageDealt == 0 })
        assertTrue("only the sentry has a brain", m.brains.size == 2) // the bot-driven player in this test, and the sentry
        assertTrue("the player got some practice in", m.player.damageDealt > 0)
        assertFalse(m.world.arena.circleBlocked(m.player.x, m.player.y, m.player.radius * 0.9f))
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

    @Test fun giantsHealSlowlyAndAttackingDoesNotStopHealing() {
        val m = Match(MatchConfig(FighterId.JUNO, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 4L))
        val boss = m.world.fighters.first { it.scale > 1f }
        val me = m.world.fighters.first { it.scale == 1f }
        boss.hp = boss.maxHp / 2
        me.hp = me.maxHp / 2
        // Neither has been hit for a while; the player has only just fired.
        boss.sinceDamaged = 60f; me.sinceDamaged = 60f; me.sinceAttack = 0f
        m.world.regenerate(boss, 1f); m.world.regenerate(me, 1f)
        val bossShare = (boss.hp - boss.maxHp / 2).toFloat() / boss.maxHp
        val myShare = (me.hp - me.maxHp / 2).toFloat() / me.maxHp
        assertTrue("attacking doesn't stop the player healing", myShare > 0.1f)
        assertTrue("the boss heals", bossShare > 0f)
        assertTrue("but far more slowly", bossShare < myShare / 4)
        // Being hit does stop it, for a few seconds.
        val before = me.hp
        me.sinceDamaged = 1f
        m.world.regenerate(me, 1f)
        assertEquals(before, me.hp)
        // The Training Area's giant heals slowly as well, and no giant has a shield.
        val t = Match(MatchConfig(FighterId.JUNO, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.TRAINING, humanPlayer = false, seed = 4L))
        val giant = t.world.fighters.first { it.scale > 1f }
        giant.hp = giant.maxHp / 2; giant.sinceDamaged = 60f
        t.world.regenerate(giant, 1f)
        assertTrue(giant.hp > giant.maxHp / 2)
    }

    @Test fun aShieldBuildsOnTopOfFullHealth() {
        val m = Match(MatchConfig(FighterId.JUNO, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 4L))
        val boss = m.world.fighters.first { it.scale > 1f }
        val me = m.world.fighters.first { it.scale == 1f }
        me.hp = me.maxHp - 1; me.sinceDamaged = 60f; me.sinceAttack = 60f
        boss.sinceDamaged = 60f; boss.sinceAttack = 60f
        m.world.regenerate(me, 1f)
        assertEquals("health is topped up before any shield", me.maxHp to 0, me.hp to me.shieldHp)
        m.world.regenerate(me, 1f)
        assertTrue("then the shield starts", me.shieldHp > 0)
        repeat(200) { m.world.regenerate(me, 1f); m.world.regenerate(boss, 1f) }
        assertEquals("up to its cap", io.github.projectwip.data.Balance.SHIELD_MAX, me.shieldHp)
        assertEquals(me.maxHp, me.hp)
        assertEquals("giants have none", 0, boss.shieldHp)
        // Still in a fight: nothing builds.
        me.shieldHp = 0; me.sinceDamaged = 1f
        m.world.regenerate(me, 1f)
        assertEquals(0, me.shieldHp)
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
