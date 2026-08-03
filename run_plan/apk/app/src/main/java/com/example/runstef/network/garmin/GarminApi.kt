package com.example.runstef.network.garmin

import com.example.runstef.data.PlanStep
import com.example.runstef.data.PlanWorkout
import com.example.runstef.data.RunPlan
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

    private fun runWorkoutJson(w: PlanWorkout): JsonObject {
        val order = intArrayOf(1)
        val steps = (w.steps ?: emptyList()).map { convStep(it, order) }
        return buildJsonObject {
            putJsonObject("sportType") { put("sportTypeId", SPORT_RUN.id); put("sportTypeKey", SPORT_RUN.key) }
            put("workoutName", w.name.take(79))
            put("description", (w.note?.let { "$it || " } ?: "").take(1024))
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

    private fun simpleWorkoutJson(w: PlanWorkout, sport: SportType, defaultMins: Int): JsonObject {
        val mins = (w.mins ?: defaultMins.toDouble()).toInt()
        val step = buildJsonObject {
            put("type", "ExecutableStepDTO")
            putJsonObject("stepType") { put("stepTypeId", 7); put("stepTypeKey", "other") }
            putJsonObject("endCondition") { put("conditionTypeId", 2); put("conditionTypeKey", "time") }
            put("endConditionValue", (mins * 60).toDouble())
            putJsonObject("targetType") { put("workoutTargetTypeId", 1); put("workoutTargetTypeKey", "no.target") }
            put("description", (w.desc ?: "").take(512))
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
                "str" -> simpleWorkoutJson(w, SPORT_STR, 20) to SPORT_STR.key
                "cross" -> {
                    val sport = CROSS_SPORT[w.gtype] ?: SPORT_OTHER
                    simpleWorkoutJson(w, sport, 45) to sport.key
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
        clearAll: Boolean,
        clearPast: Boolean,
        clearBefore: LocalDate?
    ): Result {
        val tag = plan.meta.tag
        val allItems = buildItems(plan, skipCross)
        if (allItems.isEmpty()) throw RuntimeException("В плане нет тренировок для загрузки.")
        var items = allItems
        if (testFirstWeek) {
            val end = allItems.first().date.plusDays(7)
            items = allItems.filter { it.date < end }
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

        if (clearAll || clearPast) {
            val cutoff = clearBefore ?: LocalDate.now()
            val existingResp = auth.connectApi(tokens, "/workout-service/workouts?start=0&limit=999")
            val existingText = existingResp.body?.string() ?: "[]"
            existingResp.close()
            val existing = runCatching { Json.parseToJsonElement(existingText).jsonArray }.getOrNull() ?: return Result()
            val planDates = allItems.associateBy { it.name }
            val mine = existing.filter { (it.jsonObject["workoutName"]?.jsonPrimitive?.content ?: "").startsWith(tag) }
            val toDelete = if (clearPast) {
                mine.filter { obj ->
                    val name = obj.jsonObject["workoutName"]?.jsonPrimitive?.content ?: ""
                    val d = planDates[name]?.date ?: return@filter false
                    d < cutoff
                }
            } else mine
            var removed = 0
            for (obj in toDelete) {
                val id = obj.jsonObject["workoutId"]?.jsonPrimitive?.content ?: continue
                try {
                    auth.connectApi(tokens, "/workout-service/workout/$id", "DELETE").close()
                    removed++
                } catch (e: Exception) {
                    log("  FAIL удаления workoutId=$id -> ${e.message}")
                }
            }
            log("Удалено ранее загруженных тренировок этого плана: $removed")
            return Result(cleared = removed)
        }

        var ok = 0
        var fail = 0
        for (item in items) {
            try {
                val postResp = auth.connectApi(tokens, "/workout-service/workout", "POST", Json.encodeToString(JsonObject.serializer(), item.workoutJson))
                val postText = postResp.body?.string() ?: "{}"
                postResp.close()
                if (!postResp.isSuccessful) throw RuntimeException("HTTP ${postResp.code}: ${postText.take(300)}")
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
        }
        log("Готово: $ok создано, $fail с ошибкой.")
        return Result(ok = ok, fail = fail)
    }
}
