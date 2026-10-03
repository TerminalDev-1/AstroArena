package io.github.projectwip.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The lobby music: one loop composed in [SfxSynth.renderLobbyMusic], rendered once (and cached as raw PCM) and
 * played gaplessly from memory. It plays only while the menus want it and the app is in the foreground.
 */
class Music(private val context: Context) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var wanted = false
    private var foreground = true
    private var released = false

    var volume = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); synchronized(lock) { track?.setVolume(field); apply() } }

    fun load() {
        Thread({
            try {
                val file = File(context.cacheDir, CACHE)
                val pcm = if (file.exists()) file.readBytes() else {
                    val samples = SfxSynth.renderLobbyMusic()
                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (s in samples) bytes.putShort((s.coerceIn(-1f, 1f) * 32000).toInt().toShort())
                    for (old in context.cacheDir.listFiles { f -> f.name.startsWith("music-") } ?: emptyArray()) old.delete()
                    val tmp = File(context.cacheDir, "$CACHE.tmp")
                    tmp.writeBytes(bytes.array())
                    tmp.renameTo(file)
                    bytes.array()
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SfxSynth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.setLoopPoints(0, pcm.size / 2, -1)
                synchronized(lock) {
                    if (released) { t.release(); return@Thread }
                    t.setVolume(volume)
                    track = t
                    apply()
                }
            } catch (e: Exception) {
                Log.e("Music", "lobby music unavailable", e)
            }
        }, "music-synth").start()
    }

    /** The menus want music; matches don't. */
    fun setWanted(want: Boolean) = synchronized(lock) { wanted = want; apply() }

    /** The app came to the front or went to the background. */
    fun setForeground(front: Boolean) = synchronized(lock) { foreground = front; apply() }

    private fun apply() {
        val t = track ?: return
        val play = wanted && foreground && volume > 0f
        if (play && t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        else if (!play && t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
    }

    fun release() = synchronized(lock) { released = true; track?.release(); track = null }

    private companion object {
        /** Bump when the composition changes. */
        const val CACHE = "music-v1.pcm"
    }
}
