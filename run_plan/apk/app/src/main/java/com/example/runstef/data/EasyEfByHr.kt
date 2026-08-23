package com.example.runstef.data

/**
 * Порт easy_ef_by_hr() из build_report.py (раздел 2 отчёта: "Эффективность лёгкого бега в
 * зависимости от пульса"). Бины — только для наглядности графика (среднее EF по бинам пульса
 * шириной 5 уд/мин, только бины с >=5 тренировками). "Пик" эффективности ищется квадратичной
 * аппроксимацией EF~HR на сезонно скорректированном EF (ef_seasadj) — устойчивее к шуму, чем
 * argmax по одному бину.
 *
 * Диапазон фита и нижние границы для проверки устойчивости не хардкодятся — считаются из
 * фактического распределения пульса лёгких пробежек этого атлета (перцентили 2/98) и, если
 * известно, из ПАНО (верхняя граница фита не выше околопороговой зоны: pano-15).
 *
 * Честная проверка устойчивости (как в оригинале): пробуем несколько нижних границ диапазона
 * фита (linspace между началом диапазона и медианой пульса, 5 точек). Если вершина параболы
 * пропадает (кривизна >=0) или гуляет более чем на +-10 уд/мин между вариантами - считаем, что
 * выраженного пика эффективности НЕТ, и в качестве прагматичного центра берём медиану пульса
 * лёгких пробежек, а не фиктивный "максимум".
 */
object EasyEfByHr {

    data class EfHrBin(val hrBin: Int, val meanEf: Double, val count: Int)

    data class EasyEfByHrResult(
        val bins: List<EfHrBin>,
        val peakCenter: Int,
        val peakFound: Boolean
    )

    /** [hrValues] и [efSeasadjValues] — параллельные списки (тот же порядок), пульс и сезонно
     * скорректированный EF лёгких пробежек (тот же набор, что и для EF-по-неделям). [pano] —
     * оценка ПАНО в уд/мин, если известна. */
    fun easyEfByHr(hrValues: List<Double>, efSeasadjValues: List<Double>, pano: Int?): EasyEfByHrResult {
        require(hrValues.size == efSeasadjValues.size)
        if (hrValues.isEmpty()) return EasyEfByHrResult(emptyList(), 0, false)

        val hrLo = percentile(hrValues, 0.02)
        val hrHiData = percentile(hrValues, 0.98)
        var hrHi = if (pano != null) minOf(hrHiData, pano - 15.0) else hrHiData
        if (hrHi <= hrLo) hrHi = hrHiData
        val fitLo = Math.round(hrLo).toInt()
        val fitHi = Math.round(hrHi).toInt()
        val hrMedian = median(hrValues)

        val binned = LinkedHashMap<Int, MutableList<Double>>()
        for (i in hrValues.indices) {
            val bin = (Math.floor(hrValues[i] / 5.0) * 5).toInt()
            binned.getOrPut(bin) { mutableListOf() }.add(efSeasadjValues[i])
        }
        val bins = binned.entries
            .filter { it.value.size >= 5 }
            .map { EfHrBin(it.key, it.value.average(), it.value.size) }
            .sortedBy { it.hrBin }

        val cutoffs = linspace(fitLo.toDouble(), hrMedian, 5).map { Math.round(it).toInt() }.toSortedSet().toList()
        val vertices = mutableListOf<Double>()
        for (loCut in cutoffs) {
            val hr = mutableListOf<Double>()
            val ef = mutableListOf<Double>()
            for (i in hrValues.indices) {
                if (hrValues[i] >= loCut && hrValues[i] <= fitHi) {
                    hr.add(hrValues[i])
                    ef.add(efSeasadjValues[i])
                }
            }
            if (hr.size < 20) continue
            val coef = polyfit2(hr, ef) ?: continue
            val a = coef[0]; val b = coef[1]
            if (a < 0) {
                val vertex = -b / (2 * a)
                if (vertex >= fitLo - 15 && vertex <= fitHi + 15) vertices.add(vertex)
            }
        }

        val peakFound = vertices.size >= 3 && (vertices.max() - vertices.min()) <= 10.0
        val peakCenter = if (peakFound) Math.round(median(vertices)).toInt() else Math.round(hrMedian).toInt()

        return EasyEfByHrResult(bins, peakCenter, peakFound)
    }

    private fun linspace(start: Double, end: Double, n: Int): List<Double> {
        if (n <= 1) return listOf(start)
        val step = (end - start) / (n - 1)
        return (0 until n).map { start + it * step }
    }

    private fun median(vals: List<Double>): Double {
        val s = vals.sorted()
        val n = s.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun percentile(vals: List<Double>, q: Double): Double {
        val s = vals.sorted()
        if (s.isEmpty()) return 0.0
        if (s.size == 1) return s[0]
        val pos = q * (s.size - 1)
        val lo = Math.floor(pos).toInt()
        val hi = Math.ceil(pos).toInt()
        if (lo == hi) return s[lo]
        val frac = pos - lo
        return s[lo] + (s[hi] - s[lo]) * frac
    }

    private fun polyfit2(x: List<Double>, y: List<Double>): DoubleArray? {
        val n = x.size.toDouble()
        var sx1 = 0.0; var sx2 = 0.0; var sx3 = 0.0; var sx4 = 0.0
        var sy0 = 0.0; var sy1 = 0.0; var sy2 = 0.0
        for (i in x.indices) {
            val xi = x[i]; val yi = y[i]
            val xi2 = xi * xi
            sx1 += xi; sx2 += xi2; sx3 += xi2 * xi; sx4 += xi2 * xi2
            sy0 += yi; sy1 += xi * yi; sy2 += xi2 * yi
        }
        val m = arrayOf(
            doubleArrayOf(n, sx1, sx2, sy0),
            doubleArrayOf(sx1, sx2, sx3, sy1),
            doubleArrayOf(sx2, sx3, sx4, sy2)
        )
        for (col in 0..2) {
            var pivotRow = col
            var best = Math.abs(m[col][col])
            for (r in col + 1..2) {
                if (Math.abs(m[r][col]) > best) { best = Math.abs(m[r][col]); pivotRow = r }
            }
            if (best < 1e-12) return null
            if (pivotRow != col) { val tmp = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmp }
            for (r in 0..2) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (c2 in col..3) m[r][c2] -= factor * m[col][c2]
            }
        }
        val c = m[0][3] / m[0][0]
        val b = m[1][3] / m[1][1]
        val a = m[2][3] / m[2][2]
        return doubleArrayOf(a, b, c)
    }
}
