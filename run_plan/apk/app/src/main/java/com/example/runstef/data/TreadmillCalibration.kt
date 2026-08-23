package com.example.runstef.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Порт fit_treadmill_pace_calibration()/apply_treadmill_calibration() из build_report.py —
 * см. header-комментарий там же про происхождение метода (лог-линейная регрессия EF~пульс,
 * уличные тренировки vs тредмил, в пересекающемся диапазоне пульса; посчитано напрямую по
 * БД, без внешнего calibration_profile.json).
 *
 * ЗДЕСЬ (в отличие от десктопа) калибровка применяется ТОЛЬКО к activities (distance_m/
 * avg_pace_s_per_km), НЕ к отдельным лапам/интервалам — apk пока не строит темп-по-зонам из
 * интервалов (см. TODO в памяти проекта), так что калибровка лапов пока не нужна.
 */
data class TreadmillCalibration(
    val applied: Boolean,
    val factor: Double? = null,
    val pctText: String? = null,
    val note: String
)

private const val TREADMILL_SPORT = "treadmill_running"

fun fitTreadmillPaceCalibration(
    activities: List<ActivityRow>,
    minN: Int = 30,
    clampLo: Double = 0.85,
    clampHi: Double = 1.35
): TreadmillCalibration {
    data class Pt(val hr: Double, val ef: Double)

    val pts = activities.mapNotNull { a ->
        val hr = a.avgHr?.toDouble()
        val dist = a.distanceM
        val dur = a.durationS
        if (hr == null || dist == null || dur == null) return@mapNotNull null
        if (dist <= 500.0 || dur <= 0.0) return@mapNotNull null
        if (a.typeGuess != "easy" && a.typeGuess != "long") return@mapNotNull null
        val speed = dist / dur
        val ef = speed / hr
        Triple(a.sport, hr, ef)
    }

    val outdoor = pts.filter { it.first != TREADMILL_SPORT }.map { Pt(it.second, it.third) }
    val treadmill = pts.filter { it.first == TREADMILL_SPORT }.map { Pt(it.second, it.third) }

    if (outdoor.size < minN || treadmill.size < minN) {
        return TreadmillCalibration(
            applied = false,
            note = "Калибровка дорожки не применена: недостаточно данных для сравнения " +
                "(уличных=${outdoor.size}, тредмильных=${treadmill.size}, нужно >= $minN каждой)."
        )
    }

    val lo = max(outdoor.minOf { it.hr }, treadmill.minOf { it.hr })
    val hi = min(outdoor.maxOf { it.hr }, treadmill.maxOf { it.hr })
    val outdoorM = outdoor.filter { it.hr in lo..hi }
    val treadmillM = treadmill.filter { it.hr in lo..hi }

    if (outdoorM.size < minN || treadmillM.size < minN || hi <= lo) {
        return TreadmillCalibration(
            applied = false,
            note = "Калибровка дорожки не применена: недостаточно точек в пересекающемся " +
                "диапазоне пульса ${"%.0f".format(lo)}-${"%.0f".format(hi)} " +
                "(уличных=${outdoorM.size}, тредмильных=${treadmillM.size}, нужно >= $minN каждой)."
        )
    }

    // Лог-линейная регрессия log(ef) ~ hr методом наименьших квадратов (порт np.polyfit(deg=1)).
    fun logEfFit(sub: List<Pt>): Pair<Double, Double> {
        val n = sub.size
        val meanX = sub.sumOf { it.hr } / n
        val meanY = sub.sumOf { ln(it.ef) } / n
        var num = 0.0
        var den = 0.0
        for (p in sub) {
            val dx = p.hr - meanX
            num += dx * (ln(p.ef) - meanY)
            den += dx * dx
        }
        val slope = if (den != 0.0) num / den else 0.0
        val intercept = meanY - slope * meanX
        return slope to intercept
    }

    val (slopeOut, interceptOut) = logEfFit(outdoorM)
    val (slopeTm, interceptTm) = logEfFit(treadmillM)

    val gridN = 50
    var sumLogRatio = 0.0
    for (i in 0 until gridN) {
        val hrGrid = lo + (hi - lo) * i / (gridN - 1)
        val logOut = slopeOut * hrGrid + interceptOut
        val logTm = slopeTm * hrGrid + interceptTm
        sumLogRatio += (logOut - logTm)
    }
    val rawRatio = exp(sumLogRatio / gridN)
    val factor = min(max(rawRatio, clampLo), clampHi)

    val pct = (factor - 1.0) * 100.0
    val direction = if (factor > 1) "лента занижает темп (реальная дистанция больше репортированной)"
        else "лента завышает темп (реальная дистанция меньше репортированной)"

    return TreadmillCalibration(
        applied = true,
        factor = factor,
        pctText = "${if (pct >= 0) "+" else ""}${"%.1f".format(pct)}%",
        note = "distance_m на тредмильных тренировках умножены на ${"%.3f".format(factor)} " +
            "(${if (factor >= 1) "+" else ""}${"%.1f".format(pct)}%), оценено по ${treadmillM.size} " +
            "тредмильным тренировкам с ЧСС в диапазоне ${"%.0f".format(lo)}-${"%.0f".format(hi)} " +
            "(сравнение с EF уличных пробежек того же диапазона ЧСС, лог-линейная регрессия " +
            "EF~пульс). $direction. Это оценка по прокси (EF), не прямое измерение."
    )
}

/** Применяет коэффициент из [tc] к distance_m/avg_pace_s_per_km тредмильных тренировок. */
fun applyTreadmillCalibration(activities: List<ActivityRow>, tc: TreadmillCalibration): List<ActivityRow> {
    val factor = tc.factor
    if (!tc.applied || factor == null) return activities
    return activities.map { a ->
        if (a.sport != TREADMILL_SPORT) return@map a
        val newDistance = a.distanceM?.let { it * factor }
        val newPace = a.avgPaceSPerKm?.let { it / factor }
        a.copy(distanceM = newDistance, avgPaceSPerKm = newPace)
    }
}

/**
 * Порт apply_grade_adjustment() из build_report.py (упрощённо, только на уровне activities —
 * apk не строит темп-по-зонам из интервалов, так что лапы не трогаем): там, где для тренировки
 * есть avgGapSPerKm (GAP, темп с поправкой на уклон — см. GarminActivitiesApi.fetchGradeAdjusted
 * PaceByLap), он подменяет собой avgPaceSPerKm ВЕЗДЕ дальше по отчёту (тренд темпа по неделям,
 * таблица тренировок и т.п.) — так десктопный отчёт с этого момента везде тоже работает с GAP,
 * а не с сырым темпом по GPS-дистанции. GAP есть только для уличных пробежек с заметным набором
 * высоты (см. importRange) — для остальных тренировок avgPaceSPerKm остаётся как было.
 * ВЫЗЫВАТЬ ПОСЛЕ applyTreadmillCalibration (как и в десктопе — калибровка дорожки, потом GAP).
 */
fun applyGradeAdjustment(activities: List<ActivityRow>): List<ActivityRow> =
    activities.map { a -> if (a.avgGapSPerKm != null) a.copy(avgPaceSPerKm = a.avgGapSPerKm) else a }
