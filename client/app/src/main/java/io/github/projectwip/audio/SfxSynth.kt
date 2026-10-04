package io.github.projectwip.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

enum class Sound {
    SHOOT_SPARK, SHOOT_HEAVY, SHOOT_PRISM, SUPER, HIT, HURT, KO, SUPER_READY,
    TICK, GO, TAP, UPGRADE, REWARD, VICTORY, DEFEAT, DENIED,
    PICKUP, CRATE_BREAK, DROP_TAP, DROP_UPGRADE, DROP_OPEN, WHOOSH, VERSUS,
    UI_BACK, UI_SELECT, UI_TOGGLE, UI_OPEN, BANNER, COUNT, POP, CHING, BOLT_LAND, PRISM_LAND,
}

/**
 * The game's original sound design, written as code: every effect is built from layered band-limited
 * oscillators, FM bells, filtered noise, drive, echo and a small reverb. No audio assets, nothing to license.
 * Pure Kotlin (no Android imports) so the sounds can be rendered and checked in JVM tests.
 */
object SfxSynth {
    const val RATE = 44100
    private const val DT = 1f / RATE
    private const val TAU = (2 * PI).toFloat()

    fun render(s: Sound): FloatArray = when (s) {
        Sound.SHOOT_SPARK -> sparkShot().finish(0.7f)
        Sound.SHOOT_HEAVY -> heavyShot().finish(0.8f)
        Sound.SHOOT_PRISM -> prismShot().finish(0.7f)
        Sound.SUPER -> superBlast().finish(0.85f)
        Sound.HIT -> hit().finish(0.7f)
        Sound.HURT -> hurt().finish(0.8f)
        Sound.KO -> knockOut().finish(0.85f)
        Sound.SUPER_READY -> superReady().finish(0.7f)
        Sound.TICK -> tick().finish(0.6f)
        Sound.GO -> go().finish(0.8f)
        Sound.TAP -> tap().finish(0.5f)
        Sound.UPGRADE -> upgrade().finish(0.8f)
        Sound.REWARD -> reward().finish(0.7f)
        Sound.VICTORY -> victory().finish(0.8f)
        Sound.DEFEAT -> defeat().finish(0.75f)
        Sound.DENIED -> denied().finish(0.5f)
        Sound.PICKUP -> pickup().finish(0.7f)
        Sound.CRATE_BREAK -> crateBreak().finish(0.8f)
        Sound.DROP_TAP -> dropTap().finish(0.75f)
        Sound.DROP_UPGRADE -> dropUpgrade().finish(0.8f)
        Sound.DROP_OPEN -> dropOpen().finish(0.9f)
        Sound.WHOOSH -> whoosh().finish(0.4f)
        Sound.VERSUS -> versus().finish(0.85f)
        Sound.UI_BACK -> uiBack().finish(0.45f)
        Sound.UI_SELECT -> uiSelect().finish(0.5f)
        Sound.UI_TOGGLE -> uiToggle().finish(0.45f)
        Sound.UI_OPEN -> uiOpen().finish(0.55f)
        Sound.BANNER -> banner().finish(0.8f)
        Sound.COUNT -> count().finish(0.42f)
        Sound.POP -> pop().finish(0.55f)
        Sound.CHING -> ching().finish(0.72f)
        Sound.BOLT_LAND -> boltLand().finish(0.7f)
        Sound.PRISM_LAND -> prismLand().finish(0.62f)
    }

    const val LOBBY_BPM = 124f
    const val LOBBY_BARS = 16

