package io.github.projectwip

import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.net.DuelLink
import io.github.projectwip.net.TeamLink
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.MatchConfig
import io.github.projectwip.sim.Referee
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A team of real players sharing one match: each device's [TeamLink] talking through a stand-in for the server's
 * lobby (real sockets and frames), every device running its own copy of the match the way the game does, and the
 * server's replay of what they played.
 */
class TeamLinkTest {
    private val fighters = listOf(FighterId.KITO, FighterId.BYTE, FighterId.BUDDY)

    private fun io.github.projectwip.sim.Fighter.damageTaken() = maxHp - hp

    /** What the stand-in lobby kept: every player's frames, as the real one keeps them for the replay. */
    private class Kept(size: Int) {
        val frames = List(size) { java.io.ByteArrayOutputStream() }
        val left = HashSet<Int>()
    }

    /**
     * Seats [size] connections as one team, starts a [mode] match as soon as they are all in, then passes every
     * frame one sends to the others with its slot, and tells the others when one hangs up.
     */
    private fun lobby(size: Int, mode: GameMode, kept: Kept): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            val seats = List(size) { server.accept().apply { tcpNoDelay = true } }
            val inputs = seats.map { DataInputStream(it.getInputStream()) }
            val outputs = seats.map { DataOutputStream(it.getOutputStream()) }
            val players = JSONArray()
            for ((slot, input) in inputs.withIndex()) {
                assertEquals('T'.code, input.readUnsignedByte())
                val hello = JSONObject(input.readUTF())
                players.put(JSONObject().put("name", "P$slot").put("fighter", hello.getString("fighter")).put("level", 5 + slot).put("skin", 0))
            }
            for ((slot, o) in outputs.withIndex()) synchronized(o) {
                o.writeByte('R'.code); o.writeUTF(JSONObject().put("code", "0042").put("mode", mode.name).put("boss", "").put("you", slot).put("members", players).toString())
                o.writeByte('S'.code); o.writeUTF(JSONObject().put("seed", 91L).put("slot", slot).put("mode", mode.name).put("boss", "SWEEPER")
                    .put("difficulty", "NORMAL").put("botNames", JSONArray(listOf("A", "B", "C", "D"))).put("players", players).toString())
                o.flush()
            }
            for (slot in 0 until size) thread(isDaemon = true) {
                var count = 0
                try {
                    while (true) {
                        when (inputs[slot].readUnsignedByte().toChar()) {
                            'I' -> {
                                val frame = ByteArray(17).also { inputs[slot].readFully(it) }
                                synchronized(kept) { kept.frames[slot].write(frame) }
                                count++
                                for (other in 0 until size) if (other != slot) synchronized(outputs[other]) {
                                    try { outputs[other].writeByte('I'.code); outputs[other].writeByte(slot); outputs[other].write(frame); outputs[other].flush() } catch (_: Exception) {}
                                }
                            }
                            'C' -> inputs[slot].skipBytes(8)
                            else -> break
                        }
                    }
                } catch (_: Exception) {
                }
                synchronized(kept) { kept.left += slot }
                for (other in 0 until size) if (other != slot) synchronized(outputs[other]) {
                    try { outputs[other].writeByte('Q'.code); outputs[other].writeByte(slot); outputs[other].writeInt(count); outputs[other].flush() } catch (_: Exception) {}
                }
            }
        }
        return server
    }

    /** One device: joins, waits for the start, then plays [ticks] ticks in step with the others, the way `MatchRunner` does. */
    private class Device(port: Int, val fighter: FighterId, script: Long) {
        val link = TeamLink("127.0.0.1", port)
        lateinit var match: Match
        private val hands = Random(script)
        var ticksRun = 0

        fun play(ticks: Int) {
            assertTrue(link.open(JSONObject().put("token", "t").put("version", "1").put("fighter", fighter.name).put("skin", 0).put("action", "join").put("code", "0042")))
            while (link.start == null) Thread.sleep(2)
            val start = link.start!!
            val me = start.setup.players[start.setup.slot]
            match = Match(MatchConfig(me.fighter, me.level, me.skin, me.name, start.difficulty, mode = start.mode, seed = start.seed, boss = start.boss, botNames = start.botNames, team = start.setup))
            val mine = match.player
            val wanted = io.github.projectwip.sim.Control()
            var tick = 0
            var waited = 0
            while (tick < ticks && !link.calledOff && !match.isOver) {
                if (link.sent <= tick) {
                    val foe = match.world.fighters.first { it.team != mine.team && it.alive || it.team != mine.team }
                    wanted.moveX = (foe.x - mine.x) * 0.2f + hands.nextFloat() - 0.5f; wanted.moveY = (foe.y - mine.y) * 0.2f + hands.nextFloat() - 0.5f
                    wanted.aiming = true; wanted.aimX = foe.x - mine.x; wanted.aimY = foe.y - mine.y
                    wanted.attack = hands.nextInt(5) == 0; wanted.superAttack = hands.nextInt(25) == 0; wanted.hyper = hands.nextInt(30) == 0
                    link.sendLocal(wanted)
                }
                if (!link.ready(tick)) { Thread.sleep(1); if (++waited > 20_000) break; continue }
                waited = 0
                if (tick % DuelLink.CHECK_EVERY == 0) link.check(tick, match.world.checksum())
                link.apply(tick, match.humans.map { it.control })
                match.step(Match.STEP)
                match.world.events.clear()
                tick++
            }
            ticksRun = tick
        }
    }

    @Test fun aTeamOfThreePlaysTheSameBossFightAndTheRefereeReplaysIt() {
        val kept = Kept(3)
        val server = lobby(3, GameMode.BOSS, kept)
        val devices = List(3) { Device(server.localPort, fighters[it], 10L + it) }
        val ticks = 60 * 40
        val threads = devices.map { d -> thread { d.play(ticks) } }
        for (t in threads) t.join(60_000)
        assertTrue("every device finished", threads.none { it.isAlive })
        val ran = devices[0].ticksRun
        println("team boss: ran ${devices.map { it.ticksRun }} of $ticks, over=${devices.map { it.match.isOver }}, winner ${devices[0].match.world.winningTeam}")
        assertEquals("all ran the same ticks", List(3) { ran }, devices.map { it.ticksRun })
        assertTrue("to the end of the test, or of the match", ran == ticks || devices.all { it.match.isOver })
        assertEquals("and agree on how the match stands", 1, devices.map { it.match.world.checksum() }.toSet().size)
        assertEquals("each plays a fighter of their own", setOf(0, 1, 2), devices.map { it.match.world.fighters.indexOf(it.match.player) }.toSet())
        assertTrue("and it is the one they brought", devices.all { it.match.player.def.id == it.fighter })
        assertEquals("three players and the boss", 4, devices[0].match.world.fighters.size)
        val boss = devices[0].match.world.fighters.last()
        assertEquals("the boss is tougher for a team of three", Math.round(65000 * (1f + 2 * Match.TEAM_BOSS_HEALTH)), boss.maxHp)
        assertTrue("they really fought it", boss.damageTaken() > 0)
        // The server's replay, from the frames the lobby kept, is that match.
        repeat(2000) { if (synchronized(kept) { kept.frames.minOf { it.size() } } < 17 * (ran - DuelLink.DELAY)) Thread.sleep(5) }
        val frames = synchronized(kept) { kept.frames.map { it.toByteArray().copyOf(17 * (ran - DuelLink.DELAY)) } }
        val verdict = Referee.judgeTeam(devices[0].match.config, frames, checkTick = 600)
        assertEquals(ran, verdict.ticks)
        assertEquals(devices[0].match.isOver, verdict.finished)
        assertEquals(devices[0].match.humans.map { Triple(it.kos, it.deaths, it.damageDealt) }, verdict.fighters.map { Triple(it.kos, it.deaths, it.damageDealt) })
        assertTrue(verdict.checksum != null)
        println("referee team boss: ${verdict.ticks} ticks, finished=${verdict.finished}, damage ${verdict.fighters.map { it.damageDealt }}, boss ${boss.hp}/${boss.maxHp}")
        for (d in devices) d.link.close()
        server.close()
    }

    @Test fun aTeamOfTwoPlays3v3WithABotAndOneOfThemCanLeave() {
        val kept = Kept(2)
        val server = lobby(2, GameMode.KNOCKOUT_RUSH, kept)
        val a = Device(server.localPort, fighters[0], 1L)
        val b = Device(server.localPort, fighters[1], 2L)
        val ta = thread { a.play(60 * 25) }
        // The second player walks out after five seconds; the first plays on, their teammate standing still.
        val tb = thread { b.play(60 * 5); b.link.close() }
        ta.join(60_000); tb.join(60_000)
        assertFalse(ta.isAlive || tb.isAlive)
        assertEquals("the one who stayed was not held up", 60 * 25, a.ticksRun)
        assertFalse(a.link.calledOff)
        val w = a.match.world
        assertEquals("three a side: two players and a bot against three bots", listOf(3, 3), listOf(0, 1).map { t -> w.fighters.count { it.team == t } })
        assertEquals(listOf(false, false, true, true, true, true), w.fighters.map { it.isBot })
        assertTrue("the one who stayed did some fighting", a.match.player.damageDealt > 0)
        // The replay, told who left, is the match the one who stayed played.
        val (sa, sb) = a.match.config.team!!.slot to b.match.config.team!!.slot
        // (The stand-in lobby may still be reading the last of what the one who stayed sent.)
        repeat(2000) { if (synchronized(kept) { kept.frames[sa].size() } < 17 * 60 * 25) Thread.sleep(5) }
        val frames = synchronized(kept) { kept.frames.mapIndexed { slot, f -> if (slot == sa) f.toByteArray().copyOf(17 * (60 * 25 - DuelLink.DELAY)) else f.toByteArray() } }
        val verdict = Referee.judgeTeam(a.match.config, frames, left = setOf(sb))
        assertEquals(60 * 25, verdict.ticks)
        assertEquals(a.match.humans.map { Triple(it.kos, it.deaths, it.damageDealt) }, verdict.fighters.map { Triple(it.kos, it.deaths, it.damageDealt) })
        println("referee team 3v3: ${verdict.ticks} ticks, finished=${verdict.finished}, score ${w.score.toList()}, kos ${verdict.fighters.map { it.kos }}")
        a.link.close()
        server.close()
    }
}
