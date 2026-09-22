package com.example.runstef.data

import java.time.DayOfWeek
import java.time.LocalDate

data class WeeklyMpkThresholdPoint(
    val weekStart: LocalDate,
    val mpkMin: Double,
    val thresholdMin: Double,
    val mpkMinRoll: Double,
    val thresholdMinRoll: Double
)

// Те же множества типов лап, что и LAP_ACTIVE_TYPES/LAP_REST_TYPES в build_report.py/
// garmin_activities_export.py (classify()) — держать в синхроне при изменении там.
private val LAP_ACTIVE_TYPES = setOf("INTERVAL_ACTIVE", "ACTIVE", "INTERVAL", "REPEAT", "WORK")
private val LAP_REST_TYPES = setOf("INTERVAL_REST", "RECOVERY", "REST", "RECOVERY_ACTIVE")

private fun mpkWeekStart(d: LocalDate): LocalDate {
    var x = d
    while (x.dayOfWeek != DayOfWeek.MONDAY) x = x.minusDays(1)
    return x
}

private data class WorkBlock(val durationS: Double, val avgHr: Double?)

/**
 * Порт _merge_continuous_work_blocks() из build_report.py — склеивает подряд идущие
 * типизированные Garmin work-лапы (без rest-лапа между ними) в непрерывные рабочие блоки
 * (Garmin дополнительно бьёт один непрерывный отрезок автолапами по километру). [ivSorted] —
 * лапы ОДНОЙ тренировки в хронологическом порядке (по idx). avgHr блока взвешен по длительности
 * входящих в него лапов.
 */
private fun mergeContinuousWorkBlocks(ivSorted: List<IntervalRow>): List<WorkBlock> {
    val blocks = mutableListOf<WorkBlock>()
    var curDur = 0.0
    var curHrWeighted = 0.0
    for (lap in ivSorted) {
        if (lap.lapType != null && LAP_ACTIVE_TYPES.contains(lap.lapType)) {
            val dur = lap.durationS ?: 0.0
            curDur += dur
            if (lap.avgHr != null) curHrWeighted += lap.avgHr!! * dur
        } else {
            if (curDur > 0.0) {
                blocks.add(WorkBlock(curDur, if (curDur > 0.0) curHrWeighted / curDur else null))
            }
            curDur = 0.0
            curHrWeighted = 0.0
        }
    }
    if (curDur > 0.0) {
        blocks.add(WorkBlock(curDur, if (curDur > 0.0) curHrWeighted / curDur else null))
    }
    return blocks
}

private data class QualityLap(val activityId: Long, val week: LocalDate, val durationS: Double, val avgHr: Double?)

/**
 * Порт detect_quality_work_laps() из build_report.py — единая детекция "качественных" рабочих
 * отрезков. Если Garmin сам типизировал лапы тренировки (>=2 typed work-лапа и >=1 typed rest-
 * лапа) — это ПРИОРИТЕТНЫЙ источник: подряд идущие typed work-лапы без rest между ними
 * склеиваются в непрерывные блоки (mergeContinuousWorkBlocks), без CV-эвристики. Иначе —
 * фолбэк: прежняя CV-эвристика по медиане темпа (лапы заметно быстрее медианы ЭТОЙ тренировки,
 * CV темпа >= 0.06, лапа быстрее медианы минимум на 6%).
 */
