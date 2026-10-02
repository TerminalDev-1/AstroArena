package io.github.projectwip

import android.app.Application
import io.github.projectwip.data.GameRepository
import io.github.projectwip.data.SaveStore

/** Holds the single [GameRepository] so progress survives activity recreation. */
class GameApp : Application() {
    val repository: GameRepository by lazy { GameRepository(SaveStore(this)) }
}
