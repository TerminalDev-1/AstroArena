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
        val a = Device(server.localPort, FighterId.VARUN, 1L)
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
        val a = Device(server.localPort, FighterId.JUNO, 1L, cheatAt = 100)
        val b = Device(server.localPort, FighterId.JUNO, 2L)
        run(a, b, 60 * 20)
        assertTrue("both devices see that they disagree", a.link.outOfStep && b.link.outOfStep)
        assertTrue("within a second of it happening", a.ticksRun < 100 + 4 * DuelLink.CHECK_EVERY && b.ticksRun < 100 + 4 * DuelLink.CHECK_EVERY)
        a.link.close(); b.link.close(); server.close()
    }

    @Test fun leavingIsNoticedByTheOther() {
        val server = lobby()
        val a = Device(server.localPort, FighterId.JUNO, 1L)
        val b = Device(server.localPort, FighterId.JUNO, 2L)
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
}
