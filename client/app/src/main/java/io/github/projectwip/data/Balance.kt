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

enum class FighterId { JUNO, BRAKK, MIRA, KITO }

enum class AttackShape { BURST, SPREAD, LANCE }

/**
 * How rare a fighter is. Rarer fighters take more Credits on the Spark Road ([roadCost]; the server's own table
 * in `economy.py` is the one that counts). The fighter everyone starts with has no rarity of its own.
 */
enum class Rarity(val label: String, val color: Long, val roadCost: Int) {
    STARTER("Starter", 0xFF9BE7FF, 0),
    RARE("Rare", 0xFF4ED36A, 160),
    EPIC("Epic", 0xFFA66BFF, 420),
    MYTHIC("Mythic", 0xFFFF4F6D, 900),
    LEGENDARY("Legendary", 0xFFFFD23F, 1600),
    ULTRA("Ultra", 0xFF29F0FF, 2600),
}

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
    val rarity: Rarity = Rarity.RARE,
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
    /** You against one giant, with as many lives as it takes. */
    BOSS("Boss Mode", "You against a giant · unlimited lives", 2),
    /** Practice: dummies, a swarm and a boss that just stand there, plus one sentry gun. No timer, nothing won or lost. */
    TRAINING("Training Area", "Dummies, a swarm, a sentry and a boss · no stakes", 19),
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
    /** The most shield a fighter can build up on top of full health. Damage comes off the shield first. */
    const val SHIELD_MAX = 6300
    const val REGEN_DELAY_SECONDS = 3f
    const val REGEN_FRACTION_PER_SECOND = 0.12f
    /** A giant heals too, but far more slowly: this share of its (much larger) health a second. */
    const val GIANT_REGEN_FRACTION_PER_SECOND = 0.02f

    // ---- Starting wallet ----
    const val STARTING_BOLTS = 60
    const val STARTING_PRISMS = 0

    // Health and damage use big numbers (thousands of health, hundreds to a thousand-odd per hit), the scale
    // players of the genre expect. Everything was multiplied by the same factor, so fights last just as long.

    /** Spark Crates (free-for-all): health, and what each Power Cell inside grants (stacking). */
    /** A fighter has to stay in a thicket this long before it is hidden, so brushing past grass doesn't blink it out. */
    const val THICKET_CONCEAL_SECONDS = 0.8f
    /** Enemies this close see into a thicket... */
    const val THICKET_SPOT_RADIUS = 2.2f
    /** ...and keep seeing the fighter for this long after losing contact. */
    const val THICKET_SPOT_LINGER_SECONDS = 0.5f

    const val CRATE_HP = 5600
    const val CELL_HEALTH_BONUS = 0.10f
    const val CELL_DAMAGE_BONUS = 0.10f

    /** Static Storm: waits, then shrinks to [STORM_FINAL_RADIUS] over [STORM_SHRINK_SECONDS]. */
    const val STORM_DELAY_SECONDS = 25f
    const val STORM_SHRINK_SECONDS = 100f
    const val STORM_FINAL_RADIUS = 2.5f
    /** Storm damage per second as a fraction of max health; grows with time spent shrinking. */
    const val STORM_DAMAGE_BASE = 0.10f
    const val STORM_DAMAGE_GROWTH = 0.002f

    /** Prisms for the first victory each calendar day (the server pays them; this is for showing it). */
    const val FIRST_WIN_PRISMS = 10

    val fighters: List<FighterDef> = listOf(
        FighterDef(
            id = FighterId.JUNO,
            rarity = Rarity.STARTER,
            name = "Juno",
            title = "Spark Courier",
            role = "Skirmisher",
            lore = "Delivers parcels and bad news at the same speed. Her coil blaster was a toaster once.",
            attackName = "Spark Burst",
            health = StatLine(3800, 200),
            attackDamage = StatLine(460, 24),
            superDamage = StatLine(560, 28),
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
            rarity = Rarity.RARE,
            name = "Brakk",
            title = "Scrapyard Bruiser",
            role = "Tank",
            lore = "Built himself out of a forklift and a grudge. Prefers to discuss things up close.",
            attackName = "Scrap Cannon",
            health = StatLine(5600, 280),
            attackDamage = StatLine(240, 12),
            superDamage = StatLine(1280, 64),
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
            rarity = Rarity.EPIC,
            name = "Mira",
            title = "Prism Sniper",
            role = "Marksman",
            lore = "Bends starlight through a cut crystal. Never misses twice — usually never once.",
            attackName = "Prism Shot",
            health = StatLine(2800, 140),
            attackDamage = StatLine(1040, 52),
            superDamage = StatLine(1920, 96),
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
        FighterDef(
            id = FighterId.KITO,
            rarity = Rarity.MYTHIC,
            name = "Kito",
            title = "Arc Blade",
            role = "Assassin",
            lore = "Was a stage magician until the trick with the vanishing sword worked a little too well. Now nobody sees the sword coming.",
            attackName = "Arc Slash",
            health = StatLine(3200, 160),
            attackDamage = StatLine(520, 26),
            superDamage = StatLine(1400, 70),
            moveSpeed = 4.05f,
            attack = AttackSpec(AttackShape.SPREAD, projectiles = 3, spreadDegrees = 20f, range = 5.6f, speed = 19f, radius = 0.17f, burstInterval = 0f),
            superSpec = SuperSpec(SuperKind.RAM, "Flash Step", "Blinks forward in a blur, cutting through and knocking back everyone in the way.", range = 6.8f, speed = 21f, radius = 0.5f),
            ammoMax = 3,
            reloadSeconds = 1.35f,
            superChargePerHit = 0.085f,
            radius = 0.4f,
            skins = listOf(
                Skin("Nightfall", 0xFF1F7A8C, 0xFFFF3D7F, 0xFF9BFFF0, 0),
                Skin("Ember", 0xFFB83227, 0xFFFFC145, 0xFFFFE9A8, 20),
                Skin("Frostbite", 0xFFE6F1FF, 0xFF3A86FF, 0xFFB5F2FF, 20),
            ),
        ),
    )

    fun fighter(id: FighterId): FighterDef = fighters.first { it.id == id }

    // ---- Boss Mode ----
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

    // ---- Training Area ----
    // Everything here is created at level 1 with flat stat lines, like the boss: it is a fixed yardstick.

    /** A target dummy: never attacks, soaks up damage and regenerates like anyone else. */
    val dummy: FighterDef = fighter(FighterId.JUNO).let { it.copy(name = "Dummy", title = "Target", health = StatLine(16000, 0)) }

    /** One of the swarm: a little over half size and fragile. In the Training Area it is a target and never attacks. */
    val mini: FighterDef = fighter(FighterId.JUNO).let {
        it.copy(
            name = "Mini", title = "Swarm", health = StatLine(6000, 0),
            attackDamage = StatLine(Math.round(it.attackDamage.base * 0.3f), 0), superDamage = StatLine(Math.round(it.superDamage.base * 0.3f), 0),
            radius = it.radius * 0.62f, reloadSeconds = it.reloadSeconds * 1.6f, superChargePerHit = 0f,
            attack = it.attack.copy(range = it.attack.range * 0.8f),
        )
    }

    /** The sentry: a long-range gun on an island of coolant. Slow to reload, so its shots can be dodged. */
    val sentry: FighterDef = fighter(FighterId.MIRA).let {
        it.copy(name = "Sentry", title = "Turret", health = StatLine(16000, 0), attackDamage = StatLine(it.attackDamage.base, 0),
            superDamage = StatLine(it.superDamage.base, 0), reloadSeconds = 2.4f, superChargePerHit = 0f)
    }

    /** How a locked fighter can be obtained. */
    fun unlockPrismPrice(id: FighterId): Int? = when (fighter(id).rarity) {
        Rarity.STARTER -> null
        Rarity.RARE -> 40
        Rarity.EPIC -> 70
        Rarity.MYTHIC -> 90
        Rarity.LEGENDARY -> 160
        Rarity.ULTRA -> 250
    }
}
