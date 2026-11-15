package io.github.projectwip.net

import android.content.Context
import android.content.Intent
import io.github.projectwip.GameApp
import java.io.File

/**
 * Starting the game over as a new player (the owner's decision, build 111): used when the player resets their
 * progress (their account is deleted on the server first) and when the server no longer knows this device's
 * account (a full game reset wiped them all). Everything this device kept for the player goes (the copy of the
 * account, the offline profile, the sign-in), and the game restarts itself, so it asks for a name and plays the
 * opening again, exactly as on a first install. Sounds, music and crash reports are kept.
 */
object FreshStart {
    fun wipeAndRestart(context: Context) {
        val app = context.applicationContext
        // The save files (and the backups the atomic writes leave beside them).
        for (name in listOf("save.json", GameApp.OFFLINE_SAVE)) {
            for (suffix in listOf("", ".bak", ".new")) File(app.filesDir, name + suffix).delete()
        }
        // Signed in nowhere any more: every server's id and token.
        app.getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
        // Up again from the start, in a new process (nothing of this one's state carried over).
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(launch)
        }
        Runtime.getRuntime().exit(0)
    }
}