    /**
     * The lobby music (second edition, written from scratch): sixteen bars of dark electro at 124 BPM in E minor
     * over Em - C - G - D. It is arranged in four four-bar sections so it doesn't sit on one idea:
     *  1. groove: a syncopated kick, gated snare, galloping sixteenth-note bass and a pumping pad;
     *  2. the same with a detuned-saw lead playing the tune;
     *  3. breakdown: the drums drop to a heartbeat while bells answer each other over the pad;
     *  4. everything back in, the lead an octave up, ending on a tom fill.
     * Tails are folded back onto the start, so the returned samples loop without a seam.
     */
    fun renderLobbyMusic(): FloatArray {
        val beat = 60f / LOBBY_BPM
        val bars = LOBBY_BARS
        val total = bars * 4 * beat + 3f
        val chords = listOf(intArrayOf(52, 55, 59), intArrayOf(48, 52, 55), intArrayOf(55, 59, 62), intArrayOf(50, 54, 57))
        val roots = intArrayOf(40, 36, 43, 38)
        val drums = Clip(total)
        val bass = Clip(total)
        val pad = Clip(total)
        val lead = Clip(total)
        val bells = Clip(total)
        for (bar in 0 until bars) {
            val section = bar / 4
            val breakdown = section == 2
            val ch = chords[bar % 4]
            val root = roots[bar % 4]
            val t0 = bar * 4 * beat
            // Kick: one, the "and" of two, three (a heartbeat on one only in the breakdown).
            for (at in if (breakdown) floatArrayOf(0f) else floatArrayOf(0f, 1.5f, 2f, 3.5f)) {
                drums.osc(Wave.SINE, t0 + at * beat, 0.4f, { glide(it, 0.08f, 175f, 40f) }, { perc(it, 0.001f, 0.12f) * 1.15f })
                drums.noise(2000 + bar * 8 + (at * 2).toInt(), t0 + at * beat, 0.012f, Band.HIGH, { 3200f }, 0.7f, { perc(it, 0.0003f, 0.003f) * 0.3f })
            }
            if (!breakdown) {
                // A big gated snare on two and four.
                for (b in intArrayOf(1, 3)) {
                    drums.noise(2100 + bar * 4 + b, t0 + b * beat, 0.26f, Band.BAND, { 1700f }, 0.6f, { hold(it, 0.001f, 0.13f, 0.02f) * 0.42f })
                    drums.osc(Wave.SINE, t0 + b * beat, 0.12f, { glide(it, 0.05f, 230f, 150f) }, { perc(it, 0.001f, 0.04f) * 0.35f })
                }
                // Hats on the eighths, the off-beats open.
                for (k in 0 until 8) {
                    val open = k % 2 == 1
                    drums.noise(2200 + bar * 8 + k, t0 + k * beat / 2, if (open) 0.12f else 0.04f, Band.HIGH, { 9500f }, 0.7f,
                        { perc(it, 0.0005f, if (open) 0.035f else 0.01f) * if (open) 0.17f else 0.1f })
                }
                // Galloping bass: da-dadada on every beat.
                for (b in 0 until 4) for ((s, g) in listOf(0 to 1f, 2 to 0.7f, 3 to 0.85f)) {
                    val at = t0 + b * beat + s * beat / 4
                    bass.osc(Wave.SAW, at, beat * 0.3f, { hz(root) }, { perc(it, 0.003f, 0.07f) * 0.5f * g })
                    bass.osc(Wave.SAW, at, beat * 0.3f, { hz(root) * 1.008f }, { perc(it, 0.003f, 0.07f) * 0.35f * g })
                    bass.osc(Wave.SINE, at, beat * 0.3f, { hz(root - 12) }, { hold(it, 0.004f, beat * 0.2f, 0.02f) * 0.55f * g })
                }
            } else {
                // Breakdown: a long sub note and bells answering each other.
                bass.osc(Wave.SINE, t0, 4 * beat, { hz(root - 12) }, { hold(it, 0.05f, 4 * beat - 0.2f, 0.1f) * 0.5f })
                for ((k, step) in intArrayOf(0, 2, 1, 2, 0, 1, 2, 1).withIndex()) {
                    bells.bell(t0 + k * beat / 2, ch[step] + if (k % 2 == 0) 24 else 12, 0.16f, 0.12f)
                }
            }
            // Pad: detuned saws on the chord, pumped in eighths (held flat through the breakdown).
            for (n in ch) for (det in floatArrayOf(0.995f, 1.005f)) {
                pad.osc(Wave.SAW, t0, 4 * beat + 0.2f, { hz(n) * det }, { t ->
                    val gate = if (breakdown) 1f else 0.35f + 0.65f * ((t % (beat / 2)) / (beat / 2))
                    hold(t, 0.05f, 4 * beat - 0.1f, 0.12f) * 0.06f * gate
                })
            }
            // Tom fill into the turnaround at the end of each half.
            if (bar % 8 == 7) for ((i, f) in floatArrayOf(190f, 160f, 130f, 100f).withIndex()) {
                drums.osc(Wave.SINE, t0 + 3 * beat + i * beat / 4, 0.25f, { glide(it, 0.12f, f, f * 0.6f) }, { perc(it, 0.002f, 0.07f) * 0.55f })
            }
        }
        // The tune (beats from the start of its section, note, length in beats): sections two and four.
        val tune = listOf(
            Triple(0f, 71, 1f), Triple(1f, 74, 0.5f), Triple(1.5f, 76, 1.5f), Triple(3f, 74, 0.5f), Triple(3.5f, 71, 0.5f),
            Triple(4f, 72, 1f), Triple(5f, 76, 0.5f), Triple(5.5f, 79, 1.5f), Triple(7f, 76, 1f),
            Triple(8f, 74, 0.5f), Triple(8.5f, 79, 0.5f), Triple(9f, 83, 1.5f), Triple(10.5f, 79, 0.5f), Triple(11f, 74, 1f),
            Triple(12f, 78, 1f), Triple(13f, 76, 0.5f), Triple(13.5f, 74, 0.5f), Triple(14f, 69, 1f), Triple(15f, 71, 1f),
        )
        for ((section, up) in listOf(1 to 0, 3 to 12)) for ((at, n, len) in tune) {
            val start = (section * 16 + at) * beat
            val dur = len * beat
            for (det in floatArrayOf(0.994f, 1f, 1.006f)) {
                lead.osc(Wave.SAW, start, dur + 0.12f, { hz(n + up) * det * (1f + 0.004f * sin(TAU * 5.5f * it)) }, { hold(it, 0.01f, dur - 0.03f, 0.04f) * 0.11f })
            }
        }
        bass.filter(Band.LOW, 1.5f) { 1000f }
        bass.drive(2.2f)
        pad.filter(Band.LOW, 0.9f) { 2400f }
        lead.filter(Band.LOW, 1.2f) { 5200f }
        lead.echo(beat * 0.75f, 0.32f, 0.25f)
        bells.echo(beat * 0.5f, 0.35f, 0.3f)

        val c = Clip(total)
        c.mix(drums).mix(bass).mix(pad).mix(lead).mix(bells)
        c.drive(1.5f)
        c.reverb(0.13f, 0.66f)
        return loopOut(c, ((bars * 4 * beat) * RATE).toInt(), 0.85f)
    }

    /** Folds whatever rang past the loop point back onto the start and levels it, so the result loops cleanly. */
    private fun loopOut(c: Clip, loop: Int, peak: Float): FloatArray {
        val d = c.d
        for (i in loop until d.size) d[i - loop] += d[i]
        var top = 1e-6f
        for (i in 0 until loop) top = max(top, abs(d[i]))
        return FloatArray(loop) { d[it] * peak / top }
    }

