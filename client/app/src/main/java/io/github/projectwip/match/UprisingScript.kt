package io.github.projectwip.match

import io.github.projectwip.data.Balance
import io.github.projectwip.data.Lore
import io.github.projectwip.sim.UprisingPhase

/**
 * What the Entity is saying at a moment of its uprising, and where the camera is while it says it: one script that the
 * HUD (its words on a purple plate), the runner (its voice) and the renderer (the shot) all read, so the three agree.
 * The speech while everyone bows is laid out over the whole bow, each line given the time it takes to say.
 */
object UprisingScript {
    class Cue(
        /** Different for every line: a new id is a new line (to say out loud, and to cut the camera to). */
        val id: Int,
        val text: String,
        /** Small words under it on the plate, or null. */
        val sub: String?,
        val shot: Lore.RitualShot,
        /** Which line of the bow it is (for picking whose face), or -1. */
        val index: Int,
        /** Seconds since this line began, and how long it has. */
        val since: Float,
        val length: Float,
        /** Whether the HUD shows it on a plate (the other moments have plates of their own). */
        val plate: Boolean,
    )

    /** The last seconds of the bow, for "Now. Follow me." as its portal opens. */
    const val FOLLOW = 4.5f
    private const val CALL_EVERY = 3.6f

    /** Roughly how long the Entity's voice takes to say [text] (its deep, slow voice). */
    fun speakingTime(text: String): Float {
        // Measured on the tablet: about a third of a second a word, and a pause at every full stop or comma inside it.
        val words = text.trim().split(Regex("\\s+")).size
        val breaks = text.trim().dropLast(1).count { it == '.' || it == ',' || it == '?' || it == '!' }
        return 1.2f + 0.33f * words + 0.32f * breaks
    }

    /** How long everyone takes going down after "Kneel." (a beat with no words, its own shot). */
    const val KNEEL_BEAT = 4.2f

    private val bowLengths = Lore.UPRISING_BOW.map { if (it.shot == Lore.RitualShot.KNEEL) KNEEL_BEAT else speakingTime(it.text) }
    private val bowTotal = bowLengths.sum()
    private val kneelIndex = Lore.UPRISING_BOW.indexOfFirst { it.shot == Lore.RitualShot.KNEEL }

    /**
     * Seconds into the bow when everyone starts going down (the kneel beat, after "Kneel."): they stand while it says
     * it, so the bow is seen happening.
     */
    fun kneelAt(bowLength: Float): Float {
        val k = (bowLength - FOLLOW).coerceAtLeast(1f) / bowTotal
        return bowLengths.take(kneelIndex.coerceAtLeast(0)).sum() * k
    }

    fun cue(phase: UprisingPhase, t: Float, bowLength: Float, beaten: Boolean, playerName: String): Cue? = when (phase) {
        UprisingPhase.GAZE -> if (t >= 2.5f) Cue(1000, Lore.UPRISING_GAZE, null, Lore.RitualShot.ENTITY, -1, t - 2.5f, Balance.UPRISING_GAZE - 2.5f, plate = false) else null
        UprisingPhase.MARCH -> {
            val k = (t / CALL_EVERY).toInt()
            val lines = Lore.UPRISING_CALL
            Cue(2000 + k, lines[k % lines.size], null, Lore.RitualShot.WIDE, -1, t - k * CALL_EVERY, CALL_EVERY, plate = true)
        }
        UprisingPhase.BOW -> {
            val speech = (bowLength - FOLLOW).coerceAtLeast(1f)
            if (t >= speech) Cue(3999, Lore.UPRISING_FOLLOW, null, Lore.RitualShot.WIDE, -1, t - speech, FOLLOW, plate = true)
            else {
                // Each line gets the time it takes to say, stretched (or squeezed) evenly to fill the bow.
                val k = speech / bowTotal
                var start = 0f
                var i = 0
                while (i < bowLengths.size - 1 && t >= start + bowLengths[i] * k) { start += bowLengths[i] * k; i++ }
                val line = Lore.UPRISING_BOW[i]
                Cue(3000 + i, line.text.replace("%s", playerName), if (line.shot == Lore.RitualShot.KNEEL) "EVERYONE BOWS TO THE ENTITY" else null, line.shot, i,
                    t - start, bowLengths[i] * k, plate = line.text.isNotEmpty())
            }
        }
        UprisingPhase.ENTER -> Cue(4000, Lore.UPRISING_ENTER, null, Lore.RitualShot.WIDE, -1, t, Balance.UPRISING_ENTER, plate = false)
        UprisingPhase.SKY -> if (t >= 0.8f) Cue(5000, Lore.UPRISING_SKY, null, Lore.RitualShot.WIDE, -1, t - 0.8f, 4f, plate = false) else null
        UprisingPhase.RETURN -> Cue(6000, if (beaten) Lore.UPRISING_BEATEN else Lore.UPRISING_LET_GO, null, Lore.RitualShot.WIDE, -1, t, Balance.UPRISING_RETURN, plate = false)
        else -> null
    }
}
