package com.example.runstef.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Минимальный Kotlin-порт формул VDOT (модель Дэниелса) — ТЕ ЖЕ формулы, что в JS в
 * run_plan_calculator.html / hr_pace_calculator.html / rank_calculator.html / weight_calculator.html
 * (см. project memory о требовании держать связанные файлы синхронными). Используется только для
 * того, чтобы привести лучший найденный в аналитике результат к одной из "табличных" дистанций
 * калькуляторов (5/10/21.1/42.2 км и т.д. — см. ToolUrlBuilder.kt), а не для отображения где-либо
 * в самом приложении.
 */
object VdotMath {
    /** Табличные дистанции (м), на которые снимается лучший результат из аналитики — объединение
     * дистанций, которые используют resList в run_plan_calculator.html (5/10/21.1/42.2 км) и
     * таблица нормативов в rank_calculator.html (800/1500/3000/5000/10к/21.1/42.2 км). */
    val ANCHOR_DISTANCES_M = doubleArrayOf(800.0, 1500.0, 3000.0, 5000.0, 10000.0, 21097.5, 42195.0)

    private fun vo2At(vMeterPerMin: Double): Double =
        -4.60 + 0.182258 * vMeterPerMin + 0.000104 * vMeterPerMin * vMeterPerMin

    private fun pctAt(tMin: Double): Double =
        0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)

    fun vdot(distM: Double, sec: Double): Double {
        val t = sec / 60.0
        return vo2At(distM / t) / pctAt(t)
    }

    fun timeFor(distM: Double, vd: Double): Double {
        var lo = distM / 8.0
        var hi = distM / 1.4
        repeat(60) {
            val mid = (lo + hi) / 2.0
            if (vdot(distM, mid) > vd) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }

    /** Ближайшая табличная дистанция к произвольной (по лог-шкале — как nearestTableDist() в JS). */
    fun nearestAnchor(distM: Double): Double {
        var best = ANCHOR_DISTANCES_M[0]
        var diff = Double.MAX_VALUE
        for (td in ANCHOR_DISTANCES_M) {
            val dl = kotlin.math.abs(ln(td) - ln(max(1.0, distM)))
            if (dl < diff) { diff = dl; best = td }
        }
        return best
    }
}
