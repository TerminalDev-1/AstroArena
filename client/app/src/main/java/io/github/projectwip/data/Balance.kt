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

enum class FighterId { JUNO, BRAKK, MIRA, KITO, VARUN }

/** [ROCKETS] leave in rows, packed side by side in lanes, and each bursts where it lands. */
enum class AttackShape { BURST, SPREAD, LANCE, ROCKETS }

/** The bosses of Boss Mode. Each fights through moves of its own (see `sim/Boss.kt`), not a fighter's attack and super. */
enum class BossKind { BARRAGE, SWEEPER, STAMPEDE }

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

/** [SWARM] is a salvo of rockets fired into the sky: they come down on the enemies in sight, over any wall, and hurt but never knock out. */
enum class SuperKind { VOLLEY, RAM, PIERCE, SWARM }

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
    /** Rockets: everyone within this many tiles of where one lands is hit. 0 = only what it touches. */
    val blast: Float = 0f,
    /** Rockets: how many leave side by side in each row. */
    val lanes: Int = 1,
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
    /** Set for a Boss Mode boss: which one it is. Null for every fighter. */
    val boss: BossKind? = null,
    /** What this fighter's own hyper adds to the buffs every hyper gives. Null: the plain hyper. */
    val hyper: HyperSpec? = null,
    /** What the fighter says, and when. Empty for a fighter without a voice. */
    val voice: Map<VoiceCue, List<String>> = emptyMap(),
)

/**
 * A fighter's own hyper. On top of the buffs every hyper gives (see the `HYPER_*` numbers in [Balance]), it can run
 * longer, make the fighter's shots fly faster, and charge the super faster while it runs.
 */
data class HyperSpec(
    val name: String,
    val description: String,
    val seconds: Float,
    /** Shots fly this many times as fast, and rockets fired into the sky come down that much sooner. */
    val shotSpeed: Float = 1f,
    /** The super charges this many times as fast. */
    val superCharge: Float = 1f,
)

/** The moments a fighter with a voice speaks up. */
enum class VoiceCue { START, SUPER, HYPER, KO, DOWN, BACK }

enum class BotDifficulty(val label: String, val blurb: String, val boltMultiplier: Float) {
    EASY("Easy", "Slow reactions, loose aim, wanders into danger. Good for learning.", 0.75f),
    NORMAL("Normal", "Solid everyday opponents. Keep range, lead some shots, retreat when hurt.", 1.0f),
    HARD("Hard", "Quick reactions, sharp aim, dodges shots and hunts weakened fighters.", 1.25f),
    ELITE("Elite", "Near-perfect aim and positioning. Times supers and punishes mistakes.", 1.5f),
}

enum class MatchOutcome { VICTORY, DEFEAT, DRAW }

/** Game modes. Names and rules are original to this game. */
enum class GameMode(val title: String, val tagline: String, val players: Int) {
    LAST_SPARK("Last Spark", "10-fighter free-for-all · last one standing", 10),
    KNOCKOUT_RUSH("Knockout Rush", "3v3 · first team to 10 KOs", 6),
    /** You against one giant, with as many lives as it takes. */
    BOSS("Boss Mode", "You against a boss · unlimited lives", 2),
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
    /** The most shield a fighter can build up on top of full health, as a share of that health. Damage comes off the shield first. */
    const val SHIELD_FRACTION = 0.25f
    /** A shield builds at this share of the pace health comes back, and only once the fighter has gone [REGEN_DELAY_SECONDS] without being hit. */
    const val SHIELD_BUILD_RATE = 0.5f
    const val REGEN_DELAY_SECONDS = 3f
    const val REGEN_FRACTION_PER_SECOND = 0.12f
    /** Bots heal (and shield) at half the pace a player does. The one place a bot's numbers differ from a player's. */
    const val BOT_REGEN_FRACTION_PER_SECOND = 0.06f
    /** A giant heals too, but far more slowly: this share of its (much larger) health a second. */
    const val GIANT_REGEN_FRACTION_PER_SECOND = 0.005f

    // ---- Hyper ----
    // Every fighter's third ability. It charges as main-attack hits land (more slowly than the super), and for a
    // few seconds makes the fighter hit harder, with more health and a bigger shield. Hits landed while one is
    // running charge the next, so a fighter who keeps hitting can go from one hyper into another.
    const val HYPER_SECONDS = 8f
    const val HYPER_DAMAGE_BONUS = 0.25f
    const val HYPER_HEALTH_BONUS = 0.25f
    const val HYPER_SHIELD_BONUS = 0.25f
    /** A main-attack hit charges the hyper this much as fast as it charges the super. */
    const val HYPER_CHARGE_RATE = 0.4f

    /** How far apart, in tiles, the lanes of a [AttackShape.ROCKETS] attack are. */
    const val ROCKET_LANE = 0.3f

