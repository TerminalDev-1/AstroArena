package io.github.projectwip.net

import io.github.projectwip.data.FighterId
import io.github.projectwip.sim.Control
import io.github.projectwip.sim.DuelSetup
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The line to the other player in a 1v1.
 *
 * Both devices run the same deterministic simulation from the same seed, so all they need from each other is
 * what the other player did on each tick. A player's input is recorded now but played [DELAY] ticks later, on
 * both devices at once; that gap is the time it has to cross the network. A tick is only run once both players'
 * inputs for it are in ([ready]), so the two never drift apart: if one device falls behind, the other waits.
 *
 * The game server's 1v1 lobby (`server/astro/duel.py`) pairs the two players and passes the frames between them.
 */
class DuelLink(private val host: String, private val port: Int) : Closeable {
    /** What the lobby said when it found an opponent. [level] is this player's own fighter level, as the server has it. */
    class Start(val seed: Long, val level: Int, val setup: DuelSetup)

    private class Frame(val flags: Int, val moveX: Float, val moveY: Float, val aimX: Float, val aimY: Float) {
        fun into(c: Control) {
            c.moveX = moveX; c.moveY = moveY; c.aimX = aimX; c.aimY = aimY
            c.aiming = flags and 1 != 0; c.attack = flags and 2 != 0; c.superAttack = flags and 4 != 0; c.hyper = flags and 8 != 0
        }
    }

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val lock = Any()
    /** Frames for ticks [DELAY], [DELAY] + 1, ... in order: this player's, and the other's as they arrive. */
    private val local = ArrayList<Frame>()
    private val remote = ArrayList<Frame>()
    @Volatile private var closed = false

    /** The lobby says the other player has gone (they left, or stopped responding): this player wins. */
    @Volatile var remoteLeft = false
        private set

    /** The lobby says this player stopped responding (the game was put down, or its connection stalled): they lose. */
    @Volatile var dropped = false
        private set

    /** The line to the lobby went with no word on who was at fault, or both stalled together: the match is called off. */
    @Volatile var lost = false
        private set

    /** Something the lobby wants a waiting player to know (the only other player waiting is on another build). */
    @Volatile var note: String? = null
        private set

    /** The two devices disagree about the match: something made them compute it differently. It can't go on. */
    @Volatile var outOfStep = false
        private set
    private val myChecks = HashMap<Int, Int>()
    private val theirChecks = HashMap<Int, Int>()

    /** Why the lobby turned this player away, if it did. */
    @Volatile var error: String? = null
        private set

    /**
     * Joins the lobby and waits, for as long as it takes, for an opponent. Blocking: call it off the main thread.
     * Returns null if the lobby can't be reached, turns the player away, or [close] is called while waiting.
     */
    fun find(hello: JSONObject): Start? = try {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4000)
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out = o
        o.writeByte('H'.code); o.writeUTF(hello.toString()); o.flush()
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        var start: Start? = null
        waiting@ while (true) {
            when (input.readUnsignedByte().toChar()) {
                'S' -> {
                    val j = JSONObject(input.readUTF())
                    val them = j.getJSONObject("opponent")
                    val fighter = FighterId.entries.firstOrNull { it.name == them.optString("fighter") } ?: FighterId.JUNO
                    Thread({ listen(input) }, "duel-link").apply { isDaemon = true }.start()
                    start = Start(j.getLong("seed"), j.optInt("level", 1), DuelSetup(j.getInt("side"), fighter, them.optInt("level", 1), them.optInt("skin"), them.optString("name", "Player")))
                    break@waiting
                }
                'N' -> note = input.readUTF()
                'E' -> { error = input.readUTF(); close(); break@waiting }
                else -> { close(); break@waiting }
            }
        }
        start
    } catch (_: Exception) {
        close()
        null
    }

    private fun listen(input: DataInputStream) {
        try {
            while (true) {
                when (input.readUnsignedByte().toChar()) {
                    'I' -> {
                        val f = Frame(input.readUnsignedByte(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                        synchronized(lock) { remote += f }
                    }
                    'C' -> {
                        val tick = input.readInt()
                        val sum = input.readInt()
                        synchronized(lock) { theirChecks[tick] = sum; compare(tick) }
                    }
                    'X' -> { remoteLeft = true; return }
                    'L' -> { dropped = true; return }
                    else -> break   // 'D', or anything unexpected: called off
                }
            }
        } catch (_: IOException) {
        }
        // The line went without the lobby saying whose doing it was.
        lost = true
    }

    /** Records what the player wants ([c], as it stands now) as their next frame, and sends it across. */
    fun sendLocal(c: Control) {
        val flags = (if (c.aiming) 1 else 0) or (if (c.attack) 2 else 0) or (if (c.superAttack) 4 else 0) or (if (c.hyper) 8 else 0)
        synchronized(lock) { local += Frame(flags, c.moveX, c.moveY, c.aimX, c.aimY) }
        try {
            val o = out ?: return
            o.writeByte('I'.code); o.writeByte(flags)
            o.writeFloat(c.moveX); o.writeFloat(c.moveY); o.writeFloat(c.aimX); o.writeFloat(c.aimY)
            o.flush()
        } catch (_: IOException) {
            // Nothing to do here: the listener hears from the lobby what happened, or that the line is gone.
        }
    }

    /**
     * Tells the other device what this one makes of the match after [tick] ticks ([sum], from `World.checksum`),
     * and compares with what it said. Both run the same ticks, so they must agree; if they don't, [outOfStep].
     */
    fun check(tick: Int, sum: Int) {
        synchronized(lock) { myChecks[tick] = sum; compare(tick) }
        try {
            val o = out ?: return
            o.writeByte('C'.code); o.writeInt(tick); o.writeInt(sum)
            o.flush()
        } catch (_: IOException) {
        }
    }

    /** (Holding [lock].) Once both devices' numbers for [tick] are in, they are compared and forgotten. */
    private fun compare(tick: Int) {
        val mine = myChecks[tick] ?: return
        val theirs = theirChecks[tick] ?: return
        if (mine != theirs) outOfStep = true
        myChecks.remove(tick); theirChecks.remove(tick)
    }

    /** How many of this player's frames have been recorded: the next one is for tick [DELAY] + this. */
    val sent: Int get() = synchronized(lock) { local.size }

    /** Whether both players' inputs for [tick] are in, so it can be run. */
    fun ready(tick: Int): Boolean = tick < DELAY || synchronized(lock) { tick - DELAY < remote.size && tick - DELAY < local.size }

    /** Sets both controls to what their players did on [tick]. The first [DELAY] ticks have no input from anyone. */
    fun apply(tick: Int, mine: Control, theirs: Control) {
        if (tick < DELAY) {
            mine.clear(); theirs.clear()
            return
        }
        synchronized(lock) {
            local[tick - DELAY].into(mine)
            remote[tick - DELAY].into(theirs)
        }
    }

    /** True once the match can't go on, whatever the reason. */
    val over: Boolean get() = remoteLeft || dropped || lost || outOfStep

    /** Hangs up. The lobby tells the other player, and their match ends. */
    override fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: IOException) {}
    }

    companion object {
        /** Ticks between a player's input and when it is played (6 ticks = a tenth of a second). */
        const val DELAY = 6
        /** The devices compare notes this often (30 ticks = twice a second). */
        const val CHECK_EVERY = 30
    }
}
