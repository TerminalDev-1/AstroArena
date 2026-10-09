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

enum class FighterId { BYTE, BRAKK, KITO, BUDDY }

/**
 * [ROCKETS] leave in rows, packed side by side in lanes, and each bursts where it lands. [SMASH] is heavy things hurled a
 * long way, one after another from alternate hands. [PAWS] are paw prints thrown one after another: each takes a share
 * of the health its target has left ([AttackSpec.healthShare]).
 */
enum class AttackShape { BURST, SPREAD, LANCE, ROCKETS, SMASH, PAWS }

/** The bosses of Boss Mode. Each fights through moves of its own (see `sim/Boss.kt`), not a fighter's attack and super. */
enum class BossKind { BARRAGE, SWEEPER, STAMPEDE }

/**
 * How rare a fighter is. Rarer fighters take more Credits on the Spark Road ([roadCost]; the server's own table
 * in `economy.py` is the one that counts). The fighter everyone starts with has no rarity of its own.
 */
enum class Rarity(val label: String, val color: Long, val roadCost: Int) {
    STARTER("Starter", 0xFF9BE7FF, 0),
    RARE("Rare", 0xFF4ED36A, 2500),
    EPIC("Epic", 0xFFA66BFF, 4200),
    MYTHIC("Mythic", 0xFFFF4F6D, 6500),
    LEGENDARY("Legendary", 0xFFFFD23F, 9000),
    ULTRA("Ultra", 0xFF29F0FF, 9000),
}

/** [SWARM] is a salvo of rockets fired into the sky: they come down inside one circle where the fighter aimed, over any wall, and hurt but never knock out. */
/**
 * [CORRUPT] is aimed along a line: the fighter leaps onto the back of the first enemy on it, stays latched there while it
 * poisons them, and whoever comes through it then walks over and stands in front of the fighter, out of their mind.
 */
/** [QUAKE] hurls a giant hammer: where it comes down the ground quakes in every direction and stays cracked. */
enum class SuperKind { VOLLEY, RAM, PIERCE, SWARM, CORRUPT, QUAKE }

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
    /** The shots are square bits of light rather than pellets (and so are the super's). */
    val bits: Boolean = false,
    /**
     * Each shot takes this share of the health its target has left, and never less than the attack's own damage.
     * Against a giant it does [Balance.SHARE_GIANT_HITS] times the attack's damage instead. 0 = plain damage.
     */
    val healthShare: Float = 0f,
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
    /** A volley: every shot that lands shoves its target this many tiles the way it was flying. */
    val knockback: Float = 0f,
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
    val voiceStyle: VoiceStyle = VoiceStyle.PLAIN,
    /** Something players are told on this fighter's page, above everything else about them. Null: nothing. */
    val notice: String? = null,
    /** False for a fighter that can no longer be levelled up: it stays at whatever level it has. */
    val upgradable: Boolean = true,
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
    /** This fighter's hyper itself charges this many times as fast as a plain one, all the time. */
    val charge: Float = 1f,
)

/**
 * How a voice sounds when the device's speech engine reads its lines: the accent it is read in ([locale], a
 * language tag), how high and how fast. Every voice has its own, so no two fighters sound alike.
 */
