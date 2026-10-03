package io.github.projectwip.ai

import io.github.projectwip.data.BotDifficulty

/**
 * Everything that makes one bot smarter than another. Difficulty NEVER changes health or damage —
 * only how well the bot perceives, decides, moves and aims.
 */
data class BotProfile(
    /** Seconds a target must be visible before the bot starts shooting it. */
    val reactionTime: Float,
    /** Seconds between high-level decisions (target choice, goal, path). */
    val thinkInterval: Float,
    /** Standard deviation of aim error, degrees. */
    val aimErrorDegrees: Float,
    /** 0 = aims where the target is, 1 = fully predicts where it will be. */
    val leadFactor: Float,
    /** Chance to notice and side-step an incoming shot. */
    val dodgeChance: Float,
    /** 0 = runs straight at targets, 1 = holds its weapon's ideal distance and strafes. */
    val rangeDiscipline: Float,
    /** Retreats to heal below this health fraction (0 = never retreats). */
    val retreatBelow: Float,
    /** Prefers weakened targets over the nearest one. */
    val focusWeakest: Boolean,
    /** Fires only when the target is actually in range and the shot isn't blocked. */
    val shotDiscipline: Boolean,
    /** 0..1 — how carefully supers are timed. */
    val superSkill: Float,
    /** Random extra delay between shots, seconds. */
    val fireHesitation: Float,
    /** Amount of aimless drift added to movement. */
    val wander: Float,
    /** Stays near teammates instead of charging alone. */
    val teamwork: Float,
) {
    companion object {
        fun of(d: BotDifficulty): BotProfile = when (d) {
            BotDifficulty.EASY -> BotProfile(
                reactionTime = 0.9f, thinkInterval = 0.7f, aimErrorDegrees = 21f, leadFactor = 0f,
                dodgeChance = 0f, rangeDiscipline = 0.15f, retreatBelow = 0f, focusWeakest = false,
                shotDiscipline = false, superSkill = 0.1f, fireHesitation = 1.3f, wander = 0.5f, teamwork = 0f,
            )
            BotDifficulty.NORMAL -> BotProfile(
                reactionTime = 0.48f, thinkInterval = 0.42f, aimErrorDegrees = 11f, leadFactor = 0.35f,
                dodgeChance = 0.2f, rangeDiscipline = 0.6f, retreatBelow = 0.25f, focusWeakest = false,
                shotDiscipline = true, superSkill = 0.4f, fireHesitation = 0.5f, wander = 0.18f, teamwork = 0.3f,
            )
            BotDifficulty.HARD -> BotProfile(
                reactionTime = 0.2f, thinkInterval = 0.22f, aimErrorDegrees = 4.5f, leadFactor = 0.85f,
                dodgeChance = 0.6f, rangeDiscipline = 0.9f, retreatBelow = 0.33f, focusWeakest = true,
                shotDiscipline = true, superSkill = 0.8f, fireHesitation = 0.12f, wander = 0.05f, teamwork = 0.55f,
            )
            BotDifficulty.ELITE -> BotProfile(
                reactionTime = 0.12f, thinkInterval = 0.14f, aimErrorDegrees = 2.2f, leadFactor = 1f,
                dodgeChance = 0.85f, rangeDiscipline = 1f, retreatBelow = 0.4f, focusWeakest = true,
                shotDiscipline = true, superSkill = 1f, fireHesitation = 0.04f, wander = 0f, teamwork = 0.7f,
            )
        }
    }
}
