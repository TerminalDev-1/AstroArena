package io.github.projectwip.data

/** FLOATING = unlocked: the stick appears wherever the thumb lands. FIXED = locked in place. */
enum class MoveStickMode { FLOATING, FIXED }

/**
 * Where the player put the on-screen controls: the centre of each as a fraction of the screen.
 * Negative = the default spot. A moved move-stick always stays where it was put (it no longer floats).
 */
data class ControlLayout(
    val moveX: Float = -1f, val moveY: Float = -1f,
    val attackX: Float = -1f, val attackY: Float = -1f,
    val superX: Float = -1f, val superY: Float = -1f,
) {
    val isDefault get() = moveX < 0f && attackX < 0f && superX < 0f
}

data class Settings(
    /** The difficulty the server last approved for this player. Picking another one asks the server first. */
    val botDifficulty: BotDifficulty = BotDifficulty.NORMAL,
    val sfxVolume: Float = 0.8f,
    val musicVolume: Float = 0.5f,
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
    /** The player has picked their name (new players are asked before their account is made). */
    val nameChosen: Boolean = false,
    val controlLayout: ControlLayout = ControlLayout(),
    /** Address of the game server, e.g. http://192.168.1.103:8765. Blank = the address this build was made with. */
    val serverUrl: String = "",
    /** Unlocked: touching anywhere on the right half of the screen is the attack stick. */
    val attackStickMode: MoveStickMode = MoveStickMode.FLOATING,
    /** Debug menu: 0 = normal capsule odds; each point multiplies the weight of every tier above the last. */
    val debugLuck: Float = 0f,
    /** Debug menu: opening a capsule doesn't use one up. */
    val debugInfiniteCapsules: Boolean = false,
    /** Debug menu (dev builds only): fighters can be upgraded past [Balance.MAX_LEVEL]. */
    val debugNoLevelCap: Boolean = false,
    /** Debug menu: multiplies what every upgrade costs (1 = normal, 0 = free). */
    val debugUpgradeCost: Float = 1f,
    /** Developers: show the "D" button that opens the debug menu. Off unless they switch it on in Settings. */
    val devMenu: Boolean = false,
)

data class FighterProgress(
    val unlocked: Boolean = false,
    val level: Int = 1,
    val skin: Int = 0,
    val ownedSkins: Set<Int> = setOf(0),
    /** Cups won while playing this fighter: its rank follows them (see `FighterRanks`). */
    val cups: Int = 0,
)

data class SaveData(
    val cups: Int = 0,
    val bestCups: Int = 0,
    val bolts: Int = Balance.STARTING_BOLTS,
    val prisms: Int = Balance.STARTING_PRISMS,
    /** Credits on the Spark Road, toward the next fighter along it; a copy of what the server holds. Not a wallet: they can only become that fighter. */
    val credits: Int = 0,
    /** What Credits are earned as once every fighter is unlocked. */
    val glory: Int = 0,
    val fighters: Map<FighterId, FighterProgress> = defaultFighters(),
    val selectedFighter: FighterId = FighterId.BYTE,
    val selectedMode: GameMode = GameMode.LAST_SPARK,
    /** Boss Mode: the boss the player wants to fight. Null = a random one each time. */
    val selectedBoss: BossKind? = null,
    /** Cup values of claimed track milestones. */
    val claimedMilestones: Set<Int> = emptySet(),
    val lastDailyGiftDay: Long = -1,
    val lastFirstWinDay: Long = -1,
    val matchesPlayed: Int = 0,
    val victories: Int = 0,
    val totalKos: Int = 0,
    val settings: Settings = Settings(),
    /** Offers made with the in-game Offer Creator. */
    val customOffers: List<CustomOffer> = emptyList(),
    /** Unopened Spark Capsules. */
    val capsules: Int = SparkCapsules.STARTING,
    /** The day [capsulesEarnedToday] counts for. */
    val capsuleDay: Long = -1,
    val capsulesEarnedToday: Int = 0,
    val capsulesOpened: Int = 0,
    /** How many of the unopened ones came from a split (they roll with [SparkCapsules.SPLIT_LUCK] and are opened first). */
    val boostedCapsules: Int = 0,
    /** Seeds the next capsule roll; stored so reloading the game can't re-roll a capsule. */
    val capsuleSeed: Long = 0,
) {
    fun progress(id: FighterId): FighterProgress = fighters[id] ?: FighterProgress()

    companion object {
        const val VERSION = 1

        fun defaultFighters(): Map<FighterId, FighterProgress> =
            FighterId.entries.associateWith { FighterProgress(unlocked = it == FighterId.BYTE) }
    }
}
