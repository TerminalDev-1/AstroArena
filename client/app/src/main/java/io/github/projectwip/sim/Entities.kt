package io.github.projectwip.sim

import io.github.projectwip.data.FighterDef

/**
 * What a fighter wants to do this tick. Humans (touch) and bots (AI) write the SAME structure,
 * so bots play by exactly the same rules as the player.
 */
class Control {
    /** Movement, length 0..1. */
    var moveX = 0f
    var moveY = 0f
    /** Direction for attack/super (any length; normalised by the world). */
    var aimX = 0f
    var aimY = 0f
    /** True while the player is dragging an aim stick (fighter faces the aim). */
    var aiming = false
    /** One-shot triggers, cleared by the world after each tick. */
    var attack = false
    var superAttack = false
    var hyper = false
    /** Sets off every rocket this fighter has planted (`AttackShape.PLANT`). Nothing to anyone else. */
    var detonate = false
    /** Skips the Entity's ritual (the player pressed SKIP while it holds everyone). */
    var skip = false

    fun clear() {
        moveX = 0f; moveY = 0f; aiming = false; attack = false; superAttack = false; hyper = false; detonate = false; skip = false
    }
}

/** [side]: how far to the left (+) or right (-) of the fighter the shot leaves from, in tiles. */
class PendingShot(var delay: Float, val dirX: Float, val dirY: Float, val side: Float = 0f)

