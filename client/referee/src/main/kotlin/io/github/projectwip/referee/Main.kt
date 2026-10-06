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
 *
 * A 1v1 (`mode=DUEL`) is two real players and no bots, so it is given both of them instead:
 *     seed=123456   fighter0=KITO level0=10 frames0=<base64>   fighter1=BYTE level1=7 frames1=<base64>
 *     check=600,123,456          (optional: a tick the devices disagreed on, and each one's number for it)
 * and answers
 *     finished=true ticks=4328 winner=1 kos0=1 deaths0=3 damage0=4100 kos1=3 deaths1=1 damage1=6200 wrong0=false wrong1=false
 *
 * A team of real players (`players=2` or `3`, in Boss Mode or Knockout Rush) is given like any match (mode, seed,
 * difficulty, names, bot settings, boss), with each player in place of the one fighter:
 *     players=2   fighter0=KITO level0=10 frames0=<base64>   fighter1=BYTE level1=7 frames1=<base64>
 *     left=1                     (optional: the slots of players who walked out, separated by commas)
 *     check=600                  (optional: a tick to give the replay's own checksum for)
 * and answers
 *     finished=true ticks=4328 winner=0 mvp=1 kos0=.. deaths0=.. damage0=.. kos1=.. deaths1=.. damage1=.. checksum=123
 */
fun main() {
    val fields = HashMap<String, String>()
    for (line in generateSequence(::readLine)) {
        val at = line.indexOf('=')
        if (at > 0) fields[line.substring(0, at).trim()] = line.substring(at + 1).trim()
    }
    try {
        if (fields["mode"] == "DUEL") {
            val check = fields["check"]?.split(',')?.map { it.trim().toInt() }
            val verdict = Referee.judgeDuel(
                fields.getValue("seed").toLong(),
                listOf(FighterId.valueOf(fields.getValue("fighter0")), FighterId.valueOf(fields.getValue("fighter1"))),
                listOf(fields.getValue("level0").toInt(), fields.getValue("level1").toInt()),
                Base64.getDecoder().decode(fields["frames0"].orEmpty()), Base64.getDecoder().decode(fields["frames1"].orEmpty()),
                check?.get(0) ?: -1, check?.let { intArrayOf(it[1], it[2]) },
            )
            println("finished=${verdict.finished}")
            println("ticks=${verdict.ticks}")
            println("winner=${verdict.winner}")
            for ((side, f) in verdict.fighters.withIndex()) {
                println("kos$side=${f.kos}")
                println("deaths$side=${f.deaths}")
                println("damage$side=${f.damageDealt}")
                println("wrong$side=${verdict.wrong[side]}")
            }
            return
        }
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

        val teamSize = fields["players"]?.toInt() ?: 0
        if (teamSize > 0) {
            val players = (0 until teamSize).map {
                io.github.projectwip.sim.TeamPlayer(FighterId.valueOf(fields.getValue("fighter$it")), fields.getValue("level$it").toInt(), 0, "Player")
            }
            val config = MatchConfig(
                players[0].fighter, players[0].level, 0, "Player", difficulty,
                mode = GameMode.valueOf(fields.getValue("mode")), seed = fields.getValue("seed").toLong(),
                botNames = fields["names"].orEmpty().split(',').filter { it.isNotBlank() },
                boss = fields["boss"]?.let { name -> io.github.projectwip.data.BossKind.entries.firstOrNull { it.name == name } },
                team = io.github.projectwip.sim.TeamSetup(0, players),
            )
            val verdict = Referee.judgeTeam(
                config, (0 until teamSize).map { Base64.getDecoder().decode(fields["frames$it"].orEmpty()) },
                fields["left"].orEmpty().split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toSet(),
                fields["check"]?.trim()?.toInt() ?: -1,
            )
            println("finished=${verdict.finished}")
            println("ticks=${verdict.ticks}")
            println("winner=${verdict.winner}")
            println("mvp=${verdict.mvp}")
            for ((slot, f) in verdict.fighters.withIndex()) {
                println("kos$slot=${f.kos}")
                println("deaths$slot=${f.deaths}")
                println("damage$slot=${f.damageDealt}")
            }
            verdict.checksum?.let { println("checksum=$it") }
            return
        }
        val config = MatchConfig(
            playerFighter = FighterId.valueOf(fields.getValue("fighter")),
            playerLevel = fields.getValue("level").toInt(),
            playerSkin = 0,
            playerName = "Player",
            difficulty = difficulty,
            mode = GameMode.valueOf(fields.getValue("mode")),
            seed = fields.getValue("seed").toLong(),
            botNames = fields["names"].orEmpty().split(',').filter { it.isNotBlank() },
            // Boss Mode: the boss the player asked for, if they asked for one.
            boss = fields["boss"]?.let { name -> io.github.projectwip.data.BossKind.entries.firstOrNull { it.name == name } },
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
