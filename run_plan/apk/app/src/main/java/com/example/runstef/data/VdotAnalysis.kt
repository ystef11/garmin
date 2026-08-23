package com.example.runstef.data

import kotlin.math.exp

/** Приближённая формула Джека Дэниэлса для VDOT — порт daniels_vdot() из build_report.py. */
fun danielsVdot(distanceM: Double, timeS: Double): Double {
    val tMin = timeS / 60.0
    val v = distanceM / tMin // м/мин
    val pctMax = 0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)
    val vo2 = -4.60 + 0.182258 * v + 0.000104 * v * v
    return vo2 / pctMax
}

data class RaceBlowupInfo(
    val cleanDistanceM: Double,
    val cleanTimeS: Double,
    val blowupKm: Double,
    val totalKm: Double
)

/**
 * Порт detect_race_blowup() из build_report.py: ищет "срыв" темпа на гоночной дистанции, НЕ
 * объяснимый физиологическим утомлением (замедление БЕЗ соответствующего роста пульса —
 * подпись вынужденной остановки/перехода на шаг, а не "стены", при которой пульс обычно
 * держится высоким или растёт). Идём по км-сплитам (лапам с distanceM в диапазоне 500-2000м),
 * сравниваем каждый со скользящей медианой темпа/пульса по уже пройденной "чистой" части —
 * если темп проседает более чем в [slowdownRatio] раз от базового БЕЗ роста пульса больше
 * [hrRiseRequired] — это точка срыва. Возвращает null, если срыва не найдено, лапов < 15, или
 * чистый участок короче [minCleanKm].
 */