class Fighter(
    val id: Int,
    val def: FighterDef,
    val level: Int,
    val skin: Int,
    /** (Changes only in the Entity's dimension, where everyone fights on one side, and back again.) */
    var team: Int,
    val name: String,
    val isBot: Boolean,
    /** Fixed to the spot (Training Area targets): it can turn and shoot, but never walks, dashes or gets shoved. */
    val rooted: Boolean = false,
) {
    val control = Control()
    val baseMaxHp = def.health.at(level, player = !isBot)
    var maxHp = baseMaxHp
    /** Power Cells collected (free-for-all). Each adds health and damage. */
    var cells = 0
    /** Portal Run: how much harder this enemy hits in the dimension it was brought into. */
    var dimensionDamage = 1f
    val damageMultiplier get() = dimensionDamage * (1f + io.github.projectwip.data.Balance.CELL_DAMAGE_BONUS * cells) *
        (if (hyperActive) 1f + io.github.projectwip.data.Balance.HYPER_DAMAGE_BONUS else 1f)
    val attackDamage get() = (def.attackDamage.at(level, player = !isBot) * damageMultiplier).toInt()
    val superDamage get() = (def.superDamage.at(level, player = !isBot) * damageMultiplier).toInt()
    val radius = def.radius
    /** How much bigger than a normal fighter of its kind this one is (a Boss Mode giant). */
    val scale = def.radius / io.github.projectwip.data.Balance.fighter(def.id).radius

    var x = 0f
    var y = 0f
    var prevX = 0f
    var prevY = 0f
    /** Actual velocity last tick (tiles/s) — used by AI for target leading. */
    var vx = 0f
    var vy = 0f
    var facing = if (team == 0) 0f else Math.PI.toFloat()
    var hp = maxHp
    var ammo = def.ammoMax.toFloat()
    var superCharge = 0f
    /**
     * Seconds left of a glitch (the user's lore, build 108): the Entity has taken over and the fighter fights on its
     * own (`Match.step`), whatever its player or brain wants.
     */
    var glitchLeft = 0f
    /** Seconds until this fighter can glitch again (a short breather after each glitch). */
    var glitchCooldown = 0f
    /**
     * The Entity's pull on this fighter, 0..1: it fills with time and with every power used, and when it is full the
     * fighter glitches, for sure, again and again all match long (the user's rule: never just once, no cap).
     */
    var entityPull = 0f
    /** Seconds the Entity still has this fighter, corrupted by one of its flares (it fights for the Entity). */
    var corruptLeft = 0f
    /**
     * Pulled into the Entity by one of its flares, among the hundreds of souls it carries (the user's design): out of
     * the fight (not alive, no respawn) until the Entity is beaten, which frees everyone it has.
     */
    var trapped = false
    var alive = true
    var respawnTimer = 0f
    var shield = 0f
    /** The hyper: 0..1 charged, and the seconds left of one that is running. */
    var hyperCharge = 0f
    var hyperTime = 0f
    /** Health a running hyper added to [maxHp], taken off again when it ends. */
    var hyperHpBonus = 0
    var sinceDamaged = 99f
    var sinceAttack = 99f
    /** While > 0 the fighter is visible even inside a thicket. */
    var revealTimer = 0f
    /** Seconds spent continuously inside a thicket; hidden only once this passes the conceal delay. */
    var concealTime = 0f
    /** Per team: seconds that team keeps seeing this fighter after spotting it up close in a thicket. */
    var spottedBy = FloatArray(0)
    var hitFlash = 0f
    var attackCooldown = 0f
    var walkCycle = 0f
    val pending = ArrayList<PendingShot>(4)

    // Ram dash (Brakk's super)
    var dashTime = 0f
    var dashDirX = 0f
    var dashDirY = 0f
    val dashHits = HashSet<Int>()

    // The leap (Buddy's super): the time left in the air out of the whole of it, where it started, where it is
    // coming down, and whose back that is.
    var leapTime = 0f
    var leapTotal = 0f
    var leapFromX = 0f
    var leapFromY = 0f
    var leapToX = 0f
    var leapToY = 0f
    var leapTarget = -1
    val isLeaping get() = leapTime > 0f
    /** The id of the fighter whose back this one is latched onto, compiling code into them (-1 = nobody's). */
    var latchedTo = -1
    val latched get() = latchedTo >= 0

    /** Hexed (Kira's poison): every bolt that landed is a poison of its own, and they all bite at once. */
    val hexes = ArrayList<Hex>(4)
    val hexed get() = hexes.isNotEmpty()

    // Corrupted (what Buddy's super leaves behind): whose it is (-1 = nobody's), how long it has left, and whether it
    // is on its way over to them right now (rather than standing in front of them).
    var thrallOf = -1
    var thrallLeft = 0f
    var thrallMoving = false
    val enthralled get() = thrallOf >= 0

    // Malformed code (Buddy's super): the id of whoever compiled it into this fighter (-1 = clean), what it does each
    // second, the time to its next bite, and how long it has left.
    var poisonBy = -1
    var poisonDamage = 0
    var poisonTick = 0f
    var poisonLeft = 0f
    val poisoned get() = poisonBy >= 0

    // The realm (Rocket's super): the id of whoever opened the realm this fighter is in (-1 = it is in the arena, like
    // everybody else). The one who opened it also keeps how long it has left and the time to its next rocket.
    var realm = -1
    var realmLeft = 0f
    /** Seconds left as a tempest ([io.github.projectwip.data.SuperKind.TEMPEST]), and until its next hit. */
    var tempestLeft = 0f
    var tempestTick = 0f
    var tempestBolt = 0f
    val inTempest get() = tempestLeft > 0f
    var realmTick = 0f
    /** How many strikes the realm's shadows have made so far ([SuperSpec.shadows]): they take turns. */
    var realmStrikes = 0
    /** Slowed ([AttackSpec.slowSeconds]) for this much longer. */
    var slowLeft = 0f
    val slowed get() = slowLeft > 0f
    val inRealm get() = realm >= 0
    /** Taken into a realm that lets its captive move and fight ([io.github.projectwip.data.SuperSpec.captiveFree]). */
    var realmFree = false
    /** Taken into a realm that holds its captive still: it can do nothing until it is over. */
    val heldInRealm get() = captive && !realmFree
    /** Taken into somebody else's realm: it can do nothing until it is over. */
    val captive get() = realm >= 0 && realm != id

    /** The bar of a blade (`AttackSpec.edgeSeconds`), 0..1: it fills by itself. Full, the next swing reaches further and empties it. */
    var edge = 0f
    val edgeFull get() = def.attack.edgeSeconds > 0f && edge >= 1f

    var lastAttackerId = -1
    var spawnIndex = 0
    /** Free-for-all: out for good. */
    var eliminated = false
    /** Free-for-all finishing place (1 = winner); 0 while still in the match. */
    var placement = 0
    /** Seconds until the next storm damage tick. */
    var stormTick = 0f

    // Stats
    var kos = 0
    var deaths = 0
    var damageDealt = 0

    val hpFraction get() = hp.toFloat() / maxHp
    val superReady get() = superCharge >= 1f
    /** How long this fighter's hyper runs. */
    val hyperSeconds get() = def.hyper?.seconds ?: io.github.projectwip.data.Balance.HYPER_SECONDS
    val hyperActive get() = hyperTime > 0f
    val hyperReady get() = hyperCharge >= 1f && !hyperActive
    val isDashing get() = dashTime > 0f
}

