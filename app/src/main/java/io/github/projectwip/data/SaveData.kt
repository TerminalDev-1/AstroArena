package io.github.projectwip.data

enum class MoveStickMode { FLOATING, FIXED }

data class Settings(
    val botDifficulty: BotDifficulty = BotDifficulty.NORMAL,
    val sfxVolume: Float = 0.8f,
    val muted: Boolean = false,
    val haptics: Boolean = true,
    /** Multiplier on joystick/button size. */
    val controlScale: Float = 1f,
    /** Opacity of on-screen controls, 0.3..1. */
    val controlOpacity: Float = 0.85f,
    val moveStickMode: MoveStickMode = MoveStickMode.FLOATING,
    /** Tapping the attack stick fires at the nearest visible enemy. */
    val tapToAutoAim: Boolean = true,
    /** Manually aimed shots snap onto an enemy within a few degrees. */
    val aimAssist: Boolean = true,
    val showDamageNumbers: Boolean = true,
    val highFrameRate: Boolean = true,
    val showFps: Boolean = false,
    val playerName: String = "Player",
)

data class FighterProgress(
    val unlocked: Boolean = false,
    val level: Int = 1,
    val skin: Int = 0,
    val ownedSkins: Set<Int> = setOf(0),
)

data class SaveData(
    val cups: Int = 0,
    val bestCups: Int = 0,
    val bolts: Int = Balance.STARTING_BOLTS,
    val prisms: Int = Balance.STARTING_PRISMS,
    val fighters: Map<FighterId, FighterProgress> = defaultFighters(),
    val selectedFighter: FighterId = FighterId.JUNO,
    /** Cup values of claimed track milestones. */
    val claimedMilestones: Set<Int> = emptySet(),
    val lastDailyGiftDay: Long = -1,
    val lastFirstWinDay: Long = -1,
    val matchesPlayed: Int = 0,
    val victories: Int = 0,
    val totalKos: Int = 0,
    val settings: Settings = Settings(),
) {
    fun progress(id: FighterId): FighterProgress = fighters[id] ?: FighterProgress()

    companion object {
        const val VERSION = 1

        fun defaultFighters(): Map<FighterId, FighterProgress> =
            FighterId.entries.associateWith { FighterProgress(unlocked = it == FighterId.JUNO) }
    }
}
