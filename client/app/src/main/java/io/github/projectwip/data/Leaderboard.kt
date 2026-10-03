package io.github.projectwip.data

import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

data class LeaderboardEntry(
    val rank: Int, val name: String, val cups: Int, val fighter: FighterId, val isPlayer: Boolean,
    /** A real player from the game server, as opposed to a simulated rival. */
    val online: Boolean = false,
)

/**
 * The Cup ladder. There is no server yet, so the other names are simulated rivals: a fixed cast whose Cup
 * counts follow a steep curve (a few far ahead, most bunched low) and drift a little from day to day, so the
 * player's rank moves both when they win and when the field shifts. Pure and deterministic for a given day.
 */
object Leaderboard {
    const val RIVALS = 99

    private val FIRST = listOf(
        "Nova", "Volt", "Comet", "Rogue", "Turbo", "Neon", "Lunar", "Quark", "Echo", "Blitz",
        "Orbit", "Pulse", "Zephyr", "Halo", "Vex", "Cinder", "Drift", "Jolt", "Astra", "Flux",
    )
    private val SECOND = listOf("Fox", "Byte", "Rider", "Wolf", "Ace", "Moth", "Kite", "Wren", "Lynx", "Hawk", "Crab", "Mage", "Bee", "Yak", "Owl")

    /** Everyone on the ladder, best first, with the player slotted in by Cups (ahead of anyone they tie with). */
    fun standings(
        playerName: String, playerCups: Int, playerFighter: FighterId, day: Long,
        /** Other real players from the server (not including this one). They join the ladder alongside the rivals. */
        others: List<LeaderboardEntry> = emptyList(),
    ): List<LeaderboardEntry> {
        val rivals = (0 until RIVALS).map { i ->
            val rng = Random(7919L * i + 13)
            val name = FIRST[rng.nextInt(FIRST.size)] + SECOND[rng.nextInt(SECOND.size)] + if (rng.nextBoolean()) (10 + rng.nextInt(90)).toString() else ""
            val base = 12f + 1500f * (1f - i / (RIVALS - 1f)).pow(2.4f)
            val cups = (base + base * 0.05f * sin(day * 0.9f + i * 1.7f)).toInt().coerceAtLeast(0)
            LeaderboardEntry(0, name, cups, FighterId.entries[rng.nextInt(FighterId.entries.size)], false)
        }
        val all = rivals + others.map { it.copy(isPlayer = false, online = true) } + LeaderboardEntry(0, playerName, playerCups, playerFighter, true)
        return all.sortedWith(compareByDescending<LeaderboardEntry> { it.cups }.thenByDescending { it.isPlayer })
            .mapIndexed { i, e -> e.copy(rank = i + 1) }
    }

    fun rank(playerCups: Int, day: Long): Int = standings("", playerCups, FighterId.JUNO, day).first { it.isPlayer }.rank
}
