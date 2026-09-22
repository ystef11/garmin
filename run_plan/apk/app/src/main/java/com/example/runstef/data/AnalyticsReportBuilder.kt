package com.example.runstef.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Упрощённый Kotlin-аналог build_report.py — строит самодостаточный HTML-отчёт (инлайн SVG-
 * графики, без JS/сторонних библиотек и без сети) прямо из того, что накопил AnalyticsDb.
 *
 * ЭТО НЕ полная замена десктопного отчёта: там пульсовые зоны по индивидуальной физиологии,
 * калибровка дорожки/сезонности, VDOT по гонкам/интервалам, лапы и т.д. (см. plan_uploader_gui.py
 * → вкладка «Аналитика» → «Собрать и открыть отчёт», запускает build_report.py на компьютере).
 * Здесь — быстрый взгляд прямо с телефона: недельный объём, темп лёгких/длинных тренировок,
 * ACWR (соотношение острой/хронической нагрузки по километражу), тренд RHR/HRV, таблица
 * последних тренировок.
 */
object AnalyticsReportBuilder {

    private val RU_MONTHS_SHORT = listOf(
        "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"
    )

    private fun fmtShort(d: LocalDate) = "${d.dayOfMonth} ${RU_MONTHS_SHORT[d.monthValue - 1]}"

    private fun weekStart(d: LocalDate): LocalDate {
        var x = d
        while (x.dayOfWeek != DayOfWeek.MONDAY) x = x.minusDays(1)
        return x
    }

