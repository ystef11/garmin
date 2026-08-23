package com.example.runstef.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ZoneDef(val name: String, val lo: Int, val hi: Int)

/**
 * Порт build_zones() из build_report.py — Z1-Z5 по Карвонену (%HRR), ПАНО (pano) как верхняя
 * граница Z4 (измеренный Гарминым порог, не % от HRR — прямой физиологический показатель).
 * z1Hrr/z2Hrr/z3Hrr — те же стандартные пороги 50/60/70%, что и в десктопе.
 */
fun buildZones(rhr: Double, pano: Int, maxHr: Int, z1Hrr: Double = 0.50, z2Hrr: Double = 0.60, z3Hrr: Double = 0.70): List<ZoneDef> {
    val hrr = maxHr - rhr
    fun at(pct: Double) = Math.round(rhr + pct * hrr).toInt()

    val z1Lo = at(z1Hrr)
    val z2Lo = at(z2Hrr)
    val z3LoHrr = at(z3Hrr)
    val z1Hi = z2Lo - 1

    val z4Hi = pano
    val z2Hi = z3LoHrr - 1
    val z3Lo = z2Hi + 1
    val z4Lo = Math.round((z3Lo + z4Hi) / 2.0).toInt()
    val z3Hi = z4Lo - 1
    val z5Lo = pano + 1
    val z5Hi = maxHr

    return listOf(
        ZoneDef("Z1 — восстановление", z1Lo, z1Hi),
        ZoneDef("Z2 — лёгкий/аэробный", z2Lo, z2Hi),
        ZoneDef("Z3 — марафонский темп", z3Lo, z3Hi),
        ZoneDef("Z4 — пороговый (до ПАНО)", z4Lo, z4Hi),
        ZoneDef("Z5 — VO2max/выше ПАНО", z5Lo, z5Hi)
    )
}

private fun zoneOf(zones: List<ZoneDef>, hr: Int): String? = zones.firstOrNull { hr in it.lo..it.hi }?.name

/** Персентиль методом линейной интерполяции (как numpy.percentile по умолчанию, linear). */
private fun percentile(sorted: List<Double>, pct: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val rank = pct / 100.0 * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

data class RecentPaceZoneRow(val zone: ZoneDef, val p25: Double?, val p50: Double?, val p75: Double?, val n: Int)

/**
 * Порт recent_pace_by_zone() — темп по зонам ТОЛЬКО за последние [weeksBack] недель (актуальная
 * форма, не вся история), медиана + IQR (25-75 перц.). Нужно >= 5 сплитов в зоне, иначе p25/
 * p50/p75 = null (недостаточно данных).
 */
fun recentPaceByZone(
    intervalsByActivity: Map<Long, List<IntervalRow>>,
    zones: List<ZoneDef>,
    activities: List<ActivityRow>,
    weeksBack: Int = 8
): List<RecentPaceZoneRow> {
    if (activities.isEmpty()) return zones.map { RecentPaceZoneRow(it, null, null, null, 0) }
    val maxDate = activities.maxOf { LocalDate.parse(it.date) }
    val cutoff = maxDate.minusWeeks(weeksBack.toLong())
    val recentIds = activities.filter { LocalDate.parse(it.date) >= cutoff }.map { it.activityId }.toSet()

    val paceByZone = HashMap<String, MutableList<Double>>()
    for (id in recentIds) {
        val laps = intervalsByActivity[id] ?: continue
        for (lap in laps) {
            val hr = lap.avgHr ?: continue
            val pace = lap.avgPaceSPerKm ?: continue
            if ((lap.distanceM ?: 0.0) <= 150.0 || pace >= 900.0) continue
            val zoneName = zoneOf(zones, hr) ?: continue
            paceByZone.getOrPut(zoneName) { mutableListOf() }.add(pace)
        }
    }

    return zones.map { z ->
        val vals = paceByZone[z.name]?.sorted() ?: emptyList()
        if (vals.size >= 5) {
            RecentPaceZoneRow(z, percentile(vals, 25.0), percentile(vals, 50.0), percentile(vals, 75.0), vals.size)
        } else {
            RecentPaceZoneRow(z, null, null, null, vals.size)
        }
    }
}

data class QuarterZonePace(val quarterStart: LocalDate, val zoneName: String, val paceSPerKm: Double, val n: Int)

/**
 * Порт pace_by_zone_quarterly() — темп по зонам ПО КВАРТАЛАМ (не одно число за всю историю —
 * форма меняется за 2 года, усреднять всё в одно значение некорректно). Средний темп в зоне за
 * квартал = суммарное время / суммарная дистанция (взвешенное по дистанции среднее ГАРМОНИЧЕСКОЕ
 * скорости, НЕ обратное от среднего арифметического скорости — та версия систематически
 * занижала бы темп, см. докстринг оригинала). Нужно >= 8 сплитов в зоне за квартал.
 */
fun paceByZoneQuarterly(
    intervalsByActivity: Map<Long, List<IntervalRow>>,
    zones: List<ZoneDef>,
    activities: List<ActivityRow>
): List<QuarterZonePace> {
    fun quarterStart(d: LocalDate): LocalDate {
        val qMonth = ((d.monthValue - 1) / 3) * 3 + 1
        return LocalDate.of(d.year, qMonth, 1)
    }

    val quarterByActivity = activities.associate { it.activityId to quarterStart(LocalDate.parse(it.date)) }

    // (quarter, zone) -> список (durationS, distanceM)
    val acc = HashMap<Pair<LocalDate, String>, MutableList<Pair<Double, Double>>>()
    for ((activityId, quarter) in quarterByActivity) {
        val laps = intervalsByActivity[activityId] ?: continue
        for (lap in laps) {
            val hr = lap.avgHr ?: continue
            val pace = lap.avgPaceSPerKm ?: continue
            val dist = lap.distanceM ?: continue
            val dur = lap.durationS ?: continue
            if (dist <= 150.0 || pace >= 900.0) continue
            val zoneName = zoneOf(zones, hr) ?: continue
            acc.getOrPut(quarter to zoneName) { mutableListOf() }.add(dur to dist)
        }
    }

    val out = mutableListOf<QuarterZonePace>()
    for ((key, list) in acc) {
        if (list.size < 8) continue
        val totalDur = list.sumOf { it.first }
        val totalDist = list.sumOf { it.second } / 1000.0
        if (totalDist <= 0.0) continue
        out.add(QuarterZonePace(key.first, key.second, totalDur / totalDist, list.size))
    }
    return out.sortedWith(compareBy({ it.quarterStart }, { it.zoneName }))
}
