package com.example.runstef.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class VolumeEfBin(
    val rangeLowKmPerWeek: Double,
    val rangeHighKmPerWeek: Double,
    val meanVolumeKmPerWeek: Double,
    val meanDeltaEf: Double,
    val nWeeks: Int
)

data class VolumeEfResult(
    val ok: Boolean,
    val reason: String? = null,
    val nWeeksTotal: Int = 0,
    val bins: List<VolumeEfBin> = emptyList(),
    val bestBin: VolumeEfBin? = null,
    val declineThresholdKmPerWeek: Double? = null,
    val correlationR: Double? = null,
    val correlationPApprox: Double? = null
)

/** Приближение функции ошибок erf (Abramowitz & Stegun 7.1.26, точность ~1.5e-7) — в Kotlin/apk
 * нет math.erf, используется только для грубой p-value в pearsonrApprox ниже (не строгий тест). */
private fun erf(x: Double): Double {
    val sign = if (x < 0) -1.0 else 1.0
    val ax = abs(x)
    val a1 = 0.254829592; val a2 = -0.284496736; val a3 = 1.421413741
    val a4 = -1.453152027; val a5 = 1.061405429; val p = 0.3275911
    val t = 1.0 / (1.0 + p * ax)
    val y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * exp(-ax * ax)
    return sign * y
}

/** Порт pearsonr_approx() из build_report.py — коэффициент корреляции Пирсона + грубая
 * p-value (нормальное приближение t-статистики, БЕЗ поправки на автокорреляцию пересекающихся
 * недельных окон — ориентировочная оценка величины связи, не formal significance test). */
fun pearsonrApprox(x: List<Double>, y: List<Double>): Pair<Double?, Double?> {
    if (x.size < 3) return null to null
    val mx = x.average(); val my = y.average()
    val sx = sqrt(x.sumOf { (it - mx) * (it - mx) } / x.size)
    val sy = sqrt(y.sumOf { (it - my) * (it - my) } / y.size)
    if (sx < 1e-9 || sy < 1e-9) return null to null
    val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.size
    val r = cov / (sx * sy)
    val n = x.size
    val denom = maxOf(1e-9, 1 - r * r)
    val t = r * sqrt((n - 2) / denom)
    val pApprox = 2 * (1 - 0.5 * (1 + erf(abs(t) / sqrt(2.0))))
    return Math.round(r * 1000) / 1000.0 to Math.round(pApprox * 10000) / 10000.0
}

/** Линейная интерполяция значений известных только в части точек (индексы known), по аналогии
 * с np.interp — за пределами диапазона known клампится крайними значениями (constant extension),
 * как и numpy по умолчанию. */
private fun interpLinear(n: Int, known: List<Int>, knownValues: List<Double>): DoubleArray {
    val out = DoubleArray(n)
    if (known.isEmpty()) return out
    if (known.size == 1) { out.fill(knownValues[0]); return out }
    for (i in 0 until n) {
        if (i <= known.first()) { out[i] = knownValues.first(); continue }
        if (i >= known.last()) { out[i] = knownValues.last(); continue }
        var lo = 0
        while (lo + 1 < known.size && known[lo + 1] < i) lo++
        val x0 = known[lo]; val x1 = known[lo + 1]
        val y0 = knownValues[lo]; val y1 = knownValues[lo + 1]
        out[i] = if (x1 == x0) y0 else y0 + (y1 - y0) * (i - x0).toDouble() / (x1 - x0)
    }
    return out
}

/**
 * Порт analyze_volume_ef_response() из build_report.py — дозозависимость "объём бега -> будущее
 * изменение EF": скользящий объём (км/нед, ВСЕ беговые тренировки) за [rollingWeeks] недель vs
 * изменение сезонно-детрендированной EF лёгкого бега через [horizonWeeks] вперёд, недели бьются
 * на [nBins] квантильных корзин по объёму. Описательная оценка (не formal dose-response фит) —
 * недельные окна пересекаются, correlationPApprox не учитывает автокорреляцию.
 *
 * [easyEfPoints] — (дата, ef_seasadj) лёгких тренировок (уже сезонно скорректированный EF, см.
 * EfSeasonal.kt/AnalyticsReportBuilder — переиспользуем расчёт, не считаем сезонность заново).
 */
