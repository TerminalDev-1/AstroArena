package io.github.projectwip

import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.net.DuelLink
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.MatchConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Two players' [DuelLink]s talking through a stand-in for the server's lobby: the real sockets, frames and
 * checks, with each side running its own copy of the match the way the game does.
 */
class DuelLinkTest {
    /** Pairs the first two connections, tells each to start, then passes every byte one sends to the other. */
    private fun lobby(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            val seats = List(2) { server.accept().apply { tcpNoDelay = true } }
            val fighters = seats.map { s ->
                val input = DataInputStream(s.getInputStream())
                assertEquals('H'.code, input.readUnsignedByte())
                JSONObject(input.readUTF()).getString("fighter")
            }
            for ((side, s) in seats.withIndex()) {
                val them = JSONObject().put("name", "P${1 - side}").put("fighter", fighters[1 - side]).put("level", 3).put("skin", 0)
                DataOutputStream(s.getOutputStream()).apply {
                    writeByte('S'.code); writeUTF(JSONObject().put("seed", 77L).put("side", side).put("level", 3).put("opponent", them).toString()); flush()
                }
            }
            fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
                try {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = from.getInputStream().read(buffer)
                        if (n < 0) break
                        to.getOutputStream().write(buffer, 0, n); to.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                }
                // As the real lobby does: the one left behind is told the other has gone.
                try { to.getOutputStream().write('X'.code); to.getOutputStream().flush() } catch (_: Exception) {}
                try { to.close() } catch (_: Exception) {}
            }
            pipe(seats[0], seats[1]); pipe(seats[1], seats[0])
        }
        return server
    }

    /** One device: joins, then plays [ticks] ticks in step with the other, the way `MatchRunner` does. */
    private class Device(port: Int, val fighter: FighterId, script: Long, val cheatAt: Int = -1) {
        val link = DuelLink("127.0.0.1", port)
        lateinit var match: Match
        private val hands = Random(script)
        var ticksRun = 0

        fun play(ticks: Int) {
            val start = link.find(JSONObject().put("token", "t").put("version", "1").put("fighter", fighter.name).put("skin", 0))!!
            match = Match(MatchConfig(fighter, start.level, 0, "Me", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = start.seed, duel = start.setup))
            val me = match.player
            val them = match.opponent!!
            val wanted = io.github.projectwip.sim.Control()
            var tick = 0
            while (tick < ticks && !link.over) {
                if (link.sent <= tick) {
                    // What this player wants now: walk at the other, shoot at them.
                    wanted.moveX = (them.x - me.x) * 0.3f + hands.nextFloat() - 0.5f; wanted.moveY = (them.y - me.y) * 0.3f + hands.nextFloat() - 0.5f
                    wanted.aiming = true; wanted.aimX = them.x - me.x; wanted.aimY = them.y - me.y
                    wanted.attack = hands.nextInt(6) == 0; wanted.superAttack = hands.nextInt(25) == 0; wanted.hyper = hands.nextInt(30) == 0
                    link.sendLocal(wanted)
                }
                if (!link.ready(tick)) { Thread.sleep(1); continue }
                if (tick == cheatAt) me.hp -= 1
                if (tick % DuelLink.CHECK_EVERY == 0) link.check(tick, match.world.checksum())
                link.apply(tick, me.control, them.control)
                match.step(Match.STEP)
                match.world.events.clear()
                tick++
            }
            ticksRun = tick
        }
    }

    private fun run(a: Device, b: Device, ticks: Int) {
        val threads = listOf(a, b).map { d -> thread { d.play(ticks) } }
        for (t in threads) t.join(30_000)
        assertTrue("both devices finished", threads.none { it.isAlive })
    }

    @Test fun twoLinkedDevicesPlayTheSameMatch() {
        val server = lobby()
        val a = Device(server.localPort, FighterId.BUDDY, 1L)
        val b = Device(server.localPort, FighterId.KITO, 2L)
        run(a, b, 60 * 30)
        assertFalse("they never fell out of step", a.link.outOfStep || b.link.outOfStep)
        assertEquals("both ran every tick", 60 * 30 to 60 * 30, a.ticksRun to b.ticksRun)
        assertEquals("and agree on how the match stands", a.match.world.checksum(), b.match.world.checksum())
        assertEquals("each is on its own side", setOf(0, 1), setOf(a.match.player.team, b.match.player.team))
        assertNotNull(a.match.opponent)
        assertTrue("they really fought", a.match.world.fighters.sumOf { it.damageDealt } > 0)
        a.link.close(); b.link.close(); server.close()
    }

    @Test fun aDeviceThatComputesSomethingElseIsCaught() {
        val server = lobby()
        val a = Device(server.localPort, FighterId.BYTE, 1L, cheatAt = 100)
        val b = Device(server.localPort, FighterId.BYTE, 2L)
        run(a, b, 60 * 20)
        assertTrue("both devices see that they disagree", a.link.outOfStep && b.link.outOfStep)
        assertTrue("within a second of it happening", a.ticksRun < 100 + 4 * DuelLink.CHECK_EVERY && b.ticksRun < 100 + 4 * DuelLink.CHECK_EVERY)
        a.link.close(); b.link.close(); server.close()
    }

    @Test fun leavingIsNoticedByTheOther() {
        val server = lobby()
        val a = Device(server.localPort, FighterId.BYTE, 1L)
        val b = Device(server.localPort, FighterId.BYTE, 2L)
        val ta = thread { a.play(120) }
        val tb = thread { b.play(60 * 60) }
        ta.join(20_000)
        a.link.close()
        tb.join(20_000)
        assertFalse(tb.isAlive)
        assertTrue("the one left behind is told the other has gone, and so wins", b.link.remoteLeft)
        assertFalse("and is not told anything else", b.link.outOfStep || b.link.dropped || b.link.lost)
        server.close()
    }

    /**
     * The server's replay of a 1v1 (from the frames its lobby passed on) is the match the devices played: it ends
     * on the same tick with the same winner, whichever side's device it is held against.
     */
    @Test fun theRefereeReplaysA1v1Exactly() {
        val ticks = 60 * 150
        val hands = Random(5)
        val fighters = listOf(FighterId.KITO, FighterId.BYTE)
        val levels = listOf(9, 2)
        // One device (side 1's), run the way MatchRunner runs it.
        val start = DuelLink.Start(41L, levels[1], io.github.projectwip.sim.DuelSetup(1, fighters[0], levels[0], 0, "Them"))
        val device = Match(MatchConfig(fighters[1], start.level, 0, "Me", BotDifficulty.NORMAL, mode = GameMode.DUEL, seed = start.seed, duel = start.setup))
        val sides = listOf(device.opponent!!, device.player)
        // What each player sent, as the lobby would have kept it.
        val sent = List(2) { java.nio.ByteBuffer.allocate((ticks + DuelLink.DELAY) * 17) }
        var tick = 0
        var sumAt300 = 0
        while (tick < ticks + DuelLink.DELAY && !device.isOver) {
            if (tick == 300) sumAt300 = device.world.checksum()
            // What each player wants now (to be played DELAY ticks from now): walk at the other and shoot at them.
            for (side in 0..1) {
                val me = sides[side]
                val them = sides[1 - side]
                sent[side].put((1 or (if (hands.nextInt(6) == 0) 2 else 0) or (if (hands.nextInt(25) == 0) 4 else 0) or (if (hands.nextInt(30) == 0) 8 else 0)).toByte())
                sent[side].putFloat((them.x - me.x) * 0.3f + hands.nextFloat() - 0.5f); sent[side].putFloat((them.y - me.y) * 0.3f + hands.nextFloat() - 0.5f)
                sent[side].putFloat(them.x - me.x); sent[side].putFloat(them.y - me.y)
            }
            for (side in 0..1) {
                val c = sides[side].control
                if (tick < DuelLink.DELAY) { c.clear(); continue }
                val b = java.nio.ByteBuffer.wrap(sent[side].array(), (tick - DuelLink.DELAY) * 17, 17)
                val flags = b.get().toInt()
                c.moveX = b.getFloat(); c.moveY = b.getFloat(); c.aimX = b.getFloat(); c.aimY = b.getFloat()
                c.aiming = flags and 1 != 0; c.attack = flags and 2 != 0; c.superAttack = flags and 4 != 0; c.hyper = flags and 8 != 0
            }
            device.step(Match.STEP)
            device.world.events.clear()
            tick++
        }
        val frames = sent.map { it.array().copyOf(it.position()) }
        val verdict = io.github.projectwip.sim.Referee.judgeDuel(41L, fighters, levels, frames[0], frames[1], 300, intArrayOf(sumAt300, sumAt300 + 1))
        println("referee 1v1: ${verdict.ticks} ticks, finished=${verdict.finished}, winner=${verdict.winner}, kos ${verdict.fighters.map { it.kos }}")
        assertTrue("the match was played to its end", device.isOver && verdict.finished)
        assertEquals("on the same tick", tick, verdict.ticks)
        assertEquals("with the same winner", device.world.winningTeam, verdict.winner)
        assertEquals("and the same fight", sides.map { Triple(it.kos, it.deaths, it.damageDealt) }, verdict.fighters.map { Triple(it.kos, it.deaths, it.damageDealt) })
        assertTrue("the device whose number was off is the one found wrong", !verdict.wrong[0] && verdict.wrong[1])
        // Cut short (a player left), it isn't finished, and nobody has won.
        val cut = io.github.projectwip.sim.Referee.judgeDuel(41L, fighters, levels, frames[0].copyOf(17 * 60), frames[1])
        assertFalse(cut.finished)
        assertEquals(60 + DuelLink.DELAY to -1, cut.ticks to cut.winner)
    }
}
