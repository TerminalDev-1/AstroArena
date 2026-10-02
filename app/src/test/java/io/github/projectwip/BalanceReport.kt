package io.github.projectwip

import io.github.projectwip.data.BotDifficulty
import io.github.projectwip.data.FighterId
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.MatchConfig
import org.junit.Test

/** Not an assertion test: prints per-fighter performance across many bot matches to guide balancing. */
class BalanceReport {
    @Test fun report() {
        val kos = HashMap<FighterId, Int>()
        val deaths = HashMap<FighterId, Int>()
        val dmg = HashMap<FighterId, Long>()
        val count = HashMap<FighterId, Int>()
        var totalTime = 0f
        val n = 40
        repeat(n) { i ->
            val m = Match(MatchConfig(FighterId.entries[i % 3], 5, 0, "P", BotDifficulty.HARD, humanPlayer = false, seed = 1000L + i))
            var t = 0f
            while (!m.isOver && t < 400f) { m.step(Match.STEP); t += Match.STEP }
            totalTime += t
            for (f in m.world.fighters) {
                kos.merge(f.def.id, f.kos, Int::plus); deaths.merge(f.def.id, f.deaths, Int::plus)
                dmg.merge(f.def.id, f.damageDealt.toLong(), Long::plus); count.merge(f.def.id, 1, Int::plus)
            }
        }
        println("avg match ${"%.1f".format(totalTime / n)}s")
        for (id in FighterId.entries) {
            val c = count[id] ?: continue
            println("$id n=$c  KO/match=${"%.2f".format(kos[id]!!.toFloat() / c)}  deaths=${"%.2f".format(deaths[id]!!.toFloat() / c)}  dmg=${dmg[id]!! / c}")
        }
    }
}