    /**
     * Victory theme (result screen): eight bright bars at 126 BPM over C - G - Am - F. Bouncy bass, claps, bell
     * arpeggios and a brass melody. Its own piece, not the lobby loop.
     */
    fun renderVictoryMusic(): FloatArray {
        val beat = 60f / 126f
        val bars = 8
        val total = bars * 4 * beat + 3f
        val chords = listOf(intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60))
        val roots = intArrayOf(48, 43, 45, 41)
        val c = Clip(total)
        val horn = Clip(total)
        for (bar in 0 until bars) {
            val ch = chords[bar % 4]
            val t0 = bar * 4 * beat
            for (b in 0 until 4) {
                val at = t0 + b * beat
                c.osc(Wave.SINE, at, 0.3f, { glide(it, 0.07f, 150f, 48f) }, { perc(it, 0.001f, 0.09f) * 0.8f })
                if (b % 2 == 1) c.noise(1000 + bar * 4 + b, at, 0.15f, Band.BAND, { 2300f }, 0.7f, { perc(it, 0.001f, 0.045f) * 0.38f })
                c.noise(1100 + bar * 4 + b, at + beat / 2, 0.06f, Band.HIGH, { 9000f }, 0.7f, { perc(it, 0.0005f, 0.02f) * 0.14f })
            }
            for (k in 0 until 8) {
                val at = t0 + k * beat / 2
                val n = roots[bar % 4] + if (k % 2 == 1) 12 else 0
                c.osc(Wave.TRI, at, beat * 0.5f, { hz(n) }, { perc(it, 0.004f, 0.12f) * 0.42f })
                c.osc(Wave.SINE, at, beat * 0.5f, { hz(n) }, { perc(it, 0.004f, 0.14f) * 0.3f })
                c.bell(at, ch[intArrayOf(0, 1, 2, 1, 2, 1, 0, 2)[k]] + 24, 0.09f, 0.06f)
            }
            for (n in ch) horn.brass(t0, 4 * beat - 0.1f, n, 0.07f)
        }
        // The tune, twice (beats, note, length).
        val tune = listOf(
            Triple(0f, 72, 1f), Triple(1f, 76, 0.5f), Triple(1.5f, 79, 1.5f), Triple(3f, 76, 1f),
            Triple(4f, 74, 1f), Triple(5f, 79, 0.5f), Triple(5.5f, 83, 1.5f), Triple(7f, 79, 1f),
            Triple(8f, 76, 1f), Triple(9f, 81, 0.5f), Triple(9.5f, 84, 1.5f), Triple(11f, 81, 1f),
            Triple(12f, 81, 1f), Triple(13f, 77, 0.5f), Triple(13.5f, 79, 1f), Triple(14.5f, 84, 1.5f),
        )
        for (rep in 0 until 2) for ((at, n, len) in tune) horn.brass((rep * 16 + at) * beat, len * beat - 0.06f, n, 0.3f)
        horn.filter(Band.LOW, 0.9f) { 3600f }
        c.mix(horn)
        c.drive(1.2f)
        c.reverb(0.16f, 0.68f)
        return loopOut(c, ((bars * 4 * beat) * RATE).toInt(), 0.8f)
    }

    /**
     * Defeat theme (result screen): four slow bars at 84 BPM over Am - F - Dm - E. A soft pad, a plucked line that
     * keeps falling, and a low heartbeat. Rueful rather than grim, and its own piece.
     */
    fun renderDefeatMusic(): FloatArray {
        val beat = 60f / 84f
        val bars = 4
        val total = bars * 4 * beat + 4f
        val chords = listOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(50, 53, 57), intArrayOf(52, 56, 59))
        val roots = intArrayOf(45, 41, 38, 40)
        val c = Clip(total)
        for (bar in 0 until bars) {
            val ch = chords[bar]
            val t0 = bar * 4 * beat
            for (n in ch) c.osc(Wave.TRI, t0, 4 * beat + 0.5f, { hz(n) }, { hold(it, 0.3f, 4 * beat - 0.3f, 0.35f) * 0.1f })
            c.osc(Wave.SINE, t0, 4 * beat, { hz(roots[bar]) }, { hold(it, 0.05f, 4 * beat - 0.3f, 0.2f) * 0.38f })
            // Heartbeat on one and the "and" of two.
            for (at in floatArrayOf(0f, 1.5f)) c.osc(Wave.SINE, t0 + at * beat, 0.3f, { glide(it, 0.08f, 110f, 45f) }, { perc(it, 0.002f, 0.1f) * 0.5f })
            // A falling plucked line.
            for ((k, step) in intArrayOf(2, 1, 0, 1, 2, 1, 0, 0).withIndex()) {
                c.bell(t0 + k * beat / 2, ch[step] + 12 - if (k >= 6) 12 else 0, 0.16f, 0.14f)
            }
        }
        c.filter(Band.LOW, 0.7f) { 5000f }
        c.reverb(0.3f, 0.82f)
        return loopOut(c, ((bars * 4 * beat) * RATE).toInt(), 0.7f)
    }

    // ------------------------------------------------------------------ envelopes & pitch

    /** Fast attack, exponential decay. */
    private fun perc(t: Float, attack: Float, decay: Float) = if (t < attack) t / attack else exp(-(t - attack) / decay)

    /** Attack, hold until [until], then exponential release. */
    private fun hold(t: Float, attack: Float, until: Float, release: Float) =
        if (t < attack) t / attack else if (t < until) 1f else exp(-(t - until) / release)

    /** Rises (squared) over [dur], then cuts off almost at once: a charge-up. */
    private fun swell(t: Float, dur: Float) = if (t < dur) (t / dur).pow(2) else exp(-(t - dur) / 0.012f)

    /** Exponential pitch glide from [f0] to [f1] over [dur], then stays at [f1]. */
    private fun glide(t: Float, dur: Float, f0: Float, f1: Float) = f0 * (f1 / f0).pow((t / dur).coerceIn(0f, 1f))

    private fun hz(midi: Int) = 440f * 2f.pow((midi - 69) / 12f)

    // ------------------------------------------------------------------ building blocks

    private enum class Wave { SINE, TRI, SAW, SQUARE }
    private enum class Band { LOW, BAND, HIGH }

    /** Smooths the step in saw/square waves so they don't alias into harsh digital fizz. */
    private fun polyBlep(p: Float, inc: Float): Float = when {
        p < inc -> { val x = p / inc; x + x - x * x - 1f }
        p > 1f - inc -> { val x = (p - 1f) / inc; x * x + x + x + 1f }
        else -> 0f
    }

    /** Zero-delay state-variable filter (stable at any cutoff). */
    private class Svf {
        private var ic1 = 0f
        private var ic2 = 0f
        private var a1 = 0f; private var a2 = 0f; private var a3 = 0f; private var k = 1f
        fun set(cutoff: Float, q: Float) {
            val g = tan(PI.toFloat() * cutoff.coerceIn(20f, RATE * 0.45f) / RATE)
            k = 1f / q
            a1 = 1f / (1f + g * (g + k)); a2 = g * a1; a3 = g * a2
        }
        fun run(x: Float, band: Band): Float {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            return when (band) { Band.LOW -> v2; Band.BAND -> v1; Band.HIGH -> x - k * v1 - v2 }
        }
    }

    /** A mono buffer that layers are added into. Times are seconds; layer lambdas get seconds since the layer began. */
    private class Clip(seconds: Float) {
        val d = FloatArray((seconds * RATE).toInt())

        private inline fun span(at: Float, dur: Float, body: (index: Int, t: Float) -> Unit) {
            val i0 = (at * RATE).toInt()
            val n = min((dur * RATE).toInt(), d.size - i0)
            for (i in 0 until n) body(i0 + i, i * DT)
        }

        fun osc(wave: Wave, at: Float, dur: Float, freq: (Float) -> Float, amp: (Float) -> Float): Clip {
            var p = 0f
            span(at, dur) { i, t ->
                val inc = freq(t) / RATE
                p += inc
                if (p >= 1f) p -= 1f
                val v = when (wave) {
                    Wave.SINE -> sin(TAU * p)
                    Wave.TRI -> 4f * abs(p - 0.5f) - 1f
                    Wave.SAW -> 2f * p - 1f - polyBlep(p, inc)
                    Wave.SQUARE -> (if (p < 0.5f) 1f else -1f) + polyBlep(p, inc) - polyBlep((p + 0.5f) % 1f, inc)
                }
                d[i] += v * amp(t)
            }
            return this
        }

        /** Two-operator FM: metallic and glassy tones depending on [ratio] and [index]. */
        fun fm(at: Float, dur: Float, freq: (Float) -> Float, ratio: Float, index: (Float) -> Float, amp: (Float) -> Float): Clip {
            var pc = 0f
            var pm = 0f
            span(at, dur) { i, t ->
                val inc = freq(t) / RATE
                pc += inc; if (pc >= 1f) pc -= 1f
                pm += inc * ratio; pm -= pm.toInt()
                d[i] += sin(TAU * pc + index(t) * sin(TAU * pm)) * amp(t)
            }
            return this
        }

        fun noise(seed: Int, at: Float, dur: Float, band: Band, cutoff: (Float) -> Float, q: Float, amp: (Float) -> Float): Clip {
            val rng = Random(seed)
            val f = Svf()
            var n = 0
            span(at, dur) { i, t ->
                if (n++ % 16 == 0) f.set(cutoff(t), q)
                d[i] += f.run(rng.nextFloat() * 2f - 1f, band) * amp(t)
            }
            return this
        }

        /** A struck glass/metal bell: bright strike that mellows as it rings. */
        fun bell(at: Float, midi: Int, gain: Float, decay: Float): Clip {
            val f = hz(midi)
            val len = decay * 6f
            fm(at, len, { f }, 3.5f, { 2.4f * exp(-it / (decay * 0.35f)) }, { perc(it, 0.002f, decay) * gain * 0.75f })
            osc(Wave.SINE, at, len, { f * 2f }, { perc(it, 0.002f, decay * 0.6f) * gain * 0.2f })
            return this
        }

        /** A brassy voice: two slightly detuned saws. Lowpass the clip afterwards to taste. */
        fun brass(at: Float, dur: Float, gain: Float, freq: (Float) -> Float): Clip {
            val amp = { t: Float -> hold(t, 0.018f, dur, 0.05f) * gain * 0.5f }
            osc(Wave.SAW, at, dur + 0.3f, { freq(it) * 1.004f }, amp)
            osc(Wave.SAW, at, dur + 0.3f, { freq(it) * 0.996f }, amp)
            return this
        }

        fun brass(at: Float, dur: Float, midi: Int, gain: Float) = hz(midi).let { f -> brass(at, dur, gain) { f } }

        fun mix(other: Clip, at: Float = 0f, gain: Float = 1f): Clip {
            val i0 = (at * RATE).toInt()
            for (i in 0 until min(other.d.size, d.size - i0)) d[i0 + i] += other.d[i] * gain
            return this
        }

        fun filter(band: Band, q: Float, cutoff: (Float) -> Float): Clip {
            val f = Svf()
            for (i in d.indices) {
                if (i % 16 == 0) f.set(cutoff(i * DT), q)
                d[i] = f.run(d[i], band)
            }
            return this
        }

        /** Soft saturation: thickens and glues layers together. */
        fun drive(amount: Float): Clip {
            for (i in d.indices) d[i] = tanh(d[i] * amount)
            return this
        }

        fun echo(delay: Float, feedback: Float, mix: Float): Clip {
            val n = (delay * RATE).toInt()
            val wet = FloatArray(d.size)
            for (i in n until d.size) wet[i] = d[i - n] + wet[i - n] * feedback
            for (i in d.indices) d[i] += wet[i] * mix
            return this
        }

        /** Small room: four damped comb filters into two allpasses. */
        fun reverb(mix: Float, decay: Float): Clip {
            val wet = FloatArray(d.size)
            for (len in intArrayOf(1557, 1617, 1491, 1422)) {
                val buf = FloatArray(len)
                var lp = 0f
                for (i in d.indices) {
                    val k = i % len
                    val out = buf[k]
                    lp = out * 0.7f + lp * 0.3f
                    buf[k] = d[i] + lp * decay
                    wet[i] += out * 0.25f
                }
            }
            for (len in intArrayOf(225, 556)) {
                val buf = FloatArray(len)
                for (i in wet.indices) {
                    val k = i % len
                    val delayed = buf[k]
                    val x = wet[i]
                    buf[k] = x + delayed * 0.5f
                    wet[i] = delayed - x * 0.5f
                }
            }
            for (i in d.indices) d[i] += wet[i] * mix
            return this
        }

        /** Removes any DC offset, fades the tail to silence and scales the loudest sample to [peak]. */
        fun finish(peak: Float): FloatArray {
            var mean = 0f
            for (v in d) mean += v
            mean /= max(1, d.size)
            var top = 1e-6f
            for (i in d.indices) { d[i] -= mean; top = max(top, abs(d[i])) }
            val fade = min(d.size, RATE / 80)
            val k = peak / top
            for (i in d.indices) {
                val left = d.size - 1 - i
                d[i] *= k * if (left < fade) left.toFloat() / fade else 1f
            }
            return d
        }
    }

    // ------------------------------------------------------------------ weapons

    /** Juno's coil blaster: three quick zaps, timed to the burst leaving the barrel. */
    private fun sparkShot() = Clip(0.36f).apply {
        for (k in 0 until 3) {
            val at = k * 0.075f
            val g = 1f - k * 0.12f
            val up = 1f + k * 0.07f
            osc(Wave.SQUARE, at, 0.1f, { glide(it, 0.07f, 2100f * up, 520f) }, { perc(it, 0.001f, 0.022f) * 0.45f * g })
            osc(Wave.SINE, at, 0.1f, { glide(it, 0.05f, 900f, 170f) }, { perc(it, 0.001f, 0.03f) * 0.55f * g })
            noise(11 + k, at, 0.03f, Band.HIGH, { 5000f }, 0.8f, { perc(it, 0.0005f, 0.006f) * 0.5f * g })
        }
        filter(Band.LOW, 0.7f) { 7500f }
        echo(0.05f, 0.2f, 0.12f)
    }

    /** Brakk's scrap cannon: a chesty boom, a blast of grit and a clank of loose metal. */
    private fun heavyShot() = Clip(0.6f).apply {
        osc(Wave.SINE, 0f, 0.35f, { glide(it, 0.12f, 170f, 42f) }, { perc(it, 0.002f, 0.09f) })
        noise(21, 0f, 0.3f, Band.LOW, { glide(it, 0.18f, 7000f, 500f) }, 0.9f, { perc(it, 0.001f, 0.06f) * 0.9f })
        noise(22, 0f, 0.02f, Band.HIGH, { 3500f }, 0.7f, { perc(it, 0.0005f, 0.005f) * 0.7f })
        fm(0.012f, 0.25f, { 310f }, 2.76f, { 3f * exp(-it / 0.03f) }, { perc(it, 0.001f, 0.04f) * 0.25f })
        drive(1.8f)
        reverb(0.12f, 0.5f)
    }

    /** Mira's prism rifle: a rising crystalline zing with a ringing tail. */
    private fun prismShot() = Clip(0.6f).apply {
        osc(Wave.SINE, 0f, 0.3f, { glide(it, 0.09f, 900f, 2600f) }, { perc(it, 0.003f, 0.07f) * 0.5f })
        osc(Wave.TRI, 0f, 0.3f, { glide(it, 0.09f, 1350f, 3900f) }, { perc(it, 0.003f, 0.05f) * 0.22f })
        fm(0.02f, 0.45f, { 1760f }, 3.01f, { 2f * exp(-it / 0.06f) }, { perc(it, 0.002f, 0.11f) * 0.32f })
        noise(31, 0f, 0.12f, Band.BAND, { glide(it, 0.1f, 3000f, 9000f) }, 2f, { perc(it, 0.002f, 0.03f) * 0.35f })
        osc(Wave.SINE, 0f, 0.1f, { glide(it, 0.06f, 300f, 90f) }, { perc(it, 0.001f, 0.03f) * 0.45f })
        echo(0.085f, 0.3f, 0.25f)
    }

    /** Any super: a quarter-second charge that slams into a boom with sparks flying off it. */
    private fun superBlast() = Clip(1.2f).apply {
        val charge = 0.24f
        noise(41, 0f, 0.3f, Band.BAND, { glide(it, charge, 350f, 5000f) }, 3f, { swell(it, charge) * 0.6f })
        osc(Wave.SAW, 0f, 0.3f, { glide(it, charge, 110f, 440f) }, { swell(it, charge) * 0.28f })
        osc(Wave.SAW, 0f, 0.3f, { glide(it, charge, 165f, 660f) }, { swell(it, charge) * 0.2f })
        osc(Wave.SINE, charge, 0.6f, { glide(it, 0.25f, 190f, 38f) }, { perc(it, 0.002f, 0.16f) })
        noise(42, charge, 0.6f, Band.LOW, { glide(it, 0.35f, 9000f, 300f) }, 0.8f, { perc(it, 0.001f, 0.12f) * 0.8f })
        for ((i, n) in intArrayOf(84, 88, 91, 96).withIndex()) bell(charge + 0.03f + i * 0.035f, n, 0.16f, 0.1f)
        drive(1.5f)
        reverb(0.2f, 0.7f)
    }

    // ------------------------------------------------------------------ combat feedback

    /** Your shot connected: a crisp, bright smack. */
    private fun hit() = Clip(0.16f).apply {
        noise(51, 0f, 0.06f, Band.BAND, { 2600f }, 1.2f, { perc(it, 0.0005f, 0.012f) * 0.9f })
        osc(Wave.SINE, 0f, 0.1f, { glide(it, 0.05f, 820f, 240f) }, { perc(it, 0.001f, 0.022f) * 0.8f })
        osc(Wave.SQUARE, 0f, 0.03f, { 1900f }, { perc(it, 0.0005f, 0.006f) * 0.18f })
    }

    /** You got hit: lower, duller and heavier than [hit], so the two never get confused. */
    private fun hurt() = Clip(0.3f).apply {
        osc(Wave.SINE, 0f, 0.25f, { glide(it, 0.1f, 260f, 62f) }, { perc(it, 0.002f, 0.06f) })
        osc(Wave.SAW, 0f, 0.16f, { glide(it, 0.1f, 330f, 110f) }, { perc(it, 0.002f, 0.035f) * 0.4f })
        noise(61, 0f, 0.1f, Band.LOW, { 1800f }, 0.8f, { perc(it, 0.001f, 0.02f) * 0.7f })
        filter(Band.LOW, 0.7f) { 3200f }
        drive(2f)
    }

    /** Knockout: a pop, a cartoon "pwoo" falling away, and three glassy pings. */
    private fun knockOut() = Clip(1.2f).apply {
        noise(71, 0f, 0.55f, Band.LOW, { glide(it, 0.4f, 8000f, 180f) }, 1f, { perc(it, 0.001f, 0.13f) })
        osc(Wave.SINE, 0f, 0.5f, { glide(it, 0.3f, 320f, 36f) }, { perc(it, 0.002f, 0.14f) })
        osc(Wave.SQUARE, 0f, 0.35f, { glide(it, 0.28f, 1400f, 160f) }, { perc(it, 0.002f, 0.08f) * 0.22f })
        for ((i, n) in intArrayOf(79, 86, 91).withIndex()) bell(0.1f + i * 0.07f, n, 0.2f, 0.1f)
        drive(1.4f)
        reverb(0.22f, 0.75f)
    }

    private fun superReady() = Clip(0.95f).apply {
        bell(0f, 88, 0.5f, 0.07f)
        bell(0.09f, 95, 0.5f, 0.1f)
        bell(0.18f, 100, 0.32f, 0.1f)
        noise(81, 0f, 0.3f, Band.HIGH, { 7000f }, 0.7f, { perc(it, 0.05f, 0.08f) * 0.06f })
        echo(0.11f, 0.3f, 0.2f)
    }

    /** Power Cell collected: a quick upward bloop with a bright ding on top. */
    private fun pickup() = Clip(0.5f).apply {
        osc(Wave.SINE, 0f, 0.12f, { glide(it, 0.08f, 420f, 1250f) }, { perc(it, 0.003f, 0.04f) * 0.7f })
        osc(Wave.TRI, 0f, 0.12f, { glide(it, 0.08f, 840f, 2500f) }, { perc(it, 0.003f, 0.03f) * 0.2f })
        bell(0.07f, 93, 0.4f, 0.07f)
    }

    /** Spark Crate breaking: a crunch, clattering shards and the core discharging. */
    private fun crateBreak() = Clip(0.7f).apply {
        osc(Wave.SINE, 0f, 0.25f, { glide(it, 0.1f, 160f, 48f) }, { perc(it, 0.002f, 0.07f) })
        noise(141, 0f, 0.12f, Band.BAND, { 1300f }, 0.9f, { perc(it, 0.001f, 0.03f) })
        for ((i, at) in floatArrayOf(0.05f, 0.09f, 0.15f, 0.2f).withIndex()) {
            noise(142 + i, at, 0.05f, Band.BAND, { 2400f + i * 700f }, 2.5f, { perc(it, 0.0005f, 0.012f) * 0.5f })
        }
        fm(0.02f, 0.3f, { glide(it, 0.2f, 700f, 1500f) }, 1.41f, { 2f * exp(-it / 0.05f) }, { perc(it, 0.002f, 0.06f) * 0.3f })
        drive(1.5f)
        reverb(0.15f, 0.55f)
    }

    // ------------------------------------------------------------------ match flow

    /** Countdown tick: a tuned woodblock. */
    private fun tick() = Clip(0.2f).apply {
        osc(Wave.SINE, 0f, 0.18f, { 880f }, { perc(it, 0.001f, 0.035f) * 0.8f })
        osc(Wave.SINE, 0f, 0.1f, { 1760f }, { perc(it, 0.001f, 0.015f) * 0.3f })
        noise(91, 0f, 0.012f, Band.HIGH, { 4000f }, 0.7f, { perc(it, 0.0003f, 0.003f) * 0.4f })
    }

    /** "Fight!": a short brass stab with a kick under it. */
    private fun go() = Clip(0.9f).apply {
        val horn = Clip(0.9f).apply {
            for (n in intArrayOf(72, 76, 79, 84)) brass(0f, 0.3f, n, 0.3f)
            filter(Band.LOW, 1.1f) { 900f + 4200f * perc(it, 0.02f, 0.25f) }
        }
        mix(horn)
        noise(101, 0f, 0.12f, Band.HIGH, { 5000f }, 0.7f, { perc(it, 0.001f, 0.03f) * 0.25f })
        osc(Wave.SINE, 0f, 0.25f, { glide(it, 0.1f, 200f, 60f) }, { perc(it, 0.002f, 0.06f) * 0.7f })
        reverb(0.18f, 0.6f)
    }

    /** Victory fanfare: da-da-da-daaa, da-DAAA. */
    private fun victory() = Clip(2.3f).apply {
        val horn = Clip(2.3f).apply {
            val tune = listOf(Triple(67, 0f, 0.1f), Triple(72, 0.13f, 0.1f), Triple(76, 0.26f, 0.1f), Triple(79, 0.39f, 0.28f),
                Triple(76, 0.72f, 0.1f), Triple(84, 0.85f, 0.75f))
            for ((n, at, dur) in tune) brass(at, dur, n, 0.4f)
            for (n in intArrayOf(60, 64, 67)) brass(0.39f, 0.28f, n, 0.16f)
            for (n in intArrayOf(72, 76, 79)) brass(0.85f, 0.75f, n, 0.2f)
            filter(Band.LOW, 0.9f) { 3600f }
        }
        mix(horn)
        osc(Wave.SINE, 0.39f, 0.4f, { hz(48) }, { hold(it, 0.01f, 0.25f, 0.05f) * 0.35f })
        osc(Wave.SINE, 0.85f, 1f, { hz(48) }, { hold(it, 0.01f, 0.7f, 0.08f) * 0.4f })
        for (at in floatArrayOf(0f, 0.13f, 0.26f, 0.39f, 0.72f, 0.85f)) {
            noise((at * 100).toInt() + 150, at, 0.08f, Band.HIGH, { 6000f }, 0.7f, { perc(it, 0.001f, 0.02f) * 0.12f })
        }
        for ((i, n) in intArrayOf(96, 100, 103, 108).withIndex()) bell(0.87f + i * 0.06f, n, 0.14f, 0.12f)
        reverb(0.25f, 0.78f)
    }

    /** Defeat: four falling notes, the last one sagging. */
    private fun defeat() = Clip(2.1f).apply {
        val horn = Clip(2.1f).apply {
            brass(0f, 0.26f, 64, 0.4f)
            brass(0.3f, 0.26f, 62, 0.4f)
            brass(0.6f, 0.26f, 60, 0.4f)
            brass(0.9f, 0.7f, 0.45f) { hz(57) * glide(it, 0.8f, 1f, 0.94f) * (1f + 0.006f * sin(TAU * 5.5f * it)) }
            filter(Band.LOW, 0.8f) { 1500f }
        }
        mix(horn)
        osc(Wave.SINE, 0.9f, 0.9f, { hz(33) }, { hold(it, 0.02f, 0.6f, 0.1f) * 0.5f })
        reverb(0.25f, 0.75f)
    }

    // ------------------------------------------------------------------ menus

