package io.github.projectwip.sim

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Everything the player did in a match: their [Control] on every tick, exactly as the simulation saw it.
 *
 * The simulation is deterministic, so this log plus the match's seed is the whole match. The game server plays
 * it back through the same code ([Referee]) to see for itself how the match went, instead of taking the
 * device's word for the result.
 *
 * The format is a run-length list of records, each 19 bytes, big-endian:
 * ticks (2 bytes, unsigned) · flags (1: aiming, 2: attack, 4: super) · moveX · moveY · aimX · aimY (4-byte floats).
 * Floats are stored bit for bit, so the replay gets exactly the numbers the match did.
 */
class InputLog {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)
    private var run = 0
    private var flags = 0
    private var moveX = 0
    private var moveY = 0
    private var aimX = 0
    private var aimY = 0

    /** Ticks recorded so far. */
    var ticks = 0
        private set

    /** Notes what [c] holds for the tick that is about to run. */
    fun record(c: Control) {
        val f = (if (c.aiming) AIMING else 0) or (if (c.attack) ATTACK else 0) or (if (c.superAttack) SUPER else 0)
        val mx = c.moveX.toRawBits()
        val my = c.moveY.toRawBits()
        val ax = c.aimX.toRawBits()
        val ay = c.aimY.toRawBits()
        if (run in 1 until MAX_RUN && f == flags && mx == moveX && my == moveY && ax == aimX && ay == aimY) {
            run++
        } else {
            flush()
            run = 1; flags = f; moveX = mx; moveY = my; aimX = ax; aimY = ay
        }
        ticks++
    }

    private fun flush() {
        if (run == 0) return
        out.writeShort(run)
        out.writeByte(flags)
        out.writeInt(moveX); out.writeInt(moveY); out.writeInt(aimX); out.writeInt(aimY)
        run = 0
    }

    /** The log so far. Recording can carry on afterwards. */
    fun toBytes(): ByteArray {
        flush()
        return bytes.toByteArray()
    }

    companion object {
        private const val AIMING = 1
        private const val ATTACK = 2
        private const val SUPER = 4
        private const val MAX_RUN = 65535

        /**
         * Plays a log back: [tick] is called once per recorded tick, after [control] has been set to what the
         * player's control held on that tick. Stops early (returning false) if [tick] does. A log cut short in
         * the middle of a record simply ends there.
         */
        fun replay(log: ByteArray, control: Control, tick: () -> Boolean): Boolean {
            val input = DataInputStream(ByteArrayInputStream(log))
            try {
                while (true) {
                    val count = try { input.readUnsignedShort() } catch (_: EOFException) { return true }
                    val f = input.readUnsignedByte()
                    val mx = Float.fromBits(input.readInt())
                    val my = Float.fromBits(input.readInt())
                    val ax = Float.fromBits(input.readInt())
                    val ay = Float.fromBits(input.readInt())
                    repeat(count) {
                        control.moveX = mx; control.moveY = my; control.aimX = ax; control.aimY = ay
                        control.aiming = f and AIMING != 0
                        control.attack = f and ATTACK != 0
                        control.superAttack = f and SUPER != 0
                        if (!tick()) return false
                    }
                }
            } catch (_: EOFException) {
                return true
            }
        }
    }
}
