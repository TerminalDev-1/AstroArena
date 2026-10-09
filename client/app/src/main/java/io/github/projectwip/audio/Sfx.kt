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

/**
 * Plays the sound effects designed in [SfxSynth]. They are rendered once to small WAV files in the cache
 * dir (bump [CACHE] whenever the sound design changes) and played through a SoundPool.
 */
class Sfx(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = IntArray(Sound.entries.size)
    @Volatile private var loaded = false
    /** 0..1: how much of the sound set is ready (for the loading screen). */
    @Volatile var progress = 0f
        private set
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
            for (old in listOf("sfx-v1", "sfx-v2", "sfx-v3", "sfx-v4", "sfx-v5", "sfx-v6")) File(context.cacheDir, old).deleteRecursively()
            val dir = File(context.cacheDir, CACHE).apply { mkdirs() }
            for (s in Sound.entries) {
                val f = File(dir, "${s.name.lowercase()}.wav")
                if (!f.exists()) {
                    // Write to a temp name first so a half-written file is never mistaken for a finished one.
                    val tmp = File(dir, f.name + ".tmp")
                    writeWav(tmp, SfxSynth.render(s))
                    tmp.renameTo(f)
                }
                ids[s.ordinal] = pool.load(f.absolutePath, 1)
                progress = (s.ordinal + 1f) / Sound.entries.size
            }
            loaded = true
        }, "sfx-synth").start()
    }

    /**
     * SoundPool and the vibrator are system calls that can block for milliseconds, and matches call these from
     * the render thread, so they are handed to a worker thread instead of stalling a frame.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sfx-play").apply { isDaemon = true } }

    fun play(s: Sound, gain: Float = 1f, pitch: Float = 1f) {
        if (!loaded || volume <= 0f) return
        val v = (volume * gain).coerceIn(0f, 1f)
        worker.execute { pool.play(ids[s.ordinal], v, v, 1, 0, pitch.coerceIn(0.5f, 2f)) }
    }

    fun buzz(ms: Long, strength: Int) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        worker.execute {
            val amp = if (v.hasAmplitudeControl()) strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(ms, amp))
        }
    }

    // ---- Voice lines. Nothing is recorded: the device's own speech engine reads a fighter's lines out.
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var lastSaid = 0L

    /** Wakes the speech engine up, so the first line isn't lost. A device without one simply stays silent. */
    fun loadVoice() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            val t = tts
            if (status != android.speech.tts.TextToSpeech.SUCCESS || t == null) return@TextToSpeech
            ttsReady = true
        }
    }

    /** Says a line out loud in [style]'s voice, unless one was said a moment ago. */
    fun say(line: String, style: io.github.projectwip.data.VoiceStyle = io.github.projectwip.data.VoiceStyle.FIREFIGHTER) {
        val t = tts ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (!ttsReady || volume <= 0f || now - lastSaid < 2200) return
        lastSaid = now
        val params = android.os.Bundle().apply { putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        try {
            // Each voice has an accent, a pitch and a pace of its own. An accent the device doesn't have falls back to plain English.
            val locale = java.util.Locale.forLanguageTag(style.locale)
            t.language = if (t.isLanguageAvailable(locale) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) locale else java.util.Locale.US
            t.setPitch(style.pitch)
            t.setSpeechRate(style.rate)
            t.speak(line, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "line")
        } catch (_: Exception) {
        }
    }

    fun release() { worker.shutdown(); pool.release(); tts?.shutdown(); tts = null }

    private fun writeWav(f: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SfxSynth.RATE); putInt(SfxSynth.RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        FileOutputStream(f).use { it.write(header.array()); it.write(data.array()) }
    }

    private companion object {
        const val CACHE = "sfx-v15"
    }
}
