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
        Sound.TAP -> tap().finish(0.45f)
        Sound.UPGRADE -> upgrade().finish(0.8f)
        Sound.REWARD -> reward().finish(0.7f)
        Sound.VICTORY -> victory().finish(0.6f)
        Sound.DEFEAT -> defeat().finish(0.75f)
        Sound.DENIED -> denied().finish(0.5f)
        Sound.PICKUP -> pickup().finish(0.7f)
        Sound.CRATE_BREAK -> crateBreak().finish(0.8f)
        Sound.DROP_TAP -> dropTap().finish(0.75f)
        Sound.DROP_UPGRADE -> dropUpgrade().finish(0.8f)
        Sound.DROP_OPEN -> dropOpen().finish(0.9f)
        Sound.WHOOSH -> whoosh().finish(0.4f)
        Sound.VERSUS -> versus().finish(0.85f)
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

    /** Victory: a short, warm rising chime over a soft chord. Pleased, not triumphant. */
    private fun victory() = Clip(1.7f).apply {
        for ((i, n) in intArrayOf(72, 76, 79, 84).withIndex()) bell(i * 0.11f, n, 0.42f, 0.11f)
        bell(0.44f, 88, 0.3f, 0.2f)
        val pad = Clip(1.7f).apply {
            for (n in intArrayOf(60, 67, 72, 76)) osc(Wave.TRI, 0.3f, 1.3f, { hz(n) }, { hold(it, 0.12f, 0.5f, 0.25f) * 0.16f })
            filter(Band.LOW, 0.7f) { 2200f }
        }
        mix(pad)
        reverb(0.2f, 0.7f)
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

    private fun tap() = Clip(0.08f).apply {
        osc(Wave.SINE, 0f, 0.07f, { glide(it, 0.03f, 1500f, 850f) }, { perc(it, 0.001f, 0.012f) * 0.7f })
        osc(Wave.TRI, 0f, 0.05f, { 420f }, { perc(it, 0.001f, 0.01f) * 0.4f })
        noise(111, 0f, 0.008f, Band.HIGH, { 6000f }, 0.7f, { perc(it, 0.0003f, 0.002f) * 0.3f })
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
