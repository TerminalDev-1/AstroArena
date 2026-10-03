package io.github.projectwip.data

/**
 * ALL gameplay balance lives in this file.
 *
 * Design rule: progression is transparent. Every scaling stat is a [StatLine] —
 * `value = base + perLevel * (level - 1)` — so an upgrade always adds the same, visible amount.
 * Change a number here and both the game and the upgrade screen follow automatically.
 */

/** A stat that grows by a fixed amount every level. */
data class StatLine(val base: Int, val perLevel: Int) {
    fun at(level: Int): Int = base + perLevel * (level.coerceAtLeast(1) - 1)
}

enum class FighterId { JUNO, BRAKK, MIRA }

enum class AttackShape { BURST, SPREAD, LANCE }

enum class SuperKind { VOLLEY, RAM, PIERCE }

/** How a fighter's main attack behaves. Distances are in tiles, times in seconds. */
data class AttackSpec(
    val shape: AttackShape,
    val projectiles: Int,
    val spreadDegrees: Float,
    val range: Float,
    val speed: Float,
    val radius: Float,
    /** Time between projectiles of the same shot (burst weapons). 0 = all at once. */
    val burstInterval: Float,
    val pierce: Boolean = false,
)

data class SuperSpec(
    val kind: SuperKind,
    val name: String,
    val description: String,
    val projectiles: Int = 1,
    val spreadDegrees: Float = 0f,
    val range: Float,
    val speed: Float,
    val radius: Float,
)

/** A colourway. Index 0 is the default and always owned. */
data class Skin(val name: String, val primary: Long, val secondary: Long, val accent: Long, val pricePrisms: Int)

data class FighterDef(
    val id: FighterId,
    val name: String,
    val title: String,
    val role: String,
    val lore: String,
    val attackName: String,
    val health: StatLine,
    /** Damage of ONE projectile of the main attack. */
    val attackDamage: StatLine,
    /** Damage of ONE projectile / impact of the super. */
    val superDamage: StatLine,
    val moveSpeed: Float,
    val attack: AttackSpec,
    val superSpec: SuperSpec,
    val ammoMax: Int,
    val reloadSeconds: Float,
    /** Super charge gained per main-attack projectile that hits (1.0 = full). */
    val superChargePerHit: Float,
    val radius: Float = 0.42f,
    val skins: List<Skin>,
)

enum class BotDifficulty(val label: String, val blurb: String, val cupBonus: Int, val boltMultiplier: Float) {
    EASY("Easy", "Slow reactions, loose aim, wanders into danger. Good for learning.", 6, 0.75f),
    NORMAL("Normal", "Solid everyday opponents. Keep range, lead some shots, retreat when hurt.", 8, 1.0f),
    HARD("Hard", "Quick reactions, sharp aim, dodges shots and hunts weakened fighters.", 10, 1.25f),
    ELITE("Elite", "Near-perfect aim and positioning. Times supers and punishes mistakes.", 12, 1.5f),
}

enum class MatchOutcome { VICTORY, DEFEAT, DRAW }

/** Game modes. Names and rules are original to this game. */
enum class GameMode(val title: String, val tagline: String, val players: Int) {
    LAST_SPARK("Last Spark", "10-fighter free-for-all · last one standing", 10),
    KNOCKOUT_RUSH("Knockout Rush", "3v3 · first team to 10 KOs", 6),
    /** You against one giant. Knock it out before it knocks you out three times. */
    BOSS("Boss Mode", "You against a giant · 3 lives", 2),
}

object Balance {
    /** The highest level a fighter can normally reach. Dev builds can switch the cap off in the debug menu. */
    const val MAX_LEVEL = 10

    /** Stops a damaged save from loading a nonsense level (levels above [MAX_LEVEL] are valid with the cap off). */
    const val LEVEL_LIMIT = 9999

    /** Bolts needed to go FROM level (index+1) TO level (index+2). Past the end of the table see [upgradeCostFrom]. */
    val upgradeCost = intArrayOf(10, 20, 35, 60, 95, 145, 210, 290, 400)

    /** Each level past the table costs this much more than the one before. */
    const val UPGRADE_COST_STEP = 50

    /** The table covers levels up to [MAX_LEVEL]; with the cap off the price keeps climbing by a fixed step. */
    fun upgradeCostFrom(level: Int): Int {
        val l = level.coerceAtLeast(1)
        return if (l <= upgradeCost.size) upgradeCost[l - 1] else upgradeCost.last() + UPGRADE_COST_STEP * (l - upgradeCost.size)
    }

    // ---- Match format ----
    const val KO_TARGET = 10
    const val MATCH_SECONDS = 150f
    const val RESPAWN_SECONDS = 3f
    const val SPAWN_SHIELD_SECONDS = 2f
    const val REGEN_DELAY_SECONDS = 3f
    const val REGEN_FRACTION_PER_SECOND = 0.12f

