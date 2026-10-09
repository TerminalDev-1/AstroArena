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

    fun clear() {
        moveX = 0f; moveY = 0f; aiming = false; attack = false; superAttack = false; hyper = false
    }
}

/** [side]: how far to the left (+) or right (-) of the fighter the shot leaves from, in tiles. */
class PendingShot(var delay: Float, val dirX: Float, val dirY: Float, val side: Float = 0f)

class Fighter(
    val id: Int,
    val def: FighterDef,
    val level: Int,
    val skin: Int,
    val team: Int,
    val name: String,
    val isBot: Boolean,
    /** Fixed to the spot (Training Area targets): it can turn and shoot, but never walks, dashes or gets shoved. */
    val rooted: Boolean = false,
) {
    val control = Control()
    val baseMaxHp = def.health.at(level)
    var maxHp = baseMaxHp
    /** Power Cells collected (free-for-all). Each adds health and damage. */
    var cells = 0
    val damageMultiplier get() = (1f + io.github.projectwip.data.Balance.CELL_DAMAGE_BONUS * cells) *
        (if (hyperActive) 1f + io.github.projectwip.data.Balance.HYPER_DAMAGE_BONUS else 1f)
    val attackDamage get() = (def.attackDamage.at(level) * damageMultiplier).toInt()
    val superDamage get() = (def.superDamage.at(level) * damageMultiplier).toInt()
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

enum class ShotStyle { SPARK, PELLET, PRISM, VOLLEY, LANCE, ROCKET, BIT, COMPUTER, PAW, HAMMER, HEX }

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
) {
    var prevX = x
    var prevY = y
    var alive = true
    var age = 0f
    val hit = HashSet<Int>(2)
}

/** A Power Cell lying on the ground. */
class Pickup(val x: Float, val y: Float) {
    var alive = true
    var age = 0f
}

sealed interface GameEvent {
    data class Shot(val fighterId: Int, val isSuper: Boolean, val x: Float, val y: Float, val dirX: Float, val dirY: Float) : GameEvent
    data class Hit(val targetId: Int, val sourceId: Int, val damage: Int, val x: Float, val y: Float, val isSuper: Boolean) : GameEvent
    data class Blocked(val x: Float, val y: Float) : GameEvent
    data class WallHit(val x: Float, val y: Float, val style: ShotStyle) : GameEvent
    data class Ko(val killerId: Int, val victimId: Int, val x: Float, val y: Float) : GameEvent
    data class Eliminated(val fighterId: Int, val placement: Int) : GameEvent
    data class StormHit(val targetId: Int, val damage: Int, val x: Float, val y: Float) : GameEvent
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
    /** A rocket went off. */
    data class Burst(val x: Float, val y: Float, val radius: Float) : GameEvent
    /** A fighter fired a salvo of rockets into the sky. */
    data class Launch(val fighterId: Int, val count: Int) : GameEvent
    /** A marked patch of ground went off. */
    data class Blast(val x: Float, val y: Float, val radius: Float, val kind: HazardKind) : GameEvent
    data object CountdownTick : GameEvent
    data object MatchStart : GameEvent
    data class MatchEnd(val winningTeam: Int) : GameEvent
}
