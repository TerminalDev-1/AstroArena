package io.github.projectwip.data

/**
 * ALL gameplay balance lives in this file.
 *
 * Design rule: progression is transparent. Every scaling stat is a [StatLine] —
 * `value = base + perLevel * (level - 1)` — so an upgrade always adds the same, visible amount.
 * Change a number here and both the game and the upgrade screen follow automatically.
 */

/**
 * A stat that grows by a fixed amount every level. A player's fighter at level 1 ([player]) gets
 * [Balance.LEVEL_ONE_BOOST] of a level's growth on top, so a new fighter isn't hopeless; level 2 and up are untouched,
 * and bots (who fight at the player's level) never get it.
 */
data class StatLine(val base: Int, val perLevel: Int) {
    fun at(level: Int, player: Boolean = false): Int {
        val l = level.coerceAtLeast(1)
        val boost = if (player && l == 1) (perLevel * Balance.LEVEL_ONE_BOOST).toInt() else 0
        return base + perLevel * (l - 1) + boost
    }
}

/** [BYTE] is Swish, the starter (the id has been Byte, Rocket, Spark and now her). Spark had an id of his own from build 96 until he was retired (build 102). */
/**
 * The fighters ([BYTE] is Ninja; the next four are legacy), and from build 116 the minions: [MINION_BOT] a melee
 * robot, [MINION_LASER] a laser bot, [MINION_HOUND] a hound that pounces, [MINION_BRUTE] Swarm's boss and
 * [DRAGONLING] the Dragon's own small copies. Minions are never anyone's to play.
 */
enum class FighterId { BYTE, BRAKK, KIRA, KITO, BUDDY, MINION_BOT, MINION_LASER, MINION_HOUND, MINION_BRUTE, DRAGONLING }

/**
 * [ROCKETS] leave in rows, packed side by side in lanes, and each bursts where it lands. [SMASH] is heavy things hurled a
 * long way, one after another from alternate hands. [PAWS] are paw prints thrown one after another: each takes a share
 * of the health its target has left ([AttackSpec.healthShare]). [HEX] is one bolt that poisons whoever it hits
 * ([AttackSpec.hexSeconds]). [PLANT] lobs one rocket at a spot (the aim is how far as well as which way), over any wall:
 * it sticks in the ground there and does nothing at all until its owner sets it off (`Control.detonate`), and then it
 * hits every enemy within [AttackSpec.blast]. (Nobody plants rockets any more: it was Rocket's attack for one build.)
 * [SLASH] is a swing of a blade: no shot at all, it hits every enemy within [AttackSpec.range] inside an arc of
 * [AttackSpec.spreadDegrees] at once. A bar fills by itself ([AttackSpec.edgeSeconds]), and a full bar makes the next swing reach further.
 * [STAR] lobs a star at a spot like dynamite (aimed like [PLANT]: how far the stick is pushed is how far it goes), over
 * any wall: it bursts where it lands, [Balance.STAR_FLIGHT_SECONDS] later, and hits and slows every enemy within
 * [AttackSpec.blast]. (A straight shot with [AttackSpec.slowSeconds] still slows whoever it hits.)
 */
/**
 * [ARROW] is one long arrow with a diamond tip that homes: once in flight it turns, at most [AttackSpec.homing] degrees a
 * second, toward the nearest enemy it can see within [Balance.ARROW_SEEK_RADIUS] ahead of it, until it hits or runs out
 * of reach. Walls still stop it.
 */
/** [BULLET] is one bullet from a gun (Ninja's), fast and straight. */
/** [LASER] is a bolt of red laser (minions). */
enum class AttackShape { BURST, SPREAD, LANCE, ROCKETS, SMASH, PAWS, HEX, PLANT, SLASH, STAR, ARROW, BULLET, LASER }

/** The bosses of Boss Mode. Each fights through moves of its own (see `sim/Boss.kt`), not a fighter's attack and super. */
/**
 * How rare a fighter is. Rarer fighters take more Credits on the Spark Road ([roadCost]; the server's own table
 * in `economy.py` is the one that counts). Swish, the fighter everyone starts with, is the Starter: free, never on the road, and with no price.
 */
enum class Rarity(val label: String, val color: Long, val roadCost: Int) {
    STARTER("Starter", 0xFF9BE7FF, 0),
    RARE("Rare", 0xFF4ED36A, 2500),
    EPIC("Epic", 0xFFA66BFF, 4200),
    MYTHIC("Mythic", 0xFFFF4F6D, 6500),
    LEGENDARY("Legendary", 0xFFFFD23F, 7000),
}

/** [SWARM] is a salvo of rockets fired into the sky: they come down inside one circle where the fighter aimed, over any wall, and hurt but never knock out. */
/**
 * [CORRUPT] is aimed along a line: the fighter leaps onto the back of the first enemy on it, stays latched there while it
 * poisons them, and whoever comes through it then walks over and stands in front of the fighter, out of their mind.
 */
