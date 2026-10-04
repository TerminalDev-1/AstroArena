package io.github.projectwip.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.projectwip.audio.Sfx
import io.github.projectwip.audio.Sound
import io.github.projectwip.data.GameRepository
import io.github.projectwip.net.Account
import io.github.projectwip.net.GameServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Takes on what the server holds for this player: Cups, drops, currencies, fighters, claims and shop deals. */
fun GameRepository.sync(account: Account) =
    syncAccount(account.cups, account.drops, account.dropsLeftToday, account.profile, account.deals, account.day.takeIf { it >= 0 })

/**
 * How the menus ask the game server to do something (buy, upgrade, claim...). The request runs off the main
 * thread; the account the server sends back is taken on; and [then] runs with the answer if the server agreed.
 * If it didn't, or there is no server, the player hears the "no" sound and is told why.
 *
 *     ask({ buy(item.key) }) { reward -> showReward(reward) }
 */
class ServerCall(
    private val scope: CoroutineScope,
    private val server: GameServer,
    private val repo: GameRepository,
    private val sfx: Sfx?,
    private val say: (String) -> Unit,
) {
    operator fun <T : Any> invoke(request: GameServer.() -> T?, then: (T) -> Unit = {}) {
        if (!server.status.value.online) {
            sfx?.play(Sound.DENIED)
            say("That needs the server, and you're offline.")
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
