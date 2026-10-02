package io.github.projectwip.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sound {
    SHOOT_SPARK, SHOOT_HEAVY, SHOOT_PRISM, SUPER, HIT, HURT, KO, SUPER_READY,
    TICK, GO, TAP, UPGRADE, REWARD, VICTORY, DEFEAT, DENIED,
}

/**
 * All sound effects are synthesised at startup (no audio assets, nothing to license).
 * They're rendered to small WAV files in the cache dir and played through a SoundPool.
 */
class Sfx(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = IntArray(Sound.entries.size)
    @Volatile private var loaded = false
    @Volatile var volume = 0.8f
    @Volatile var hapticsEnabled = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun load() {
        Thread({
            val dir = File(context.cacheDir, "sfx-v1").apply { mkdirs() }
            for (s in Sound.entries) {
                val f = File(dir, "${s.name.lowercase()}.wav")
                if (!f.exists()) writeWav(f, synth(s))
                ids[s.ordinal] = pool.load(f.absolutePath, 1)
            }
            loaded = true
        }, "sfx-synth").start()
    }

    fun play(s: Sound, gain: Float = 1f, pitch: Float = 1f) {
        if (!loaded || volume <= 0f) return
        val v = (volume * gain).coerceIn(0f, 1f)
        pool.play(ids[s.ordinal], v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun buzz(ms: Long, strength: Int) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private val rate = 22050

    private fun synth(s: Sound): FloatArray = when (s) {
        Sound.SHOOT_SPARK -> render(0.09f) { t, _ -> square(sweep(t, 1500f, 700f, 0.09f)) * env(t, 0.002f, 0.08f) * 0.35f }
        Sound.SHOOT_HEAVY -> {
            val n = Random(1)
            render(0.16f) { t, _ -> (sin(2 * PI * 110 * t).toFloat() * 0.6f + (n.nextFloat() * 2 - 1) * 0.6f * exp(-t * 30f)) * env(t, 0.002f, 0.15f) * 0.6f }
        }
        Sound.SHOOT_PRISM -> render(0.2f) { t, _ -> (sine(sweep(t, 700f, 2100f, 0.12f)) * 0.5f + sine(3200f * t) * 0.15f) * env(t, 0.004f, 0.19f) * 0.5f }
        Sound.SUPER -> render(0.38f) { t, _ -> (square(sweep(t, 220f, 880f, 0.3f)) * 0.25f + sine(sweep(t, 440f, 1760f, 0.3f)) * 0.3f) * env(t, 0.01f, 0.36f) }
        Sound.HIT -> {
            val n = Random(2)
            render(0.06f) { t, _ -> ((n.nextFloat() * 2 - 1) * 0.5f + sine(sweep(t, 900f, 300f, 0.06f)) * 0.5f) * env(t, 0.001f, 0.055f) * 0.55f }
        }
        Sound.HURT -> render(0.12f) { t, _ -> square(sweep(t, 260f, 90f, 0.12f)) * env(t, 0.002f, 0.11f) * 0.4f }
        Sound.KO -> {
            val n = Random(3)
            render(0.5f) { t, _ -> (sine(sweep(t, 900f, 80f, 0.45f)) * 0.55f + (n.nextFloat() * 2 - 1) * 0.35f * exp(-t * 8f)) * env(t, 0.003f, 0.48f) }
        }
        Sound.SUPER_READY -> notes(listOf(988f, 1319f, 1976f), 0.07f, 0.25f, 0.35f)
        Sound.TICK -> render(0.12f) { t, _ -> sine(880f * t) * env(t, 0.003f, 0.11f) * 0.45f }
        Sound.GO -> render(0.35f) { t, _ -> (sine(1320f * t) * 0.4f + square(660f * t) * 0.12f) * env(t, 0.003f, 0.33f) }
        Sound.TAP -> render(0.05f) { t, _ -> (sine(sweep(t, 1400f, 900f, 0.05f))) * env(t, 0.001f, 0.045f) * 0.35f }
        Sound.UPGRADE -> notes(listOf(523f, 659f, 784f, 1047f, 1319f), 0.07f, 0.35f, 0.4f)
        Sound.REWARD -> notes(listOf(1047f, 1319f, 1568f, 2093f), 0.05f, 0.3f, 0.3f)
        Sound.VICTORY -> notes(listOf(523f, 659f, 784f, 1047f, 784f, 1047f), 0.11f, 0.5f, 0.4f)
        Sound.DEFEAT -> notes(listOf(392f, 349f, 311f, 262f), 0.16f, 0.5f, 0.35f)
        Sound.DENIED -> render(0.18f) { t, _ -> square(if (t < 0.08f) 220f * t else 180f * t) * env(t, 0.002f, 0.17f) * 0.3f }
    }

    /** Phase accumulator-free helpers: callers pass the integrated phase (cycles). */
    private fun sine(phase: Float) = sin(2 * PI * phase).toFloat()
    private fun square(phase: Float) = if ((phase % 1f) < 0.5f) 1f else -1f

    /** Integrated phase of a linear frequency sweep f0→f1 over [dur] seconds, evaluated at time t. */
    private fun sweep(t: Float, f0: Float, f1: Float, dur: Float): Float {
        val tt = t.coerceAtMost(dur)
        val k = (f1 - f0) / dur
        return f0 * tt + 0.5f * k * tt * tt + (t - tt) * f1
    }

    private fun env(t: Float, attack: Float, length: Float): Float =
        if (t < attack) t / attack else exp(-(t - attack) / (length * 0.35f))

    private fun notes(freqs: List<Float>, step: Float, tail: Float, gain: Float): FloatArray {
        val dur = step * freqs.size + tail
        return render(dur) { t, _ ->
            var v = 0f
            freqs.forEachIndexed { i, f ->
                val st = t - i * step
                if (st >= 0f) v += (sine(f * st) * 0.7f + sine(f * 2 * st) * 0.15f) * env(st, 0.004f, tail)
            }
            v * gain / 1.6f
        }
    }

    private inline fun render(seconds: Float, fn: (Float, Int) -> Float): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> fn(i.toFloat() / rate, i) }
    }

    private fun writeWav(f: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        FileOutputStream(f).use { it.write(header.array()); it.write(data.array()) }
    }
}