    private fun fmtPace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0) return "—"
        val s = secPerKm.roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun fmtHours(h: Double): String {
        val totalMin = (h * 60).roundToInt()
        return "${totalMin / 60}ч ${totalMin % 60}м"
    }

    // ---- инлайн SVG: line/bar-графики без сторонних библиотек ----

    private fun svgLine(
        points: List<Double?>,
        labels: List<String>,
        color: String,
        height: Int = 200,
        widthPerPoint: Int = 34,
        yFormat: (Double) -> String = { it.roundToInt().toString() }
    ): String {
        val padL = 42; val padR = 14; val padT = 12; val padB = 26
        val n = max(points.size, 1)
        val w = padL + padR + widthPerPoint * n
        val plotW = w - padL - padR
        val plotH = height - padT - padB
        val known = points.filterNotNull()
        if (known.isEmpty()) return "<div class='muted'>Недостаточно данных.</div>"
        val loRaw = known.min(); val hiRaw = known.max()
        val lo = if (loRaw == hiRaw) loRaw - 1 else loRaw
        val hi = if (loRaw == hiRaw) hiRaw + 1 else hiRaw
        fun x(i: Int) = padL + if (n > 1) plotW * i.toDouble() / (n - 1) else plotW / 2.0
        fun y(v: Double) = padT + plotH - (v - lo) / (hi - lo) * plotH

        val sb = StringBuilder()
        sb.append("<svg viewBox='0 0 $w $height' width='$w' height='$height' style='display:block'>")
        // сетка/подписи по Y (3 линии)
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val v = lo + (hi - lo) * frac
            val yy = y(v)
            sb.append("<line x1='$padL' y1='${"%.1f".format(Locale.ROOT, yy)}' x2='${w - padR}' y2='${"%.1f".format(Locale.ROOT, yy)}' stroke='#e5e5e5' stroke-width='1'/>")
            sb.append("<text x='4' y='${"%.1f".format(Locale.ROOT, yy + 4)}' font-size='11' fill='#888'>${yFormat(v)}</text>")
        }
        // линия
        val path = points.mapIndexedNotNull { i, v -> if (v == null) null else "${"%.1f".format(Locale.ROOT, x(i))},${"%.1f".format(Locale.ROOT, y(v))}" }
        if (path.size >= 2) {
            sb.append("<polyline fill='none' stroke='$color' stroke-width='2.5' points='${path.joinToString(" ")}'/>")
        }
        points.forEachIndexed { i, v ->
            if (v != null) sb.append("<circle cx='${"%.1f".format(Locale.ROOT, x(i))}' cy='${"%.1f".format(Locale.ROOT, y(v))}' r='3' fill='$color'/>")
        }
        // подписи X (каждая N-я, чтобы не налезали)
        val step = max(1, n / 10)
        labels.forEachIndexed { i, lbl ->
            if (i % step == 0) {
                sb.append("<text x='${"%.1f".format(Locale.ROOT, x(i))}' y='${height - 6}' font-size='10' fill='#888' text-anchor='middle'>$lbl</text>")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun svgBars(
        points: List<Double>,
        labels: List<String>,
        color: String,
        height: Int = 200,
        widthPerPoint: Int = 26
    ): String {
        val padL = 42; val padR = 14; val padT = 12; val padB = 26
        val n = max(points.size, 1)
        val w = padL + padR + widthPerPoint * n
        val plotW = w - padL - padR
        val plotH = height - padT - padB
        val hi = max(points.maxOrNull() ?: 1.0, 1.0)
        val barW = plotW / n.toDouble() * 0.7
        fun x(i: Int) = padL + plotW * i.toDouble() / n
        fun y(v: Double) = padT + plotH - (v / hi) * plotH

        val sb = StringBuilder()
        sb.append("<svg viewBox='0 0 $w $height' width='$w' height='$height' style='display:block'>")
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val v = hi * frac
            val yy = y(v)
            sb.append("<line x1='$padL' y1='${"%.1f".format(Locale.ROOT, yy)}' x2='${w - padR}' y2='${"%.1f".format(Locale.ROOT, yy)}' stroke='#e5e5e5' stroke-width='1'/>")
            sb.append("<text x='2' y='${"%.1f".format(Locale.ROOT, yy + 4)}' font-size='11' fill='#888'>${v.roundToInt()}</text>")
        }
        points.forEachIndexed { i, v ->
            val bx = x(i) + (plotW / n.toDouble() - barW) / 2
            val by = y(v)
            sb.append("<rect x='${"%.1f".format(Locale.ROOT, bx)}' y='${"%.1f".format(Locale.ROOT, by)}' width='${"%.1f".format(Locale.ROOT, barW)}' height='${"%.1f".format(Locale.ROOT, padT + plotH - by)}' fill='$color' rx='2'/>")
        }
        val step = max(1, n / 10)
        labels.forEachIndexed { i, lbl ->
            if (i % step == 0) {
                sb.append("<text x='${"%.1f".format(Locale.ROOT, x(i) + plotW / n.toDouble() / 2)}' y='${height - 6}' font-size='10' fill='#888' text-anchor='middle'>$lbl</text>")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Порт plot_weekly_type_shares() из build_report.py — 100%-стековая столбчатая диаграмма
     * долей времени тренировок по типам за неделю (легенда сверху, каждый бар суммируется до
     * 100%). [series] — упорядоченный список (метка, цвет, значения % по неделям, тот же
     * порядок недель что и [labels]). Значения одной недели по всем сериям в сумме дают 100
     * (или 0, если на неделе вообще не было тренировок — тогда столбец просто пустой). */
    private fun svgStackedBars100(
        series: List<Triple<String, String, List<Double>>>,
        labels: List<String>,
        height: Int = 220,
        widthPerPoint: Int = 26
    ): String {
        val n = max(labels.size, 1)
        val padL = 34; val padR = 14; val padT = 34; val padB = 26
        val w = padL + padR + widthPerPoint * n
        val plotW = w - padL - padR
        val plotH = height - padT - padB
        fun x(i: Int) = padL + plotW * i.toDouble() / n
        val barW = plotW / n.toDouble() * 0.7

        val sb = StringBuilder()
        sb.append("<svg viewBox='0 0 $w $height' width='$w' height='$height' style='display:block'>")
        // легенда сверху (перенос по 3 в ряд, чтобы не вылезала за пределы SVG на узких графиках)
        series.forEachIndexed { si, (label, color, _) ->
            val row = si / 3; val col = si % 3
            val lx = padL + col * 130
            val ly = 12 + row * 14
            sb.append("<rect x='$lx' y='${ly - 8}' width='9' height='9' fill='$color'/>")
            sb.append("<text x='${lx + 12}' y='$ly' font-size='10' fill='#555'>${esc(label)}</text>")
        }
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val yy = padT + plotH - plotH * frac
            sb.append("<line x1='$padL' y1='${"%.1f".format(Locale.ROOT, yy)}' x2='${w - padR}' y2='${"%.1f".format(Locale.ROOT, yy)}' stroke='#e5e5e5' stroke-width='1'/>")
            sb.append("<text x='2' y='${"%.1f".format(Locale.ROOT, yy + 4)}' font-size='11' fill='#888'>${(frac * 100).roundToInt()}</text>")
        }
        for (i in 0 until n) {
            var acc = 0.0
            val bx = x(i) + (plotW / n.toDouble() - barW) / 2
            for ((_, color, values) in series) {
                val v = values.getOrNull(i) ?: 0.0
                if (v <= 0.0) continue
                val yTop = padT + plotH - (acc + v) / 100.0 * plotH
                val segH = v / 100.0 * plotH
                sb.append("<rect x='${"%.1f".format(Locale.ROOT, bx)}' y='${"%.1f".format(Locale.ROOT, yTop)}' width='${"%.1f".format(Locale.ROOT, barW)}' height='${"%.1f".format(Locale.ROOT, segH)}' fill='$color'/>")
                acc += v
            }
        }
        val step = max(1, n / 10)
        labels.forEachIndexed { i, lbl ->
            if (i % step == 0) {
                sb.append("<text x='${"%.1f".format(Locale.ROOT, x(i) + plotW / n.toDouble() / 2)}' y='${height - 6}' font-size='10' fill='#888' text-anchor='middle'>$lbl</text>")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Мультилинейный график (2-3 серии на одном полотне, общая ось Y) с легендой сверху —
     * порт двух `ax2.plot(...)` в plot_weekly_quality_frequency() (МПК/порог по неделям). Не
     * пытается делать настоящую двойную ось Y, как в десктопе (ax1/ax2 разного масштаба) —
     * упрощение: одна общая ось для минут качественной работы, достаточно для наглядности на
     * телефоне. [series] — (метка, цвет, значения по неделям, тот же порядок что и [labels]). */
    private fun svgMultiLine(
        series: List<Triple<String, String, List<Double?>>>,
        labels: List<String>,
        height: Int = 200,
        widthPerPoint: Int = 26
    ): String {
        val padL = 42; val padR = 14; val padT = 30; val padB = 26
        val n = max(labels.size, 1)
        val w = padL + padR + widthPerPoint * n
        val plotW = w - padL - padR
        val plotH = height - padT - padB
        val known = series.flatMap { it.third }.filterNotNull()
        if (known.isEmpty()) return "<div class='muted'>Недостаточно данных.</div>"
        val loRaw = known.min(); val hiRaw = known.max()
        val lo = minOf(0.0, loRaw)
        val hi = if (hiRaw <= lo) lo + 1.0 else hiRaw
        fun x(i: Int) = padL + if (n > 1) plotW * i.toDouble() / (n - 1) else plotW / 2.0
        fun y(v: Double) = padT + plotH - (v - lo) / (hi - lo) * plotH

        val sb = StringBuilder()
        sb.append("<svg viewBox='0 0 $w $height' width='$w' height='$height' style='display:block'>")
        series.forEachIndexed { si, (label, color, _) ->
            val row = si / 2; val col = si % 2
            val lx = padL + col * 180
            val ly = 12 + row * 14
            sb.append("<rect x='$lx' y='${ly - 8}' width='9' height='9' fill='$color'/>")
            sb.append("<text x='${lx + 12}' y='$ly' font-size='10' fill='#555'>${esc(label)}</text>")
        }
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val v = lo + (hi - lo) * frac
            val yy = y(v)
            sb.append("<line x1='$padL' y1='${"%.1f".format(Locale.ROOT, yy)}' x2='${w - padR}' y2='${"%.1f".format(Locale.ROOT, yy)}' stroke='#e5e5e5' stroke-width='1'/>")
            sb.append("<text x='4' y='${"%.1f".format(Locale.ROOT, yy + 4)}' font-size='11' fill='#888'>${v.roundToInt()}</text>")
        }
        for ((_, color, values) in series) {
            val path = values.mapIndexedNotNull { i, v -> if (v == null) null else "${"%.1f".format(Locale.ROOT, x(i))},${"%.1f".format(Locale.ROOT, y(v))}" }
            if (path.size >= 2) {
                sb.append("<polyline fill='none' stroke='$color' stroke-width='2' points='${path.joinToString(" ")}'/>")
            }
        }
        val step = max(1, n / 10)
        labels.forEachIndexed { i, lbl ->
            if (i % step == 0) {
                sb.append("<text x='${"%.1f".format(Locale.ROOT, x(i))}' y='${height - 6}' font-size='10' fill='#888' text-anchor='middle'>$lbl</text>")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** [hrZones] — Triple(z2Hi, z4Lo, pano) в уд/мин, порт build_zones_for_classifier() из
     * build_report.py (см. GarminActivitiesApi.buildZonesForClassifier/estimateRealHrZones) —
     * null, если в базе ещё нет истории ПАНО (lactate_threshold), тогда раздел зон не рисуется. */
    fun build(
        accountLabel: String,
        activities: List<ActivityRow>,
        wellness: List<WellnessRow>,
        hrZones: Triple<Int, Int, Int>? = null,
        crossActivities: List<CrossActivityRow> = emptyList(),
        raceIntervalsByActivity: Map<Long, List<IntervalRow>> = emptyMap(),
        lactateThresholdHistory: List<LactateThresholdRow> = emptyList(),
        rhrMaxHr: Pair<Double, Int>? = null
    ): String {
        val tc = fitTreadmillPaceCalibration(activities)
        val calibrated = applyTreadmillCalibration(activities, tc)
        // GAP (grade-adjusted pace) — ПОСЛЕ калибровки дорожки, как и в десктопном build_report.py
        // (см. applyGradeAdjustment в TreadmillCalibration.kt): подменяет avgPaceSPerKm там, где
        // есть GAP, всеми дальнейшими расчётами (тренд темпа, таблица тренировок).
        val acts = applyGradeAdjustment(calibrated).sortedBy { it.date }

        // ИСПРАВЛЕНО 2026-08-24: та же калибровка дорожки + GAP теперь применяется и к лапам
        // (raceIntervalsByActivity), а не только к activities — иначе разделы, которые считают
        // прямо по лапам (темп по зонам, детекция рабочих отрезков, срыв темпа на гонке, VDOT по
        // интервалам), работали на "сыром" темпе без поправки на уклон/дорожку, в отличие от
        // десктопа, где apply_grade_adjustment() централизованно накрывает и activities, и
        // intervals ДО любого дальнейшего анализа. Тот же порядок: сначала дорожка, потом GAP.
        val treadmillActivityIds = activities.filter { it.sport == "treadmill_running" }.map { it.activityId }.toSet()
        val raceIntervalsByActivity = applyGradeAdjustmentToIntervals(
            applyTreadmillCalibrationToIntervals(raceIntervalsByActivity, treadmillActivityIds, tc)
        )
        val html = StringBuilder()
        html.append(HTML_HEAD)
        html.append("<h1>Аналитика тренировок</h1>")
        html.append("<div class='muted'>Аккаунт: ${esc(accountLabel)}. Собрано на телефоне — упрощённая версия отчёта " +
            "(см. desktop-версию build_report.py для полного анализа: VDOT, EF-анализ, дрейф беговой динамики).</div>")
        if (hrZones != null) {
            val (z2Hi, z4Lo, pano) = hrZones
            html.append(
                section(
                    "Пульсовые зоны (по истории ПАНО из Garmin)",
                    "<div style='font-size:15px;line-height:1.7'>" +
                        "Верх лёгкой/аэробной зоны (Z2): <b>$z2Hi</b> уд/мин<br>" +
                        "Низ порогового усилия (Z4): <b>$z4Lo</b> уд/мин<br>" +
                        "ПАНО (верх Z4, по последним данным Garmin): <b>$pano</b> уд/мин" +
                        "</div>"
                )
            )
        }

        // --- частота "качественных" рабочих недель (порт weekly_quality_training_frequency) ---
        if (raceIntervalsByActivity.isNotEmpty()) {
            val qWeekly = weeklyQualityTrainingFrequency(acts, raceIntervalsByActivity)
            if (qWeekly.isNotEmpty()) {
                html.append(
                    section(
                        "Рабочие отрезки по неделям (скользящее среднее, 4 нед.) — сколько тренировок содержали заметно более быстрые вставки",
                        svgLine(qWeekly.map { it.nWorkLapsRoll }, qWeekly.map { fmtShort(it.weekStart) }, "#D67D2C", yFormat = { "%.1f".format(Locale.ROOT, it) })
                    )
                )
            }

            // --- МПК/пороговая работа по неделям (порт weekly_mpk_threshold_minutes) — та же
            // детекция рабочих отрезков (detectQualityWorkLaps), но по ФИЗИОЛОГИЧЕСКОЙ
            // длительности блока делится на короткие МПК/VO2max-повторы (<=6 мин) и длинные
            // пороговые усилия (6-30 мин), а не по типу тренировки. См. build_report.py: доля
            // МПК может проседать на фоне в целом нормального объёма — это отдельный, более
            // тонкий сигнал, чем просто "была/не была рабочая неделя" выше. ---
            val mpkThr = weeklyMpkThresholdMinutes(acts, raceIntervalsByActivity)
            if (mpkThr.isNotEmpty()) {
                html.append(
                    section(
                        "МПК/пороговая работа по неделям, мин (скользящее среднее, 4 нед.)",
                        svgMultiLine(
                            listOf(
                                Triple("МПК/VO2max (\u2264 6 мин)", "#E0574C", mpkThr.map { it.mpkMinRoll }),
                                Triple("Порог (6\u201330 мин)", "#E0A62C", mpkThr.map { it.thresholdMinRoll })
                            ),
                            mpkThr.map { fmtShort(it.weekStart) }
                        )
                    )
                )
            }
        }

        // --- устойчивость формы к нагрузке (порт easy_hr_fitness_response) ---
        if (hrZones != null && lactateThresholdHistory.isNotEmpty()) {
            val hrFitness = easyHrFitnessResponse(acts, lactateThresholdHistory, hrZones.third)
            if (hrFitness != null) {
                val (quarters, info) = hrFitness
                val corrText = info.corr?.let { "Корреляция %ПАНО лёгких тренировок ↔ изменение порогового темпа в следующем квартале: r=${"%.2f".format(Locale.ROOT, it)}." }
                    ?: "Корреляцию посчитать не удалось (недостаточно разброса)."
                val ceilingText = info.respCeilingHr?.let {
                    " Ориентировочный потолок пульса лёгких тренировок, после которого форма обычно ещё росла: ~$it уд/мин (~${"%.0f".format(Locale.ROOT, info.respCeilingPct)}% от ПАНО)."
                } ?: ""
                html.append(
                    section(
                        "Устойчивость формы к нагрузке лёгких тренировок (по кварталам; ${info.nImproved} улучшений / ${info.nWorsened} без улучшения из ${info.nQuartersValid})",
                        buildString {
                            append("<div style='font-size:14px;line-height:1.6'>$corrText$ceilingText</div>")
                            append("<div style='overflow-x:auto;margin-top:8px'><table><tr><th>Квартал</th><th>Пульс лёгких (взвеш.)</th>" +
                                "<th>% от ПАНО</th><th>Порог. темп</th><th>Порог. темп след. кв.</th><th>Δ</th></tr>")
                            for (q in quarters) {
                                append("<tr>")
                                append("<td>${q.quarterStart.year} Q${(q.quarterStart.monthValue - 1) / 3 + 1}</td>")
                                append("<td>${"%.0f".format(Locale.ROOT, q.weightedHr)}</td>")
                                append("<td>${"%.0f".format(Locale.ROOT, q.pctPano)}%</td>")
                                append("<td>${fmtPace(q.thrPace)}</td>")
                                append("<td>${q.thrPaceNext?.let { fmtPace(it) } ?: "—"}</td>")
                                append("<td>${q.deltaNext?.let { "%+.0f".format(Locale.ROOT, it) + " с/км" } ?: "—"}</td>")
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }
        }

        val vo2Points = garminVo2maxProxy(lactateThresholdHistory)
        if (vo2Points.isNotEmpty()) {
            html.append(
                section(
                    "VO2max-прокси по истории ПАНО Garmin (поля vo2max нет в выгрузке — это оценка)",
                    svgLine(
                        vo2Points.map { it.vo2MaxProxy },
                        vo2Points.map { fmtShort(LocalDate.parse(it.date.take(10))) },
                        "#8C2A22",
                        yFormat = { "%.1f".format(Locale.ROOT, it) }
                    )
                )
            )
        }

        if (crossActivities.isNotEmpty()) {
            // Порт идеи из build_report.py: кросс-тренировки учитываются только как суммарная
            // нагрузка (часы/кол-во по группам cycling/skiing/swimming/strength_training), не по
            // отдельным осям/зонам — этого достаточно, чтобы не терять контекст общей нагрузки.
            val byGroup = crossActivities.groupBy { it.sport }
            val rows = byGroup.entries.sortedByDescending { it.value.sumOf { r -> r.durationS ?: 0.0 } }
                .joinToString("<br>") { (group, list) ->
                    val hours = list.sumOf { (it.durationS ?: 0.0) / 3600.0 }
                    "${crossGroupLabel(group)}: ${list.size} трен., ${"%.1f".format(Locale.ROOT, hours)} ч"
                }
            html.append(section("Кросс-тренировки (суммарная нагрузка)", "<div style='font-size:15px;line-height:1.7'>$rows</div>"))
        }

        if (tc.applied) {
            html.append(section("Калибровка темпа дорожки", "<div style='font-size:14px;line-height:1.6'>${esc(tc.note)}</div>"))
        }

        val gapCount = activities.count { it.avgGapSPerKm != null }
        if (gapCount > 0) {
            html.append(
                section(
                    "Коррекция темпа на уклон (GAP)",
                    "<div style='font-size:14px;line-height:1.6'>Для $gapCount уличных тренировок с заметным " +
                        "набором высоты темп ниже (тренд по неделям, таблица тренировок) посчитан с поправкой на " +
                        "уклон (grade-adjusted pace, GAP) вместо сырого темпа по GPS-дистанции — как в разделе " +
                        "«Коррекция на уклон» build_report.py.</div>"
                )
            )
        }

        if (acts.isEmpty()) {
            html.append("<p class='muted' style='margin-top:24px'>В базе пока нет тренировок за выбранный период — сначала нажми «Импортировать тренировки».</p>")
            html.append(HTML_TAIL)
            return html.toString()
        }

        val totalKm = acts.sumOf { (it.distanceM ?: 0.0) / 1000.0 }
        val totalHours = acts.sumOf { (it.durationS ?: 0.0) / 3600.0 }
        // "quality" — legacy-метка ещё не переклассифицированных тренировок (сохранённых до
        // этого перехода на детальные категории) и грубого фолбэк-классификатора (когда лапов
        // вообще не было) — считаем её вместе с mixed, т.к. смысл тот же: "не подошло уверенно
        // ни под один профиль, но и не лёгкая/длинная".
        val easyCount = acts.count { it.typeGuess == "easy" }
        val longCount = acts.count { it.typeGuess == "long" }
        val intervalCount = acts.count { it.typeGuess == "interval" }
        val thresholdCount = acts.count { it.typeGuess == "threshold" }
        val mixedCount = acts.count { it.typeGuess == "mixed" || it.typeGuess == "quality" }

        html.append("<div class='cards'>")
        html.append(card("Тренировок", acts.size.toString()))
        html.append(card("Дистанция", "%.0f км".format(Locale.ROOT, totalKm)))
        html.append(card("Время", fmtHours(totalHours)))
        html.append(card(
            "Лёгкие / интервалы / порог / смеш. / длинные",
            "$easyCount / $intervalCount / $thresholdCount / $mixedCount / $longCount"
        ))
        html.append("</div>")

        // --- недельный объём + доля качественных ---
        val byWeek = acts.groupBy { weekStart(LocalDate.parse(it.date)) }.toSortedMap()
        val weekKeys = byWeek.keys.toList()
        val weekVol = weekKeys.map { wk -> byWeek[wk]!!.sumOf { (it.distanceM ?: 0.0) / 1000.0 } }
        val weekLabels = weekKeys.map { fmtShort(it) }

        html.append(section("Недельный объём, км", svgBars(weekVol, weekLabels, "#3B7DD8")))

        // --- темп лёгких/длинных тренировок по неделям ---
        val weekPace = weekKeys.map { wk ->
            val ps = byWeek[wk]!!.filter { (it.typeGuess == "easy" || it.typeGuess == "long") && it.avgPaceSPerKm != null }
                .map { it.avgPaceSPerKm!! }
            if (ps.isEmpty()) null else ps.average()
        }
        html.append(
            section(
                "Темп лёгких/длинных тренировок по неделям (мин:с/км, ниже — быстрее)",
                svgLine(weekPace, weekLabels, "#4C9F70", yFormat = { fmtPace(it) })
            )
        )

        // --- EF (аэробная эффективность = скорость/пульс) лёгких тренировок по неделям ---
        // Порт compute_easy_ef()/weekly_ef() + add_seasonally_adjusted_ef()/seasonal_detrend_ef()
        // из build_report.py — гармоническая регрессия log(EF) по дню года (только по уличным
        // тренировкам, тредмильные не трогаются — они уже откалиброваны отдельно), чтобы отделить
        // "лето снижает EF из-за жары" от реального изменения формы (см. data/EfSeasonal.kt).
        val easyForEf = acts.filter {
            it.typeGuess == "easy" && it.avgHr != null && it.avgPaceSPerKm != null && (it.distanceM ?: 0.0) > 2000.0
        }
        if (easyForEf.size >= 4) {
            val efDates = easyForEf.map { LocalDate.parse(it.date) }
            val efRaw = easyForEf.map { a ->
                val speed = 1000.0 / a.avgPaceSPerKm!!
                speed / a.avgHr!! * 1000.0
            }
            val efIsOutdoor = easyForEf.map { it.sport != "treadmill_running" }
            val (efSeasadj, seasonalInfo) = seasonalDetrendEf(efDates, efRaw, efIsOutdoor)

            data class EfPoint(val date: LocalDate, val distanceM: Double, val ef: Double)
            val efPoints = easyForEf.indices.map { i -> EfPoint(efDates[i], easyForEf[i].distanceM ?: 0.0, efSeasadj[i]) }
            val efByWeek = efPoints.groupBy { weekStart(it.date) }
            val efWeekKeys = efByWeek.keys.sorted()
            val weekEf = efWeekKeys.map { wk ->
                val list = efByWeek[wk]!!
                val totalDist = list.sumOf { it.distanceM }
                if (totalDist <= 0.0) null else list.sumOf { it.ef * it.distanceM } / totalDist
            }
            val seasonNote = if (seasonalInfo.applied) {
                " (с сезонной поправкой: пик формы обычно около дня года ${seasonalInfo.peakAroundDoy}, " +
                    "спад — около ${seasonalInfo.troughAroundDoy}, разброс ${seasonalInfo.dropPct}%)"
            } else {
                " (без сезонной поправки — ${seasonalInfo.reason})"
            }
            html.append(
                section(
                    "EF лёгких тренировок по неделям (аэробная эффективность, выше — лучше)$seasonNote",
                    svgLine(weekEf, efWeekKeys.map { fmtShort(it) }, "#9B59B6", yFormat = { "%.2f".format(Locale.ROOT, it) })
                )
            )

            // --- пик эффективности по пульсу (порт easy_ef_by_hr) ---
            // Тот же набор точек (easyForEf/efSeasadj), что и для EF-по-неделям выше — единая
            // точка расчёта EF на весь отчёт, как и в десктопе (compute_easy_ef).
            val panoForEfHr = hrZones?.third
            val efByHr = EasyEfByHr.easyEfByHr(
                easyForEf.map { it.avgHr!!.toDouble() },
                efSeasadj.toList(),
                panoForEfHr
            )
            if (efByHr.bins.size >= 3) {
                val peakNote = if (efByHr.peakFound)
                    "пик эффективности (квадр. аппрокс.) ~${efByHr.peakCenter} уд/мин"
                else
                    "выраженного пика нет - медиана пульса лёгких пробежек ~${efByHr.peakCenter} уд/мин"
                html.append(
                    section(
                        "Эффективность (EF) лёгкого бега в зависимости от пульса ($peakNote)",
                        svgBars(efByHr.bins.map { it.meanEf }, efByHr.bins.map { "${it.hrBin}" }, "#4C9F70")
                    )
                )
            }

            // --- дозозависимость объём->EF (порт analyze_volume_ef_response) ---
            // Переиспользуем efPoints (уже сезонно скорректированные) — не считаем сезонность
            // ещё раз, ровно как в десктопе (там тоже единая точка расчёта EF на весь отчёт).
            val volEf = analyzeVolumeEfResponse(acts, efPoints.map { it.date to it.ef })
            if (volEf.ok && volEf.bins.isNotEmpty()) {
                val corrText = if (volEf.correlationR != null)
                    "Корреляция объём↔будущее изменение EF: r=${volEf.correlationR} (p≈${volEf.correlationPApprox}, грубая оценка, без поправки на автокорреляцию пересекающихся недель)."
                else "Корреляцию посчитать не удалось (слишком мало разброса по объёму или по EF)."
                val bestText = volEf.bestBin?.let {
                    "Лучший диапазон объёма по среднему приросту EF через 4 нед.: ${it.rangeLowKmPerWeek}–${it.rangeHighKmPerWeek} км/нед " +
                        "(среднее ${it.meanVolumeKmPerWeek} км/нед, ${it.nWeeks} нед. в выборке)."
                } ?: ""
                val declineText = volEf.declineThresholdKmPerWeek?.let {
                    " Начиная примерно с $it км/нед в среднем прирост EF сменяется устойчивым спадом."
                } ?: ""
                html.append(
                    section(
                        "Дозозависимость: объём бега → будущее изменение EF (описательная оценка, не строгий тест)",
                        buildString {
                            append("<div style='font-size:14px;line-height:1.6'>$corrText $bestText$declineText</div>")
                            append("<div style='overflow-x:auto;margin-top:8px'><table><tr><th>Объём, км/нед</th>" +
                                "<th>Среднее, км/нед</th><th>Ср. Δ EF через 4 нед.</th><th>Недель</th></tr>")
                            for (b in volEf.bins) {
                                append("<tr>")
                                append("<td>${b.rangeLowKmPerWeek}–${b.rangeHighKmPerWeek}</td>")
                                append("<td>${b.meanVolumeKmPerWeek}</td>")
                                append("<td>${b.meanDeltaEf}</td>")
                                append("<td>${b.nWeeks}</td>")
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }
        }

        // --- ACWR: острая (7дн) / хроническая (28дн, /4 для сопоставимости) нагрузка.
        // Десктоп считает ДВУМЯ способами (compute_acwr): по Garmin activity_training_load
        // и по объёму км — оба показаны отдельными графиками (9а/9б).
        val firstDate = LocalDate.parse(acts.first().date)
        val lastDate = LocalDate.parse(acts.last().date)
        val dailyKm = HashMap<LocalDate, Double>()
        val dailyLoad = HashMap<LocalDate, Double>()
        for (a in acts) {
            val d = LocalDate.parse(a.date)
            dailyKm[d] = (dailyKm[d] ?: 0.0) + (a.distanceM ?: 0.0) / 1000.0
            dailyLoad[d] = (dailyLoad[d] ?: 0.0) + (a.activityTrainingLoad ?: 0.0)
        }
        val days = mutableListOf<LocalDate>()
        var d = firstDate
        while (!d.isAfter(lastDate)) { days.add(d); d = d.plusDays(1) }
        fun computeAcwr(daily: Map<LocalDate, Double>): List<Double?> {
            val out = mutableListOf<Double?>()
            for (i in days.indices) {
                val acuteStart = max(0, i - 6)
                val chronicStart = max(0, i - 27)
                val acute = (acuteStart..i).sumOf { daily[days[it]] ?: 0.0 }
                val chronicSum = (chronicStart..i).sumOf { daily[days[it]] ?: 0.0 }
                val chronicDays = i - chronicStart + 1
                val chronicWeekly = chronicSum / chronicDays * 7.0
                out.add(if (chronicWeekly > 0.0) acute / chronicWeekly else null)
            }
            return out
        }
        val acwrLoad = computeAcwr(dailyLoad)
        val acwrKm = computeAcwr(dailyKm)
        // прореживаем до одной точки в неделю, чтобы график не был перегружен по дням
        val weeklyIdx = days.indices.filter { it % 7 == 0 }
        val acwrLoadWeekly = weeklyIdx.map { acwrLoad[it] }
        val acwrKmWeekly = weeklyIdx.map { acwrKm[it] }
        val acwrLabels = weeklyIdx.map { fmtShort(days[it]) }
        val hasLoadData = dailyLoad.values.any { it > 0.0 }
        if (hasLoadData) {
            html.append(
                section(
                    "9а. ACWR по Garmin training load (ориентир: 0.8–1.3 безопаснее, выше 1.5 — риск)",
                    svgLine(acwrLoadWeekly, acwrLabels, "#E0574C", yFormat = { "%.1f".format(Locale.ROOT, it) })
                )
            )
        }
        html.append(
            section(
                "9б. ACWR по объёму (км) (ориентир: 0.8–1.3 безопаснее, выше 1.5 — риск)",
                svgLine(acwrKmWeekly, acwrLabels, "#3B7DD8", yFormat = { "%.1f".format(Locale.ROOT, it) })
            )
        )

        // --- Доли типов тренировок по неделям (порт weekly_type_shares/plot_weekly_type_shares).
        // УПРОЩЕНИЕ относительно десктопа: там марафонский темп внутри смешанной тренировки
        // выделяется отдельной долей (marathon_time_per_activity, разбор структуры лапов на
        // марафонский сегмент) — здесь такого разбора нет, вся длительность тренировки идёт в
        // долю её общего type_guess (long/easy/interval/threshold/mixed/unknown) целиком. ---
        run {
            data class TypeShareColors(val color: String, val label: String)
            val typeMeta = mapOf(
                "easy" to TypeShareColors("#4C9F70", "лёгкий"),
                "long" to TypeShareColors("#3B7DD8", "длительный"),
                "interval" to TypeShareColors("#E0574C", "интервалы"),
                "threshold" to TypeShareColors("#E0A62C", "пороговый"),
                "mixed" to TypeShareColors("#9B6BC7", "смешанный"),
                "unknown" to TypeShareColors("#B0B0B0", "неизвестно")
            )
            val actsByWeekType = acts.groupBy { weekStart(LocalDate.parse(it.date)) }.toSortedMap()
            val typeWeekKeys = actsByWeekType.keys.toList()
            if (typeWeekKeys.isNotEmpty()) {
                val order = listOf("easy", "long", "threshold", "interval", "mixed", "unknown")
                val sharesByType = order.associateWith { type ->
                    typeWeekKeys.map { wk ->
                        val weekActs = actsByWeekType[wk]!!
                        val totalS = weekActs.sumOf { it.durationS ?: 0.0 }
                        if (totalS <= 0.0) return@map 0.0
                        val typeS = weekActs.filter { (it.typeGuess ?: "unknown").let { g -> if (g == "quality") "mixed" else g } == type }
                            .sumOf { it.durationS ?: 0.0 }
                        typeS / totalS * 100.0
                    }
                }
                val series = order.map { type ->
                    Triple(typeMeta.getValue(type).label, typeMeta.getValue(type).color, sharesByType.getValue(type))
                }
                val typeLabels = typeWeekKeys.map { fmtShort(it) }
                html.append(
                    section(
                        "Доли типов тренировок по неделям (% от времени)",
                        svgStackedBars100(series, typeLabels)
                    )
                )
            }
        }

        // --- самочувствие: RHR/HRV по неделям ---
        if (wellness.isNotEmpty()) {
            val wByWeek = wellness.groupBy { weekStart(LocalDate.parse(it.date)) }.toSortedMap()
            val wWeekKeys = wByWeek.keys.toList()
            val rhrWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.rhr?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            val hrvWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.hrvLastNightAvg }
                if (vs.isEmpty()) null else vs.average()
            }
            val wLabels = wWeekKeys.map { fmtShort(it) }
            html.append(section("ЧСС покоя по неделям (RHR, уд/мин)", svgLine(rhrWeekly, wLabels, "#9B6BC7")))
            html.append(section("Вариабельность пульса ночью по неделям (HRV, мс)", svgLine(hrvWeekly, wLabels, "#E0A62C")))

            // --- Body Battery по неделям (среднее мин/макс за день, усреднённое по неделе) ---
            val bbMinWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.bodyBatteryMin?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            val bbMaxWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.bodyBatteryMax?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            if (bbMinWeekly.any { it != null } || bbMaxWeekly.any { it != null }) {
                html.append(
                    section(
                        "Body Battery по неделям (среднее мин/макс за день, 0–100)",
                        "<div style='font-size:13px;color:#888;margin-bottom:4px'>Макс. (утренний заряд):</div>" +
                            svgLine(bbMaxWeekly, wLabels, "#3FAE6E") +
                            "<div style='font-size:13px;color:#888;margin:8px 0 4px'>Мин. (к концу дня):</div>" +
                            svgLine(bbMinWeekly, wLabels, "#C75B3F")
                    )
                )
            }

            // --- Стресс по неделям (средний за день, шкала Garmin 0-100) ---
            val stressWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.stressAvg?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            if (stressWeekly.any { it != null }) {
                html.append(section("Средний стресс по неделям (0–100, шкала Garmin)", svgLine(stressWeekly, wLabels, "#D2691E")))
            }

            // --- Training Readiness по неделям (готовность к тренировке, 0-100) ---
            val readinessWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.trainingReadinessScore?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            if (readinessWeekly.any { it != null }) {
                html.append(section("Training Readiness по неделям (готовность к тренировке, 0–100)", svgLine(readinessWeekly, wLabels, "#2E8B8B")))
            }

            // --- Качество сна по неделям (0-100) ---
            val sleepScoreWeekly = wWeekKeys.map { wk ->
                val vs = wByWeek[wk]!!.mapNotNull { it.sleepScore?.toDouble() }
                if (vs.isEmpty()) null else vs.average()
            }
            if (sleepScoreWeekly.any { it != null }) {
                html.append(section("Качество сна по неделям (0–100, оценка Garmin)", svgLine(sleepScoreWeekly, wLabels, "#5B7FBF")))
            }
        }

        // --- Внутритренировочный дрейф беговой динамики (порт compute_lap_drift) — рост GCT/
        // падение каденса к концу тренировки при том же темпе/пульсе - сигнал локального
        // нервно-мышечного утомления ВНУТРИ одной тренировки. Показываем по неделям (среднее
        // среди тренировок с посчитанным дрейфом за неделю), т.к. посчитан не для каждой
        // тренировки (нужно >=6 "рабочих" лапов с известным каденсом, см. computeLapDrift). ---
        run {
            val actsByWeek = acts.groupBy { weekStart(LocalDate.parse(it.date)) }.toSortedMap()
            val weekKeys = actsByWeek.keys.toList()
            val cadenceDriftWeekly = weekKeys.map { wk ->
                val vs = actsByWeek[wk]!!.mapNotNull { it.cadenceDriftPct }
                if (vs.isEmpty()) null else vs.average()
            }
            val gctDriftWeekly = weekKeys.map { wk ->
                val vs = actsByWeek[wk]!!.mapNotNull { it.gctDriftPct }
                if (vs.isEmpty()) null else vs.average()
            }
            if (cadenceDriftWeekly.any { it != null } || gctDriftWeekly.any { it != null }) {
                val driftLabels = weekKeys.map { fmtShort(it) }
                html.append(
                    section(
                        "Внутритренировочный дрейф беговой динамики по неделям (%, first vs last треть «рабочих» лапов)",
                        "<div style='font-size:13px;color:#888;margin-bottom:4px'>Каденс (отрицательное — каденс падает к концу тренировки):</div>" +
                            svgLine(cadenceDriftWeekly, driftLabels, "#8B5CF6", yFormat = { "%.1f%%".format(Locale.ROOT, it) }) +
                            "<div style='font-size:13px;color:#888;margin:8px 0 4px'>Время контакта с землёй, GCT (положительное — GCT растёт к концу, признак утомления):</div>" +
                            svgLine(gctDriftWeekly, driftLabels, "#C75B3F", yFormat = { "%.1f%%".format(Locale.ROOT, it) })
                    )
                )
            }
        }

        // --- VDOT по гонкам (порт race_vdot_points/daniels_vdot/detect_race_blowup) ---
        if (hrZones != null) {
            val pano = hrZones.third
            val (races, excludedCount) = raceVdotPoints(acts, pano, raceIntervalsByActivity)
            if (races.isNotEmpty()) {
                html.append(
                    section(
                        "Гонки, использованные для VDOT${if (excludedCount > 0) " (ещё $excludedCount активностей похожи на гонку, но пульс не подтверждает полноценный эффорт — исключены)" else ""}",
                        buildString {
                            // порт таблицы под plot_ef_vdot() в build_report.py — 8 колонок, как
                            // в десктопе: отдельно VDOT по всей дистанции и VDOT (использован),
                            // чтобы при обнаруженном срыве темпа было видно обе оценки, а не
                            // только итоговую.
                            append("<div style='overflow-x:auto'><table><tr><th>Дата</th><th>Название</th><th>Дистанция</th>" +
                                "<th>Время</th><th>Пульс</th><th>VDOT (вся дистанция)</th><th>Срыв темпа?</th><th>VDOT (использован)</th></tr>")
                            for (r in races) {
                                val timeStr = "${"%.1f".format(Locale.ROOT, r.durationS / 60.0)} мин"
                                val blowupStr = if (r.blowupDetected) "да, после ${r.blowupKm} км" else "нет"
                                append("<tr>")
                                append("<td>${esc(r.date)}</td>")
                                append("<td>${esc(r.name)}</td>")
                                append("<td>${"%.2f".format(Locale.ROOT, r.distanceM / 1000.0)} км</td>")
                                append("<td>$timeStr</td>")
                                append("<td>${r.avgHr}</td>")
                                append("<td>${"%.1f".format(Locale.ROOT, r.vdotFull)}</td>")
                                append("<td>$blowupStr</td>")
                                append("<td>${"%.1f".format(Locale.ROOT, r.vdot)}</td>")
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }
        }

        // --- VDOT по интервальным/пороговым рабочим отрезкам (порт interval_threshold_vdot_points) ---
        if (raceIntervalsByActivity.isNotEmpty()) {
            val pano = hrZones?.third
            val ivPoints = intervalThresholdVdotPoints(acts, raceIntervalsByActivity, pano)
            if (ivPoints.isNotEmpty()) {
                html.append(
                    section(
                        "VDOT по рабочим отрезкам (интервалы/пороговые вставки, ${ivPoints.size} тренировок; шумнее гоночной оценки)",
                        buildString {
                            append("<div style='overflow-x:auto'><table><tr><th>Дата</th><th>Название</th>" +
                                "<th>VDOT</th><th>Лапов</th><th>Доверие</th><th>% от ПАНО</th></tr>")
                            for (p in ivPoints) {
                                append("<tr>")
                                append("<td>${esc(p.date)}</td>")
                                append("<td>${esc(p.name)}</td>")
                                append("<td>${"%.1f".format(Locale.ROOT, p.vdot)}</td>")
                                append("<td>${p.nWorkLaps}</td>")
                                append("<td>${"%.0f".format(Locale.ROOT, p.weight * 100)}%</td>")
                                append("<td>${p.pctOfPano?.let { "%.0f".format(Locale.ROOT, it * 100) + "%" } ?: "—"}</td>")
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }
        }

        // --- Темп по зонам (порт build_zones/recent_pace_by_zone/zones_table_with_recent_pace/
        // pace_by_zone_quarterly) — нужны и hrZones (для pano), и rhrMaxHr (rhr/maxHr), и лапы. ---
        if (hrZones != null && rhrMaxHr != null && raceIntervalsByActivity.isNotEmpty()) {
            val (rhr, maxHr) = rhrMaxHr
            val pano = hrZones.third
            val zones = buildZones(rhr, pano, maxHr)
            val weeksBack = 8
            val recentRows = recentPaceByZone(raceIntervalsByActivity, zones, acts, weeksBack)
            html.append(
                section(
                    "Темп по пульсовым зонам (Z1-Z5, Карвонен %HRR + ПАНО), последние $weeksBack нед.",
                    buildString {
                        append("<div style='overflow-x:auto'><table><tr><th>Зона</th><th>Пульс, уд/мин</th>" +
                            "<th>% от ПАНО</th><th>Темп (мед., 25-75 перц.)</th><th>Сплитов</th></tr>")
                        for (r in recentRows) {
                            val pctLo = Math.round(r.zone.lo.toDouble() / pano * 100)
                            val pctHi = Math.round(r.zone.hi.toDouble() / pano * 100)
                            val paceText = if (r.p50 != null) "${fmtPace(r.p25)} – ${fmtPace(r.p75)} (медиана ${fmtPace(r.p50)})" else "недостаточно данных"
                            append("<tr>")
                            append("<td>${esc(r.zone.name)}</td>")
                            append("<td>${r.zone.lo}-${r.zone.hi}</td>")
                            append("<td>$pctLo-$pctHi%</td>")
                            append("<td>$paceText</td>")
                            append("<td>${r.n}</td>")
                            append("</tr>")
                        }
                        append("</table></div>")
                    }
                )
            )

            val quarterly = paceByZoneQuarterly(raceIntervalsByActivity, zones, acts)
            if (quarterly.isNotEmpty()) {
                val quarters = quarterly.map { it.quarterStart }.distinct().sorted()
                html.append(
                    section(
                        "Темп по зонам в динамике, по кварталам (интервалы/сплиты)",
                        buildString {
                            append("<div style='overflow-x:auto'><table><tr><th>Квартал</th>")
                            for (z in zones) append("<th>${esc(z.name)}</th>")
                            append("</tr>")
                            for (q in quarters) {
                                append("<tr><td>${q.year} Q${(q.monthValue - 1) / 3 + 1}</td>")
                                for (z in zones) {
                                    val cell = quarterly.firstOrNull { it.quarterStart == q && it.zoneName == z.name }
                                    append("<td>${cell?.let { fmtPace(it.paceSPerKm) } ?: "—"}</td>")
                                }
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }

            // --- Факт vs рекомендовано по зонам (порт current_zone_shares + полная DIST_TARGETS
            // из build_report.py, все 10 целевых дистанций). УПРОЩЕНИЕ относительно десктопа:
            // там ЕЩЁ есть полная zone_share_time_series (график в динамике по неделям,
            // скользящее окно 4 нед., с интерактивным JS-переключателем дистанции прямо на
            // графике) — здесь только ТЕКУЩИЙ срез (последние 12 недель, статичная таблица) и
            // рекомендованные % сразу для всех дистанций рядом (в HTML-отчёте на телефоне нет
            // JS-переключателя, как в десктопе, поэтому показываем все столбцы сразу). ---
            val recentIdsForShares = acts.filter {
                LocalDate.parse(it.date) >= LocalDate.parse(acts.last().date).minusWeeks(12)
            }.map { it.activityId }.toSet()
            val lapsRecentForShares = recentIdsForShares.flatMap { raceIntervalsByActivity[it] ?: emptyList() }
                .filter { it.avgHr != null }
            val totalSForShares = lapsRecentForShares.sumOf { it.durationS ?: 0.0 }
            if (totalSForShares > 0.0) {
                // Точный порт DIST_TARGETS из build_report.py — ориентировочное распределение
                // недельного времени бега по пульсовым зонам Z1-Z5 для разных целевых дистанций
                // забега (литературные ориентиры, не подгонка под конкретного атлета).
                val distTargets = linkedMapOf(
                    3 to mapOf("Z1" to 10, "Z2" to 40, "Z3" to 7, "Z4" to 18, "Z5" to 25),
                    5 to mapOf("Z1" to 11, "Z2" to 44, "Z3" to 8, "Z4" to 17, "Z5" to 20),
                    10 to mapOf("Z1" to 12, "Z2" to 49, "Z3" to 10, "Z4" to 17, "Z5" to 12),
                    21 to mapOf("Z1" to 15, "Z2" to 54, "Z3" to 12, "Z4" to 13, "Z5" to 6),
                    42 to mapOf("Z1" to 15, "Z2" to 62, "Z3" to 12, "Z4" to 6, "Z5" to 5),
                    60 to mapOf("Z1" to 20, "Z2" to 65, "Z3" to 9, "Z4" to 4, "Z5" to 2),
                    80 to mapOf("Z1" to 22, "Z2" to 67, "Z3" to 7, "Z4" to 3, "Z5" to 1),
                    100 to mapOf("Z1" to 24, "Z2" to 68, "Z3" to 5, "Z4" to 2, "Z5" to 1),
                    120 to mapOf("Z1" to 26, "Z2" to 68, "Z3" to 4, "Z4" to 2, "Z5" to 0),
                    160 to mapOf("Z1" to 28, "Z2" to 68, "Z3" to 3, "Z4" to 1, "Z5" to 0)
                )
                html.append(
                    section(
                        "Текущее фактическое распределение по пульсовым зонам (последние 12 недель)",
                        buildString {
                            append("<div style='overflow-x:auto'><table><tr><th>Зона</th><th>Сейчас, % времени</th></tr>")
                            for (z in zones) {
                                val sumS = lapsRecentForShares.filter { (it.avgHr ?: -1) in z.lo..z.hi }.sumOf { it.durationS ?: 0.0 }
                                val actualPct = sumS / totalSForShares * 100.0
                                append("<tr><td>${esc(z.name)}</td><td>${"%.1f".format(Locale.ROOT, actualPct)}%</td></tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
                html.append(
                    section(
                        "Рекомендуемое распределение по зонам для разных целевых дистанций, %",
                        buildString {
                            append("<div style='font-size:13px;color:#888;margin-bottom:4px'>Таблица транспонирована: строки — пульсовые зоны, столбцы — целевая дистанция забега (км). Каждый столбец в сумме даёт 100% недельного времени бега.</div>")
                            append("<div style='overflow-x:auto'><table><tr><th>Зона</th>")
                            for (d in distTargets.keys) append("<th>$d км</th>")
                            append("</tr>")
                            for (z in zones) {
                                val zKey = z.name.take(2)
                                append("<tr><td>${esc(z.name)}</td>")
                                for (d in distTargets.keys) {
                                    append("<td>${distTargets[d]?.get(zKey) ?: "—"}%</td>")
                                }
                                append("</tr>")
                            }
                            append("</table></div>")
                        }
                    )
                )
            }
        }

        // --- ПАНО по устойчивым пороговым тренировкам (порт pano_table из build_report.py) ---
        // Только НЕПРЕРЫВНЫЕ эффорты (typeGuess=="threshold" — единственная категория, где
        // classifyByLaps фиксирует устойчивое плато пульса), 35-65 минут (не интервалы, не
        // марафон/полумарафон), пульс не ниже 85-го перцентиля собственного avgHr атлета (если
        // ПАНО из истории Гармина неизвестно — см. hrZones, тогда порог просто не применяется).
        val panoThresholdActs = acts.filter {
            it.typeGuess == "threshold" && it.durationS != null &&
                it.durationS >= 2100.0 && it.durationS <= 3900.0 && it.avgHr != null
        }
        val avgHrsForPctl = acts.mapNotNull { it.avgHr }.sorted()
        val minHrGate = if (avgHrsForPctl.isNotEmpty()) {
            val idx = (0.85 * (avgHrsForPctl.size - 1)).toInt()
            avgHrsForPctl[idx].toDouble()
        } else null
        val panoRows = panoThresholdActs.filter { minHrGate == null || (it.avgHr ?: 0) >= minHrGate }
            .sortedBy { it.date }
        if (panoRows.isNotEmpty()) {
            val panoEstimate = panoRows.mapNotNull { it.avgHr }.average()
            html.append(
                section(
                    "ПАНО по устойчивым пороговым тренировкам (${panoRows.size} шт., среднее: ${"%.0f".format(Locale.ROOT, panoEstimate)} уд/мин)",
                    buildString {
                        append("<div style='overflow-x:auto'><table><tr><th>Дата</th><th>Название</th><th>Пульс</th><th>Темп</th><th>Длительность</th></tr>")
                        for (a in panoRows) {
                            append("<tr>")
                            append("<td>${esc(a.date)}</td>")
                            append("<td>${esc(a.name)}</td>")
                            append("<td>${a.avgHr}</td>")
                            append("<td>${fmtPace(a.avgPaceSPerKm)}</td>")
                            append("<td>${Math.round((a.durationS ?: 0.0) / 60.0)} мин</td>")
                            append("</tr>")
                        }
                        append("</table></div>")
                    }
                )
            )
        }

        // --- таблица последних тренировок ---
        html.append("<h2>Последние тренировки</h2>")
        html.append("<div style='overflow-x:auto'><table><tr><th>Дата</th><th>Тип</th><th>Название</th><th>Км</th><th>Темп</th><th>Пульс</th></tr>")
        for (a in acts.takeLast(30).reversed()) {
            html.append("<tr>")
            html.append("<td>${esc(a.date)}</td>")
            html.append("<td>${typeLabel(a.typeGuess)}</td>")
            html.append("<td>${esc(a.name)}</td>")
            html.append("<td>${"%.1f".format(Locale.ROOT, (a.distanceM ?: 0.0) / 1000.0)}</td>")
            html.append("<td>${fmtPace(a.avgPaceSPerKm)}</td>")
            html.append("<td>${a.avgHr?.toString() ?: "—"}</td>")
            html.append("</tr>")
        }
        html.append("</table></div>")

        html.append(HTML_TAIL)
        return html.toString()
    }

    private fun crossGroupLabel(g: String) = when (g) {
        "cycling" -> "Велосипед"
        "skiing" -> "Лыжи"
        "swimming" -> "Плавание"
        "strength_training" -> "Силовая"
        else -> g
    }

    private fun typeLabel(t: String?) = when (t) {
        "long" -> "длинная"
        "interval" -> "интервалы"
        "threshold" -> "порог"
        "mixed" -> "смешанная"
        "quality" -> "качественная" // legacy-метка старого грубого классификатора
        "easy" -> "лёгкая"
        else -> "—"
    }

    private fun card(label: String, value: String) =
        "<div class='card'><div class='card-value'>${esc(value)}</div><div class='card-label'>${esc(label)}</div></div>"

    private fun section(title: String, bodyHtml: String) =
        "<h2>${esc(title)}</h2><div class='chart'>$bodyHtml</div>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val HTML_HEAD = """
        <!doctype html><html lang="ru"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes">
        <title>Аналитика тренировок</title>
        <style>
          body { font-family: -apple-system, Roboto, Arial, sans-serif; margin: 0; padding: 16px 16px 40px; color: #1c1c1e; background: #fff; }
          h1 { font-size: 20px; margin: 4px 0 8px; }
          h2 { font-size: 15px; margin: 22px 0 8px; color: #333; }
          .muted { color: #777; font-size: 13px; }
          .cards { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0; }
          .card { background: #f4f5f7; border-radius: 10px; padding: 10px 14px; min-width: 110px; }
          .card-value { font-size: 18px; font-weight: 600; }
          .card-label { font-size: 11px; color: #777; margin-top: 2px; }
          .chart { overflow-x: auto; -webkit-overflow-scrolling: touch; }
          table { border-collapse: collapse; font-size: 13px; width: 100%; }
          th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #eee; white-space: nowrap; }
          th { color: #777; font-weight: 500; }
        </style>
        </head><body>
    """.trimIndent()

    private const val HTML_TAIL = "</body></html>"
}
