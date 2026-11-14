package io.github.projectwip.data

/**
 * One row of the leaderboard. It is the game server's: its real accounts, ranked by wins (Cups were removed). There are no
 * made-up rivals, so offline there is no ladder to show.
 */
data class LeaderboardEntry(val rank: Int, val name: String, val wins: Int, val fighter: FighterId, val isPlayer: Boolean)