/** [QUAKE] hurls a giant hammer: where it comes down the ground quakes in every direction and stays cracked. */
/**
 * [BREW] lobs a flask at a spot (aimed like [SWARM]: how far the stick is pushed is how far it goes). Where it bursts,
 * enemies in the circle are hurt, and the thrower and any allies in the circle are healed by as much.
 */
/**
 * [REALM] is aimed along a line like [CORRUPT]: the fighter takes the first enemy on it away into a realm of their own
 * for [Balance.REALM_SECONDS]. In there the enemy can do nothing, nobody outside can touch either of them, and the
 * fighter reloads and charges faster while a rocket of the realm's own hits the enemy every second, or, with
 * [SuperSpec.shadows], that many shadows of the fighter's take turns to strike them, each once a second.
 */
/**
 * [BLINK] is aimed along a line like [CORRUPT]: the fighter is at once beside the first enemy on it, through anything
 * in between, hits them for the super's damage as it arrives, and has its ammo and its blade's bar full. With nobody on the line it goes to the end of its reach, for nothing.
 */
/**
 * [TEMPEST]: the fighter becomes a lightning-storm tornado for [SuperSpec.tempestSeconds]. It flies over anything
 * [Balance.TEMPEST_SPEED] times as fast and can't attack; lightning comes down on enemies near it (`Balance.TEMPEST_BOLT_*`),
 * and every [Balance.TEMPEST_TICK_SECONDS] every enemy within [SuperSpec.radius] of it (the funnel's wide top) is hit
 * for the super's damage and flung [SuperSpec.knockback] tiles outward. Its hits charge the super by only
 * [Balance.TEMPEST_CHARGE] of a hit, and a full super in the middle of one starts it over.
 */
/**
 * [COSMIC] (Ninja's): [SuperSpec.projectiles] cosmic bullets in quick succession ([Balance.COSMIC_GAP_SECONDS] apart),
 * each the super's damage; against the Entity [Balance.COSMIC_ENTITY_MULT] times that, and it is stunned for
 * [Balance.COSMIC_STUN_SECONDS].
 */
enum class SuperKind { VOLLEY, RAM, PIERCE, SWARM, CORRUPT, QUAKE, BREW, REALM, BLINK, TEMPEST, COSMIC }

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
     * A blade ([AttackShape.SLASH]) has a bar apart from its ammo, which fills by itself in this many seconds whether
     * or not the fighter attacks. With the bar full, the next swing reaches [Balance.EDGE_REACH] times as far and
     * empties it, and it fills again. 0 = no bar.
     */
    val edgeSeconds: Float = 0f,
    /**
     * Each shot takes this share of the health its target has left, and never less than the attack's own damage.
     * Against a giant it does [Balance.SHARE_GIANT_HITS] times the attack's damage instead. 0 = plain damage.
     */
    val healthShare: Float = 0f,
    /**
     * Each shot that lands poisons its target for this long: the hit is the first bite, and the attack's damage is
     * done again every [Balance.HEX_TICK_SECONDS] until the time is up. Hexes stack: every shot that lands is a poison
     * of its own, biting on its own clock. 0 = no poison.
     */
    val hexSeconds: Float = 0f,
    /** Each shot that lands slows its target to [Balance.SLOW_FACTOR] of its speed for this long. 0 = it doesn't. */
    val slowSeconds: Float = 0f,
    /** A homing shot ([AttackShape.ARROW]) turns toward its target at most this many degrees a second. 0 = it flies straight. */
    val homing: Float = 0f,
    /** Every shot that lands pushes its target this many tiles along the shot's path (not through walls). 0 = it doesn't. */
    val knockback: Float = 0f,
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
    /**
     * A realm ([SuperKind.REALM]): instead of the realm's own rocket, this many shadows of the fighter fight the captive,
     * each striking once every [Balance.REALM_ROCKET_SECONDS] for the super's damage, in turn. 0 = the rocket.
     */
    val shadows: Int = 0,
    /** A realm ([SuperKind.REALM]): how long it lasts, in seconds. 0 = [Balance.REALM_SECONDS]. */
    val realmSeconds: Float = 0f,
    /**
     * A realm ([SuperKind.REALM]) whose captive is not held: it can move and fight in there, but at
     * [Balance.REALM_CAPTIVE_SPEED] of its speed and [Balance.REALM_CAPTIVE_DAMAGE] of its damage. False = held still and helpless.
     */
    val captiveFree: Boolean = false,
    /** A tempest ([SuperKind.TEMPEST]): how long the fighter is the storm, in seconds. */
    val tempestSeconds: Float = 0f,
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
    /** What this fighter's own hyper adds to the buffs every hyper gives. Null: the plain hyper. */
    val hyper: HyperSpec? = null,
    /** What the fighter says, and when. Empty for a fighter without a voice. */
    val voice: Map<VoiceCue, List<String>> = emptyMap(),
    val voiceStyle: VoiceStyle = VoiceStyle.PLAIN,
    /** Something players are told on this fighter's page, above everything else about them. Null: nothing. */
    val notice: String? = null,
    /** False for a fighter that can no longer be levelled up: it stays at whatever level it has. */
    val upgradable: Boolean = true,
    /** A minion (build 116): called into a match from its pool, never respawned, never anyone's to play. */
    val minion: Boolean = false,
    /** Its super charges by itself, this much a second (minions: they pounce and blink on their own clock). */
    val superPerSecond: Float = 0f,
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
    /** A plain speaking voice, low and brisk: Ninja's, in the opening (it was Varun's, then nobody's). */
    PLAIN("en-US", 0.8f, 1.1f),
    /** Buddy: a machine reading its own log. Flat, deep and unhurried. */
    MACHINE("en-GB", 0.42f, 0.86f),
    /**
     * The Entity (the Void in the code): as deep as a voice will go, from somewhere a long way down, and slow, but not so
     * slow that its speech in the uprising can't be said in the time it has.
     */
    VOID("en-GB", 0.3f, 0.92f),
    /** Swish, in the opening (the one time she speaks): light and quick, a little breathless. */
    STORM("en-US", 1.3f, 1.02f),
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

