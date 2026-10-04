package io.github.projectwip.data

/**
 * One row of the Cup ladder. The ladder is the game server's: its real accounts, ranked by Cups. There are no
 * made-up rivals, so offline there is no ladder to show.
 */
data class LeaderboardEntry(val rank: Int, val name: String, val cups: Int, val fighter: FighterId, val isPlayer: Boolean, val glory: Int = 0)
