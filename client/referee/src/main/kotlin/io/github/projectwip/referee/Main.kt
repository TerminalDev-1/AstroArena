package io.github.projectwip.referee

import io.github.projectwip.ai.BotProfile
import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.GameMode
import io.github.projectwip.sim.MatchConfig
import io.github.projectwip.sim.Referee
import java.util.Base64

/**
 * The game server's match referee. The server (Python) starts this program, writes one match on standard input
 * as `key=value` lines, and reads the verdict from standard output the same way.
 *
 * Input:
 *     mode=LAST_SPARK            fighter=KITO           level=10          difficulty=NORMAL
 *     seed=123456                names=Rivet,Moss,...   (the bots' names, in order)
 *     bot.reactiontime=0.48      (any number of bot settings for this difficulty, as the server's bots.cfg has them)
 *     inputs=<base64>            (the player's input log: see InputLog)
 *
 * Output:
 *     outcome=DEFEAT placement=4 kos=2 deaths=1 damage=5120 mvp=false ticks=4328 finished=true fingerprint=...
 * or, if the input makes no sense, a single line `error=...` and exit code 2.
 */
fun main() {
    val fields = HashMap<String, String>()
    for (line in generateSequence(::readLine)) {
        val at = line.indexOf('=')
        if (at > 0) fields[line.substring(0, at).trim()] = line.substring(at + 1).trim()
    }
    try {
        val difficulty = BotDifficulty.valueOf(fields.getValue("difficulty"))
        // The bots behave as the server's settings say, exactly as they did on the device.
        val settings = HashMap<String, Any>()
        for ((key, value) in fields) {
            if (!key.startsWith("bot.")) continue
            settings[key.removePrefix("bot.")] = when (value) {
                "true" -> true
                "false" -> false
                else -> value.toDouble()
            }
        }
        BotProfile.overrides = if (settings.isEmpty()) emptyMap() else mapOf(difficulty to BotProfile.builtIn(difficulty).withOverrides(settings))

        val config = MatchConfig(
            playerFighter = FighterId.valueOf(fields.getValue("fighter")),
            playerLevel = fields.getValue("level").toInt(),
            playerSkin = 0,
            playerName = "Player",
            difficulty = difficulty,
            mode = GameMode.valueOf(fields.getValue("mode")),
            seed = fields.getValue("seed").toLong(),
            botNames = fields["names"].orEmpty().split(',').filter { it.isNotBlank() },
        )
        val verdict = Referee.judge(config, Base64.getDecoder().decode(fields["inputs"].orEmpty()))
        val r = verdict.report
        println("outcome=${r.outcome.name}")
        println("placement=${r.placement}")
        println("kos=${r.kos}")
        println("deaths=${r.deaths}")
        println("damage=${r.damageDealt}")
        println("mvp=${r.mvp}")
        println("ticks=${verdict.ticks}")
        println("finished=${verdict.finished}")
        println("fingerprint=${verdict.fingerprint}")
    } catch (e: Exception) {
        println("error=${e.javaClass.simpleName}: ${e.message}")
        kotlin.system.exitProcess(2)
    }
}