fun analyzeVolumeEfResponse(
    activities: List<ActivityRow>,
    easyEfPoints: List<Pair<LocalDate, Double>>,
    rollingWeeks: Int = 4,
    horizonWeeks: Int = 4,
    nBins: Int = 5,
    minPoints: Int = 20
): VolumeEfResult {
    if (easyEfPoints.size < minPoints) {
        return VolumeEfResult(ok = false, reason = "недостаточно EF-измерений лёгкого бега (${easyEfPoints.size}, нужно >= $minPoints)")
    }
    if (activities.isEmpty()) return VolumeEfResult(ok = false, reason = "нет тренировок")

    val day0 = activities.minOf { LocalDate.parse(it.date) }
    val day1 = activities.maxOf { LocalDate.parse(it.date) }
    val nDays = ChronoUnit.DAYS.between(day0, day1).toInt() + 1
    val nWeeks = nDays / 7
    if (nWeeks < 2 * (rollingWeeks + horizonWeeks)) {
        return VolumeEfResult(ok = false, reason = "недостаточно недель охвата ($nWeeks) для окна $rollingWeeks+$horizonWeeks нед.")
    }

    val dailyKm = DoubleArray(nDays)
    for (a in activities) {
        val d = ChronoUnit.DAYS.between(day0, LocalDate.parse(a.date)).toInt()
        if (d in 0 until nDays) dailyKm[d] += (a.distanceM ?: 0.0) / 1000.0
    }

    val efByWeekAcc = HashMap<Int, MutableList<Double>>()
    for ((date, ef) in easyEfPoints) {
        val idx = ChronoUnit.DAYS.between(day0, date).toInt()
        val w = idx / 7
        if (w in 0 until nWeeks) efByWeekAcc.getOrPut(w) { mutableListOf() }.add(ef)
    }
    val haveWeeks = efByWeekAcc.keys.sorted()
    if (haveWeeks.size < minPoints) {
        return VolumeEfResult(ok = false, reason = "недостаточно недель с EF-измерениями после недельной агрегации")
    }
    val haveValues = haveWeeks.map { efByWeekAcc[it]!!.average() }
    val efWeekInterp = interpLinear(nWeeks, haveWeeks, haveValues)

    val volRoll = DoubleArray(nWeeks)
    for (w in 0 until nWeeks) {
        val hi = (w + 1) * 7
        val lo = maxOf(0, hi - rollingWeeks * 7)
        var sum = 0.0
        for (d in lo until minOf(hi, nDays)) sum += dailyKm[d]
        volRoll[w] = sum / rollingWeeks
    }

    val validCount = nWeeks - horizonWeeks
    if (validCount <= 0) return VolumeEfResult(ok = false, reason = "недостаточно недель охвата для горизонта $horizonWeeks нед.")

    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (w in 0 until validCount) {
        val vol = volRoll[w]
        if (vol > 0.0) {
            xs.add(vol)
            ys.add(efWeekInterp[w + horizonWeeks] - efWeekInterp[w])
        }
    }
    if (xs.size < nBins * 3) {
        return VolumeEfResult(ok = false, reason = "недостаточно недель с валидным объёмом (${xs.size})")
    }

    val order = xs.indices.sortedBy { xs[it] }
    val xSorted = order.map { xs[it] }
    val ySorted = order.map { ys[it] }

    // np.array_split-подобная разбивка на nBins почти равных корзин (первые (n%nBins) корзин
    // получают на 1 элемент больше).
    val n = xSorted.size
    val baseSize = n / nBins
    val remainder = n % nBins
    val bins = mutableListOf<VolumeEfBin>()
    var pos = 0
    for (b in 0 until nBins) {
        val size = baseSize + if (b < remainder) 1 else 0
        if (size == 0) continue
        val xChunk = xSorted.subList(pos, pos + size)
        val yChunk = ySorted.subList(pos, pos + size)
        pos += size
        bins.add(
            VolumeEfBin(
                rangeLowKmPerWeek = Math.round(xChunk.min() * 100) / 100.0,
                rangeHighKmPerWeek = Math.round(xChunk.max() * 100) / 100.0,
                meanVolumeKmPerWeek = Math.round(xChunk.average() * 100) / 100.0,
                meanDeltaEf = Math.round(yChunk.average() * 10000) / 10000.0,
                nWeeks = size
            )
        )
    }
    val best = bins.maxByOrNull { it.meanDeltaEf }

    var declineThreshold: Double? = null
    for (i in bins.indices.reversed()) {
        if (bins[i].meanDeltaEf < 0 && (i until bins.size).all { bins[it].meanDeltaEf < 0 }) {
            declineThreshold = bins[i].rangeLowKmPerWeek
        } else break
    }

    val (r, p) = pearsonrApprox(xs, ys)

    return VolumeEfResult(
        ok = true,
        nWeeksTotal = nWeeks,
        bins = bins,
        bestBin = best,
        declineThresholdKmPerWeek = declineThreshold,
        correlationR = r,
        correlationPApprox = p
    )
}
