package com.example.runstef.data

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

data class SeasonalEfInfo(
    val applied: Boolean,
    val peakAroundDoy: Int? = null,
    val troughAroundDoy: Int? = null,
    val dropPct: Double? = null,
    val reason: String? = null
)

/** Решает X*coef = y методом наименьших квадратов через нормальные уравнения
 * (X^T X) coef = X^T y и гауссово исключение с выбором ведущего элемента по столбцу —
 * X here always 5 columns (см. seasonalDetrendEf), матрица маленькая, точности достаточно. */
private fun leastSquares5(X: List<DoubleArray>, y: DoubleArray): DoubleArray {
    val n = 5
    val xtx = Array(n) { DoubleArray(n) }
    val xty = DoubleArray(n)
    for (row in X.indices) {
        val xi = X[row]
        for (a in 0 until n) {
            xty[a] += xi[a] * y[row]
            for (b in 0 until n) xtx[a][b] += xi[a] * xi[b]
        }
    }
    // Гауссово исключение с частичным выбором ведущего элемента на augmented [xtx | xty].
    val aug = Array(n) { r -> DoubleArray(n + 1).also { for (c in 0 until n) it[c] = xtx[r][c]; it[n] = xty[r] } }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (kotlin.math.abs(aug[r][col]) > kotlin.math.abs(aug[pivot][col])) pivot = r
        val tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp
        val pv = aug[col][col]
        if (kotlin.math.abs(pv) < 1e-12) continue
        for (c in col until n + 1) aug[col][c] = aug[col][c] / pv
        for (r in 0 until n) {
            if (r == col) continue
            val factor = aug[r][col]
            if (factor == 0.0) continue
            for (c in col until n + 1) aug[r][c] -= factor * aug[col][c]
        }
    }
    return DoubleArray(n) { aug[it][n] }
}

private fun harmonicRow(doy: Double): DoubleArray {
    val w = 2.0 * PI * doy / 365.25
    return doubleArrayOf(1.0, sin(w), cos(w), sin(2 * w), cos(2 * w))
}

/**
 * Порт seasonal_detrend_ef()/add_seasonally_adjusted_ef() из build_report.py (гармоническая
 * регрессия log(EF) по дню года — годовой + полугодовой цикл, только по уличным тренировкам,
 * тредмильные точки возвращаются без изменения — они уже откалиброваны отдельно, см.
 * TreadmillCalibration.kt). Если охвата данных недостаточно (< minSpanDays или < minPoints
 * уличных точек) — коррекция не применяется, возвращает исходные values как есть.
 */
fun seasonalDetrendEf(
    dates: List<LocalDate>,
    values: List<Double>,
    isOutdoor: List<Boolean>,
    minSpanDays: Int = 300,
    minPoints: Int = 20
): Pair<List<Double>, SeasonalEfInfo> {
    val datesOut = dates.filterIndexed { i, _ -> isOutdoor[i] }
    val valuesOut = values.filterIndexed { i, _ -> isOutdoor[i] }
    val span = if (datesOut.isNotEmpty()) {
        java.time.temporal.ChronoUnit.DAYS.between(datesOut.min(), datesOut.max())
    } else 0L

    if (span < minSpanDays || valuesOut.size < minPoints) {
        return values to SeasonalEfInfo(
            applied = false,
            reason = "недостаточно охвата для годового цикла по уличным тренировкам " +
                "(span=${span}д, n_outdoor=${valuesOut.size}, нужно >= ${minSpanDays}д и >= $minPoints)"
        )
    }

    val xOut = datesOut.map { harmonicRow(it.dayOfYear.toDouble()) }
    val logv = DoubleArray(valuesOut.size) { ln(valuesOut[it]) }
    val coef = leastSquares5(xOut, logv)

    val adjusted = DoubleArray(values.size)
    for (i in values.indices) {
        if (!isOutdoor[i]) {
            adjusted[i] = values[i]
            continue
        }
        val row = harmonicRow(dates[i].dayOfYear.toDouble())
        var seasonalLog = 0.0
        for (k in 0 until 5) seasonalLog += row[k] * coef[k]
        seasonalLog -= coef[0]
        adjusted[i] = values[i] / exp(seasonalLog)
    }

    var maxCurve = Double.NEGATIVE_INFINITY
    var minCurve = Double.POSITIVE_INFINITY
    var peakDoy = 1
    var troughDoy = 1
    for (doy in 1..366) {
        val row = harmonicRow(doy.toDouble())
        var v = 0.0
        for (k in 0 until 5) v += row[k] * coef[k]
        v -= coef[0]
        if (v > maxCurve) { maxCurve = v; peakDoy = doy }
        if (v < minCurve) { minCurve = v; troughDoy = doy }
    }
    val dropPct = (exp(maxCurve) - exp(minCurve)) / exp(maxCurve) * 100.0

    return adjusted.toList() to SeasonalEfInfo(
        applied = true,
        peakAroundDoy = peakDoy,
        troughAroundDoy = troughDoy,
        dropPct = Math.round(dropPct * 10) / 10.0
    )
}