/**
 * Game modes. Names and rules are original to this game. AstroArena is a boss-fight game about the Entity (the
 * user's direction, build 111): Knockout Rush, Last Spark and Dimension were removed, and Boss Mode is the fight
 * against the Entity itself, its ritual first.
 */
enum class GameMode(val title: String, val tagline: String, val players: Int) {
    /** Endless minions, every kill making you stronger; bosses make them stronger; then the Dragon, and a way out (build 116). */
    SWARM("Swarm", "Endless minions · every kill makes you stronger · earn your way out", 1),
    /** Your team against the Entity: its ritual, then its world. */
    BOSS("The Dragon", "Your team against the Dragon · its ritual, then its world", 3),
    /** Practice: dummies, a swarm and a giant that just stand there, plus one sentry gun. No timer, nothing won or lost. */
    TRAINING("Training Area", "Dummies, a swarm, a sentry and a giant · no stakes", 19),
    /** One real player against another, each on their own device. */
    DUEL("1v1", "You against one real player · first to 3 knockouts", 2),
}

object Balance {
    /** The highest level a fighter can normally reach. Dev builds can switch the cap off in the debug menu. */
    const val MAX_LEVEL = 10

    /** Stops a damaged save from loading a nonsense level (levels above [MAX_LEVEL] are valid with the cap off). */
    const val LEVEL_LIMIT = 9999
    /** How much of one upgrade's growth a player's level-1 fighter gets for free ([StatLine.at]). */
    const val LEVEL_ONE_BOOST = 0.5f
    /** In a realm that lets its captive fight ([SuperSpec.captiveFree]), the captive moves this much of its speed... */
    const val REALM_CAPTIVE_SPEED = 0.75f
    /** ...and deals this much of its damage. */
    const val REALM_CAPTIVE_DAMAGE = 0.75f

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
    /** The Dragon cannot speak (the owner's decision, build 115): no voice and no talking jaw; its words are only text. */
    const val DRAGON_SPEAKS = false
    /** Hypers are switched off: they don't exist in the game since build 114 (the owner's decision). */
    const val HYPERS = false
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
     * A boss gets no less: the full time, by the user's decision.
     */
    const val THRALL_SECONDS = 21f
    /** A [SuperKind.BREW] flask bursts this long after it is thrown. */
    const val BREW_DELAY_SECONDS = 0.6f
    /** A hex ([AttackSpec.hexSeconds]) bites this often: fast. */
    const val HEX_TICK_SECONDS = 0.5f
    /** A [SuperKind.CORRUPT] poison bites this often. The super's damage is what it does each second. */
    const val POISON_TICK_SECONDS = 0.5f
    /** The poison wears off after this long (or with a knockout)... */
    const val POISON_SECONDS = 6f
    /** ...and a boss shakes it off sooner. */
    const val POISON_GIANT_SECONDS = 4f

    /** A planted rocket ([AttackShape.PLANT]) takes this long to fly to its spot, and a fighter can have this many in the ground: one more sets the oldest off. */
    const val PLANT_FLIGHT_SECONDS = 0.45f
    const val PLANT_MAX = 4