fun detectRaceBlowup(
    intervals: List<IntervalRow>,
    minCleanKm: Double = 15.0,
    slowdownRatio: Double = 1.20,
    hrRiseRequired: Double = 1.0
): RaceBlowupInfo? {
    val iv = intervals
        .filter { it.avgHr != null && it.avgPaceSPerKm != null && (it.distanceM ?: 0.0) > 500.0 && (it.distanceM ?: 0.0) < 2000.0 }
        .sortedBy { it.idx }
    if (iv.size < 15) return null

    val cumDist = DoubleArray(iv.size)
    val cumTime = DoubleArray(iv.size)
    var runningDist = 0.0
    var runningTime = 0.0
    for (i in iv.indices) {
        runningDist += iv[i].distanceM ?: 0.0
        runningTime += iv[i].durationS ?: 0.0
        cumDist[i] = runningDist
        cumTime[i] = runningTime
    }

    fun median(vals: List<Double>): Double {
        val s = vals.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    var blowupIdx: Int? = null
    for (i in 8 until iv.size) {
        val windowStart = max(0, i - 5)
        val basePaces = (windowStart until i).map { iv[it].avgPaceSPerKm!! }
        val baseHrs = (windowStart until i).map { iv[it].avgHr!!.toDouble() }
        val baselinePace = median(basePaces)
        val baselineHr = median(baseHrs)
        val thisPace = iv[i].avgPaceSPerKm!!
        val thisHr = iv[i].avgHr!!.toDouble()
        if (thisPace >= baselinePace * slowdownRatio && thisHr <= baselineHr + hrRiseRequired) {
            blowupIdx = i
            break
        }
    }
    val bi = blowupIdx ?: return null
    val cleanDist = cumDist[bi - 1]
    val cleanTime = cumTime[bi - 1]
    if (cleanDist < minCleanKm * 1000.0) return null

    return RaceBlowupInfo(
        cleanDistanceM = cleanDist,
        cleanTimeS = cleanTime,
        blowupKm = Math.round(cleanDist / 1000.0 * 10) / 10.0,
        totalKm = Math.round(cumDist.last() / 1000.0 * 10) / 10.0
    )
}

private fun max(a: Int, b: Int) = if (a > b) a else b

data class RaceVdotPoint(
    val date: String,
    val name: String,
    val distanceM: Double,
    val durationS: Double,
    val avgHr: Int,
    val vdotFull: Double,
    val blowupDetected: Boolean,
    val blowupKm: Double?,
    val vdotClean: Double?,
    val vdot: Double
)

/**
 * Порт race_vdot_points() из build_report.py: VDOT считается только по гонкам, где эффорт
 * подтверждён пульсом (не только названием/дистанцией) — порог % от ПАНО зависит от дистанции
 * (короче гонка — выше ожидаемый % от ПАНО: <=12км — 95%, <=25км — 90%, длиннее — 80%, марафон
 * физиологически бежится ниже порога на всей дистанции). Если найден "срыв" темпа без роста
 * пульса (см. [detectRaceBlowup]) — VDOT считается по чистому участку ДО срыва (полная
 * дистанция занижает истинную форму на момент гонки).
 *
 * [intervalsByActivity] — лапы гоночных активностей (обычно предзагруженные из БД только для
 * кандидатов — не для всех тренировок, иначе на телефоне это лишние сотни лапов-запросов).
 */
fun raceVdotPoints(
    activities: List<ActivityRow>,
    pano: Int,
    intervalsByActivity: Map<Long, List<IntervalRow>> = emptyMap()
): Pair<List<RaceVdotPoint>, Int> {
    val nameRe = Regex("race|марафон|marathon", RegexOption.IGNORE_CASE)
    val candidates = activities.filter { a ->
        (nameRe.containsMatchIn(a.name) || (a.distanceM ?: 0.0) >= 40000.0) &&
            (a.distanceM ?: 0.0) > 4000.0 && a.durationS != null && a.avgHr != null
    }

    fun minPctPano(distanceM: Double): Double = when {
        distanceM <= 12000.0 -> 0.95
        distanceM <= 25000.0 -> 0.90
        else -> 0.80
    }

    var excludedCount = 0
    val races = mutableListOf<RaceVdotPoint>()
    for (a in candidates.sortedBy { it.date }) {
        val dist = a.distanceM!!
        val dur = a.durationS!!
        val hr = a.avgHr!!
        val pctOfPano = hr.toDouble() / pano
        if (pctOfPano < minPctPano(dist)) {
            excludedCount++
            continue
        }
        val vdotFull = danielsVdot(dist, dur)
        var blowupDetected = false
        var blowupKm: Double? = null
        var vdotClean: Double? = null
        var vdot = vdotFull
        val laps = intervalsByActivity[a.activityId]
        if (laps != null) {
            val info = detectRaceBlowup(laps)
            if (info != null) {
                blowupDetected = true
                blowupKm = info.blowupKm
                vdotClean = danielsVdot(info.cleanDistanceM, info.cleanTimeS)
                vdot = vdotClean
            }
        }
        races.add(RaceVdotPoint(a.date, a.name, dist, dur, hr, vdotFull, blowupDetected, blowupKm, vdotClean, vdot))
    }
    return races to excludedCount
}

data class IntervalVdotPoint(
    val date: String,
    val name: String,
    val vdot: Double,
    val weight: Double,
    val nWorkLaps: Int,
    val pctOfPano: Double?
)

private fun median(vals: List<Double>): Double {
    val s = vals.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/** Выборочное стандартное отклонение (ddof=1, как pandas .std() по умолчанию) — null/0.0 для
 * списков короче 2 элементов (нет смысла/непосчитать). */
private fun sampleStd(vals: List<Double>): Double {
    if (vals.size < 2) return 0.0
    val mean = vals.average()
    val sumSq = vals.sumOf { (it - mean) * (it - mean) }
    return kotlin.math.sqrt(sumSq / (vals.size - 1))
}

/**
 * Порт interval_threshold_vdot_points() из build_report.py — вспомогательная (более шумная,
 * чем гоночная) оценка VDOT по РАБОЧИМ ОТРЕЗКАМ ЛЮБЫХ тренировок (не только размеченных как
 * interval/threshold — жёсткие вставки/пикапы попадаются и в "long"/"easy"/"mixed", см.
 * докстринг оригинала), заполняющая пробелы там, где гонок не было вовсе. См. комментарии по
 * шагам ниже — прямой перенос алгоритма и констант из десктопа, включая эмпирические веса по
 * длительности лапа и линейную поправку на % от ПАНО (обученную один раз по истории конкретного
 * пользователя, см. докстринг оригинала — те же коэффициенты).
 *
 * [intervalsByActivity] должен содержать лапы для ВСЕХ активностей с непустым avgHr (не только
 * гоночных кандидатов, в отличие от raceVdotPoints) — метод ищет рабочие отрезки везде.
 */
fun intervalThresholdVdotPoints(
    activities: List<ActivityRow>,
    intervalsByActivity: Map<Long, List<IntervalRow>>,
    pano: Int?,
    minConf: Double = 0.15,
    panoCorrA: Double = -44.841,
    panoCorrB: Double = 40.973,
    panoCorrClip: Double = 0.20
): List<IntervalVdotPoint> {
    val candidates = activities.filter { it.avgHr != null }
    val points = mutableListOf<IntervalVdotPoint>()

    for (act in candidates) {
        val allLaps = intervalsByActivity[act.activityId] ?: continue
        val iv = allLaps.filter {
            it.avgHr != null && it.avgPaceSPerKm != null &&
                (it.distanceM ?: 0.0) >= 150.0 && (it.durationS ?: 0.0) >= 30.0 &&
                it.avgPaceSPerKm < 900.0
        }
        if (iv.size < 3) continue

        val paces = iv.map { it.avgPaceSPerKm!! }
        val medianPace = median(paces)
        if (medianPace <= 0.0) continue
        val cv = sampleStd(paces) / medianPace
        if (cv < 0.06) continue

        val work = iv.filter { it.avgPaceSPerKm!! <= medianPace * 0.94 }
        if (work.isEmpty()) continue

        val rawVdots = mutableListOf<Double>()
        val weights = mutableListOf<Double>()
        val hrs = mutableListOf<Double>()
        for (lap in work) {
            val dur = lap.durationS ?: continue
            var vdot = danielsVdot(lap.distanceM ?: 0.0, dur)
            val w: Double
            when {
                dur < 90.0 -> w = 0.3
                dur < 150.0 -> w = 0.6
                dur <= 900.0 -> w = 1.0
                dur <= 1800.0 -> { w = 0.85; vdot *= 0.98 }
                else -> { w = 0.55; vdot *= 0.95 }
            }
            rawVdots.add(vdot)
            weights.add(w)
            hrs.add(lap.avgHr!!.toDouble())
        }
        if (rawVdots.isEmpty()) continue

        // Взвешенная медиана: сортируем по vdot, ищем точку, где кумулятивный вес переваливает
        // за половину суммарного веса (порт np.searchsorted(cw, cw[-1]/2.0)).
        val order = rawVdots.indices.sortedBy { rawVdots[it] }
        val sv = order.map { rawVdots[it] }
        val sw = order.map { weights[it] }
        val totalW = sw.sum()
        var cum = 0.0
        var medIdx = sv.size - 1
        for (i in sv.indices) {
            cum += sw[i]
            if (cum >= totalW / 2.0) { medIdx = i; break }
        }
        var vdotPoint = sv[medIdx]

        val spread = if (vdotPoint != 0.0) sampleStd(rawVdots) / vdotPoint else 1.0
        val consistencyPenalty = if (work.size > 1) maxOf(0.3, 1.0 - spread) else 0.6
        val nLapsPenalty = minOf(1.0, work.size / 3.0)
        val conf = weights.average() * consistencyPenalty * nLapsPenalty
        if (conf < minConf) continue

        var pctOfPano: Double? = null
        if (pano != null && pano > 0) {
            val avgHrWork = hrs.indices.sumOf { hrs[it] * weights[it] } / weights.sum()
            val pct = avgHrWork / pano
            pctOfPano = pct
            var correction = panoCorrA + panoCorrB * pct
            val clip = vdotPoint * panoCorrClip
            correction = correction.coerceIn(-clip, clip)
            vdotPoint -= correction
        }

        points.add(
            IntervalVdotPoint(
                date = act.date,
                name = act.name,
                vdot = vdotPoint,
                weight = minOf(conf, 1.0),
                nWorkLaps = work.size,
                pctOfPano = pctOfPano
            )
        )
    }

    if (points.isEmpty()) return points

    val med = median(points.map { it.vdot })
    val mad = median(points.map { kotlin.math.abs(it.vdot - med) })
    val filtered = if (mad > 0.0) points.filter { kotlin.math.abs(it.vdot - med) <= 3.5 * mad } else points

    return filtered.sortedBy { it.date }
}

data class Vo2MaxPoint(val date: String, val vo2MaxProxy: Double)

/**
 * Порт garmin_vo2max_proxy() из build_report.py — VO2max-прокси из истории ПАНО Garmin
 * (поля vo2max нет в выгрузке вообще, это приближение через формулу Дэниэлса при t=60 мин,
 * применённую к скорости на уровне ПАНО). ВАЖНО: сырые значения threshold_pace_s_per_km
 * физиологически правдоподобны только после деления на 10 (иначе получается ~40+ мин/км —
 * см. тот же комментарий в оригинале про происхождение этой странности в данных Garmin).
 */
fun garminVo2maxProxy(lt: List<LactateThresholdRow>): List<Vo2MaxPoint> {
    val tMin = 60.0
    val pctMax = 0.8 + 0.1894393 * Math.exp(-0.012778 * tMin) + 0.2989558 * Math.exp(-0.1932605 * tMin)
    return lt.filter { it.thresholdHr != null && it.thresholdPaceSPerKm != null }
        .mapNotNull { row ->
            val paceCorrected = row.thresholdPaceSPerKm!! / 10.0
            if (paceCorrected <= 0.0) return@mapNotNull null
            val velocity = 60000.0 / paceCorrected
            val vo2 = -4.60 + 0.182258 * velocity + 0.000104 * velocity * velocity
            Vo2MaxPoint(row.date, vo2 / pctMax)
        }
        .sortedBy { it.date }
}
