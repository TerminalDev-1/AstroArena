package io.github.projectwip.sim

import io.github.projectwip.data.MatchReport

/**
 * Plays a recorded match back to find out how it really went.
 *
 * This is what the game server runs (see `client/referee` and `server/astro/referee.py`): given the match it
 * set up and the player's [InputLog], it runs the same simulation the device ran and reads the result off its
 * own copy. Nothing the device claims about the result is used.
 */
object Referee {
    /** No match lasts this long; a log that does is cut off here. */
    const val MAX_TICKS = 60 * 60 * 20

    class Verdict(
        /** How the match went. A log that stops before the match is decided counts as walking out. */
        val report: MatchReport,
        /** Ticks actually played back. */
        val ticks: Int,
        /** The match reached its end (as opposed to the player leaving). */
        val finished: Boolean,
        /** A fingerprint of where everything ended up, for comparing two runs of the same match. */
        val fingerprint: Long,
    )

    fun judge(config: MatchConfig, inputs: ByteArray): Verdict {
        val match = Match(config.copy(humanPlayer = true))
        var ticks = 0
        InputLog.replay(inputs, match.player.control) {
            match.step(Match.STEP)
            match.world.events.clear()
            // Once the match has been over for as long as the game shows it, the rest of the log is ignored.
            ++ticks < MAX_TICKS && !(match.isOver && match.overFor > END_SECONDS)
        }
        return Verdict(if (match.isOver) match.report() else match.forfeit(), ticks, match.isOver, fingerprint(match))
    }

    /** The match is reported this long after it is decided (the game lingers on the last moment). */
    const val END_SECONDS = 2.8f

    /** In a 1v1, ticks between a player's input and when it is played, on both devices (6 ticks = a tenth of a second). */
    const val DUEL_DELAY = 6
    /** One input frame of a 1v1 as it crosses the network: flags, then moveX, moveY, aimX, aimY. */
    const val DUEL_FRAME_BYTES = 17

    class DuelVerdict(
        /** The replay reached the end of the match. */
        val finished: Boolean,
        val ticks: Int,
        /** The side that won (0 or 1), or -1 for a draw or a match that wasn't played out. */
        val winner: Int,
        /** The two fighters as the replay left them, side 0 first. */
        val fighters: List<Fighter>,
        /** For a tick the two devices disagreed on: whether each side's number differs from the replay's. */
        val wrong: BooleanArray,
    )

    /**
     * Plays a 1v1 back from both players' input frames ([frames0] is side 0's), exactly as the two devices ran
     * it: nothing for the first [DUEL_DELAY] ticks, then one frame from each per tick, until the match ends or
     * either player's frames run out. [checks] are the numbers the two devices gave for [checkTick]
     * (`World.checksum` before that tick ran), to be held against the replay's own.
     */
    fun judgeDuel(seed: Long, fighters: List<io.github.projectwip.data.FighterId>, levels: List<Int>, frames0: ByteArray, frames1: ByteArray, checkTick: Int = -1, checks: IntArray? = null): DuelVerdict {
        val match = Match(MatchConfig(
            fighters[0], levels[0], 0, "Player", io.github.projectwip.data.BotDifficulty.NORMAL, mode = io.github.projectwip.data.GameMode.DUEL,
            seed = seed, duel = DuelSetup(0, fighters[1], levels[1], 0, "Player"),
        ))
        val sides = listOf(match.player, match.opponent!!)
        val logs = listOf(java.nio.ByteBuffer.wrap(frames0), java.nio.ByteBuffer.wrap(frames1))
        val frames = minOf(frames0.size, frames1.size) / DUEL_FRAME_BYTES
        val wrong = BooleanArray(2)
        var tick = 0
        fun check() {
            if (tick != checkTick || checks == null) return
            val sum = match.world.checksum()
            for (side in 0..1) wrong[side] = checks[side] != sum
        }
        while (tick < frames + DUEL_DELAY && tick < MAX_TICKS && !match.isOver) {
            check()
            for (side in 0..1) {
                val c = sides[side].control
                if (tick < DUEL_DELAY) { c.clear(); continue }
                val at = (tick - DUEL_DELAY) * DUEL_FRAME_BYTES
                val flags = logs[side].get(at).toInt()
                c.moveX = logs[side].getFloat(at + 1); c.moveY = logs[side].getFloat(at + 5); c.aimX = logs[side].getFloat(at + 9); c.aimY = logs[side].getFloat(at + 13)
                c.aiming = flags and 1 != 0; c.attack = flags and 2 != 0; c.superAttack = flags and 4 != 0; c.hyper = flags and 8 != 0
            }
            match.step(Match.STEP)
            match.world.events.clear()
            tick++
        }
        if (!match.isOver) check()
        return DuelVerdict(match.isOver, tick, if (match.isOver) match.world.winningTeam else -1, sides, wrong)
    }