    /** A [SuperKind.REALM] lasts this long (or until either of the two in it is knocked out). */
    const val REALM_SECONDS = 12f
    /** Spark's Shadow Realm is short: six seconds and it is over (12 felt endless with his shadows at work). */
    const val SPARK_REALM_SECONDS = 6f
    /** In the realm a rocket of its own hits the captive this often, for the super's damage. It costs no ammo, and charges the super like one of her own. */
    const val REALM_ROCKET_SECONDS = 1f
    /** Whoever opened the realm reloads this many times as fast in it, and charges their super this many times as fast. */
    const val REALM_RELOAD = 1.5f
    const val REALM_CHARGE = 1.5f
    /**
     * A strike of a realm's shadows ([SuperSpec.shadows]) charges the super (and the hyper) by this share of a normal
     * hit: they strike four times a second, so at full rate the super came back in under a second. At 2.5% a whole
     * six-second realm of shadow strikes gives back about a sixth of a super; the realm stays chainable.
     */
    const val REALM_SHADOW_CHARGE = 0.025f

    /** A slowed fighter ([AttackSpec.slowSeconds]) moves at this share of its speed. */
    const val SLOW_FACTOR = 0.5f

    /** A homing arrow ([AttackShape.ARROW]) only turns toward enemies within this many tiles, and ahead of it. */
    const val ARROW_SEEK_RADIUS = 3.5f

    /** A tempest ([SuperKind.TEMPEST]) hits everyone in it this often... */
    const val TEMPEST_TICK_SECONDS = 0.4f
    /** ...flies (over walls and water) this many times as fast as the fighter walks... */
    const val TEMPEST_SPEED = 1.6f

    // ---- The Entity's uprising (sim/Uprising.kt; the user's idea, 1.3). It comes in every match of these modes,
    // between UPRISING_EARLIEST and UPRISING_EARLIEST + UPRISING_SPREAD seconds of play (the seed picks when).
    const val UPRISING_EARLIEST = 30f
    const val UPRISING_SPREAD = 30f
    /** How long the sky glitches and everyone stares up at it before it calls them. */
    const val UPRISING_GAZE = 6f
    /** The longest the walk to it can take; past [UPRISING_PULL] it drags whoever is still on the way. */
    const val UPRISING_MARCH = 16f
    const val UPRISING_PULL = 11f
    /** It holds everyone for this long in all (the user: "at least a minute") before they go through its portal. */
    const val UPRISING_HOLD = 60f
    const val UPRISING_ENTER = 4f
    /**
     * The bow lasts at least this long, however long the walk took: long enough for the Entity's whole speech in its
     * own voice, with the camera on each face it speaks to (`match/UprisingScript`).
     */
    const val UPRISING_BOW = 72f
    /** The longest the fight in its dimension lasts: then it lets them go, beaten or not. */
    const val UPRISING_SKY = 60f
    const val UPRISING_RETURN = 1.5f
    /** Its health in its dimension: everyone's attack damage (all of a volley) added up, this many times over. */
    const val UPRISING_HP_SHOTS = 100
    /**
     * It hovers over its platform, and the shadow under it, this many tiles round it, is what attacks hit: they stay
     * on the ground (the user's design) and land on it where they reach that shadow.
     */
    const val UPRISING_ENTITY_RADIUS = 2.2f
    /** It drifts after the fight, toward the middle of everyone, this many tiles a second. */
    const val UPRISING_ENTITY_SPEED = 1.1f
    /** A super with nothing of its own to do to it (Buddy's leap) lands on it for this many attacks' worth. */
    const val UPRISING_SUPER_SHOTS = 4
    /** Its own strikes down at them: marked circles that hurt everyone (never a knock-out). */
    const val UPRISING_STRIKE_GAP = 2.4f
    const val UPRISING_STRIKE_RADIUS = 1.4f
    const val UPRISING_STRIKE_DELAY = 1.3f
    const val UPRISING_STRIKE_DAMAGE = 900
    /** Boss Mode (the Entity's fight): it begins this soon, lasts at most this long, is this much tougher, and hits this much harder. */
    const val UPRISING_BOSS_START = 1.5f
    const val UPRISING_BOSS_SECONDS = 210f
    const val UPRISING_BOSS_HP = 1.6f
    // (3 since build 111: the user wanted it to "actually do some damage".)
    // (Lower again once its fight had no respawns: at 3 the whole team was down in twenty seconds.)
    const val UPRISING_BOSS_DAMAGE = 1.6f
    /** Its corruption flares: they follow their target this long, then come down this much later, over this circle. */
    const val FLARE_LOCK = 1.6f
    // (0.9 s from when it stops following to when it lands: being pulled in lasts until the Entity is beaten, so it
    // has to be fair to step out of. At 0.55 s a whole team was in it within a minute.)
    const val FLARE_FALL = 0.9f
    /**
     * Since build 111 its flares hunt (`World.stepFlares`): they rise out of it for [FLARE_EMERGE], then fly after
     * their target at [FLARE_SPEED] (a little under a fighter's run, 3.6 to 4 tiles a second: only running gets
     * away), turning [FLARE_TURN] radians a second, for up to [FLARE_LIFE]; [FLARE_RADIUS] is how close is a hit.
     */
    const val FLARE_EMERGE = 0.6f
    const val FLARE_SPEED = 2.6f
    const val FLARE_TURN = 1.8f
    const val FLARE_LIFE = 4f
    const val FLARE_RADIUS = 0.55f
    /** A fighter caught under one fights for the Entity, against its own side, for this long. */
    const val CORRUPT_SECONDS = 6f
    /** Its hand's circle (its slam), and how far apart its chase's strikes come down. */
    const val ENTITY_GRASP_RADIUS = 2.6f
    const val ENTITY_CHASE_GAP = 0.45f
    /** Its dash: the wind-up (its lane marked) and how fast it charges down it. */
    const val ENTITY_DASH_WIND = 1.1f
    const val ENTITY_DASH_SPEED = 11f
    /**
     * Its sword spin (its main attack): the wind-up, how long it spins, a cut every tick over its whole reach (the
     * blade is exactly this long), what a cut does before its fight's multiplier, and how fast it drifts as it spins.
     */
    const val ENTITY_SPIN_WIND = 1.2f
    const val ENTITY_SPIN_SECONDS = 2.8f
    const val ENTITY_SPIN_TICK = 0.4f
    const val ENTITY_SPIN_RADIUS = 4.6f
    const val ENTITY_SPIN_DAMAGE = 500
    const val ENTITY_SPIN_MOVE = 2.2f
    /** Feeding on whoever it holds: health a second (a share of its whole) and harder hits, for each of them. */
    const val ENTITY_FEED_HEAL = 0.0015f
    const val ENTITY_FEED_DAMAGE = 0.2f
    /**
     * Its red flares drain whoever they land on: each drain takes this share off their damage and their speed for the
     * rest of the match and heals it this share of its whole; this many and they have nothing left and it takes them.
     */
    const val ENTITY_DRAIN_DAMAGE = 0.15f
    const val ENTITY_DRAIN_SPEED = 0.1f
    const val ENTITY_DRAIN_HEAL = 0.03f
    const val ENTITY_DRAINS_TO_TAKE = 3
    /** Each tier (one more for every fight with it, won or lost) adds this much health, damage and pace. */
    const val ENTITY_TIER_HP = 0.05f
    const val ENTITY_TIER_DAMAGE = 0.03f
    const val ENTITY_TIER_PACE = 0.015f
    /** The calling walk is paced to reach its circle at this many seconds. */
    const val UPRISING_MARCH_PACE = 10f
    /** A tornado sets her down at least this far clear of anyone (past both their edges), so nobody hides her. */
    const val TEMPEST_LANDING_GAP = 0.6f
    /** ...and each of its hits charges the super by only this share of a normal hit, so a tempest doesn't pay for the next one. */
    const val TEMPEST_CHARGE = 0.25f
    /**
     * ...and brings lightning down every [TEMPEST_BOLT_SECONDS] on the nearest enemy it can see within [TEMPEST_BOLT_RANGE],
     * up to [TEMPEST_BOLT_SCATTER] tiles off, hitting everyone within [TEMPEST_BOLT_RADIUS] of where it lands for this share of
     * the super's damage.
     */
    const val TEMPEST_BOLT_SECONDS = 0.7f
    const val TEMPEST_BOLT_RANGE = 4.5f
    const val TEMPEST_BOLT_SCATTER = 0.6f
    const val TEMPEST_BOLT_RADIUS = 0.9f
    const val TEMPEST_BOLT_DAMAGE = 0.75f

