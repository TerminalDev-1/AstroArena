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
        enableEdgeToEdge()
        hideSystemBars()

        val repo = (application as? GameApp)?.repository ?: GameRepository(SaveStore(this))
        val server = (application as? GameApp)?.server ?: io.github.projectwip.net.GameServer(this)
        // Every save also goes to the server (when there is one), so a fresh install can get it back.
        repo.onCommit = { server.pushSave(SaveStore.toJson(it)) }
        // Debug: `--es server http://host:port` points this install at another server ("default" clears it).
        if (BuildConfig.DEBUG) intent?.getStringExtra("server")?.let { url -> repo.updateSettings { it.copy(serverUrl = if (url == "default") "" else url) } }
        sfx = Sfx(this).also { it.load(); it.loadVoice() }
        music = io.github.projectwip.audio.Music(this).also { it.load() }
        io.github.projectwip.render3d.Portraits.start()
        applyRefreshRate(repo.save.value.settings.highFrameRate)

        // Debug builds accept `--es screen match|fighters|shop|track|settings|capsuleN` for automated testing.
        val start = if (BuildConfig.DEBUG) intent?.getStringExtra("screen") else null
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
    fun applyRefreshRate(high: Boolean) {
        val display = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        display ?: return
        val modes = display.supportedModes
        val current = display.mode
        val best = if (high) {
            modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= 145f }
                .maxByOrNull { it.refreshRate }
        } else {
            modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        }
        best?.let { m ->
            window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId }
        }
    }
}