    // ---- Starting wallet ----
    const val STARTING_BOLTS = 60
    const val STARTING_PRISMS = 0

    // ---- Rewards (all visible on the result screen) ----
    fun boltsFor(outcome: MatchOutcome, kos: Int, difficulty: BotDifficulty): Int {
        val base = when (outcome) {
            MatchOutcome.VICTORY -> 24
            MatchOutcome.DRAW -> 14
            MatchOutcome.DEFEAT -> 10
        }
        val koBonus = 2 * kos.coerceAtMost(6)
        return Math.round((base + koBonus) * difficulty.boltMultiplier)
    }

    /** Cups change. Wins are rewarded by difficulty; losses cost more as your count grows. */
    fun cupsFor(outcome: MatchOutcome, currentCups: Int, difficulty: BotDifficulty, mvp: Boolean): Int = when (outcome) {
        MatchOutcome.VICTORY -> difficulty.cupBonus + if (mvp) 2 else 0
        MatchOutcome.DRAW -> 1
        MatchOutcome.DEFEAT -> -minOf(6, currentCups / 80).coerceAtMost(currentCups)
    }

    // ---- Last Spark (free-for-all) ----
    /** Cups by placement (index 0 = 1st). Positive values scale with bot difficulty. */
    val placementCups = intArrayOf(10, 8, 6, 4, 2, 0, -1, -2, -3, -4)
    /** Bolts by placement, before the difficulty multiplier and KO bonus. */
    val placementBolts = intArrayOf(30, 26, 22, 18, 15, 12, 10, 8, 6, 5)

    /** Spark Crates (free-for-all): health, and what each Power Cell inside grants (stacking). */
    /** A fighter has to stay in a thicket this long before it is hidden, so brushing past grass doesn't blink it out. */
    const val THICKET_CONCEAL_SECONDS = 0.8f
    /** Enemies this close see into a thicket... */
    const val THICKET_SPOT_RADIUS = 2.2f
    /** ...and keep seeing the fighter for this long after losing contact. */
    const val THICKET_SPOT_LINGER_SECONDS = 0.5f

    const val CRATE_HP = 1400
    const val CELL_HEALTH_BONUS = 0.10f
    const val CELL_DAMAGE_BONUS = 0.10f

    /** Static Storm: waits, then shrinks to [STORM_FINAL_RADIUS] over [STORM_SHRINK_SECONDS]. */
    const val STORM_DELAY_SECONDS = 25f
    const val STORM_SHRINK_SECONDS = 100f
    const val STORM_FINAL_RADIUS = 2.5f
    /** Storm damage per second as a fraction of max health; grows with time spent shrinking. */
    const val STORM_DAMAGE_BASE = 0.10f
    const val STORM_DAMAGE_GROWTH = 0.002f

    fun cupsForPlacement(placement: Int, currentCups: Int, difficulty: BotDifficulty): Int {
        val base = placementCups[(placement - 1).coerceIn(0, placementCups.lastIndex)]
        return if (base > 0) Math.round(base * difficulty.cupBonus / 8f)
        else if (currentCups < 40) 0 // beginners don't lose Cups
        else maxOf(base, -currentCups)
    }

    fun boltsForPlacement(placement: Int, kos: Int, difficulty: BotDifficulty): Int {
        val base = placementBolts[(placement - 1).coerceIn(0, placementBolts.lastIndex)]
        return Math.round((base + 2 * kos.coerceAtMost(6)) * difficulty.boltMultiplier)
    }

    /** Prisms for the first victory each calendar day. */
    const val FIRST_WIN_PRISMS = 10