    /** A lobbed star ([AttackShape.STAR]) takes this long to come down on its spot. */
    const val STAR_FLIGHT_SECONDS = 0.6f

    /** A blade with a full bar ([AttackSpec.edgeSeconds]) reaches this many times as far. */
    const val EDGE_REACH = 1.25f

    /**
     * A boss can miss: every move is aimed at a spot up to this many tiles off where its target really is (usually
     * much less: the error is two random numbers added together).
     */
    const val BOSS_MISS_TILES = 1.8f

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
    /**
     * The Entity's glitch (the user's lore, build 108): after using a super or a hypercharge, a fighter may glitch, and
     * for [GLITCH_SECONDS] it fights on its own (the Entity fighting through them).
     */
    /** Entity control / mid-match player takeover removed by the owner. */
    const val ENTITY_CONTROL = false
    const val GLITCH_SECONDS = 7f
    /**
     * The Entity's pull (`Fighter.entityPull`) fills by these: a little every second, more for every attack, a bit more for
     * a super or a hypercharge (not so much that it only ever comes with a super: the user wants it mid-fight). Full, the fighter glitches, every time, as often as it fills: about every 20 to 30
     * seconds of fighting.
     */
    const val GLITCH_PULL_PER_SECOND = 1f / 30f
    const val GLITCH_PULL_ATTACK = 0.1f
    const val GLITCH_PULL_SUPER = 0.15f
    const val GLITCH_PULL_HYPER = 0.15f
    /** The breather after a glitch before the pull starts filling again. */
    const val GLITCH_COOLDOWN = 4f

