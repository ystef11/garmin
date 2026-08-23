package com.example.runstef.data

import java.time.DayOfWeek
import java.time.LocalDate

data class WeeklyQualityPoint(
    val weekStart: LocalDate,
    val nSessions: Int,
    val nWorkLaps: Int,
    val nSessionsRoll: Double,
    val nWorkLapsRoll: Double
)

private fun weekStartMonday(d: LocalDate): LocalDate {
    var x = d
    while (x.dayOfWeek != DayOfWeek.MONDAY) x = x.minusDays(1)
    return x
}

private fun sampleStdOrZero(vals: List<Double>): Double {
    if (vals.size < 2) return 0.0
    val mean = vals.average()
    return kotlin.math.sqrt(vals.sumOf { (it - mean) * (it - mean) } / (vals.size - 1))
}

/**
 * Порт weekly_quality_training_frequency() из build_report.py — для каждой тренировки с
 * известным пульсом считает число "рабочих" лапов (лапы заметно быстрее собственной медианы
 * лапов ЭТОЙ тренировки: CV темпа >= 0.06, иначе структуру не отделить — n_work_laps=0). НЕ
 * ограничивается typeGuess — рабочие отрезки ищутся во ВСЕХ тренировках, включая long/easy/
 * mixed (пикапы/вставки внутри длительных и лёгких иначе теряются, см. докстринг оригинала).
 * Это просто счётчик фактов (тренировка была/рабочих отрезков было N), включая тренировки с
 * n_work_laps=0 — НЕ детекция типизированных Garmin work/rest-блоков (см. detect_quality_
 * work_laps в десктопе — это отдельный, более точный путь, здесь НЕ портирован, см. TODO).
 *
 * Возвращает по одной точке на КАЖДУЮ неделю от первой до последней тренировки в [activities]
 * (недели без тренировок — 0/0), со скользящим средним (4 недели, min_periods=1 — как в
 * pandas .rolling(4, min_periods=1)) по числу сессий и рабочих лапов.
 */
fun weeklyQualityTrainingFrequency(
    activities: List<ActivityRow>,
    intervalsByActivity: Map<Long, List<IntervalRow>>
): List<WeeklyQualityPoint> {
    val candidates = activities.filter { it.avgHr != null }
    if (candidates.isEmpty()) return emptyList()

    data class Row(val week: LocalDate, val nWork: Int)
    val rows = candidates.map { act ->
        var nWork = 0
        val allLaps = intervalsByActivity[act.activityId]
        if (allLaps != null) {
            val iv = allLaps.filter {
                it.avgHr != null && it.avgPaceSPerKm != null &&
                    (it.distanceM ?: 0.0) >= 150.0 && (it.durationS ?: 0.0) >= 30.0 &&
                    it.avgPaceSPerKm < 900.0
            }
            if (iv.size >= 3) {
                val paces = iv.map { it.avgPaceSPerKm!! }
                val sorted = paces.sorted()
                val medianPace = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                if (medianPace > 0.0) {
                    val cv = sampleStdOrZero(paces) / medianPace
                    if (cv >= 0.06) {
                        nWork = iv.count { it.avgPaceSPerKm!! <= medianPace * 0.94 }
                    }
                }
            }
        }
        Row(weekStartMonday(LocalDate.parse(act.date)), nWork)
    }

    val byWeek = rows.groupBy { it.week }
    val firstWeek = byWeek.keys.min()
    val lastWeek = byWeek.keys.max()

    val allWeeks = mutableListOf<LocalDate>()
    var w = firstWeek
    while (!w.isAfter(lastWeek)) { allWeeks.add(w); w = w.plusWeeks(1) }

    val nSessions = allWeeks.map { byWeek[it]?.size ?: 0 }
    val nWorkLaps = allWeeks.map { (byWeek[it] ?: emptyList()).sumOf { r -> r.nWork } }

    fun rollingMean4(vals: List<Int>): List<Double> = vals.indices.map { i ->
        val lo = maxOf(0, i - 3)
        vals.subList(lo, i + 1).average()
    }
    val nSessionsRoll = rollingMean4(nSessions)
    val nWorkLapsRoll = rollingMean4(nWorkLaps)

    return allWeeks.indices.map { i ->
        WeeklyQualityPoint(allWeeks[i], nSessions[i], nWorkLaps[i], nSessionsRoll[i], nWorkLapsRoll[i])
    }
}