/** Button press: a rounded pop with a glassy tick on top. */
    private fun tap() = Clip(0.14f).apply {
        osc(Wave.SINE, 0f, 0.09f, { glide(it, 0.03f, 1250f, 700f) }, { perc(it, 0.001f, 0.014f) * 0.7f })
        osc(Wave.TRI, 0f, 0.06f, { 350f }, { perc(it, 0.001f, 0.012f) * 0.45f })
        fm(0f, 0.1f, { 2100f }, 2f, { 0.8f }, { perc(it, 0.001f, 0.02f) * 0.18f })
        noise(111, 0f, 0.008f, Band.HIGH, { 6000f }, 0.7f, { perc(it, 0.0003f, 0.002f) * 0.3f })
    }

    /** Going back: the same pop, falling instead of bright. */
    private fun uiBack() = Clip(0.18f).apply {
        osc(Wave.SINE, 0f, 0.15f, { glide(it, 0.09f, 700f, 380f) }, { perc(it, 0.002f, 0.04f) * 0.7f })
        osc(Wave.TRI, 0f, 0.1f, { glide(it, 0.09f, 350f, 200f) }, { perc(it, 0.002f, 0.03f) * 0.3f })
    }

    /** Choosing a tab or an option: two quick glass notes going up. */
    private fun uiSelect() = Clip(0.4f).apply {
        bell(0f, 79, 0.5f, 0.035f)
        bell(0.055f, 86, 0.5f, 0.05f)
        osc(Wave.SINE, 0f, 0.05f, { 300f }, { perc(it, 0.001f, 0.012f) * 0.3f })
    }

    /** A switch flipping: click, then a short tone (played higher for on, lower for off). */
    private fun uiToggle() = Clip(0.2f).apply {
        noise(201, 0f, 0.01f, Band.HIGH, { 5000f }, 0.7f, { perc(it, 0.0003f, 0.003f) * 0.4f })
        osc(Wave.SINE, 0f, 0.06f, { 900f }, { perc(it, 0.001f, 0.015f) * 0.5f })
        osc(Wave.SINE, 0.05f, 0.14f, { 1350f }, { perc(it, 0.002f, 0.03f) * 0.6f })
        osc(Wave.TRI, 0.05f, 0.1f, { 675f }, { perc(it, 0.002f, 0.02f) * 0.2f })
    }

    /** A dialog or picker opening: a breath of air into a soft two-note chime. */
    private fun uiOpen() = Clip(0.7f).apply {
        noise(202, 0f, 0.16f, Band.BAND, { glide(it, 0.12f, 800f, 4000f) }, 1.5f, { perc(it, 0.06f, 0.04f) * 0.3f })
        bell(0.09f, 76, 0.45f, 0.06f)
        bell(0.15f, 83, 0.45f, 0.08f)
        reverb(0.15f, 0.6f)
    }

    /**
     * The result banner landing: a drum hit under a bright brass chord, then chimes running up. Deliberately no
     * rush of air before it: that made it sound like an automatic door sliding open.
     */
    private fun banner() = Clip(1.5f).apply {
        osc(Wave.SINE, 0f, 0.45f, { glide(it, 0.14f, 150f, 48f) }, { perc(it, 0.002f, 0.12f) })
        noise(203, 0f, 0.09f, Band.BAND, { 2200f }, 0.9f, { perc(it, 0.001f, 0.022f) * 0.55f })
        val horn = Clip(1.5f).apply {
            for (n in intArrayOf(60, 64, 67, 72)) brass(0f, 0.32f, n, 0.28f)
            brass(0f, 0.06f, 71, 0.2f)
            filter(Band.LOW, 1f) { 900f + 3400f * perc(it, 0.015f, 0.22f) }
        }
        mix(horn)
        drive(1.25f)
        for ((i, n) in intArrayOf(84, 88, 91, 96, 100).withIndex()) bell(0.12f + i * 0.055f, n, 0.18f, 0.09f)
        reverb(0.2f, 0.7f)
    }

    /**
     * Cha-ching: a rattle of coins ("cha"), then a bright three-note bell with some body and a sparkle on top
     * ("ching"). Fuller and livelier than a bare ping, with a little room around it.
     */
    private fun ching() = Clip(0.95f).apply {
        for ((i, at) in floatArrayOf(0f, 0.035f, 0.065f).withIndex()) {
            noise(205 + i, at, 0.03f, Band.BAND, { 5200f + i * 900f }, 2.2f, { perc(it, 0.0005f, 0.008f) * 0.5f })
            fm(at, 0.06f, { 2400f + i * 500f }, 1.47f, { 1.2f }, { perc(it, 0.0006f, 0.012f) * 0.25f })
        }
        val hit = 0.1f
        for ((n, g) in listOf(96 to 0.5f, 100 to 0.4f, 103 to 0.34f)) {
            fm(hit, 0.7f, { hz(n) }, 1.47f, { 1.5f * exp(-it / 0.07f) }, { perc(it, 0.0008f, 0.16f) * g })
            osc(Wave.SINE, hit, 0.5f, { hz(n) * 2.01f }, { perc(it, 0.0008f, 0.07f) * g * 0.3f })
        }
        osc(Wave.SINE, hit, 0.3f, { hz(84) }, { perc(it, 0.001f, 0.08f) * 0.3f })
        noise(209, hit, 0.02f, Band.HIGH, { 7000f }, 0.7f, { perc(it, 0.0003f, 0.004f) * 0.5f })
        for ((i, n) in intArrayOf(108, 112, 115).withIndex()) bell(hit + 0.09f + i * 0.045f, n, 0.1f, 0.05f)
        filter(Band.HIGH, 0.7f) { 500f }
        reverb(0.18f, 0.62f)
    }

    /** One step of a number counting up (played over and over at rising pitch). */
    private fun count() = Clip(0.07f).apply {
        osc(Wave.SINE, 0f, 0.06f, { 1568f }, { perc(it, 0.001f, 0.012f) * 0.6f })
        osc(Wave.TRI, 0f, 0.05f, { 784f }, { perc(it, 0.001f, 0.01f) * 0.3f })
    }

    /** A Bolt landing in the wallet: a steel nut dropped on the pile, with a hard clank, a dull body and one small bounce. */
    private fun boltLand() = Clip(0.4f).apply {
        for ((i, at) in floatArrayOf(0f, 0.085f).withIndex()) {
            val g = if (i == 0) 1f else 0.4f
            noise(221 + i, at, 0.02f, Band.BAND, { 3800f }, 1.8f, { perc(it, 0.0004f, 0.006f) * 0.6f * g })
            fm(at, 0.16f, { 1480f + i * 240f }, 2.76f, { 2.4f * exp(-it / 0.02f) }, { perc(it, 0.0005f, 0.035f) * 0.55f * g })
            fm(at, 0.12f, { 2210f + i * 300f }, 1.19f, { 1.2f }, { perc(it, 0.0005f, 0.02f) * 0.25f * g })
        }
        osc(Wave.SINE, 0f, 0.1f, { glide(it, 0.05f, 330f, 190f) }, { perc(it, 0.001f, 0.025f) * 0.5f })
        reverb(0.1f, 0.4f)
    }

    /** A Prism landing in the wallet: glass, not metal. A quick upward glint, then high partials that ring and shimmer. */
    private fun prismLand() = Clip(0.8f).apply {
        osc(Wave.SINE, 0f, 0.08f, { glide(it, 0.06f, 2600f, 5200f) }, { perc(it, 0.002f, 0.02f) * 0.25f })
        for ((i, n) in intArrayOf(100, 107, 112).withIndex()) bell(i * 0.03f, n, 0.32f - i * 0.06f, 0.12f)
        // A second sine a hair off the first makes the tail beat slowly, like light in a crystal.
        osc(Wave.SINE, 0f, 0.5f, { hz(100) * 1.004f }, { perc(it, 0.001f, 0.14f) * 0.15f })
        noise(231, 0f, 0.05f, Band.HIGH, { 9000f }, 0.7f, { perc(it, 0.0005f, 0.012f) * 0.2f })
        reverb(0.3f, 0.75f)
    }

    /** Something popping into place (reward rows, sliders). */
    private fun pop() = Clip(0.3f).apply {
        osc(Wave.SINE, 0f, 0.1f, { glide(it, 0.05f, 500f, 1100f) }, { perc(it, 0.002f, 0.03f) * 0.7f })
        bell(0.03f, 84, 0.3f, 0.04f)
    }

    private fun denied() = Clip(0.32f).apply {
        osc(Wave.SQUARE, 0f, 0.1f, { 196f }, { hold(it, 0.003f, 0.07f, 0.01f) * 0.5f })
        osc(Wave.SQUARE, 0.11f, 0.2f, { 147f }, { hold(it, 0.003f, 0.1f, 0.015f) * 0.5f })
        filter(Band.LOW, 0.9f) { 1400f }
    }

    /** Level up: a rising run of bells over a charge, landing on a bright chord. */
    private fun upgrade() = Clip(1.6f).apply {
        val land = 0.42f
        noise(121, 0f, 0.5f, Band.BAND, { glide(it, land, 500f, 6000f) }, 2.5f, { swell(it, land) * 0.3f })
        for ((i, n) in intArrayOf(72, 76, 79, 84, 88).withIndex()) bell(i * 0.075f, n, 0.4f, 0.06f)
        bell(land, 91, 0.5f, 0.15f)
        bell(land, 96, 0.4f, 0.15f)
        bell(land, 84, 0.35f, 0.15f)
        osc(Wave.SINE, land, 0.25f, { glide(it, 0.1f, 180f, 60f) }, { perc(it, 0.002f, 0.07f) * 0.6f })
        reverb(0.22f, 0.7f)
    }

    private fun reward() = Clip(0.9f).apply {
        bell(0f, 83, 0.5f, 0.05f)
        bell(0.07f, 88, 0.55f, 0.1f)
        bell(0.07f, 95, 0.25f, 0.09f)
        noise(131, 0f, 0.02f, Band.HIGH, { 6000f }, 0.7f, { perc(it, 0.0005f, 0.004f) * 0.3f })
        echo(0.12f, 0.25f, 0.18f)
    }

    /** A screen sliding in: a quick breath of filtered air. */
    private fun whoosh() = Clip(0.34f).apply {
        noise(191, 0f, 0.32f, Band.BAND, { glide(it, 0.25f, 500f, 3800f) }, 1.4f, { perc(it, 0.09f, 0.07f) * 0.8f })
        noise(192, 0f, 0.32f, Band.HIGH, { 5000f }, 0.7f, { perc(it, 0.12f, 0.05f) * 0.15f })
    }

    /** The "VS" landing on the line-up screen: a short rush, then a heavy slam with a metal ring to it. */
    private fun versus() = Clip(1.3f).apply {
        val hit = 0.12f
        noise(193, 0f, 0.16f, Band.BAND, { glide(it, hit, 400f, 4500f) }, 2.5f, { swell(it, hit) * 0.5f })
        osc(Wave.SINE, hit, 0.6f, { glide(it, 0.2f, 170f, 36f) }, { perc(it, 0.002f, 0.18f) })
        noise(194, hit, 0.5f, Band.LOW, { glide(it, 0.3f, 6000f, 300f) }, 0.9f, { perc(it, 0.001f, 0.09f) * 0.8f })
        fm(hit, 0.6f, { 196f }, 1.41f, { 3f * exp(-it / 0.06f) }, { perc(it, 0.001f, 0.12f) * 0.4f })
        val horn = Clip(1.3f).apply {
            for (n in intArrayOf(43, 50, 55)) brass(hit, 0.22f, n, 0.3f)
            filter(Band.LOW, 1f) { 500f + 2600f * perc(it - hit, 0.02f, 0.2f).coerceIn(0f, 1f) }
        }
        mix(horn)
        drive(1.5f)
        reverb(0.22f, 0.72f)
    }

    // ------------------------------------------------------------------ Spark Capsules

    /** Knocking on a capsule: a hollow metal thunk with something rattling inside. */
    private fun dropTap() = Clip(0.4f).apply {
        fm(0f, 0.3f, { 233f }, 1.41f, { 2.2f * exp(-it / 0.04f) }, { perc(it, 0.001f, 0.06f) * 0.8f })
        osc(Wave.SINE, 0f, 0.15f, { glide(it, 0.06f, 180f, 70f) }, { perc(it, 0.001f, 0.04f) * 0.7f })
        for ((i, at) in floatArrayOf(0.04f, 0.075f, 0.12f).withIndex()) {
            noise(161 + i, at, 0.03f, Band.BAND, { 3200f + i * 500f }, 3f, { perc(it, 0.0005f, 0.008f) * 0.3f })
        }
        reverb(0.12f, 0.5f)
    }

    /** The capsule charging up a tier: a zap that climbs into a two-note chime. */
    private fun dropUpgrade() = Clip(1f).apply {
        val land = 0.16f
        osc(Wave.SAW, 0f, 0.2f, { glide(it, land, 300f, 1800f) }, { swell(it, land) * 0.4f })
        noise(171, 0f, 0.2f, Band.BAND, { glide(it, land, 800f, 7000f) }, 3f, { swell(it, land) * 0.5f })
        filter(Band.LOW, 0.8f) { 6000f }
        bell(land, 86, 0.5f, 0.1f)
        bell(land + 0.05f, 93, 0.45f, 0.12f)
        osc(Wave.SINE, land, 0.2f, { glide(it, 0.08f, 220f, 70f) }, { perc(it, 0.002f, 0.05f) * 0.6f })
        reverb(0.2f, 0.65f)
    }

    /** The capsule bursting open: boom, rush of air, a bright chord and falling sparkles. */
    private fun dropOpen() = Clip(2.2f).apply {
        osc(Wave.SINE, 0f, 0.6f, { glide(it, 0.25f, 210f, 36f) }, { perc(it, 0.002f, 0.17f) })
        noise(181, 0f, 0.7f, Band.LOW, { glide(it, 0.5f, 10000f, 400f) }, 0.8f, { perc(it, 0.001f, 0.15f) * 0.8f })
        drive(1.4f)
        for ((i, n) in intArrayOf(72, 79, 84, 88, 91, 96).withIndex()) bell(0.03f + i * 0.04f, n, 0.3f, 0.16f)
        val rng = Random(182)
        repeat(9) { bell(0.35f + it * 0.09f + rng.nextFloat() * 0.04f, 96 + intArrayOf(0, 4, 7, 12)[rng.nextInt(4)], 0.1f, 0.06f) }
        reverb(0.28f, 0.8f)
    }
}