    /** Bots don't attack for this long once a match starts (they also hold fire while their spawn shield is up). */
    const val BOT_WARMUP_SECONDS = 4f

    // ---- Dimension (GameMode.PORTAL): a lore mode, not a fighting one (build 104, the user's decision)
    /** Echo shards to find in each dimension before its portal opens. */
    const val PORTAL_SHARDS = 3
    /** How close (beyond the fighter's own radius) a shard is picked up. */
    const val PORTAL_SHARD_REACH = 0.7f
    /** Shards lie at least this far from where the player arrives, and from each other. */
    const val PORTAL_SHARD_SPREAD = 5f
    /** The pause on arriving in a dimension, and after the last portal before the story ends. */
    const val PORTAL_ARRIVE_SECONDS = 1.2f
    const val PORTAL_TRAVEL_SECONDS = 1.2f
    /** How close (beyond the fighter's own radius) to the middle of the open gate takes you through. */
    const val PORTAL_RADIUS = 0.75f
    /** The chained fighter: how close (beyond the player's radius) counts as being beside them, to talk. */
    const val PORTAL_CAPTIVE_REACH = 1.6f
    /**
     * Glitch rifts: from the second dimension on the world tears open near the player now and then. A rift is marked
     * for [PORTAL_RIFT_DELAY] seconds and then takes [PORTAL_RIFT_SHARE] of their health if they are still in it.
     */
    const val PORTAL_RIFT_DELAY = 1.3f
    const val PORTAL_RIFT_SHARE = 0.2f
    /** Seconds between rifts in [dimension] (none in the first). */
    fun portalRiftEvery(dimension: Int) = if (dimension < 2) Float.MAX_VALUE else maxOf(2.2f, 5.5f - 0.5f * (dimension - 2))
    fun portalRiftRadius(dimension: Int) = minOf(1.6f, 0.9f + 0.1f * dimension)

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
    const val MIN_HEALTH = 6600
    const val MIN_AMMO_DAMAGE = 1300

    /**
     * Spark, retired in build 102: his def lives in [io.github.projectwip.data.legacy.LegacyFighters] now (build 114),
     * kept because the tests keep his lobbed star and shadow realm working with it.
     */
    val spark: FighterDef get() = io.github.projectwip.data.legacy.LegacyFighters.spark

    /**
     * The fighters in the game: since build 114 (the owner's decision, ARENA 1.4) only Ninja. Swish, Bark, Kira, Kito
     * and Buddy were removed, their defs kept in [io.github.projectwip.data.legacy.LegacyFighters], and their players
     * got nothing back.
     */
    val fighters: List<FighterDef> = listOf(
        FighterDef(
            // (The starter's id, BYTE, for lore and old saves: the owner's decision. Starter, as the starter always is.)
            id = FighterId.BYTE,
            rarity = Rarity.STARTER,
            name = "Ninja",
            title = "The Faceless",
            role = "Gunslinger",
            lore = "A hooded ninja with nothing under the hood: no face, just a dark that looks back. Nobody remembers what he looked like before the Dragon got to him. He doesn't either. He kept the gun.",
            attackName = "Void Round",
            health = StatLine(8200, 410),
            // One bullet: 1,150 base (buffed from 900).
            attackDamage = StatLine(1150, 58),
            // One cosmic bullet: double a normal one, and far more against the Entity (COSMIC_ENTITY_MULT).
            superDamage = StatLine(2300, 115),
            moveSpeed = 3.6f,
            attack = AttackSpec(AttackShape.BULLET, projectiles = 1, spreadDegrees = 0f, range = 8.5f, speed = 24f, radius = 0.14f, burstInterval = 0f),
            superSpec = SuperSpec(SuperKind.COSMIC, "Cosmic Rounds", "Fires three blinding electric-blue cosmic bullets in quick succession. Each hits twice as hard as a normal bullet, and against the Dragon they do massive damage and stun it for 2.5 seconds.", projectiles = 3, range = 10f, speed = 26f, radius = 0.24f),
            ammoMax = 5,
            reloadSeconds = 0.9f,
            superChargePerHit = 0.085f,
            // (Three colourways, in the slots Swish's had, so whoever owned one still does.)
            skins = listOf(
                Skin("Void", 0xFF17141F, 0xFF2A2540, 0xFF5CC8FF, 0),
                Skin("Ash", 0xFF8A8796, 0xFF3B3946, 0xFFE9F5FF, 20),
                Skin("Crimson", 0xFF5A0F1C, 0xFF1C0A0E, 0xFFFF4D5E, 20),
            ),
        ),
    )

