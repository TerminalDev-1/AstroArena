package io.github.projectwip.match

import io.github.projectwip.sim.Phase

/**
 * Everything the 2D HUD draws, captured on the render thread after each frame and handed to the
 * UI thread. Double-buffered: the renderer fills [back], then [publish] swaps it with the front.
 */
class HudSnapshot {
    var width = 1
    var height = 1
    val viewProj = FloatArray(16)

    var phase = Phase.COUNTDOWN
    var phaseTime = 0f
    var countdownSeconds = 3f
    var timeLeft = 0f
    var myScore = 0
    var theirScore = 0
    var koTarget = 10
    var winningTeam = -1
    var playerTeam = 0

    var playerAlive = true
    var respawnTimer = 0f
    var ammo = 0f
    var ammoMax = 3
    var superCharge = 0f
    var hyperCharge = 0f
    /** The share (0..1) of the player's running hyper still to go; 0 when none is running. */
    var hyperLeft = 0f
    /** A 1v1's line across the middle of the screen: 0 none, 1 waiting for the other player, 2 called off (out of step), 3 called off (connection lost). */
    var duelNotice = 0
    var autoTargetId = -1
    var matchesPlayed = 0
    var fps = 0
    var freeForAll = false
    /** Boss Mode: the giant's health and the player's remaining lives go on the HUD instead of a score. */
    var bossMode = false
    /** How often the player has been knocked out (Boss Mode has no life limit, it just counts). */
    var timesDown = 0
    /** Training Area: no clock, no target. */
    var practice = false
    /** Damage the player has dealt so far. */
    var damage = 0
    var bossHp = 0
    var bossMaxHp = 1
    var bossName: String? = null
    var aliveCount = 0
    var placement = 0
    var stormElapsed = -1f
    var playerOutsideStorm = false

    val count get() = n
    var n = 0
    val ids = IntArray(MAX)
    val sx = FloatArray(MAX)
    val sy = FloatArray(MAX)
    val visible = BooleanArray(MAX)
    val hp = IntArray(MAX)
    val maxHp = IntArray(MAX)
    /** 0 = you, 1 = ally, 2 = enemy */
    val relation = IntArray(MAX)
    val names = arrayOfNulls<String>(MAX)
    val superReady = BooleanArray(MAX)
    /** A hyper is running. */
    val hyper = BooleanArray(MAX)
    /** Malformed code is running in them (Buddy's super). */
    val poisoned = BooleanArray(MAX)
    val cells = IntArray(MAX)

    fun copyFrom(o: HudSnapshot) {
        width = o.width; height = o.height
        System.arraycopy(o.viewProj, 0, viewProj, 0, 16)
        phase = o.phase; phaseTime = o.phaseTime; countdownSeconds = o.countdownSeconds; timeLeft = o.timeLeft
        myScore = o.myScore; theirScore = o.theirScore; koTarget = o.koTarget; winningTeam = o.winningTeam; playerTeam = o.playerTeam
        playerAlive = o.playerAlive; respawnTimer = o.respawnTimer; ammo = o.ammo; ammoMax = o.ammoMax; superCharge = o.superCharge
        hyperCharge = o.hyperCharge; hyperLeft = o.hyperLeft; duelNotice = o.duelNotice
        autoTargetId = o.autoTargetId; matchesPlayed = o.matchesPlayed; fps = o.fps
        bossMode = o.bossMode; timesDown = o.timesDown; practice = o.practice; damage = o.damage; bossHp = o.bossHp; bossMaxHp = o.bossMaxHp; bossName = o.bossName
        freeForAll = o.freeForAll; aliveCount = o.aliveCount; placement = o.placement
        stormElapsed = o.stormElapsed; playerOutsideStorm = o.playerOutsideStorm
        n = o.n
        for (i in 0 until n) {
            ids[i] = o.ids[i]; sx[i] = o.sx[i]; sy[i] = o.sy[i]; visible[i] = o.visible[i]
            hp[i] = o.hp[i]; maxHp[i] = o.maxHp[i]; relation[i] = o.relation[i]; names[i] = o.names[i]; superReady[i] = o.superReady[i]; hyper[i] = o.hyper[i]; poisoned[i] = o.poisoned[i]; cells[i] = o.cells[i]
        }
    }

    /** The most fighters a match can hold (the Training Area is the biggest, at 19). */
    companion object { const val MAX = 32 }
}

/** Lock-protected hand-off between render thread (writer) and UI thread (reader). */
class HudChannel {
    val back = HudSnapshot()
    private val front = HudSnapshot()
    private val lock = Any()
    @Volatile var hasFrame = false
        private set

    fun publish() {
        synchronized(lock) { front.copyFrom(back) }
        hasFrame = true
    }

    fun <T> read(block: (HudSnapshot) -> T): T = synchronized(lock) { block(front) }
}
