package io.github.projectwip.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.projectwip.data.BossKind
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.sim.Control
import io.github.projectwip.sim.TeamPlayer
import io.github.projectwip.sim.TeamSetup
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
 * The line to a team: two or three real players who share one Boss Mode or Knockout Rush match.
 *
 * The game server's lobby (`server/astro/team.py`, on the 1v1 lobby's port) keeps the team together under a short
 * code, and during a match passes each player's inputs to the others. The match itself is run the way a 1v1 is
 * ([DuelLink]): every device runs the same simulation, bots included, a player's input is played [DuelLink.DELAY]
 * ticks after it was made, and a tick only runs once every player's input for it is in.
 *
 * The line stays open between matches: the team is still together afterwards, and its leader can start another.
 */
class TeamLink(private val host: String, private val port: Int) : Closeable {
    /** The team as the lobby describes it. Member 0 leads; [you] is this player's place in [members]. */
    class Roster(val code: String, val mode: GameMode, val boss: BossKind?, val you: Int, val members: List<TeamPlayer>) {
        val leading: Boolean get() = you == 0
    }

    /** A match the lobby has started: everything every device needs to build the same one. */
    class Start(
        val seed: Long, val mode: GameMode, val boss: BossKind?, val difficulty: BotDifficulty,
        val botNames: List<String>, val bots: JSONObject?, val setup: TeamSetup,
    )

    private class Frame(val flags: Int, val moveX: Float, val moveY: Float, val aimX: Float, val aimY: Float) {
        fun into(c: Control) {
            c.moveX = moveX; c.moveY = moveY; c.aimX = aimX; c.aimY = aimY
            c.aiming = flags and 1 != 0; c.attack = flags and 2 != 0; c.superAttack = flags and 4 != 0; c.hyper = flags and 8 != 0
        }
    }

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val lock = Any()
    /** Every player's frames for ticks [DuelLink.DELAY], [DuelLink.DELAY] + 1, ... by slot: this player's own, and the others' as they arrive. */
    private var frames: List<ArrayList<Frame>> = emptyList()
    /** For a player who has gone: how many of their frames count. From then on their fighter stands still. */
    private val gone = HashMap<Int, Int>()
    private var slot = 0

    /** Who is in the team. Null until the lobby has said. (These are read by the screens, so they are state.) */
    var roster by mutableStateOf<Roster?>(null)
        private set
    /** The match to play now, once the leader has pressed Play. Null between matches. */
    var start by mutableStateOf<Start?>(null)
        private set
    /** Why the lobby turned this player away, if it did. */
    var error by mutableStateOf<String?>(null)
        private set
    /** Something the lobby wants said (a team needs two players before it can start). */
    var note by mutableStateOf<String?>(null)
        private set
    /** The line is gone: the team is over for this player. */
    var ended by mutableStateOf(false)
        private set

    /** The lobby says this player stopped responding and was taken out of the match: they lose. */
    @Volatile var dropped = false
        private set
    /** The match was called off (the devices disagreed, or everyone stalled), or the line went. */
    @Volatile var calledOff = false
        private set
    @Volatile private var verdict: JSONObject? = null