/** One hex on a fighter: who did it, what each bite does, the time to the next bite, and how long it has left. */
class Hex(val by: Int, val damage: Int, var tick: Float, var left: Float)

enum class ShotStyle { SPARK, PELLET, PRISM, VOLLEY, LANCE, ROCKET, BIT, COMPUTER, PAW, HAMMER, HEX, STAR, ARROW, GUST }

class Projectile(
    val ownerId: Int,
    val team: Int,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val radius: Float,
    val damage: Int,
    var rangeLeft: Float,
    val pierce: Boolean,
    val isSuper: Boolean,
    val style: ShotStyle,
    /** Bursts where it lands, hitting every enemy within this many tiles (0 = only what it touches). */
    val blast: Float = 0f,
    /** Shoves whoever it hits this many tiles the way it is flying. */
    val knock: Float = 0f,
    /** Takes this share of the health its target has left, when that is more than [damage] (0 = plain damage). */
    val share: Float = 0f,
    /** Poisons whoever it hits for this many seconds (0 = it doesn't): see `AttackSpec.hexSeconds`. */
    val hex: Float = 0f,
    /** Slows whoever it hits for this many seconds (0 = it doesn't): see `AttackSpec.slowSeconds`. */
    val slow: Float = 0f,
    /** Turns toward the nearest enemy ahead of it at most this many radians a second (0 = flies straight): see `AttackSpec.homing`. */
    val homing: Float = 0f,
) {
    var prevX = x
    var prevY = y
    var alive = true
    var age = 0f
    val hit = HashSet<Int>(2)
}

/**
 * A rocket somebody planted (`AttackShape.PLANT`): it flies from where it was thrown to ([x], [y]) in [flight] seconds,
 * sticks in the ground there, and waits for its owner to set it off.
 */
class Planted(val ownerId: Int, val team: Int, val fromX: Float, val fromY: Float, val x: Float, val y: Float, val flight: Float, val blast: Float) {
    var age = 0f
    val landed get() = age >= flight
}

/**
 * A star somebody lobbed (`AttackShape.STAR`): it flies in an arc from where it was thrown to ([x], [y]) in [flight]
 * seconds, over anything in the way, and bursts there, hitting every enemy within [blast] and slowing them for [slow] seconds.
 */
class Lob(val ownerId: Int, val team: Int, val fromX: Float, val fromY: Float, val x: Float, val y: Float, val flight: Float, val blast: Float, val slow: Float) {
    var age = 0f
}

/** A Power Cell lying on the ground. */
class Pickup(val x: Float, val y: Float) {
    var alive = true
    var age = 0f
}