private fun detectQualityWorkLaps(
    activities: List<ActivityRow>,
    intervalsByActivity: Map<Long, List<IntervalRow>>,
    minDurS: Double = 90.0,
    maxDurS: Double = 1800.0
): List<QualityLap> {
    val out = mutableListOf<QualityLap>()
    val candidates = activities.filter { it.avgHr != null }
    for (act in candidates) {
        val week = mpkWeekStart(LocalDate.parse(act.date))
        val ivAll = (intervalsByActivity[act.activityId] ?: emptyList()).sortedBy { it.idx }
        val typedActive = ivAll.filter { it.lapType != null && LAP_ACTIVE_TYPES.contains(it.lapType) }
        val typedRest = ivAll.filter { it.lapType != null && LAP_REST_TYPES.contains(it.lapType) }

        if (typedActive.size >= 2 && typedRest.size >= 1) {
            for (block in mergeContinuousWorkBlocks(ivAll)) {
                if (block.durationS < minDurS || block.durationS > maxDurS) continue
                out.add(QualityLap(act.activityId, week, block.durationS, block.avgHr))
            }
            continue
        }

        val iv = ivAll.filter {
            it.avgHr != null && it.avgPaceSPerKm != null &&
                (it.distanceM ?: 0.0) >= 150.0 && (it.durationS ?: 0.0) >= 30.0 &&
                it.avgPaceSPerKm!! < 900.0
        }
        if (iv.size < 3) continue
        val paces = iv.map { it.avgPaceSPerKm!! }
        val sorted = paces.sorted()
        val medianPace = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        if (medianPace <= 0.0) continue
        val mean = paces.average()
        val std = if (paces.size < 2) 0.0 else
            kotlin.math.sqrt(paces.sumOf { (it - mean) * (it - mean) } / (paces.size - 1))
        val cv = std / medianPace
        if (cv < 0.06) continue
        for (lap in iv) {
            if (lap.avgPaceSPerKm!! > medianPace * 0.94) continue
            val dur = lap.durationS ?: 0.0
            if (dur < minDurS || dur > maxDurS) continue
            out.add(QualityLap(act.activityId, week, dur, lap.avgHr?.toDouble()))
        }
    }
    return out
}

/**
 * Порт weekly_mpk_threshold_minutes() из build_report.py — делит обнаруженные рабочие отрезки
 * (detectQualityWorkLaps) на МПК/VO2max (короткие быстрые повторы, <= mpkMaxS = 6 мин) и
 * пороговые (длинные непрерывные усилия, mpkMaxS < duration <= thresholdMaxS = 30 мин) ПО
 * ФИЗИОЛОГИЧЕСКОЙ ДЛИТЕЛЬНОСТИ, а не по типу тренировки. Возвращает по одной точке на каждую
 * неделю от первой до последней недели с рабочими отрезками (недели без них — 0/0), со
 * скользящим средним 4 недели (min_periods=1).
 */
fun weeklyMpkThresholdMinutes(
    activities: List<ActivityRow>,
    intervalsByActivity: Map<Long, List<IntervalRow>>,
    mpkMaxS: Double = 360.0,
    thresholdMaxS: Double = 1800.0
): List<WeeklyMpkThresholdPoint> {
    val laps = detectQualityWorkLaps(activities, intervalsByActivity, minDurS = 90.0, maxDurS = thresholdMaxS)
    if (laps.isEmpty()) return emptyList()

    val mpkByWeek = HashMap<LocalDate, Double>()
    val thrByWeek = HashMap<LocalDate, Double>()
    for (lap in laps) {
        val min = lap.durationS / 60.0
        if (lap.durationS <= mpkMaxS) {
            mpkByWeek[lap.week] = (mpkByWeek[lap.week] ?: 0.0) + min
        } else {
            thrByWeek[lap.week] = (thrByWeek[lap.week] ?: 0.0) + min
        }
    }

    val allWeekKeys = (mpkByWeek.keys + thrByWeek.keys)
    val firstWeek = allWeekKeys.min()
    val lastWeek = allWeekKeys.max()
    val allWeeks = mutableListOf<LocalDate>()
    var w = firstWeek
    while (!w.isAfter(lastWeek)) { allWeeks.add(w); w = w.plusWeeks(1) }

    val mpkMin = allWeeks.map { mpkByWeek[it] ?: 0.0 }
    val thresholdMin = allWeeks.map { thrByWeek[it] ?: 0.0 }

    fun rollingMean4(vals: List<Double>): List<Double> = vals.indices.map { i ->
        val lo = maxOf(0, i - 3)
        vals.subList(lo, i + 1).average()
    }
    val mpkRoll = rollingMean4(mpkMin)
    val thrRoll = rollingMean4(thresholdMin)

    return allWeeks.indices.map { i ->
        WeeklyMpkThresholdPoint(allWeeks[i], mpkMin[i], thresholdMin[i], mpkRoll[i], thrRoll[i])
    }
}
