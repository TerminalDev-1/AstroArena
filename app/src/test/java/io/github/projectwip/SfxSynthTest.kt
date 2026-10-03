package io.github.projectwip

import io.github.projectwip.audio.SfxSynth
import io.github.projectwip.audio.Sound
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class SfxSynthTest {
    /** Every sound must be audible, clean (no NaN, no clipping, no click at either end) and a sensible length. */
    @Test fun everySoundIsCleanAndAudible() {
        val out = System.getProperty("sfx.dump")?.let { File(it).apply { mkdirs() } }
        for (s in Sound.entries) {
            val d = SfxSynth.render(s)
            val seconds = d.size.toFloat() / SfxSynth.RATE
            val peak = d.maxOf { abs(it) }
            val rms = sqrt(d.sumOf { (it * it).toDouble() } / d.size).toFloat()
            println("%-13s %.2fs peak=%.2f rms=%.3f".format(s, seconds, peak, rms))
            assertTrue("$s has bad samples", d.all { it.isFinite() })
            assertTrue("$s length $seconds", seconds in 0.05f..2.5f)
            assertTrue("$s peak $peak", peak in 0.4f..0.95f)
            assertTrue("$s is nearly silent (rms $rms)", rms > 0.03f)
            assertTrue("$s starts with a click", abs(d[0]) < 0.2f)
            assertTrue("$s ends with a click", abs(d[d.size - 1]) < 0.01f)
            out?.let { dir ->
                val bb = ByteBuffer.allocate(44 + d.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.put("RIFF".toByteArray()).putInt(36 + d.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(SfxSynth.RATE).putInt(SfxSynth.RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(d.size * 2)
                for (v in d) bb.putShort((v * 32767).toInt().toShort())
                File(dir, "${s.name.lowercase()}.wav").writeBytes(bb.array())
            }
        }
    }

    /** The lobby loop must be clean and must meet itself at the seam without a click. */
    @Test fun lobbyMusicLoopsCleanly() {
        val d = SfxSynth.renderLobbyMusic()
        val seconds = d.size.toFloat() / SfxSynth.RATE
        val rms = sqrt(d.sumOf { (it * it).toDouble() } / d.size).toFloat()
        println("lobby music %.2fs rms=%.3f seam=%.3f".format(seconds, rms, abs(d[0] - d[d.size - 1])))
        assertTrue(d.all { it.isFinite() })
        assertTrue("eight bars at 112 BPM", abs(seconds - 8 * 4 * 60f / 112f) < 0.01f)
        assertTrue(d.maxOf { abs(it) } <= 0.71f)
        assertTrue("audible", rms > 0.05f)
        // The jump from the last sample back to the first should be no bigger than the steps around it.
        val typical = (1 until 2000).maxOf { abs(d[it] - d[it - 1]) }
        assertTrue("click at the loop point", abs(d[0] - d[d.size - 1]) <= typical * 1.5f + 0.01f)
    }
}