sealed interface GameEvent {
    /** The Entity's uprising moved on to [phase] (an `UprisingPhase` ordinal). */
    data class Uprising(val phase: Int) : GameEvent
    /** In the Entity's sky, [fighterId] struck it for [damage] (a super: [isSuper]). */
    /** One of the Entity's flares locked onto [targetId] (or, [landed], came down at x, y). */
    data class EntityFlare(val targetId: Int, val x: Float, val y: Float, val landed: Boolean) : GameEvent
    /** The Entity pulled [fighterId] into itself ([on]), or it was freed. */
    data class Trapped(val fighterId: Int, val on: Boolean) : GameEvent
    /** The Entity corrupted a fighter ([on]) or let it go. */
    data class Corrupted(val fighterId: Int, val on: Boolean) : GameEvent
    data class EntityHit(val fighterId: Int, val isSuper: Boolean, val damage: Int, val x: Float, val y: Float) : GameEvent
    /** The Entity began a move ([move]: an `EntityMove` ordinal), for its sound. */
    data class EntityAttack(val move: Int) : GameEvent
    data class Shot(val fighterId: Int, val isSuper: Boolean, val x: Float, val y: Float, val dirX: Float, val dirY: Float) : GameEvent
    data class Hit(val targetId: Int, val sourceId: Int, val damage: Int, val x: Float, val y: Float, val isSuper: Boolean) : GameEvent
    data class Blocked(val x: Float, val y: Float) : GameEvent
    data class WallHit(val x: Float, val y: Float, val style: ShotStyle) : GameEvent
    data class Ko(val killerId: Int, val victimId: Int, val x: Float, val y: Float) : GameEvent
    data class Spawned(val fighterId: Int) : GameEvent
    data class CrateHit(val tx: Int, val ty: Int) : GameEvent
    data class CrateBroken(val tx: Int, val ty: Int) : GameEvent
    data class CellPicked(val fighterId: Int, val x: Float, val y: Float) : GameEvent
    data class SuperReady(val fighterId: Int) : GameEvent
    data class HyperReady(val fighterId: Int) : GameEvent
    /** A fighter switched its hyper on. */
    data class Hyper(val fighterId: Int) : GameEvent
    data class Dash(val fighterId: Int) : GameEvent
    /** A fighter was healed by something other than resting (a brew). */
    data class Heal(val targetId: Int, val amount: Int, val x: Float, val y: Float) : GameEvent
    /** A fighter left the ground to come down on someone's back. */
    data class Leap(val fighterId: Int) : GameEvent
    /** A leaper landed on [targetId] and started compiling malformed code into them. */
    data class Corrupt(val fighterId: Int, val targetId: Int) : GameEvent
    /** The code has taken [targetId] over: it is [fighterId]'s for a while. */
    data class Enthralled(val fighterId: Int, val targetId: Int) : GameEvent
    /** A thrown hammer came down: the ground quakes out to [radius] and stays cracked there. */
    data class Quake(val x: Float, val y: Float, val radius: Float) : GameEvent
    /** A blade was swung from ([x], [y]) along ([dirX], [dirY]): it reached [range] tiles across [degrees]. [charged]: with its bar full. */
    data class Slash(val fighterId: Int, val x: Float, val y: Float, val dirX: Float, val dirY: Float, val range: Float, val degrees: Float, val charged: Boolean) : GameEvent
    /** A fighter vanished from ([fromX], [fromY]) and is at ([x], [y]). */
    data class Blink(val fighterId: Int, val fromX: Float, val fromY: Float, val x: Float, val y: Float) : GameEvent
    /** A planted rocket stuck in the ground, ready to be set off. */
    data class Armed(val fighterId: Int, val x: Float, val y: Float) : GameEvent
    /** [fighterId] took [targetId] into a realm ([open]), or the realm they were in is over. */
    data class Realm(val fighterId: Int, val targetId: Int, val open: Boolean) : GameEvent
    /** [fighterId] became a tempest ([on]: Swish's super) or stopped being one. */
    data class Tempest(val fighterId: Int, val on: Boolean) : GameEvent
    /** A tempest's hit on [targetId], who is flung away from it. */
    data class TempestHit(val fighterId: Int, val targetId: Int, val x: Float, val y: Float) : GameEvent
    /** A tempest's lightning bolt comes down at ([x], [y]). */
    data class TempestBolt(val fighterId: Int, val x: Float, val y: Float) : GameEvent
    /** Portal Run: wave [wave] of dimension [dimension] is coming in. */
    /** The Entity has taken over [fighterId] for a few seconds (it glitched). */
    data class Glitched(val fighterId: Int) : GameEvent
    /** A rocket went off. */
    data class Burst(val x: Float, val y: Float, val radius: Float) : GameEvent
    /** A lobbed star came down and burst. */
    data class StarBurst(val ownerId: Int, val x: Float, val y: Float, val radius: Float) : GameEvent
    /** A fighter fired a salvo of rockets into the sky. */
    data class Launch(val fighterId: Int, val count: Int) : GameEvent
    /** A marked patch of ground went off. */
    data class Blast(val x: Float, val y: Float, val radius: Float, val kind: HazardKind) : GameEvent
    data object CountdownTick : GameEvent
    data object MatchStart : GameEvent
    data class MatchEnd(val winningTeam: Int) : GameEvent
}

/** What kind of ground attack a [Hazard] is, for drawing it. */
/** [BREW] is a fighter's flask on its way down: it hurts one side and heals the other. */
/** [RIFT] is the Entity's strike: the ground tearing open under someone. */
enum class HazardKind { ROCKET, MINE, SLAM, BREW, RIFT }

/**
 * A marked patch of ground that goes off after [delay] seconds, hurting everyone of the other team standing in
 * it. The mark is there from the start, so it can always be walked out of.
 */
class Hazard(
    val ownerId: Int, val team: Int, val x: Float, val y: Float, val radius: Float, val delay: Float, val damage: Int, val kind: HazardKind,
    /** False: it can take a fighter down to its last point of health, but never knocks it out. */
    val lethal: Boolean = true,
) {
    var age = 0f
}
