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

    fun clear() {
        moveX = 0f; moveY = 0f; aiming = false; attack = false; superAttack = false
    }
}

class PendingShot(var delay: Float, val dirX: Float, val dirY: Float)

class Fighter(
    val id: Int,
    val def: FighterDef,
    val level: Int,
    val skin: Int,
    val team: Int,
    val name: String,
    val isBot: Boolean,
) {
    val control = Control()
    val baseMaxHp = def.health.at(level)
    var maxHp = baseMaxHp
    /** Power Cells collected (free-for-all). Each adds health and damage. */
    var cells = 0
    val damageMultiplier get() = 1f + io.github.projectwip.data.Balance.CELL_DAMAGE_BONUS * cells
    val attackDamage get() = (def.attackDamage.at(level) * damageMultiplier).toInt()
    val superDamage get() = (def.superDamage.at(level) * damageMultiplier).toInt()
    val radius = def.radius

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
    val isDashing get() = dashTime > 0f
}

enum class ShotStyle { SPARK, PELLET, PRISM, VOLLEY, LANCE }

class Projectile(
    val ownerId: Int,
    val team: Int,
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val radius: Float,
    val damage: Int,
    var rangeLeft: Float,
    val pierce: Boolean,
    val isSuper: Boolean,
    val style: ShotStyle,
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
    data class Dash(val fighterId: Int) : GameEvent
    data object CountdownTick : GameEvent
    data object MatchStart : GameEvent
    data class MatchEnd(val winningTeam: Int) : GameEvent
}
