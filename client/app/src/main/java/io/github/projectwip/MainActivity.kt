package io.github.projectwip

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.projectwip.audio.Sfx
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.SaveStore
import io.github.projectwip.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var sfx: Sfx
    private lateinit var music: io.github.projectwip.audio.Music

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.projectwip.gl.PowerWatch.start(this)
        enableEdgeToEdge()
        hideSystemBars()

        val repo = (application as? GameApp)?.repository ?: GameRepository(SaveStore(this), SaveStore(this, GameApp.OFFLINE_SAVE))
        val server = (application as? GameApp)?.server ?: io.github.projectwip.net.GameServer(this)
        // Every online save also goes to the server (when there is one), so a fresh install can get it back. The offline profile never does.
        repo.onCommit = { server.pushSave(SaveStore.toJson(it)) }
        // Debug: `--es server http://host:port` points this install at another server ("default" clears it).
        if (BuildConfig.DEBUG) intent?.getStringExtra("server")?.let { url -> repo.updateSettings { it.copy(serverUrl = if (url == "default") "" else url) } }
        sfx = Sfx(this).also { it.load(); it.loadVoice() }
        music = io.github.projectwip.audio.Music(this).also { it.load() }
        io.github.projectwip.render3d.Portraits.start()
        // The graphics preset and frame rate follow the settings (App calls this again when they change).
        applyGraphics(repo.save.value.settings)

        // Debug builds accept `--es screen match|fighters|shop|track|settings|capsuleN` for automated testing.
        val start = if (BuildConfig.DEBUG) intent?.getStringExtra("screen") else null
        // Debug: `--es screen crash` crashes the game on purpose, to try the crash reporter.
        if (start == "crash") window.decorView.postDelayed({ throw IllegalStateException("A test crash, asked for with --es screen crash") }, 1500)
        setContent { App(repo, sfx, music, server, start) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        music.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        music.setForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
        music.release()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Ask for the display's highest refresh mode (up to 144 Hz) when high frame rate is enabled. */
    /** Makes [s]'s graphics preset the one the renderers use; the menus never run above 60 frames a second. */
    fun applyGraphics(s: io.github.projectwip.data.Settings) {
        val g = io.github.projectwip.gl.GraphicsProfile.resolve(this, s)
        io.github.projectwip.gl.GraphicsProfile.current = g
        if (!inMatch) applyRefreshRate(g.menuFrameRate)
    }

    /** A match is on screen: it sets the window's refresh rate itself. */
    var inMatch = false

    /** Runs the panel at the slowest mode that still shows [fps] (60 for 30: the game paces itself to half). */
    fun applyRefreshRate(fps: Int) {
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val modes = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val want = maxOf(fps, 60) - 1f
        val best = modes.filter { it.refreshRate >= want }.minByOrNull { it.refreshRate } ?: modes.maxByOrNull { it.refreshRate }
        best?.let { m ->
            window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId }
        }
    }
}