    val fighters: List<FighterDef> = listOf(
        FighterDef(
            id = FighterId.JUNO,
            name = "Juno",
            title = "Spark Courier",
            role = "Skirmisher",
            lore = "Delivers parcels and bad news at the same speed. Her coil blaster was a toaster once.",
            attackName = "Spark Burst",
            health = StatLine(950, 50),
            attackDamage = StatLine(115, 6),
            superDamage = StatLine(140, 7),
            moveSpeed = 3.7f,
            attack = AttackSpec(AttackShape.BURST, projectiles = 3, spreadDegrees = 6f, range = 7.5f, speed = 17f, radius = 0.16f, burstInterval = 0.075f),
            superSpec = SuperSpec(SuperKind.VOLLEY, "Overcharge Volley", "Unloads a wide fan of 9 charged sparks.", projectiles = 9, spreadDegrees = 50f, range = 8.5f, speed = 18f, radius = 0.2f),
            ammoMax = 3,
            reloadSeconds = 1.25f,
            superChargePerHit = 0.075f,
            skins = listOf(
                Skin("Courier", 0xFFFF8A1F, 0xFF2EC4F1, 0xFFFFE066, 0),
                Skin("Night Shift", 0xFF5B5BD6, 0xFFFF4FA3, 0xFFB8F2FF, 20),
                Skin("Mint Rush", 0xFF2ED8A3, 0xFFFFD23F, 0xFFFFFFFF, 20),
            ),
        ),
        FighterDef(
            id = FighterId.BRAKK,
            name = "Brakk",
            title = "Scrapyard Bruiser",
            role = "Tank",
            lore = "Built himself out of a forklift and a grudge. Prefers to discuss things up close.",
            attackName = "Scrap Cannon",
            health = StatLine(1400, 70),
            attackDamage = StatLine(60, 3),
            superDamage = StatLine(320, 16),
            moveSpeed = 3.45f,
            attack = AttackSpec(AttackShape.SPREAD, projectiles = 5, spreadDegrees = 34f, range = 4.6f, speed = 15f, radius = 0.17f, burstInterval = 0f),
            superSpec = SuperSpec(SuperKind.RAM, "Ram Charge", "Charges forward, slamming and knocking back every enemy in the way.", range = 5.5f, speed = 15f, radius = 0.55f),
            ammoMax = 3,
            reloadSeconds = 1.6f,
            superChargePerHit = 0.06f,
            radius = 0.48f,
            skins = listOf(
                Skin("Rustbucket", 0xFF8C9A5B, 0xFFE0702A, 0xFFFFD166, 0),
                Skin("Chrome", 0xFFB9C6D6, 0xFF3A86FF, 0xFFE9F5FF, 20),
                Skin("Lava Core", 0xFF3B2F2F, 0xFFFF5A1F, 0xFFFFC145, 20),
            ),
        ),
        FighterDef(
            id = FighterId.MIRA,
            name = "Mira",
            title = "Prism Sniper",
            role = "Marksman",
            lore = "Bends starlight through a cut crystal. Never misses twice — usually never once.",
            attackName = "Prism Shot",
            health = StatLine(700, 35),
            attackDamage = StatLine(260, 13),
            superDamage = StatLine(480, 24),
            moveSpeed = 3.5f,
            attack = AttackSpec(AttackShape.LANCE, projectiles = 1, spreadDegrees = 0f, range = 10f, speed = 22f, radius = 0.18f, burstInterval = 0f),
            superSpec = SuperSpec(SuperKind.PIERCE, "Starlance", "A huge crystal lance that pierces through every enemy in its path.", range = 12f, speed = 20f, radius = 0.38f),
            ammoMax = 3,
            reloadSeconds = 1.9f,
            superChargePerHit = 0.26f,
            radius = 0.4f,
            skins = listOf(
                Skin("Starlight", 0xFF8E5CF7, 0xFF2EE6D6, 0xFFFFF3B0, 0),
                Skin("Ruby Cut", 0xFFE0314F, 0xFFFFC145, 0xFFFFE4EC, 20),
                Skin("Glacier", 0xFF4CC9F0, 0xFFFFFFFF, 0xFFB5F2FF, 20),
            ),
        ),
    )

    fun fighter(id: FighterId): FighterDef = fighters.first { it.id == id }

    // ---- Boss Mode ----
    /** Lives the player gets against the boss. */
    const val BOSS_LIVES = 3

    /**
     * The giant version of any fighter, as fought in Boss Mode: two and a half times the size, a mountain of
     * health, slower on its feet, hitting harder and reaching further. Its stats are FIXED: a boss is always
     * created at level 1 and none of these lines grow, so it does not get tougher as the player levels up.
     */
    fun boss(id: FighterId): FighterDef {
        val f = fighter(id)
        return f.copy(
            name = "Titan ${f.name}", title = "Boss",
            health = StatLine(f.health.base * 16, 0),
            attackDamage = StatLine(Math.round(f.attackDamage.base * 1.5f), 0),
            superDamage = StatLine(Math.round(f.superDamage.base * 1.5f), 0),
            moveSpeed = f.moveSpeed * 0.62f, radius = f.radius * 2.5f,
            reloadSeconds = f.reloadSeconds * 1.15f, superChargePerHit = f.superChargePerHit * 0.5f,
            attack = f.attack.copy(range = f.attack.range * 1.25f, radius = f.attack.radius * 1.5f),
            superSpec = f.superSpec.copy(range = f.superSpec.range * 1.25f, radius = f.superSpec.radius * 1.5f),
        )
    }

    /** How a locked fighter can be obtained. */
    fun unlockPrismPrice(id: FighterId): Int? = when (id) {
        FighterId.JUNO -> null
        FighterId.BRAKK -> 40
        FighterId.MIRA -> 70
    }
}