    /**
     * Makes a team or joins one ([hello] says which: see `GameServer.teamHello`). Blocking: call it off the main
     * thread. False if the lobby can't be reached; if it turns the player away, [error] says why.
     */
    fun open(hello: JSONObject): Boolean = try {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4000)
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out = o
        o.writeByte('T'.code); o.writeUTF(hello.toString()); o.flush()
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        Thread({ listen(input) }, "team-link").apply { isDaemon = true }.start()
        true
    } catch (_: Exception) {
        close()
        false
    }

    private fun fighter(name: String) = FighterId.entries.firstOrNull { it.name == name } ?: FighterId.BYTE

    private fun players(o: org.json.JSONArray) = (0 until o.length()).map { i ->
        val p = o.getJSONObject(i)
        TeamPlayer(fighter(p.optString("fighter")), p.optInt("level", 1), p.optInt("skin"), p.optString("name", "Player"))
    }

    private fun listen(input: DataInputStream) {
        try {
            while (true) {
                when (input.readUnsignedByte().toChar()) {
                    'R' -> {
                        val j = JSONObject(input.readUTF())
                        roster = Roster(
                            j.getString("code"), GameMode.entries.firstOrNull { it.name == j.optString("mode") } ?: GameMode.BOSS,
                            BossKind.entries.firstOrNull { it.name == j.optString("boss") }, j.getInt("you"), players(j.getJSONArray("members")),
                        )
                        note = null
                    }
                    'S' -> {
                        val j = JSONObject(input.readUTF())
                        val team = players(j.getJSONArray("players"))
                        val names = j.optJSONArray("botNames")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                        synchronized(lock) {
                            slot = j.getInt("slot")
                            frames = List(team.size) { ArrayList() }
                            gone.clear()
                        }
                        dropped = false; calledOff = false; verdict = null
                        start = Start(
                            j.getLong("seed"), GameMode.entries.firstOrNull { it.name == j.optString("mode") } ?: GameMode.BOSS,
                            BossKind.entries.firstOrNull { it.name == j.optString("boss") },
                            BotDifficulty.entries.firstOrNull { it.name == j.optString("difficulty") } ?: BotDifficulty.NORMAL,
                            names, j.optJSONObject("bots"), TeamSetup(j.getInt("slot"), team),
                        )
                    }
                    'I' -> {
                        val from = input.readUnsignedByte()
                        val f = Frame(input.readUnsignedByte(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                        synchronized(lock) { frames.getOrNull(from)?.add(f) }
                    }
                    'Q' -> {
                        val from = input.readUnsignedByte()
                        val count = input.readInt()
                        synchronized(lock) { gone[from] = count }
                    }
                    'L' -> dropped = true
                    'D' -> calledOff = true
                    'V' -> verdict = JSONObject(input.readUTF())
                    'N' -> note = input.readUTF()
                    'E' -> { error = input.readUTF(); break }
                    else -> break
                }
            }
        } catch (_: Exception) {
        }
        calledOff = true
        ended = true
        close()
    }

    private fun send(write: (DataOutputStream) -> Unit) {
        try {
            synchronized(lock) { out?.let { write(it); it.flush() } }
        } catch (_: IOException) {
            // The listener notices the line has gone.
        }
    }

    /** The leader presses Play. */
    fun go() = send { it.writeByte('G'.code) }

    /** Records what the player wants ([c], as it stands now) as their next frame, and sends it to the team. */
    fun sendLocal(c: Control) {
        val flags = (if (c.aiming) 1 else 0) or (if (c.attack) 2 else 0) or (if (c.superAttack) 4 else 0) or (if (c.hyper) 8 else 0)
        synchronized(lock) { frames.getOrNull(slot)?.add(Frame(flags, c.moveX, c.moveY, c.aimX, c.aimY)) }
        send { o ->
            o.writeByte('I'.code); o.writeByte(flags)
            o.writeFloat(c.moveX); o.writeFloat(c.moveY); o.writeFloat(c.aimX); o.writeFloat(c.aimY)
        }
    }

    /** Tells the lobby what this device makes of the match after [tick] ticks; it holds everyone's against each other. */
    fun check(tick: Int, sum: Int) = send { it.writeByte('C'.code); it.writeInt(tick); it.writeInt(sum) }

    /** How many of this player's frames have been recorded: the next one is for tick [DuelLink.DELAY] + this. */
    val sent: Int get() = synchronized(lock) { frames.getOrNull(slot)?.size ?: 0 }

    /** Whether everyone's inputs for [tick] are in, so it can be run. A player who has gone isn't waited for. */
    fun ready(tick: Int): Boolean {
        val at = tick - DuelLink.DELAY
        if (at < 0) return true
        return synchronized(lock) { frames.indices.all { s -> at < frames[s].size || (gone[s]?.let { at >= it } ?: false) } }
    }

    /** Sets every player's control ([controls], in slot order) to what they did on [tick]. */
    fun apply(tick: Int, controls: List<Control>) {
        val at = tick - DuelLink.DELAY
        synchronized(lock) {
            for ((s, c) in controls.withIndex()) {
                val theirs = frames.getOrNull(s)
                val counted = gone[s]
                if (at < 0 || theirs == null || at >= theirs.size || (counted != null && at >= counted)) c.clear() else theirs[at].into(c)
            }
        }
    }

    /**
     * Tells the lobby this device's match is over and waits for what it was worth (the lobby replays the match
     * from everyone's inputs). Blocking: call it off the main thread. Null if there was nothing to give. Either
     * way the match is finished with: [start] is cleared, ready for the next one.
     */
    fun result(): JSONObject? {
        if (!dropped && !calledOff) send { it.writeByte('F'.code) }
        val until = System.currentTimeMillis() + 40_000
        while (verdict == null && !ended && !calledOff && System.currentTimeMillis() < until) Thread.sleep(50)
        // (A match that was called off has no verdict to wait for, but one may be on its way after a drop.)
        if (verdict == null && dropped) {
            val soon = System.currentTimeMillis() + 3000
            while (verdict == null && !ended && System.currentTimeMillis() < soon) Thread.sleep(50)
        }
        val v = verdict
        start = null
        return v?.takeIf { it.has("cups") }
    }

    /** Leaves the team. */
    override fun close() {
        ended = true
        try { socket?.close() } catch (_: IOException) {}
    }
}
