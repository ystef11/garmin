package com.example.runstef.data

import java.time.LocalDate

data class HrFitnessQuarter(
    val quarterStart: LocalDate,
    val n: Int,
    val weightedHr: Double,
    val thrHr: Double,
    val thrPace: Double,
    val pctPano: Double,
    val thrPaceNext: Double?,
    val deltaNext: Double?
)

data class HrFitnessResponseInfo(
    val corr: Double?,
    val nQuartersValid: Int,
    val nImproved: Int,
    val nWorsened: Int,
    val respCeilingPct: Double?,
    val respCeilingHr: Int?
)

private fun quarterStartOf(d: LocalDate): LocalDate {
    val qMonth = ((d.monthValue - 1) / 3) * 3 + 1
    return LocalDate.of(d.year, qMonth, 1)
}

private fun median(vals: List<Double>): Double {
    val s = vals.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * Порт easy_hr_fitness_response() из build_report.py — квартальная агрегация: взвешенный по
 * длительности пульс "лёгких" тренировок (typeGuess=="easy", avgHr>60 — то же множество, что
 * и в EF-тренде) ПРОТИВ среднего порогового темпа Гармина (lactate_threshold) в ЭТОМ и
 * СЛЕДУЮЩЕМ квартале — устойчивость формы к текущей нагрузке: если "лёгкие" тренировки в этом
 * квартале бегались на непропорционально высоком % от ПАНО, а в следующем квартале пороговый
 * темп НЕ улучшился (или ухудшился) — это сигнал, что нагрузка была на грани/за пределами
 * "продуктивной" зоны для этого атлета в этот период.
 *
 * Возвращает null, если данных недостаточно для содержательного вывода (см. [minQuarters]) —
 * тогда вызывающий код должен просто не показывать блок.
 */
fun easyHrFitnessResponse(
    activities: List<ActivityRow>,
    lactateThresholdHistory: List<LactateThresholdRow>,
    panoFinal: Int,
    minActivitiesPerQ: Int = 3,
    minQuarters: Int = 4
): Pair<List<HrFitnessQuarter>, HrFitnessResponseInfo>? {
    val easy = activities.filter { it.typeGuess == "easy" && it.avgHr != null && it.avgHr!! > 60 && it.durationS != null }
    if (easy.isEmpty()) return null

    val ltValid = lactateThresholdHistory.filter { it.thresholdHr != null && it.thresholdPaceSPerKm != null }
    if (ltValid.isEmpty()) return null

    val ltByQuarter = ltValid.groupBy { quarterStartOf(LocalDate.parse(it.date.take(10))) }
    val thrByQuarter = ltByQuarter.mapValues { (_, rows) ->
        rows.map { it.thresholdHr!!.toDouble() }.average() to rows.map { it.thresholdPaceSPerKm!! }.average()
    }

    val easyByQuarter = easy.groupBy { quarterStartOf(LocalDate.parse(it.date)) }
    val quarterRows = mutableListOf<HrFitnessQuarter>()
    for ((q, list) in easyByQuarter) {
        if (list.size < minActivitiesPerQ) continue
        val thr = thrByQuarter[q] ?: continue
        val totalDur = list.sumOf { it.durationS!! }
        if (totalDur <= 0.0) continue
        val weightedHr = list.sumOf { it.avgHr!! * it.durationS!! } / totalDur
        val pctPano = weightedHr / panoFinal * 100.0
        quarterRows.add(HrFitnessQuarter(q, list.size, weightedHr, thr.first, thr.second, pctPano, null, null))
    }
    if (quarterRows.size < minQuarters) return null

    val sorted = quarterRows.sortedBy { it.quarterStart }
    val withNext = sorted.mapIndexed { i, row ->
        val next = sorted.getOrNull(i + 1)
        val thrPaceNext = next?.thrPace
        val deltaNext = thrPaceNext?.let { it - row.thrPace }
        row.copy(thrPaceNext = thrPaceNext, deltaNext = deltaNext)
    }

    val valid = withNext.filter { it.deltaNext != null }
    var corr: Double? = null
    if (valid.size >= 4) {
        val xs = valid.map { it.pctPano }
        val ys = valid.map { it.deltaNext!! }
        val (r, _) = pearsonrApprox(xs, ys)
        corr = r
    }

    val improved = valid.filter { it.deltaNext!! < 0 }
    val worsened = valid.filter { it.deltaNext!! >= 0 }

    val respCeilingPct = if (improved.size >= 2) median(improved.map { it.pctPano }) else null
    val respCeilingHr = respCeilingPct?.let { Math.round(it / 100.0 * panoFinal).toInt() }

    val info = HrFitnessResponseInfo(
        corr = corr,
        nQuartersValid = valid.size,
        nImproved = improved.size,
        nWorsened = worsened.size,
        respCeilingPct = respCeilingPct,
        respCeilingHr = respCeilingHr
    )
    return withNext to info
}
