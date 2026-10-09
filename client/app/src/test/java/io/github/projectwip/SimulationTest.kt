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
            val m = Match(MatchConfig(FighterId.KITO, 4, 0, "Test", if (seed == 0) BotDifficulty.NORMAL else BotDifficulty.HARD, mode = GameMode.LAST_SPARK, humanPlayer = false, seed = 7L + seed))
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
        val def = Balance.sentry
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
        val def = Balance.fighter(FighterId.BYTE)
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
            val m = Match(MatchConfig(FighterId.BYTE, 3, 0, "T", BotDifficulty.NORMAL, mode = mode, humanPlayer = false, seed = 100L + seed))
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
        val def = Balance.fighter(FighterId.BYTE)
        val f = Fighter(0, def, 1, 0, 0, "A", true)
        val w = World(a, listOf(f, Fighter(1, def, 1, 0, 2, "B", true)), io.github.projectwip.sim.MatchRules.lastSpark())
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
            val m = Match(MatchConfig(FighterId.BYTE, 3, 0, "T", BotDifficulty.HARD, mode = GameMode.LAST_SPARK, humanPlayer = false, seed = 300L + seed))
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
        assertEquals("room for a team of three", 3, a.spawns[0].size)
        assertEquals(1, a.spawns[1].size)
        assertFalse("the boss needs room", a.circleBlocked(a.spawns[1][0].x, a.spawns[1][0].y, 1.25f))

        // Each boss is its own thing, and its strength ignores the player's level.
        for (kind in io.github.projectwip.data.BossKind.entries) {
            val low = Match(MatchConfig(FighterId.BYTE, 1, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 1L, boss = kind))
            val high = Match(MatchConfig(FighterId.BYTE, 60, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 1L, boss = kind))
            val b1 = low.world.fighters.single { it.team != low.player.team }
            val b2 = high.world.fighters.single { it.team != high.player.team }
            assertEquals(kind, b1.def.boss)
            assertEquals(io.github.projectwip.data.Balance.boss(kind).name, b1.name)
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

    /** Each boss fights with moves of its own: marked ground that goes off, and shots no fighter fires. */
    @Test fun bossesHaveMovesOfTheirOwn() {
        for (kind in io.github.projectwip.data.BossKind.entries) {
            val m = Match(MatchConfig(FighterId.BRAKK, 10, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 7L, boss = kind))
            var marks = 0; var blasts = 0; var shots = 0; var dashes = 0
            var t = 0f
            while (!m.isOver && t < 40f) {
                val before = m.world.hazards.size
                m.step(Match.STEP); t += Match.STEP
                marks += maxOf(0, m.world.hazards.size - before)
                for (e in m.world.events) when (e) {
                    is io.github.projectwip.sim.GameEvent.Blast -> blasts++
                    is io.github.projectwip.sim.GameEvent.Dash -> dashes++
                    else -> Unit
                }
                m.world.events.clear()
                shots = maxOf(shots, m.world.projectiles.count { it.team != m.player.team })
            }
            println("$kind: $marks marks, $blasts blasts, $shots shots in the air at once, $dashes charges")
            assertTrue("$kind marks the ground", marks > 0)
            assertEquals("every mark goes off", marks, blasts + m.world.hazards.size)
            when (kind) {
                io.github.projectwip.data.BossKind.BARRAGE -> assertTrue(shots >= 8)
                io.github.projectwip.data.BossKind.SWEEPER -> assertTrue(shots >= 12)
                io.github.projectwip.data.BossKind.STAMPEDE -> assertTrue(dashes > 0)
            }
        }
        // A mark hurts whoever is still standing in it when it goes off, and nobody outside it.
        val m = Match(MatchConfig(FighterId.BYTE, 1, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 3L, boss = io.github.projectwip.data.BossKind.BARRAGE))
        while (m.world.phase != Phase.PLAYING) m.step(Match.STEP)
        val boss = m.world.fighters.first { it.def.boss != null }
        val me = m.player
        val full = me.hp
        m.world.hazards.clear()
        m.world.hazards += io.github.projectwip.sim.Hazard(boss.id, boss.team, me.x + 6f, me.y, 1f, 0.05f, 500, io.github.projectwip.sim.HazardKind.ROCKET)
        repeat(6) { m.world.step(Match.STEP) }
        assertEquals("outside the mark: untouched", full, me.hp)
        m.world.hazards += io.github.projectwip.sim.Hazard(boss.id, boss.team, me.x, me.y, 1f, 0.02f, 500, io.github.projectwip.sim.HazardKind.ROCKET)
        m.world.step(Match.STEP); m.world.step(Match.STEP)
        assertTrue("inside it: hurt", me.hp < full || !me.alive)
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

        val m = Match(MatchConfig(FighterId.BYTE, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.TRAINING, humanPlayer = false, seed = 9L))
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
            val m = Match(MatchConfig(FighterId.BYTE, 3, 0, "Test", d, mode = GameMode.KNOCKOUT_RUSH, humanPlayer = false, seed = 42L + d.ordinal))
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

    @Test fun healingWaitsForAPauseInTheFightBotsHealMoreSlowlyAndGiantsSlowest() {
        // A human player and a bot of the same kind, in the same match.
        val m = Match(MatchConfig(FighterId.BYTE, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.KNOCKOUT_RUSH, seed = 4L))
        val me = m.world.fighters.first { !it.isBot }
        val bot = m.world.fighters.first { it.isBot }
        me.hp = me.maxHp / 2; bot.hp = bot.maxHp / 2
        // Just hit, or just fired: no healing yet.
        me.sinceDamaged = 0f; me.sinceAttack = 60f
        m.world.regenerate(me, 1f)
        assertEquals("not while being hit", me.maxHp / 2, me.hp)
        me.sinceDamaged = 60f; me.sinceAttack = Balance.REGEN_DELAY_SECONDS - 0.5f
        m.world.regenerate(me, 1f)
        assertEquals("and not while attacking", me.maxHp / 2, me.hp)
        // A few seconds out of the fight, and it starts.
        me.sinceAttack = 60f; bot.sinceDamaged = 60f; bot.sinceAttack = 60f
        m.world.regenerate(me, 1f); m.world.regenerate(bot, 1f)
        val myShare = (me.hp - me.maxHp / 2).toFloat() / me.maxHp
        val botShare = (bot.hp - bot.maxHp / 2).toFloat() / bot.maxHp
        assertTrue("then the player heals", myShare > 0.1f)
        assertEquals("a bot heals at half the pace", myShare / 2, botShare, 0.005f)
        repeat(60) { m.world.regenerate(me, 1f) }
        assertEquals("up to full health, and no further: there are no shields", me.maxHp, me.hp)
        // A giant heals far more slowly.
        val b = Match(MatchConfig(FighterId.BYTE, 5, 0, "T", BotDifficulty.NORMAL, mode = GameMode.BOSS, humanPlayer = false, seed = 4L))
        val boss = b.world.fighters.first { it.scale > 1f }
        boss.hp = boss.maxHp / 2; boss.sinceDamaged = 1f
        b.world.regenerate(boss, 1f)
        assertEquals("not while it is being hit", boss.maxHp / 2, boss.hp)
        boss.sinceDamaged = 60f
        b.world.regenerate(boss, 1f)
        val bossShare = (boss.hp - boss.maxHp / 2).toFloat() / boss.maxHp
        assertTrue(bossShare > 0f && bossShare < botShare / 8)
    }

    /** Two fighters past the countdown: Varun, and a target standing where a wall blocks every straight shot. */
    private fun varunBehindAWall(): Triple<World, Fighter, Fighter> {
        val a = Arenas.staticCanyon()
        val varun = Fighter(0, Balance.fighter(FighterId.VARUN), 1, 0, 0, "V", true)
        val target = Fighter(1, Balance.fighter(FighterId.BRAKK), 1, 0, 1, "T", true)
        val w = World(a, listOf(varun, target), io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w.step(Match.STEP) }
        // Find two open spots a few tiles apart with something solid between them.
        search@ for (y in 3 until a.height - 3) for (x in 3 until a.width - 9) {
            val x0 = x + 0.5f; val y0 = y + 0.5f; val x1 = x + 6.5f
            if (a.circleBlocked(x0, y0, 0.5f) || a.circleBlocked(x1, y0, 0.5f) || a.shotClear(x0, y0, x1, y0)) continue
            if (a.inThicket(x0, y0) || a.inThicket(x1, y0)) continue
            varun.x = x0; varun.y = y0; target.x = x1; target.y = y0
            break@search
        }
        assertFalse("a wall stands between them", a.shotClear(varun.x, varun.y, target.x, target.y))
        return Triple(w, varun, target)
    }

    @Test fun varunFiresOneSmallRocket() {
        val (w, varun, target) = varunBehindAWall()
        varun.control.aimX = -(target.x - varun.x); varun.control.aimY = 0f; varun.control.attack = true
        w.step(Match.STEP)
        assertEquals(1, varun.def.attack.projectiles)
        assertTrue(varun.pending.isEmpty())
        assertEquals("one ammo, one rocket", 1, w.projectiles.size)
        assertEquals("worth sixty", 60, w.projectiles[0].damage)
        assertEquals("that takes twelve seconds to reload and reaches one tile", 12f to 1f, varun.def.reloadSeconds to varun.def.attack.range)
    }

    @Test fun varunsRocketsBurstAndCatchEveryoneNearby() {
        val a = Arenas.staticCanyon()
        val varun = Fighter(0, Balance.fighter(FighterId.VARUN), 1, 0, 0, "V", true)
        val one = Fighter(1, Balance.fighter(FighterId.BRAKK), 1, 0, 1, "A", true)
        val two = Fighter(2, Balance.fighter(FighterId.BRAKK), 1, 0, 1, "B", true)
        val w = World(a, listOf(varun, one, two), io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w.step(Match.STEP) }
        var placed = false
        search@ for (y in 3 until a.height - 3) for (x in 3 until a.width - 8) {
            val x0 = x + 0.5f; val y0 = y + 0.5f
            if ((0..5).any { a.circleBlocked(x0 + it, y0, 0.9f) } || a.circleBlocked(x0 + 5.9f, y0, 0.6f)) continue
            varun.x = x0; varun.y = y0; one.x = x0 + 1.5f; one.y = y0; two.x = x0 + 1.5f; two.y = y0 + 1.3f
            placed = true
            break@search
        }
        assertTrue(placed)
        one.shield = 0f; two.shield = 0f
        varun.control.aimX = 1f; varun.control.aimY = 0f; varun.control.attack = true
        var bursts = 0
        repeat(90) { w.step(Match.STEP); bursts += w.events.count { it is io.github.projectwip.sim.GameEvent.Burst }; w.events.clear() }
        assertEquals("every rocket goes off", 1, bursts)
        assertTrue("the one in the way is hit", one.hp < one.maxHp)
        assertTrue("and so is the one standing beside it, out of the rockets' path", two.hp < two.maxHp)
    }

    @Test fun varunsRocketRainLandsWhereItIsAimedOverWallsAndNeverKnocksOut() {
        val (w, varun, target) = varunBehindAWall()
        // Aimed at the spot the target stands on: the aim is the offset to it, in tiles.
        fun fire() {
            varun.superCharge = 1f
            varun.control.aimX = target.x - varun.x; varun.control.aimY = target.y - varun.y; varun.control.superAttack = true
            w.step(Match.STEP)
        }
        fire()
        assertEquals("eight rockets on their way down", 8, w.hazards.size)
        assertTrue("no straight shots: nothing for the wall to stop", w.projectiles.isEmpty())
        assertEquals("the first lands dead centre of the aim", target.x to target.y, w.hazards[0].x to w.hazards[0].y)
        val spec = varun.def.superSpec
        assertTrue("and all of them inside the one circle", w.hazards.all { kotlin.math.hypot(it.x - target.x, it.y - target.y) + it.radius <= spec.radius + 0.01f })
        var t = 0f
        while (w.hazards.isNotEmpty() && t < 6f) { w.step(Match.STEP); t += Match.STEP }
        assertTrue("they all came down, and quickly", w.hazards.isEmpty() && t < 2.5f)
        assertTrue("massive damage: ${varun.damageDealt}", varun.damageDealt >= minOf(target.maxHp - 1, 4 * varun.superDamage))
        assertTrue("but the target is left standing", target.alive && target.hp >= 1)
        // Even a target on its last legs survives a whole salvo.
        target.hp = 5
        fire()
        t = 0f
        do { w.step(Match.STEP); t += Match.STEP } while (w.hazards.isNotEmpty() && t < 6f)
        assertTrue(target.alive)
        assertEquals(1, target.hp)
        assertEquals(0, varun.kos)
        // Aimed somewhere else, it lands somewhere else: nothing steers it onto the target.
        target.hp = target.maxHp
        varun.superCharge = 1f
        varun.control.aimX = -(target.x - varun.x); varun.control.aimY = 0f; varun.control.superAttack = true
        w.step(Match.STEP)
        while (w.hazards.isNotEmpty()) w.step(Match.STEP)
        assertEquals("a rain aimed away misses", target.maxHp, target.hp)
        // And it can't be thrown further than its range.
        varun.superCharge = 1f
        varun.control.aimX = 50f; varun.control.aimY = 0f; varun.control.superAttack = true
        w.step(Match.STEP)
        assertEquals(varun.x + spec.range, w.hazards[0].x, 0.01f)
    }

    @Test fun aHyperBuffsDamageAndHealth() {
        val (w, varun, _) = varunBehindAWall()
        val hp = varun.maxHp; val dmg = varun.attackDamage
        varun.hp = hp
        varun.control.hyper = true
        w.step(Match.STEP)
        assertFalse("nothing happens until it is charged", varun.hyperActive)
        varun.hyperCharge = 1f
        varun.control.hyper = true
        w.step(Match.STEP)
        assertTrue(varun.hyperActive)
        assertEquals("a quarter more health", hp + hp / 4, varun.maxHp)
        assertEquals("which it has at once", varun.maxHp, varun.hp)
        assertEquals("a quarter more damage", dmg * 1.25f, varun.attackDamage.toFloat(), 1f)
        assertEquals("and the charge is spent", 0f, varun.hyperCharge, 0f)
        var t = 0f
        while (varun.hyperActive && t < 20f) { w.step(Match.STEP); t += Match.STEP }
        assertEquals("Varun's own hyper runs four seconds; a plain one eight", 4f to 8f, varun.hyperSeconds to Fighter(9, Balance.fighter(FighterId.BYTE), 1, 0, 0, "J", true).hyperSeconds)
        assertEquals(varun.hyperSeconds, t, 0.1f)
        assertEquals("then everything is as it was", Triple(hp, hp, dmg), Triple(varun.maxHp, varun.hp, varun.attackDamage))
    }

    @Test fun varunsOwnHyperSpeedsUpHisRocketsAndHisSuper() {
        val (w, varun, _) = varunBehindAWall()
        val plain = varun.def.attack.speed
        varun.hyperCharge = 1f; varun.control.hyper = true
        varun.control.aimX = 1f; varun.control.aimY = 0f; varun.control.attack = true
        w.step(Match.STEP)
        val fast = w.projectiles.firstOrNull()?.let { kotlin.math.hypot(it.vx, it.vy) }
        // (The hyper comes on after the shot is asked for in the same tick, so fire again to be sure.)
        varun.control.attack = true; varun.attackCooldown = 0f; varun.pending.clear(); w.projectiles.clear()
        w.step(Match.STEP)
        val speed = kotlin.math.hypot(w.projectiles.first().vx, w.projectiles.first().vy)
        assertEquals("rockets fly faster during his hyper", plain * varun.def.hyper!!.shotSpeed, speed, 0.01f)
        assertTrue(fast == null || fast >= plain)
        varun.superCharge = 1f; varun.control.superAttack = true
        w.step(Match.STEP)
        assertEquals("and the rain comes down sooner", Balance.RAIN_DELAY_SECONDS / varun.def.hyper!!.shotSpeed, w.hazards[0].delay, 0.001f)
    }

    @Test fun twoDevicesStayInStepInA1v1() {
        // The same match as each device builds it: one plays side 0, the other side 1.
        val a = Match(MatchConfig(FighterId.VARUN, 5, 0, "A", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = 9L, duel = io.github.projectwip.sim.DuelSetup(0, FighterId.KITO, 7, 1, "B")))
        val b = Match(MatchConfig(FighterId.KITO, 7, 1, "B", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = 9L, duel = io.github.projectwip.sim.DuelSetup(1, FighterId.VARUN, 5, 0, "A")))
        assertEquals("each device's player is on its own side", 0 to 1, a.player.team to b.player.team)
        assertTrue("nobody in a 1v1 is a bot", a.brains.isEmpty() && a.world.fighters.none { it.isBot } && a.world.fighters.size == 2)
        assertEquals(listOf("A", "B"), a.world.fighters.map { it.name })
        assertEquals("both devices line the fighters up the same way", a.world.fighters.map { it.name to it.def.id }, b.world.fighters.map { it.name to it.def.id })
        val hands = Random(3)
        var t = 0
        while (t < 60 * 90 && !a.isOver) {
            // What each player does goes to both devices.
            for ((me, there) in listOf(a.player to b.opponent!!.control, b.player to a.opponent!!.control)) {
                val here = me.control
                val foe = (if (me === a.player) a.opponent else b.opponent)!!
                // Each walks at the other, weaving, and shoots at them most of the time.
                if (t % 9 == 0) { here.moveX = (foe.x - me.x) * 0.3f + hands.nextFloat() - 0.5f; here.moveY = (foe.y - me.y) * 0.3f + hands.nextFloat() - 0.5f }
                here.aiming = true
                here.aimX = foe.x - me.x + hands.nextFloat() - 0.5f; here.aimY = foe.y - me.y + hands.nextFloat() - 0.5f
                here.attack = hands.nextInt(7) == 0; here.superAttack = hands.nextInt(30) == 0; here.hyper = hands.nextInt(40) == 0
                there.moveX = here.moveX; there.moveY = here.moveY; there.aimX = here.aimX; there.aimY = here.aimY
                there.aiming = here.aiming; there.attack = here.attack; there.superAttack = here.superAttack; there.hyper = here.hyper
            }
            a.step(Match.STEP); b.step(Match.STEP)
            a.world.events.clear(); b.world.events.clear()
            t++
            if (t % 30 == 0) assertEquals("the checksums the devices would exchange agree at tick $t", a.world.checksum(), b.world.checksum())
        }
        // The checksum notices the smallest disagreement.
        val before = a.world.checksum()
        a.world.fighters[0].hp -= 1
        assertTrue(before != a.world.checksum())
        a.world.fighters[0].hp += 1
        fun state(m: Match) = m.world.fighters.map { listOf(it.x, it.y, it.hp, it.kos, it.superCharge, it.hyperCharge) } + listOf(m.world.score.toList(), listOf(m.world.phase, m.world.winningTeam))
        assertEquals("after $t ticks the two devices agree on everything", state(a), state(b))
        assertTrue("and something happened", a.world.fighters.sumOf { it.damageDealt } > 0)
        // One player leaving hands the other the win.
        val c = Match(MatchConfig(FighterId.BYTE, 1, 0, "A", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = 2L, duel = io.github.projectwip.sim.DuelSetup(1, FighterId.BYTE, 1, 0, "B")))
        c.world.forfeit(c.opponent!!.team)
        assertEquals(io.github.projectwip.data.MatchOutcome.VICTORY, c.report().outcome)
        // A match the devices disagree about is called off: a draw.
        val e = Match(MatchConfig(FighterId.BYTE, 1, 0, "A", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = 2L, duel = io.github.projectwip.sim.DuelSetup(0, FighterId.BYTE, 1, 0, "B")))
        e.world.abandon()
        assertEquals(io.github.projectwip.data.MatchOutcome.DRAW, e.report().outcome)
        // Offline, a 1v1 is practice against one bot.
        val d = Match(MatchConfig(FighterId.BYTE, 1, 0, "A", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = 2L))
        assertEquals(1, d.brains.size)
    }

    @Test fun theHyperChargesFromHitsAndBotsUseIt() {
        val m = Match(MatchConfig(FighterId.VARUN, 5, 0, "T", BotDifficulty.HARD, mode = GameMode.KNOCKOUT_RUSH, humanPlayer = false, seed = 21L))
        var hypers = 0
        var t = 0f
        while (m.world.phase != Phase.ENDED && t < 200f) {
            m.step(Match.STEP); t += Match.STEP
            hypers += m.world.events.count { it is io.github.projectwip.sim.GameEvent.Hyper }
            m.world.events.clear()
        }
        println("hypers used in one Knockout Rush: $hypers")
        assertTrue("bots charge and use their hyper", hypers > 0)
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

    /** Buddy and one or two Brakks on open ground, two tiles apart in a row, past the countdown and the spawn shield. */
    private fun buddyAndTargets(count: Int = 1, level: Int = 1): Triple<World, Fighter, List<Fighter>> {
        val a = Arenas.staticCanyon()
        val buddy = Fighter(0, Balance.fighter(FighterId.BUDDY), level, 0, 0, "B", true)
        val others = (1..count).map { Fighter(it, Balance.fighter(FighterId.BRAKK), 1, 0, 1, "T$it", true) }
        val w = World(a, listOf(buddy) + others, io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w.step(Match.STEP) }
        var placed = false
        search@ for (y in 3 until a.height - 3) for (x in 3 until a.width - 9) {
            val x0 = x + 0.5f; val y0 = y + 0.5f
            if ((0..7).any { a.circleBlocked(x0 + it, y0, 0.9f) || a.inThicket(x0 + it, y0) }) continue
            buddy.x = x0; buddy.y = y0
            others.forEachIndexed { i, o -> o.x = x0 + 2f + i * 4f; o.y = y0 }
            placed = true
            break@search
        }
        assertTrue(placed)
        for (f in listOf(buddy) + others) f.shield = 0f
        w.events.clear()
        return Triple(w, buddy, others)
    }

    @Test fun buddyHurlsTwoComputersThatSmashIntoTheFirstInTheirWay() {
        val (w, buddy, others) = buddyAndTargets(2)
        val (near, far) = others
        buddy.control.aimX = 1f; buddy.control.aimY = 0f; buddy.control.attack = true
        val hits = ArrayList<io.github.projectwip.sim.GameEvent.Hit>()
        repeat(50) { w.step(Match.STEP); hits += w.events.filterIsInstance<io.github.projectwip.sim.GameEvent.Hit>(); w.events.clear() }
        assertEquals("two heavy hits, both on the first in their way", listOf(near.id to buddy.attackDamage, near.id to buddy.attackDamage), hits.map { it.targetId to it.damage })
        assertEquals("they stop at whoever they smash into", far.maxHp, far.hp)
        assertTrue("and they are thrown a long way", buddy.def.attack.range >= 8f)
    }

    @Test fun buddysMalformedCodePoisonsTheNearestEnemyForAWhileAndThenWearsOff() {
        val (w, buddy, others) = buddyAndTargets(2)
        val (near, far) = others
        buddy.superCharge = 1f
        // No aiming: it points the other way and still finds the nearest one.
        buddy.control.aimX = -1f; buddy.control.aimY = 0f; buddy.control.superAttack = true
        w.step(Match.STEP)
        assertTrue("the nearest enemy is the one poisoned", near.poisoned && !far.poisoned)
        assertEquals(0f, buddy.superCharge, 0f)
        var t = 0f
        var healedWhilePoisoned = false
        var last = near.hp
        while (near.poisoned && t < 30f) {
            w.step(Match.STEP); t += Match.STEP
            if (near.hp > last) healedWhilePoisoned = true
            last = near.hp
        }
        assertEquals("it wears off by itself", Balance.POISON_SECONDS, t, 0.2f)
        assertTrue("having hurt, but not finished, a healthy fighter", near.alive && near.hp < near.maxHp)
        assertFalse("who could not heal through it", healedWhilePoisoned)
        assertEquals(far.maxHp, far.hp)
        assertTrue("a boss shakes it off sooner still", Balance.POISON_GIANT_SECONDS < Balance.POISON_SECONDS)
        // It only reaches three quarters as far as it used to.
        assertEquals(6.75f, buddy.def.superSpec.range, 0f)
        println("malformed code did ${near.maxHp - near.hp} of ${near.maxHp} health")
    }

    @Test fun brakksPawPrintsTakeAShareOfTheHealthTheirTargetHasLeft() {
        val (w, _, others) = buddyAndTargets(1)
        val target = others[0]
        // (A new world puts everyone back on a spawn: remember the open ground the helper found.)
        val x = target.x; val y = target.y
        val brakk = Fighter(7, Balance.fighter(FighterId.BRAKK), 1, 0, 0, "D", false)
        val w2 = World(w.arena, listOf(brakk, target), io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w2.step(Match.STEP) }
        fun throwAt(hp: Int): List<Int> {
            target.x = x; target.y = y
            brakk.x = x - 3f; brakk.y = y; brakk.shield = 0f; target.shield = 0f
            target.hp = hp; brakk.ammo = 3f
            brakk.control.aimX = 1f; brakk.control.aimY = 0f; brakk.control.attack = true
            val hits = ArrayList<Int>()
            repeat(40) { w2.step(Match.STEP); hits += w2.events.filterIsInstance<io.github.projectwip.sim.GameEvent.Hit>().map { it.damage }; w2.events.clear() }
            return hits
        }
        val share = brakk.def.attack.healthShare
        val full = target.maxHp
        val first = (full * share).toInt()
        assertEquals("two paw prints, each a share of what is left", listOf(first, ((full - first) * share).toInt()), throwAt(full))
        assertEquals("never less than its own damage", listOf(brakk.attackDamage, brakk.attackDamage), throwAt(2000))
        assertTrue("and he throws them a long way", brakk.def.attack.range >= 8f)
    }

    @Test fun kitosHammerQuakesInEveryDirectionWhereItComesDown() {
        val (w, _, others) = buddyAndTargets(2)
        val (hit, beside) = others
        val x = hit.x; val y = hit.y
        val kito = Fighter(7, Balance.fighter(FighterId.KITO), 1, 0, 0, "K", false)
        val w2 = World(w.arena, listOf(kito, hit, beside), io.github.projectwip.sim.MatchRules.lastSpark())
        repeat((3.1f / Match.STEP).toInt()) { w2.step(Match.STEP) }
        kito.x = x - 3f; kito.y = y; hit.x = x; hit.y = y
        // The other stands behind the first, out of the hammer's way but inside the quake.
        beside.x = hit.x + 1.6f; beside.y = hit.y
        for (f in w2.fighters) f.shield = 0f
        assertEquals(8000 to 2200, kito.def.health.base to kito.def.attackDamage.base * kito.def.attack.projectiles)
        kito.superCharge = 1f
        kito.control.aimX = 1f; kito.control.aimY = 0f; kito.control.superAttack = true
        val quakes = ArrayList<io.github.projectwip.sim.GameEvent.Quake>()
        repeat(40) { w2.step(Match.STEP); quakes += w2.events.filterIsInstance<io.github.projectwip.sim.GameEvent.Quake>(); w2.events.clear() }
        assertEquals("the hammer comes down once", 1, quakes.size)
        assertEquals("on the first enemy in its way", hit.x, quakes[0].x, 0.9f)
        assertEquals(kito.def.superSpec.radius, quakes[0].radius, 0f)
        assertEquals("the quake hits them", hit.maxHp - kito.superDamage, hit.hp)
        assertEquals("and everyone else near where it landed", beside.maxHp - kito.superDamage, beside.hp)
        assertFalse("he doesn't go anywhere himself", kito.isDashing)
    }

    @Test fun buddyKeepsHisSuperWhenThereIsNobodyToPoison() {
        val (w, buddy, others) = buddyAndTargets(1)
        buddy.superCharge = 1f
        // Out of reach: move the target right away.
        others[0].x = (buddy.x + 30f).coerceAtMost(w.arena.width - 2f); others[0].y = (buddy.y + 30f).coerceAtMost(w.arena.height - 2f)
        if (kotlin.math.hypot(others[0].x - buddy.x, others[0].y - buddy.y) > buddy.def.superSpec.range) {
            buddy.control.superAttack = true
            w.step(Match.STEP)
            assertEquals("the charge is kept", 1f, buddy.superCharge, 0f)
            assertFalse(others[0].poisoned)
        }
    }

    @Test fun aBossShrugsThePoisonOffAfterAWhile() {
        val a = Arenas.provingGround()
        val buddy = Fighter(0, Balance.fighter(FighterId.BUDDY), 1, 0, 0, "B", false)
        val boss = Fighter(1, Balance.bosses[0], 1, 0, 1, "Boss", true)
        val w = World(a, listOf(buddy, boss), io.github.projectwip.sim.MatchRules.bossMode())
        repeat((3.1f / Match.STEP).toInt()) { w.step(Match.STEP) }
        boss.shield = 0f
        buddy.superCharge = 1f
        if (w.corruptTarget(buddy) != null) {
            buddy.control.superAttack = true
            w.step(Match.STEP)
            assertTrue(boss.poisoned)
            repeat(((Balance.POISON_GIANT_SECONDS + 1f) / Match.STEP).toInt()) { w.step(Match.STEP) }
            assertFalse("a boss is not doomed by one super", boss.poisoned)
            assertTrue(boss.alive && boss.hp < boss.maxHp)
        }
    }

    @Test fun botsCanPlayBuddyThroughAWholeMatch() {
        val m = Match(MatchConfig(FighterId.BUDDY, 5, 0, "T", BotDifficulty.HARD, mode = GameMode.KNOCKOUT_RUSH, humanPlayer = false, seed = 33L))
        var t = 0f
        while (m.world.phase != Phase.ENDED && t < 200f) { m.step(Match.STEP); t += Match.STEP; m.world.events.clear() }
        println("Buddy bot: ${m.player.kos} KOs, ${m.player.deaths} deaths, ${m.player.damageDealt} damage")
        assertTrue("a bot playing Buddy gets close enough to hit things", m.player.damageDealt > 0)
    }
}
