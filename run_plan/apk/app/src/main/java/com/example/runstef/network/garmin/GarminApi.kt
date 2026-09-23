package com.example.runstef.network.garmin

import com.example.runstef.data.PlanStep
import com.example.runstef.data.PlanWorkout
import com.example.runstef.data.RunPlan
import com.example.runstef.network.ImportCancelledException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Порт garmin_plan_import.py: создание структурированных тренировок Garmin Connect
 * и их расписания по датам через connectapi.garmin.com (Bearer OAuth2, см. GarminAuth).
 */
class GarminApi(
    private val auth: GarminAuth,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private val SPORT_RUN = SportType(1, "running")
        private val SPORT_STR = SportType(5, "strength_training")
        private val SPORT_BIKE = SportType(2, "cycling")
        private val SPORT_SWIM = SportType(4, "swimming")
        private val SPORT_OTHER = SportType(3, "other")
        private val CROSS_SPORT = mapOf(
            "cycling" to SPORT_BIKE,
            "lap_swimming" to SPORT_SWIM,
            "swimming" to SPORT_SWIM,
            "cardio_training" to SPORT_OTHER,
            "other" to SPORT_OTHER,
            "strength_training" to SPORT_STR
        )
        private val STEP_TYPE_ID = mapOf(
            "warmup" to 1, "cooldown" to 2, "interval" to 3, "recovery" to 4,
            "rest" to 5, "repeat" to 6, "other" to 7
        )
    }

    data class SportType(val id: Int, val key: String)

    private fun sportJson(s: SportType) = buildJsonObject {
        put("sportTypeId", s.id)
        put("sportTypeKey", s.key)
    }

    private fun paceMs(p: String): Double {
        val (m, s) = p.trim().split(":")
        return Math.round(1000.0 / (m.toInt() * 60 + s.toInt()) * 10000.0) / 10000.0
    }

    private fun targetJson(step: PlanStep): JsonObject = buildJsonObject {
        val tg = step.tg
        when {
            tg?.pace != null -> {
                val a = paceMs(tg.pace[0]); val b = paceMs(tg.pace[1])
                putJsonObject("targetType") { put("workoutTargetTypeId", 6); put("workoutTargetTypeKey", "pace.zone") }
                put("targetValueOne", minOf(a, b))
                put("targetValueTwo", maxOf(a, b))
            }
            tg?.bpm != null -> {
                putJsonObject("targetType") { put("workoutTargetTypeId", 4); put("workoutTargetTypeKey", "heart.rate.zone") }
                put("targetValueOne", tg.bpm[0])
                put("targetValueTwo", tg.bpm[1])
            }
            tg?.hr != null -> {
                putJsonObject("targetType") { put("workoutTargetTypeId", 4); put("workoutTargetTypeKey", "heart.rate.zone") }
                put("zoneNumber", tg.hr)
            }
            else -> {
                putJsonObject("targetType") { put("workoutTargetTypeId", 1); put("workoutTargetTypeKey", "no.target") }
            }
        }
    }

    private fun convStep(step: PlanStep, order: IntArray): JsonObject {
        val myOrder = order[0]
        order[0]++
        if (step.t == "repeat") {
            return buildJsonObject {
                put("type", "RepeatGroupDTO")
                putJsonObject("stepType") { put("stepTypeId", 6); put("stepTypeKey", "repeat") }
                put("numberOfIterations", step.n ?: 1)
                put("smartRepeat", false)
                put("stepOrder", myOrder)
                putJsonArray("workoutSteps") {
                    (step.steps ?: emptyList()).forEach { add(convStep(it, order)) }
                }
            }
        }
        val stepTypeId = STEP_TYPE_ID[step.t] ?: 3
        val endCondition = if (step.end == "distance") 3 else 2
        val endKey = if (step.end == "distance") "distance" else "time"
        val target = targetJson(step)
        return buildJsonObject {
            put("type", "ExecutableStepDTO")
            putJsonObject("stepType") { put("stepTypeId", stepTypeId); put("stepTypeKey", step.t) }
            putJsonObject("endCondition") { put("conditionTypeId", endCondition); put("conditionTypeKey", endKey) }
            put("endConditionValue", step.v ?: 0.0)
            for ((k, v) in target) put(k, v)
            step.d?.let { put("description", it) }
            put("stepOrder", myOrder)
        }
    }

    private fun paceStrFromMs(ms: Double): String {
        val s = Math.round(1000.0 / ms).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun kmStr(meters: Double): String {
        val km = meters / 1000.0
        val rounded = Math.round(km * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toInt().toString()
            else rounded.toString().trimEnd('0').trimEnd('.')
    }

    /** Порт _amt() из garmin_plan_import.py — читаемая длительность/дистанция шага для
     * человекочитаемого description тренировки (см. describeSteps). */
    private fun amtStr(step: PlanStep): String {
        val v = step.v ?: 0.0
        if (step.end == "distance") return "${kmStr(v)} км"
        val vi = v.toInt()
        return when {
            vi < 60 -> "${vi}с"
            vi % 60 == 0 -> "${vi / 60} мин"
            else -> "${vi / 60}:${(vi % 60).toString().padStart(2, '0')}"
        }
    }

    /** Порт _tgt() — читаемая цель шага (пульс/темп/зона) для описания. */
    private fun tgtStr(step: PlanStep): String {
        val tg = step.tg ?: return ""
        return when {
            tg.pace != null -> {
                val a = paceMs(tg.pace[0]); val b = paceMs(tg.pace[1])
                "${paceStrFromMs(maxOf(a, b))}-${paceStrFromMs(minOf(a, b))}/км"
            }
            tg.bpm != null -> "${tg.bpm[0]}\u2013${tg.bpm[1]} уд"
            tg.hr != null -> "Z${tg.hr}"
            else -> ""
        }
    }

    /** Порт _prose()/describe() из garmin_plan_import.py — человекочитаемая расшифровка шагов
     * тренировки одной строкой (напр. "10 мин Z2 · 3×(5 мин 4:30-4:45/км; 2 мин отдых)"),
     * попадает в description беговой тренировки в Garmin Connect (см. runWorkoutJson). Раньше в
     * apk description беговой тренировки состоял ТОЛЬКО из note (или был пустым, если note не
     * задан) — сама расшифровка шагов терялась, хотя у каждого шага description (step.d) и так
     * проставляется отдельно внутри самого JSON-шага — это не заменяет общий человекочитаемый
     * обзор всей тренировки, который desktop-скрипт кладёт в описание сверху. */
    private fun proseStr(step: PlanStep): String {
        if (step.t == "repeat") {
            val inner = (step.steps ?: emptyList()).joinToString("; ") { proseStr(it) }
            return "${step.n ?: 1}\u00d7($inner)"
        }
        val body = listOf(amtStr(step), tgtStr(step)).filter { it.isNotEmpty() }.joinToString(" ")
        val lbl = step.d
        return if (!lbl.isNullOrEmpty()) "$lbl $body".trim() else body
    }

    private fun describeSteps(steps: List<PlanStep>): String = steps.joinToString(" \u00b7 ") { proseStr(it) }

    private fun runWorkoutJson(w: PlanWorkout): JsonObject {
        val order = intArrayOf(1)
        val stepList = w.steps ?: emptyList()
        val steps = stepList.map { convStep(it, order) }
        val desc = describeSteps(stepList)
        val fullDesc = if (!w.note.isNullOrEmpty()) "${w.note} || $desc" else desc
        return buildJsonObject {
            putJsonObject("sportType") { put("sportTypeId", SPORT_RUN.id); put("sportTypeKey", SPORT_RUN.key) }
            put("workoutName", w.name.take(79))
            put("description", fullDesc.take(1024))
            putJsonArray("workoutSegments") {
                addJsonObjectSegment(1, SPORT_RUN, steps)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObjectSegment(order: Int, sport: SportType, steps: List<JsonObject>) {
        add(buildJsonObject {
            put("segmentOrder", order)
            putJsonObject("sportType") { put("sportTypeId", sport.id); put("sportTypeKey", sport.key) }
            putJsonArray("workoutSteps") { steps.forEach { add(it) } }
        })
    }

    /** [stepTypeId]/[stepTypeKey]/[stepDescription] раньше были хардкожены на "other"(7) и
     * w.desc для ОБОИХ вызовов (str и cross) — расхождение с десктопом (garmin_plan_import.py
     * str_json/cross_json): там силовая (str) действительно "other"(7) с description ШАГА
     * ВСЕГДА "Силовая" (не w.desc — тот идёт только в description ВОРКАУТА целиком), а кросс
     * (cross) — "interval"(3) с description ШАГА = w.desc. Из-за общей хардкоженной "other" все
     * кросс-тренировки (велосипед/плавание/лыжи) уходили в Garmin с НЕВЕРНЫМ типом шага. */
    private fun simpleWorkoutJson(
        w: PlanWorkout,
        sport: SportType,
        defaultMins: Int,
        stepTypeId: Int,
        stepTypeKey: String,
        stepDescription: String
    ): JsonObject {
        val mins = (w.mins ?: defaultMins.toDouble()).toInt()
        val step = buildJsonObject {
            put("type", "ExecutableStepDTO")
            putJsonObject("stepType") { put("stepTypeId", stepTypeId); put("stepTypeKey", stepTypeKey) }
            putJsonObject("endCondition") { put("conditionTypeId", 2); put("conditionTypeKey", "time") }
            put("endConditionValue", (mins * 60).toDouble())
            putJsonObject("targetType") { put("workoutTargetTypeId", 1); put("workoutTargetTypeKey", "no.target") }
            put("description", stepDescription.take(512))
            put("stepOrder", 1)
        }
        return buildJsonObject {
            putJsonObject("sportType") { put("sportTypeId", sport.id); put("sportTypeKey", sport.key) }
            put("workoutName", w.name.take(79))
            put("description", (w.desc ?: "").take(1024))
            putJsonArray("workoutSegments") { addJsonObjectSegment(1, sport, listOf(step)) }
            if (sport == SPORT_SWIM) {
                put("poolLength", 50.0)
                putJsonObject("poolLengthUnit") { put("unitId", 1); put("unitKey", "meter"); put("factor", 100.0) }
            }
        }
    }

    data class PlanItem(val date: LocalDate, val name: String, val workoutJson: JsonObject, val sportKey: String)

    private fun buildItems(plan: RunPlan, skipCross: Set<String>): List<PlanItem> {
        val items = mutableListOf<PlanItem>()
        for (w in plan.workouts) {
            val date = LocalDate.parse(w.date)
            if (w.kind == "cross") {
                val gt = (w.gtype ?: "").lowercase()
                if (skipCross.isNotEmpty() && ("all" in skipCross || "cross" in skipCross || gt in skipCross)) continue
            }
            val (json, sportKey) = when (w.kind) {
                "run" -> runWorkoutJson(w) to SPORT_RUN.key
                "str" -> simpleWorkoutJson(w, SPORT_STR, 20, 7, "other", "Силовая") to SPORT_STR.key
                "cross" -> {
                    val sport = CROSS_SPORT[w.gtype] ?: SPORT_OTHER
                    simpleWorkoutJson(w, sport, 45, 3, "interval", w.desc ?: "") to sport.key
                }
                else -> continue
            }
            items.add(PlanItem(date, w.name.take(79), json, sportKey))
        }
        return items.sortedBy { it.date }
    }

    data class Result(
        val ok: Int = 0, val fail: Int = 0, val cleared: Int = 0,
        val dryRun: Boolean = false, val count: Int = 0
    )

    fun upload(
        plan: RunPlan,
        tokens: GarminTokens,
        skipCross: Set<String>,
        dryRun: Boolean,
        testFirstWeek: Boolean,
        allDates: Boolean = false,
        fromDate: LocalDate? = null,
        // Кооперативная проверка кнопки «Стоп» на вкладке «Экспорт» (см.
        // ExportViewModel.cancelExport / AnalyticsImportBus.cancelRequested) - опрашивается
        // между запросами, т.к. этот метод не suspend и job.cancel() не прервёт блокирующий
        // HTTP-вызов внутри него. По умолчанию no-op.
        isCancelled: () -> Boolean = { false }
    ): Result {
        val tag = plan.meta.tag
        val allItems = buildItems(plan, skipCross)
        if (allItems.isEmpty()) throw RuntimeException("В плане нет тренировок для загрузки.")

        // По умолчанию (allDates=false) создаём только тренировки с датой >= сегодня —
        // прошедшие пропускаем (как в десктопном plan_export_garmin.py).
        var items = allItems
        if (!allDates) {
            val cutoffFrom = fromDate ?: LocalDate.now()
            val skippedPast = items.count { it.date < cutoffFrom }
            items = items.filter { it.date >= cutoffFrom }
            if (skippedPast > 0) {
                log("Пропущено прошедших тренировок (дата < $cutoffFrom): $skippedPast (включи «Весь план целиком», чтобы загрузить всё)")
            }
            if (items.isEmpty()) {
                throw RuntimeException("После фильтра по дате (>= $cutoffFrom) в плане не осталось тренировок. Включи «Весь план целиком».")
            }
        }

        if (testFirstWeek) {
            val end = items.first().date.plusDays(7)
            items = items.filter { it.date < end }
        }

        if (dryRun) {
            val runs = items.count { it.sportKey == SPORT_RUN.key }
            val strs = items.count { it.sportKey == SPORT_STR.key }
            val cross = items.size - runs - strs
            log("План: ${plan.meta.name} | тег $tag")
            log("Будет создано: ${items.size} (бег $runs, силовые $strs, кросс $cross); ${items.first().date} … ${items.last().date}")
            log("Сухой прогон — ничего не отправлено.")
            return Result(dryRun = true, count = items.size)
        }

        // Перед созданием всегда чистим в Garmin уже существующие тренировки с ТАКИМИ ЖЕ
        // именами, что и сейчас загружаемые — защита от дублей и расхождений при повторном
        // запуске/перезаливке плана (как в десктопном скрипте). Без этого шага возможны
        // дубли даже если чистить только прошедшие тренировки — расписание на будущее
        // могло измениться в новой версии плана при том же имени.
        try {
            val namesToUpload = items.map { it.name }.toSet()
            val existingText = auth.connectApi(tokens, "/workout-service/workouts?start=0&limit=999")
                .use { it.body?.string() ?: "[]" }
            val existing = runCatching { Json.parseToJsonElement(existingText).jsonArray }.getOrNull()
            val dupes = existing?.filter { (it.jsonObject["workoutName"]?.jsonPrimitive?.content ?: "") in namesToUpload } ?: emptyList()
            if (dupes.isNotEmpty()) {
                log("Удаляю ${dupes.size} уже существующих тренировок с такими же именами (чтобы не плодить дубли)…")
                var removedDupes = 0
                for (obj in dupes) {
                    if (isCancelled()) throw ImportCancelledException()
                    val id = obj.jsonObject["workoutId"]?.jsonPrimitive?.content ?: continue
                    try {
                        auth.connectApi(tokens, "/workout-service/workout/$id", "DELETE").close()
                        removedDupes++
                    } catch (e: Exception) {
                        log("  FAIL удаления дубля workoutId=$id -> ${e.message}")
                    }
                    Thread.sleep(200)
                }
                log("Удалено дублей: $removedDupes")
            }
        } catch (e: Exception) {
            log("Не удалось проверить существующие тренировки перед загрузкой (${e.message}) — продолжаю без автоочистки.")
        }

        var ok = 0
        var fail = 0
        for (item in items) {
            if (isCancelled()) throw ImportCancelledException()
            try {
                val (postCode, postSuccessful, postText) = auth.connectApi(
                    tokens, "/workout-service/workout", "POST",
                    Json.encodeToString(JsonObject.serializer(), item.workoutJson)
                ).use { Triple(it.code, it.isSuccessful, it.body?.string() ?: "{}") }
                if (!postSuccessful) throw RuntimeException("HTTP $postCode: ${postText.take(300)}")
                val workoutId = Json.parseToJsonElement(postText).jsonObject["workoutId"]?.jsonPrimitive?.content
                    ?: throw RuntimeException("Ответ без workoutId: ${postText.take(300)}")
                val scheduleBody = buildJsonObject { put("date", item.date.toString()) }
                auth.connectApi(tokens, "/workout-service/schedule/$workoutId", "POST", Json.encodeToString(JsonObject.serializer(), scheduleBody)).close()
                log("OK   ${item.date}  ${item.name}  (id=$workoutId)")
                ok++
            } catch (e: Exception) {
                log("FAIL ${item.date}  ${item.name} -> ${e.message}")
                fail++
            }
            Thread.sleep(400) // как в десктопе — иначе Garmin API может резать частые запросы (429)
        }
        log("Готово: $ok создано, $fail с ошибкой.")
        return Result(ok = ok, fail = fail)
    }
}