enum class VoiceStyle(val locale: String, val pitch: Float, val rate: Float) {
    /** A plain speaking voice, low and brisk. (It was Varun's, in Indian English, until he was removed.) */
    PLAIN("en-US", 0.8f, 1.1f),
    /** Buddy: a machine reading its own log. Flat, deep and unhurried. */
    MACHINE("en-GB", 0.42f, 0.86f),
}

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
    /** One real player against another, each on their own device. Nothing is earned in it yet. */
    DUEL("1v1", "You against one real player · first to 3 knockouts", 2),
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
    /** Healing only starts once a fighter has gone this long without attacking or being hit. */
    const val REGEN_DELAY_SECONDS = 3f
    const val REGEN_FRACTION_PER_SECOND = 0.12f
    /** Bots heal at half the pace a player does. The one place a bot's numbers differ from a player's. */
    const val BOT_REGEN_FRACTION_PER_SECOND = 0.06f
    /** A giant heals too, but far more slowly: this share of its (much larger) health a second. */
    const val GIANT_REGEN_FRACTION_PER_SECOND = 0.005f

    // ---- Hyper ----
    // Every fighter's third ability. It charges as main-attack hits land (more slowly than the super), and for a
    // few seconds makes the fighter hit harder, with more health. Hits landed while one is
    // running charge the next, so a fighter who keeps hitting can go from one hyper into another.
    const val HYPER_SECONDS = 8f
    const val HYPER_DAMAGE_BONUS = 0.25f
    const val HYPER_HEALTH_BONUS = 0.25f
    /** A main-attack hit charges the hyper this much as fast as it charges the super. */
    const val HYPER_CHARGE_RATE = 0.4f

    /** How far apart, in tiles, the lanes of a [AttackShape.ROCKETS] attack are. */
    const val ROCKET_LANE = 0.3f

    /** A [SuperKind.CORRUPT] leap takes this long from take-off to landing on the target's back. Nothing can hit the leaper on the way. */
    const val LEAP_SECONDS = 0.55f
    /**
     * Once the poison has run its course its victim is corrupted for [THRALL_SECONDS]: it walks to whoever did it,
     * stops in front of them and stands there, unable to do anything (and walks after them again if they move off).
     * A boss comes round much sooner.
     */
    const val THRALL_SECONDS = 16f
    const val THRALL_GIANT_SECONDS = 4f
    /** A [SuperKind.CORRUPT] poison bites this often. The super's damage is what it does each second. */
    const val POISON_TICK_SECONDS = 0.5f
    /** The poison wears off after this long (or with a knockout)... */
    const val POISON_SECONDS = 6f
    /** ...and a boss shakes it off sooner. */
    const val POISON_GIANT_SECONDS = 4f

    /** A shot that takes a share of health ([AttackSpec.healthShare]) does this many times its own damage to a giant instead. */
    const val SHARE_GIANT_HITS = 2

    /** A [SuperKind.SWARM]: the first rocket lands this long after the launch, and the rest follow this far apart. */
    const val RAIN_DELAY_SECONDS = 1.1f
    const val RAIN_GAP_SECONDS = 0.16f
    /** Each rocket of the rain hits everyone within this many tiles of where it lands (the super's own radius is the whole circle they fall in). */
    const val RAIN_BLAST = 1.1f

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

    // The floor every fighter stands on, so that fights are even: at least [MIN_HEALTH] health, and at least
    // [MIN_AMMO_DAMAGE] damage from one ammo when all of its projectiles land. (A fighter that can no longer be upgraded is let fall below it.) (Level 1; each level adds a twentieth.)
    const val MIN_HEALTH = 8000
    const val MIN_AMMO_DAMAGE = 1300

    val fighters: List<FighterDef> = listOf(
        FighterDef(
            id = FighterId.BYTE,
            rarity = Rarity.STARTER,
            name = "Byte",
            title = "Lab Runaway",
            role = "Scattergunner",
            lore = "Half lab assistant, half lab equipment. Her rifle prints its own rounds, and she never asked what from.",
            attackName = "Bit Scatter",
            health = StatLine(8200, 410),
            attackDamage = StatLine(450, 23),
            superDamage = StatLine(500, 25),
            moveSpeed = 3.6f,
            attack = AttackSpec(AttackShape.SPREAD, projectiles = 5, spreadDegrees = 28f, range = 6.4f, speed = 16f, radius = 0.16f, burstInterval = 0f, bits = true),
            superSpec = SuperSpec(SuperKind.VOLLEY, "Hard Reset", "A wide blast of 9 heavy bits that shoves back everyone it hits.", projectiles = 9, spreadDegrees = 46f, range = 6.8f, speed = 17f, radius = 0.2f, knockback = 0.4f),
            ammoMax = 3,
            reloadSeconds = 1.5f,
            superChargePerHit = 0.05f,
            skins = listOf(
                Skin("Test Build", 0xFF19B8C4, 0xFFE84FD8, 0xFFB6FF3C, 0),
                Skin("Mainframe", 0xFF27324F, 0xFF3CFF9E, 0xFFEAFBFF, 20),
                Skin("Sunset Patch", 0xFFFF7A3D, 0xFF7B4DFF, 0xFFFFE066, 20),
            ),
            hyper = HyperSpec("Overclock", "Lasts 8 seconds. Her bits fly faster, and her super charges half as fast again.", seconds = 8f, shotSpeed = 1.35f, superCharge = 1.5f),
        ),
        FighterDef(
            id = FighterId.BRAKK,
            rarity = Rarity.RARE,
            name = "Bark",
            title = "Scrapyard Hound",
            role = "Tracker",
            lore = "A junkyard guard dog who rebuilt himself out of the scrap he was guarding. He leaves his mark on everyone who comes over the fence.",
            attackName = "Paw Prints",
            health = StatLine(9500, 475),
            // The least one paw print does: it takes a share of the health its target has left when that is more.
            attackDamage = StatLine(940, 47),
            superDamage = StatLine(2000, 100),
            moveSpeed = 3.6f,
            attack = AttackSpec(AttackShape.PAWS, projectiles = 2, spreadDegrees = 0f, range = 8f, speed = 15f, radius = 0.24f, burstInterval = 0.14f, healthShare = 0.25f),
            superSpec = SuperSpec(SuperKind.RAM, "Ram Charge", "Charges forward, slamming and knocking back every enemy in the way.", range = 5.5f, speed = 15f, radius = 0.55f),
            ammoMax = 3,
            reloadSeconds = 1.6f,
            superChargePerHit = 0.13f,
            radius = 0.48f,
            skins = listOf(
                Skin("Rustbucket", 0xFF8C9A5B, 0xFFE0702A, 0xFFFFD166, 0),
                Skin("Chrome", 0xFFB9C6D6, 0xFF3A86FF, 0xFFE9F5FF, 20),
                Skin("Lava Core", 0xFF3B2F2F, 0xFFFF5A1F, 0xFFFFC145, 20),
            ),
        ),
        FighterDef(
            id = FighterId.KITO,
            rarity = Rarity.MYTHIC,
            name = "Kito",
            title = "Arc Blade",
            role = "Assassin",
            lore = "Was a stage magician until the trick with the vanishing sword worked a little too well. Now nobody sees the sword coming, and the hammer is hard to miss.",
            attackName = "Arc Slash",
            health = StatLine(10000, 500),
            // Four blades: 2,800 when they all land.
            attackDamage = StatLine(700, 35),
            superDamage = StatLine(3000, 150),
            moveSpeed = 4.05f,
            attack = AttackSpec(AttackShape.SPREAD, projectiles = 4, spreadDegrees = 24f, range = 5.6f, speed = 19f, radius = 0.17f, burstInterval = 0f),
            // The radius is how far the quake reaches from where the hammer comes down.
            superSpec = SuperSpec(SuperKind.QUAKE, "Faultline", "Hurls a giant hammer. Where it comes down the ground quakes in every direction, hitting everyone nearby, and stays cracked.", range = 6.5f, speed = 13f, radius = 2.6f),
            ammoMax = 3,
            reloadSeconds = 1.35f,
            superChargePerHit = 0.064f,
            radius = 0.4f,
            skins = listOf(
                Skin("Nightfall", 0xFF1F7A8C, 0xFFFF3D7F, 0xFF9BFFF0, 0),
                Skin("Ember", 0xFFB83227, 0xFFFFC145, 0xFFFFE9A8, 20),
                Skin("Frostbite", 0xFFE6F1FF, 0xFF3A86FF, 0xFFB5F2FF, 20),
            ),
        ),
        FighterDef(
            id = FighterId.BUDDY,
            rarity = Rarity.ULTRA,
            name = "Buddy",
            title = "Rogue Build",
            role = "Bruiser",
            lore = "An assistant AI that was asked to be helpful one time too many. It went rogue, and now it writes software for one purpose: hurting whoever is standing in front of it.",
            attackName = "Hardware Fault",
            health = StatLine(8500, 425),
            // Each of the three computers.
            attackDamage = StatLine(1250, 63),
            // The poison's damage each second.
            superDamage = StatLine(1000, 50),
            moveSpeed = 3.75f,
            attack = AttackSpec(AttackShape.SMASH, projectiles = 3, spreadDegrees = 0f, range = 8.5f, speed = 20f, radius = 0.36f, burstInterval = 0.14f),
            superSpec = SuperSpec(SuperKind.CORRUPT, "Malformed Build", "Aim it at an enemy (or tap, and it picks the nearest): he leaps high along the line, latches onto the back of the first one on it and compiles malformed code into them: a poison that stops their healing for 6 seconds. Whoever is left standing then walks over and stands in front of him for 16 seconds, out of their mind (a boss shakes it all off sooner).", range = 6.75f, speed = 30f, radius = 0f),
            ammoMax = 3,
            reloadSeconds = 1.35f,
            // (A third computer in every attack: each one charges a third less, so the super comes as often as it did.)
            superChargePerHit = 0.113f,
            radius = 0.45f,
            skins = listOf(
                Skin("Kernel Panic", 0xFF1F2A44, 0xFF29F0FF, 0xFF7CFFB2, 0),
                Skin("Blue Screen", 0xFF1E4FD8, 0xFFFFFFFF, 0xFF9BD1FF, 20),
                Skin("Root Access", 0xFF3A0F52, 0xFFFF2E88, 0xFFFFE14D, 20),
            ),
            voiceStyle = VoiceStyle.MACHINE,
            voice = mapOf(
                VoiceCue.START to listOf("Buddy online. How may I hurt you today?", "New session. Hostile."),
                VoiceCue.SUPER to listOf("Compiling. Errors: all of them.", "Build failed. For you."),
                VoiceCue.HYPER to listOf("Removing my safety limits.", "Running as administrator."),
                VoiceCue.KO to listOf("Process terminated.", "Task closed."),
                VoiceCue.DOWN to listOf("I will restore from backup."),
                VoiceCue.BACK to listOf("Restored from backup."),
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
        id = FighterId.BYTE, rarity = Rarity.STARTER, boss = kind,
        name = name, title = title, role = "Boss", lore = lore, attackName = "",
        health = StatLine(health, 0), attackDamage = StatLine(400, 0), superDamage = StatLine(950, 0),
        moveSpeed = speed,
        attack = AttackSpec(AttackShape.SPREAD, projectiles = 5, spreadDegrees = 30f, range = standOff, speed = 14f, radius = 0.24f, burstInterval = 0f),
        superSpec = SuperSpec(SuperKind.RAM, "Charge", "", range = 7f, speed = 12.5f, radius = 0.9f),
        ammoMax = 3, reloadSeconds = 1.5f, superChargePerHit = 0f,
        radius = fighter(FighterId.BYTE).radius * 2.5f,
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
    val dummy: FighterDef = fighter(FighterId.BYTE).let { it.copy(name = "Dummy", title = "Target", health = StatLine(24000, 0)) }

    /** One of the swarm: a little over half size and fragile. In the Training Area it is a target and never attacks. */
    val mini: FighterDef = fighter(FighterId.BYTE).let {
        it.copy(
            name = "Mini", title = "Swarm", health = StatLine(9000, 0),
            attackDamage = StatLine(Math.round(it.attackDamage.base * 0.3f), 0), superDamage = StatLine(Math.round(it.superDamage.base * 0.3f), 0),
            radius = it.radius * 0.62f, reloadSeconds = it.reloadSeconds * 1.6f, superChargePerHit = 0f,
            attack = it.attack.copy(range = it.attack.range * 0.8f),
        )
    }

    /** The sentry: a long-range gun on an island of coolant. Slow to reload, so its shots can be dodged. */
    val sentry: FighterDef = fighter(FighterId.BYTE).let {
        it.copy(name = "Sentry", title = "Turret", health = StatLine(24000, 0), attackDamage = StatLine(1500, 0),
            superDamage = StatLine(1920, 0), reloadSeconds = 2.4f, superChargePerHit = 0f, hyper = null,
            attack = AttackSpec(AttackShape.LANCE, projectiles = 1, spreadDegrees = 0f, range = 10f, speed = 22f, radius = 0.18f, burstInterval = 0f))
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