    /** A [SuperKind.SWARM]: the first rocket lands this long after the launch, and the rest follow this far apart. */
    const val RAIN_DELAY_SECONDS = 0.9f
    const val RAIN_GAP_SECONDS = 0.12f
    /** Each rocket follows its target until this long before it lands; after that, it can be stepped out of. */
    const val RAIN_LOCK_SECONDS = 0.4f

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
        FighterDef(
            id = FighterId.VARUN,
            rarity = Rarity.LEGENDARY,
            name = "Varun",
            title = "Rocket Firefighter",
            role = "Artillery",
            lore = "An Indian firefighter who was captured and told to work for the people of the Sparks. He has never left since. Nobody knows why.",
            attackName = "Rocket Pack",
            health = StatLine(4400, 220),
            attackDamage = StatLine(220, 11),
            superDamage = StatLine(900, 45),
            moveSpeed = 3.55f,
            attack = AttackSpec(AttackShape.ROCKETS, projectiles = 6, spreadDegrees = 0f, range = 8f, speed = 12f, radius = 0.17f, burstInterval = 0.1f, blast = 0.8f, lanes = 3),
            superSpec = SuperSpec(SuperKind.SWARM, "Rocket Rain", "Fires 8 rockets into the sky. They rain down on the enemies in sight, over any wall, following them until just before they land. They hit hard, but never land the knockout.", projectiles = 8, range = 12f, speed = 10f, radius = 1.0f),
            ammoMax = 3,
            reloadSeconds = 1.5f,
            superChargePerHit = 0.035f,
            radius = 0.44f,
            skins = listOf(
                Skin("Fire Engine", 0xFFD9342B, 0xFFFFC72C, 0xFFFFF1C2, 0),
                Skin("Monsoon", 0xFF1F6FB5, 0xFF2ED8A3, 0xFFE6F7FF, 20),
                Skin("Marigold", 0xFFFF9F1C, 0xFF7B2CBF, 0xFFFFF3B0, 20),
            ),
            hyper = HyperSpec("Five Alarm", "Lasts 14 seconds. His rockets fly faster, and his super charges half as fast again.", seconds = 14f, shotSpeed = 1.4f, superCharge = 1.5f),
            voice = mapOf(
                VoiceCue.START to listOf("Varun reporting. Where is the fire?", "Hoses down. Rockets up."),
                VoiceCue.SUPER to listOf("Look up!", "No wall will save you!"),
                VoiceCue.HYPER to listOf("Now I am burning bright!", "Full pressure!"),
                VoiceCue.KO to listOf("Fire is out.", "That one is contained."),
                VoiceCue.DOWN to listOf("I will be back on shift."),
                VoiceCue.BACK to listOf("Back on duty."),
            ),
        ),
    )

    fun fighter(id: FighterId): FighterDef = fighters.first { it.id == id }

    // ---- Boss Mode ----
    /**
     * A Boss Mode boss. Two and a half times a fighter's size with a mountain of health; its stats are fixed and
     * never follow the player's level. What it does in a fight is in `sim/Boss.kt`: the attack and super lines
     * here only tell the bot brain how far to stand off, and set the speed and damage of a charge.
     */
    private fun bossDef(kind: BossKind, name: String, title: String, lore: String, health: Int, speed: Float, standOff: Float, skin: Skin) = FighterDef(
        id = FighterId.JUNO, rarity = Rarity.STARTER, boss = kind,
        name = name, title = title, role = "Boss", lore = lore, attackName = "",
        health = StatLine(health, 0), attackDamage = StatLine(400, 0), superDamage = StatLine(950, 0),
        moveSpeed = speed,
        attack = AttackSpec(AttackShape.SPREAD, projectiles = 5, spreadDegrees = 30f, range = standOff, speed = 14f, radius = 0.24f, burstInterval = 0f),
        superSpec = SuperSpec(SuperKind.RAM, "Charge", "", range = 7f, speed = 12.5f, radius = 0.9f),
        ammoMax = 3, reloadSeconds = 1.5f, superChargePerHit = 0f,
        radius = fighter(FighterId.JUNO).radius * 2.5f,
        skins = listOf(skin),
    )

    val bosses: List<FighterDef> by lazy {
        listOf(
            bossDef(BossKind.BARRAGE, "Hailstorm", "Rocket Platform", "A walking launch pad. Whatever it points at is about to have a very bad few seconds.",
                60000, 2.1f, 8f, Skin("Launch Grey", 0xFF6C7A89, 0xFFFF6A1F, 0xFFFFD23F, 0)),
            bossDef(BossKind.SWEEPER, "Lighthouse", "Beam Sweeper", "Built to guide ships home. Nobody is sure who taught it to turn the light all the way up.",
                52000, 2.3f, 7f, Skin("Harbour", 0xFFE8EEF5, 0xFF2EC4F1, 0xFFFF4FA3, 0)),
            bossDef(BossKind.STAMPEDE, "Ramrod", "Wrecking Bull", "Head down, eyes shut, straight ahead. It has never once gone round anything.",
                70000, 2.7f, 3f, Skin("Oxblood", 0xFF9B2D30, 0xFFE9D8A6, 0xFFFFC145, 0)),
        )
    }

    fun boss(kind: BossKind): FighterDef = bosses.first { it.boss == kind }

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