    class TeamVerdict(
        /** The replay reached the end of the match. */
        val finished: Boolean,
        val ticks: Int,
        /** The team that won (0 is the players'), or -1 for a draw or a match that wasn't played out. */
        val winner: Int,
        /** The players' fighters as the replay left them, in slot order. */
        val fighters: List<Fighter>,
        /** The slot of the match's most valuable player, or -1 if it was a bot (or the match wasn't played out). */
        val mvp: Int,
        /** The replay's own number for the tick it was asked to check (`World.checksum` before that tick ran), or null. */
        val checksum: Int?,
    )

    /** Sets [c] to frame [index] of a 1v1 or team input log, or to nothing if the log doesn't reach that far. */
    fun frame(log: ByteArray, index: Int, c: Control) {
        if (index < 0 || (index + 1) * DUEL_FRAME_BYTES > log.size) { c.clear(); return }
        val b = java.nio.ByteBuffer.wrap(log, index * DUEL_FRAME_BYTES, DUEL_FRAME_BYTES)
        val flags = b.get().toInt()
        c.moveX = b.getFloat(); c.moveY = b.getFloat(); c.aimX = b.getFloat(); c.aimY = b.getFloat()
        c.aiming = flags and 1 != 0; c.attack = flags and 2 != 0; c.superAttack = flags and 4 != 0; c.hyper = flags and 8 != 0
    }

    /**
     * Plays a team match back from every player's input frames ([frames], in slot order), exactly as their
     * devices ran it: nothing for the first [DUEL_DELAY] ticks, then one frame from each player per tick. A
     * player in [left] walked out (or was dropped): their fighter stands still once their frames run out, and
     * the match goes on for as long as those who stayed kept playing. [config] is the match as the server set
     * it up, with its team.
     */
    fun judgeTeam(config: MatchConfig, frames: List<ByteArray>, left: Set<Int> = emptySet(), checkTick: Int = -1): TeamVerdict {
        val match = Match(config.copy(humanPlayer = true))
        val players = match.humans
        val lengths = frames.map { it.size / DUEL_FRAME_BYTES }
        val stayed = lengths.filterIndexed { slot, _ -> slot !in left }
        val last = (if (stayed.isEmpty()) lengths.max() else stayed.min()) + DUEL_DELAY
        var tick = 0
        var checksum: Int? = null
        while (tick < last && tick < MAX_TICKS && !match.isOver) {
            if (tick == checkTick) checksum = match.world.checksum()
            for ((slot, f) in players.withIndex()) frame(frames[slot], if (tick < DUEL_DELAY) -1 else tick - DUEL_DELAY, f.control)
            match.step(Match.STEP)
            match.world.events.clear()
            tick++
        }
        if (!match.isOver && tick == checkTick) checksum = match.world.checksum()
        val mvp = if (match.isOver) players.indexOf(match.world.mvp()) else -1
        return TeamVerdict(match.isOver, tick, if (match.isOver) match.world.winningTeam else -1, players, mvp, checksum)
    }

    fun fingerprint(match: Match): Long {
        var h = 1125899906842597L
        for (f in match.world.fighters) {
            h = 31 * h + f.x.toRawBits()
            h = 31 * h + f.y.toRawBits()
            h = 31 * h + f.hp
            h = 31 * h + f.kos
            h = 31 * h + f.damageDealt
        }
        return h
    }
}