    // ---- Minions (build 116, the owner's design: glitched robots of our own, the Dragon's small copies) ----
    private fun minionDef(id: FighterId, name: String, role: String, health: Int, damage: Int, superDamage: Int, speed: Float, attack: AttackSpec,
                          superSpec: SuperSpec, reload: Float, radius: Float, primary: Long, secondary: Long, accent: Long, superPerSecond: Float = 0f) =
        FighterDef(id = id, name = name, title = "Minion", role = role, lore = "One of the Dragon's.", attackName = role,
            health = StatLine(health, 0), attackDamage = StatLine(damage, 0), superDamage = StatLine(superDamage, 0), moveSpeed = speed,
            attack = attack, superSpec = superSpec, ammoMax = 1, reloadSeconds = reload, superChargePerHit = 0f, radius = radius,
            skins = listOf(Skin(name, primary, secondary, accent, 0)), minion = true, superPerSecond = superPerSecond, upgradable = false)
    private val NO_SUPER = SuperSpec(SuperKind.VOLLEY, "None", "", range = 0f, speed = 0f, radius = 0f)

    val minions: List<FighterDef> = listOf(
        // A melee robot: walks up to you and crushes.
        minionDef(FighterId.MINION_BOT, "Crusher", "Melee robot", 2800, 420, 0, 3.1f,
            AttackSpec(AttackShape.SLASH, projectiles = 1, spreadDegrees = 100f, range = 1.7f, speed = 40f, radius = 0f, burstInterval = 0f), NO_SUPER, 1.1f, 0.42f,
            0xFF3A3F55, 0xFF1C1E2A, 0xFFFF3B6B),
        // A laser bot: keeps its distance and shoots red bolts.
        minionDef(FighterId.MINION_LASER, "Zapper", "Laser bot", 2200, 380, 0, 3.0f,
            AttackSpec(AttackShape.LASER, projectiles = 1, spreadDegrees = 0f, range = 8.5f, speed = 20f, radius = 0.13f, burstInterval = 0f), NO_SUPER, 1.7f, 0.4f,
            0xFF2E3550, 0xFF14182A, 0xFFFF3030),
        // A hound: fast, scratches, and pounces (a charge that knocks you back).
        minionDef(FighterId.MINION_HOUND, "Lurcher", "Hound", 2400, 360, 600, 4.4f,
            AttackSpec(AttackShape.SLASH, projectiles = 1, spreadDegrees = 90f, range = 1.5f, speed = 40f, radius = 0f, burstInterval = 0f),
            SuperSpec(SuperKind.RAM, "Pounce", "", range = 5.5f, speed = 16f, radius = 0.5f), 0.9f, 0.4f, 0xFF4A3B5A, 0xFF231B2E, 0xFFFF7A3B, superPerSecond = 0.25f),
        // Swarm's boss: a giant robot.
        minionDef(FighterId.MINION_BRUTE, "Juggernaut", "Boss", 24000, 1100, 1500, 2.6f,
            AttackSpec(AttackShape.SLASH, projectiles = 1, spreadDegrees = 140f, range = 2.7f, speed = 40f, radius = 0f, burstInterval = 0f),
            SuperSpec(SuperKind.RAM, "Stampede", "", range = 6f, speed = 13f, radius = 1.1f), 1.4f, 1.05f, 0xFF3A2F55, 0xFF1A1430, 0xFFFF3B6B, superPerSecond = 0.15f),
        // The Dragon's own: a small copy of it, a short deadly laser, and it blinks to you and strikes.
        minionDef(FighterId.DRAGONLING, "Drakeling", "Dragonling", 2000, 700, 1100, 3.8f,
            AttackSpec(AttackShape.LASER, projectiles = 1, spreadDegrees = 0f, range = 4.5f, speed = 22f, radius = 0.16f, burstInterval = 0f),
            SuperSpec(SuperKind.BLINK, "Blink Strike", "", range = 9f, speed = 40f, radius = 0f), 1.3f, 0.36f, 0xFF2A1856, 0xFF130A2C, 0xFF3FB4FF, superPerSecond = 0.2f),
    )

    /** A fighter's def; a removed fighter's comes from [io.github.projectwip.data.legacy.LegacyFighters] (old saves, tests). */
    fun fighter(id: FighterId): FighterDef = fighters.firstOrNull { it.id == id } ?: minions.firstOrNull { it.id == id } ?: io.github.projectwip.data.legacy.LegacyFighters.def(id)

    // ---- Swarm (build 116) ----
    /** Every kill makes you this much stronger, damage and health, for the rest of the run. */
    const val SWARM_KILL_POWER = 0.03f
    /** ...and heals them this share of their health. */
    const val SWARM_KILL_HEAL = 0.06f
    /** A wave comes when the last one is cleared, or after this long, whichever is first. */
    const val SWARM_WAVE_SECONDS = 22f
    /** Every this many waves is a boss (the Juggernaut). */
    const val SWARM_BOSS_EVERY = 5
    /** Bosses beaten before the waves stop and the Dragon comes. */
    const val SWARM_BOSSES_FOR_DRAGON = 3
    /** Each boss beaten makes minions this much stronger (and more of them come). */
    const val SWARM_LEVEL_STRENGTH = 0.25f
    /** How close to the portal is through it. */
    const val SWARM_PORTAL_RADIUS = 1.4f

