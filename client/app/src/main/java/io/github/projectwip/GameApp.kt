package io.github.projectwip

import android.app.Application
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.SaveStore

/** Holds the single [GameRepository] and server connection so they survive activity recreation. */
class GameApp : Application() {
    val repository: GameRepository by lazy { GameRepository(SaveStore(this)) }
    val server: io.github.projectwip.net.GameServer by lazy { io.github.projectwip.net.GameServer(this) }
}
