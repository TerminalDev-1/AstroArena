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