    // ---- The Dragon's own mind (build 116) ----
    /** How fast it turns to face what it wants, in radians a second. */
    const val DRAGON_TURN = 1.8f
    /** Seconds between its calls for minions, and how many of its own can be out at once. */
    const val DRAGON_SUMMON_GAP = 14f
    const val DRAGON_MINION_CAP = 8

    /** Ninja's super: the gap between its three cosmic bullets, how much harder each hits the Entity, and how long it stuns it. */
    const val COSMIC_GAP_SECONDS = 0.12f
    const val COSMIC_ENTITY_MULT = 3f
    const val COSMIC_STUN_SECONDS = 2.5f
    /**
     * The jewel chained on the dragon's neck (the owner's design, build 115): while it is on, the Entity is immune.
     * It has this share of the Entity's health. It sits [JEWEL_OFFSET] tiles in front of the Entity (toward the
     * fighters' side), and shots that reach [JEWEL_RADIUS] of it hit it.
     */
    const val JEWEL_HP = 0.07f
    /** A blue flare that catches someone hits them for this share of their health (it pulled them in until build 115). */
    const val FLARE_HIT = 0.45f
    const val JEWEL_OFFSET = 1.45f
    const val JEWEL_RADIUS = 1.0f
    /**
     * Broken, it only comes back if it turns up again somewhere on the platform ([JEWEL_BACK_MIN] to
     * [JEWEL_BACK_MIN] + [JEWEL_BACK_SPREAD] seconds later, where the seed says) and the dragon gets to it first,
     * rushing at [JEWEL_RUSH_SPEED]. Fighters can break it again where it lies ([JEWEL_DROP_HP] of its health).
     */
    const val JEWEL_BACK_MIN = 18f
    const val JEWEL_BACK_SPREAD = 14f
    const val JEWEL_DROP_HP = 0.35f
    const val JEWEL_DROP_RADIUS = 0.75f
    const val JEWEL_RUSH_SPEED = 5.5f

    /** After a stun wears off the Entity can't be stunned again for this long: three Ninjas otherwise held it still for good. */
    const val COSMIC_STUN_GUARD = 6f

    // ---- Training Area ----
    // (The dummies, swarm and sentry are made from the starter, Ninja since build 114, and look like him.)
    // Everything here is created at level 1 with flat stat lines: it is a fixed yardstick.

    /** The Training Area's giant: a dummy two and a half times the size, with a mountain of health (it stands where a boss stood). */
    val giant: FighterDef by lazy { fighter(FighterId.BYTE).let { it.copy(name = "Giant", title = "Target", health = StatLine(75000, 0), radius = it.radius * 2.5f) } }

    /** A target dummy: never attacks, soaks up damage and regenerates like anyone else. */
    val dummy: FighterDef = fighter(FighterId.BYTE).let { it.copy(name = "Dummy", title = "Target", health = StatLine(8000, 0)) }

    /** One of the swarm: a little over half size and fragile. In the Training Area it is a target and never attacks. */
    val mini: FighterDef = fighter(FighterId.BYTE).let {
        it.copy(
            name = "Mini", title = "Swarm", health = StatLine(3000, 0),
            attackDamage = StatLine(Math.round(it.attackDamage.base * 0.3f), 0), superDamage = StatLine(Math.round(it.superDamage.base * 0.3f), 0),
            radius = it.radius * 0.62f, reloadSeconds = it.reloadSeconds * 1.6f, superChargePerHit = 0f,
            attack = it.attack.copy(range = it.attack.range * 0.8f),
        )
    }

    /** The sentry: a long-range gun on an island of coolant. Slow to reload, so its shots can be dodged. */
    val sentry: FighterDef = fighter(FighterId.BYTE).let {
        // (One shot of 1,000 every five seconds. It had three shots of 1,500 and a 2.4 second reload until the user
        // found it too much.)
        it.copy(name = "Sentry", title = "Turret", health = StatLine(8000, 0), attackDamage = StatLine(1000, 0),
            superDamage = StatLine(1920, 0), ammoMax = 1, reloadSeconds = 5f, superChargePerHit = 0f, hyper = null,
            attack = AttackSpec(AttackShape.LANCE, projectiles = 1, spreadDegrees = 0f, range = 10f, speed = 22f, radius = 0.18f, burstInterval = 0f))
    }

    /** How a locked fighter can be obtained. */
    fun unlockPrismPrice(id: FighterId): Int? = if (id == FighterId.BYTE) null else when (fighter(id).rarity) {
        Rarity.STARTER -> null
        Rarity.RARE -> 40
        Rarity.EPIC -> 70
        Rarity.MYTHIC -> 90
        Rarity.LEGENDARY -> 160
    }
}
