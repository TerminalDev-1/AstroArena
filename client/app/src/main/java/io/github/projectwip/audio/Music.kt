package io.github.projectwip.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The pieces of music in the game. Each is a loop composed in [SfxSynth]. */
enum class Track { LOBBY, VICTORY, DEFEAT }

/**
 * Background music. Every [Track] is rendered once (and cached as raw PCM), then played gaplessly from memory.
 * At most one plays at a time: whichever the game last asked for with [play], and only while the app is in the
 * foreground. Switching to a track starts it from the top.
 */
class Music(private val context: Context) {
    private val lock = Any()
    private val tracks = arrayOfNulls<AudioTrack>(Track.entries.size)
    private var wanted: Track? = null
    private var playing: Track? = null
    private var foreground = true
    private var released = false
    /** True once every track is loaded, or has failed to load (for the loading screen). */
    @Volatile var ready = false
        private set

    var volume = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); synchronized(lock) { for (t in tracks) t?.setVolume(field); apply() } }

    fun load() {
        Thread({
            try {
                for (old in context.cacheDir.listFiles { f -> f.name.startsWith("music-") && !f.name.startsWith(CACHE) } ?: emptyArray()) old.delete()
                for (which in Track.entries) {
                    val file = File(context.cacheDir, "$CACHE-${which.name.lowercase()}.pcm")
                    val pcm = if (file.exists()) file.readBytes() else {
                        val samples = when (which) {
                            Track.LOBBY -> SfxSynth.renderLobbyMusic()
                            Track.VICTORY -> SfxSynth.renderVictoryMusic()
                            Track.DEFEAT -> SfxSynth.renderDefeatMusic()
                        }
                        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (s in samples) bytes.putShort((s.coerceIn(-1f, 1f) * 32000).toInt().toShort())
                        val tmp = File(context.cacheDir, file.name + ".tmp")
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
                        if (released) { t.release(); ready = true; return@Thread }
                        t.setVolume(volume)
                        tracks[which.ordinal] = t
                        apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("Music", "music unavailable", e)
            }
            ready = true
        }, "music-synth").start()
    }

    /** Ask for a piece of music, or null for silence (matches, loading). */
    fun play(track: Track?) = synchronized(lock) { wanted = track; apply() }

    /** The app came to the front or went to the background. */
    fun setForeground(front: Boolean) = synchronized(lock) { foreground = front; apply() }

    private fun apply() {
        val want = if (foreground && volume > 0f) wanted else null
        if (want != playing) {
            playing?.let { tracks[it.ordinal] }?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
            // A newly chosen piece starts from the top.
            want?.let { tracks[it.ordinal] }?.let { if (it.playState != AudioTrack.PLAYSTATE_PLAYING) it.playbackHeadPosition = 0 }
            playing = null
        }
        val t = want?.let { tracks[it.ordinal] } ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        playing = want
    }

    fun release() = synchronized(lock) { released = true; for (i in tracks.indices) { tracks[i]?.release(); tracks[i] = null } }

    private companion object {
        /** Bump when any composition changes. */
        const val CACHE = "music-v4"
    }
}
