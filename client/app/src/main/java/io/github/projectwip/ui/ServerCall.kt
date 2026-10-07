package io.github.projectwip.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.GameRepository
import io.github.projectwip.net.Account
import io.github.projectwip.net.GameActions
import io.github.projectwip.net.GameServer
import io.github.projectwip.net.LocalGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Takes on what the server holds for this player: Cups, drops, currencies, fighters, claims and shop deals. */
fun GameRepository.sync(account: Account) =
    syncAccount(account.cups, account.drops, account.dropsLeftToday, account.profile, account.deals, account.difficulty, account.day.takeIf { it >= 0 })

/**
 * How the menus ask for something to be done (buy, upgrade, claim...).
 *
 * Online the game server is asked: the request runs off the main thread, the account it sends back is taken on,
 * and [then] runs with the answer if the server agreed. While the server can't be reached the game is on its
 * offline profile, and the same request is answered on the device ([LocalGame]). Either way a "no" plays the
 * "no" sound and says why.
 *
 *     ask({ buy(item.key) }) { reward -> showReward(reward) }
 */
class ServerCall(
    private val scope: CoroutineScope,
    private val server: GameServer,
    private val local: LocalGame,
    private val repo: GameRepository,
    private val sfx: Sfx?,
    private val say: (String) -> Unit,
) {
    operator fun <T : Any> invoke(request: GameActions.() -> T?, then: (T) -> Unit = {}) {
        if (repo.offlineMode) {
            val answer = local.request()
            if (answer != null) then(answer)
            else {
                sfx?.play(Sound.DENIED)
                local.lastError.takeIf { it.isNotBlank() }?.let { say("Can't do that: $it.") }
            }
            return
        }
        if (!server.status.value.online) {
            sfx?.play(Sound.DENIED)
            say("Couldn't reach the server. Try again in a moment.")
            return
        }
        scope.launch {
            val answer = withContext(Dispatchers.IO) { server.request() }
            server.status.value.account?.let { repo.sync(it) }
            if (answer != null) {
                then(answer)
                server.status.value.account?.let { repo.sync(it) }
            } else {
                sfx?.play(Sound.DENIED)
                if (!server.status.value.online) say("Couldn't reach the server. Try again in a moment.")
                else server.lastError.takeIf { it.isNotBlank() }?.let { say("The server said no: $it.") }
            }
        }
    }
}

val LocalServerCall = staticCompositionLocalOf<ServerCall> { error("No ServerCall provided") }

/** The game answered on the device while the server can't be reached, and whether that is the case right now. */
val LocalOfflineGame = staticCompositionLocalOf<LocalGame?> { null }
val LocalOfflineMode = staticCompositionLocalOf { false }
