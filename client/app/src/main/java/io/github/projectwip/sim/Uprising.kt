package io.github.projectwip.sim

/**
 * The Entity's uprising (the user's idea, 1.3): once in every match, at a moment the match's seed picks, the sky goes
 * glitchy and the Entity rises over the arena, staring down at everyone. It takes every fighter in the match (players
 * and bots alike), walks them all to it, has them bow, and talks to them; then it opens a portal and takes them all
 * to a secret dimension, where every fighter, whatever their team, fights the Entity itself, high in the sky, every
 * attack flying up at it. When it is beaten, or when it lets them go, everyone is back where they stood, as they
 * were, and the match goes on. Nothing in it is a roll: it happens in every match, only the moment is the seed's.
 *
 * It is part of the simulation (`World.stepUprising`), so every device and the referee play it out the same way.
 */
enum class UprisingPhase {
    /** Not yet: waiting for [Uprising.startAt]. */
    WAITING,
    /** The sky glitches and the Entity rises; everyone stands still, looking up. */
    GAZE,
    /** Everyone walks to it, to a place on a ring round the middle of the arena. */
    MARCH,
    /** Everyone bows while it talks to them. */
    BOW,
    /** Its portal opens in the middle and everyone walks into it. */
    ENTER,
    /** The secret dimension: everyone against the Entity, in the sky. */
    SKY,
    /** Back where they were. */
    RETURN,
    DONE,
}

class Uprising(
    /** Seconds of play after which it begins. */
    val startAt: Float,
) {
    var phase = UprisingPhase.WAITING
    /** Seconds in the current phase. */
    var t = 0f
    /** Seconds the Entity has held everyone (gaze, march, bow and portal together). */
    var heldFor = 0f
    /** Where everyone gathers (the middle of the arena), and where its portal opens. */
    var cx = 0f
    var cy = 0f
    /** Who it took, in id order. */
    val members = ArrayList<Int>()
    val slotX = HashMap<Int, Float>()
    val slotY = HashMap<Int, Float>()
    /** Where each one stood and the health they had, to put them back afterwards: x, y, hp. */
    val saved = HashMap<Int, FloatArray>()
    /** The Entity's health in the sky. */
    var hp = 0
    var maxHp = 0
    var strikeIn = 0f
    var strikes = 0
    /** True once it has been beaten (rather than having let them go). */
    var beaten = false

    val active get() = phase != UprisingPhase.WAITING && phase != UprisingPhase.DONE
    /** Everyone is the Entity's: nobody controls themselves. */
    val holding get() = phase == UprisingPhase.GAZE || phase == UprisingPhase.MARCH || phase == UprisingPhase.BOW || phase == UprisingPhase.ENTER || phase == UprisingPhase.RETURN
}
