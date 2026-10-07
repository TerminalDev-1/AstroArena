package io.github.projectwip

import android.app.Application
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.SaveStore

/** Holds the single [GameRepository] and server connection so they survive activity recreation. */
class GameApp : Application() {
    companion object {
        /** The offline profile's file: what is played on while the server can't be reached. */
        const val OFFLINE_SAVE = "offline.json"
    }

    val repository: GameRepository by lazy { GameRepository(SaveStore(this), SaveStore(this, OFFLINE_SAVE)) }
    val server: io.github.projectwip.net.GameServer by lazy { io.github.projectwip.net.GameServer(this) }
}
