package com.example.runstef.network

import com.example.runstef.data.PlanStep
import com.example.runstef.data.PlanTarget
import com.example.runstef.data.RunPlan
import com.example.runstef.data.PlanWorkout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/**
 * Порт intervals_icu_import.py: загрузка плана в intervals.icu как события
 * календаря (category=WORKOUT) через REST API с Basic-авторизацией по API key.
 */
class IntervalsApi(
    private val apiKey: String,
    private val athleteId: String,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val BASE = "https://intervals.icu/api/v1"
        private val TYPE_RUN = "Run"
        private val TYPE_STR = "WeightTraining"
        private val CROSS_TYPE = mapOf(
            "cycling" to "Ride",
            "lap_swimming" to "Swim",
            "swimming" to "Swim",
            "other" to "Workout",
            "cardio_training" to "Workout",
            "strength_training" to "WeightTraining"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Event(
        val startDateLocal: String,
        val name: String,
        val type: String,
        val description: String = "",
        val movingTimeSec: Int? = null
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("start_date_local", startDateLocal)
            put("category", "WORKOUT")
            put("name", name)
            put("type", type)
            put("description", description)
            movingTimeSec?.let { put("moving_time", it) }
        }
    }

    private fun paceSec(p: String): Int {
        val (m, s) = p.split(":")
        return m.toInt() * 60 + s.toInt()
    }

    private fun fmtDur(sec: Double): String {
        val i = Math.round(sec).toInt()
        return if (i >= 60 && i % 60 == 0) "${i / 60}m" else "${i}s"
    }

    private fun stepLines(st: PlanStep, indent: String = "- "): List<String> {
        if (st.t == "repeat") {
            val n = st.n ?: 1
            val out = mutableListOf("${n}x")
            (st.steps ?: emptyList()).forEach { sub ->
                out += stepLines(sub).map { "  $it" }
            }
            return out
        }
        val v = st.v ?: 0.0
        val tg = st.tg
        val durS: String = if (st.end == "distance") {
            val avg = if (tg?.pace != null) (paceSec(tg.pace[0]) + paceSec(tg.pace[1])) / 2.0 else 360.0
            fmtDur(v / 1000.0 * avg)
        } else {
            fmtDur(v)
        }
        val target = when {
            tg?.pace != null -> "${tg.pace[0]}-${tg.pace[1]}/km"
            tg?.hr != null -> "Z${tg.hr.coerceIn(1, 5)}"
            else -> "Z2"
        }
        return listOf("$indent$durS $target")
    }

    private fun runDescription(w: PlanWorkout): String {
        val lines = mutableListOf<String>()
        w.note?.let { lines += "# $it" }
        (w.steps ?: emptyList()).forEach { lines += stepLines(it) }
        return lines.joinToString("\n")
    }

    /** Возвращает событие или null, если кросс-тренировка исключена фильтром skipCross. */
    private fun eventFor(w: PlanWorkout, skipCross: Set<String>): Event? {
        val date = w.date
        return when (w.kind) {
            "cross" -> {
                val gt = (w.gtype ?: "").lowercase()
                if (skipCross.isNotEmpty() && ("all" in skipCross || "cross" in skipCross || gt in skipCross)) {
                    return null
                }
                Event(
                    startDateLocal = "${date}T00:00:00",
                    name = w.name,
                    type = CROSS_TYPE[w.gtype] ?: "Workout",
                    description = w.desc ?: "",
                    movingTimeSec = w.mins?.let { (it * 60).toInt() }
                )
            }
            "str" -> Event(
                startDateLocal = "${date}T00:00:00",
                name = w.name,
                type = TYPE_STR,
                description = w.desc ?: "",
                movingTimeSec = w.mins?.let { (it * 60).toInt() }
            )
            else -> Event(
                startDateLocal = "${date}T00:00:00",
                name = w.name,
                type = TYPE_RUN,
                description = runDescription(w)
            )
        }
    }

    private fun authHeader(): String = Credentials.basic("API_KEY", apiKey)

    private fun request(method: String, url: String, body: JsonObject? = null): String {
        val builder = Request.Builder().url(url).addHeader("Authorization", authHeader())
        val req = when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(
                (body?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: "{}")
                    .toRequestBody("application/json".toMediaType())
            )
            else -> throw IllegalArgumentException("method $method")
        }.build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("Ошибка API intervals.icu $method $url: ${resp.code} ${text.take(400)}")
            }
            return text
        }
    }

    data class Result(val ok: Int = 0, val cleared: Int = 0, val dryRun: Boolean = false, val count: Int = 0)

    fun upload(plan: RunPlan, skipCross: Set<String>, dryRun: Boolean, clear: Boolean): Result {
        val tag = plan.meta.tag
        val events = plan.workouts.mapNotNull { eventFor(it, skipCross) }.sortedBy { it.startDateLocal }
        if (events.isEmpty()) throw RuntimeException("В плане нет тренировок для импорта.")
        val d0 = events.first().startDateLocal.substring(0, 10)
        val d1 = events.last().startDateLocal.substring(0, 10)
        val runs = events.count { it.type == TYPE_RUN }
        val strs = events.count { it.type == TYPE_STR }
        val cross = events.size - runs - strs
        log("План: ${plan.meta.name} | тег $tag")
        log("Будет создано: ${events.size} (бег $runs, силовые $strs, кросс $cross); $d0 … $d1")

        if (dryRun) {
            log("Сухой прогон — ничего не отправлено. Отключите «Сухой прогон» для загрузки.")
            return Result(dryRun = true, count = events.size)
        }
        if (apiKey.isBlank() || athleteId.isBlank()) {
            throw RuntimeException("Нужны API key и Athlete ID (intervals.icu → Settings → Developer).")
        }

        val evUrl = "$BASE/athlete/$athleteId/events"
        var cleared = 0
        if (clear) {
            val listJson = request("GET", "$evUrl?oldest=$d0&newest=$d1&category=WORKOUT")
            val arr = runCatching { Json.parseToJsonElement(listJson).jsonArray }.getOrNull()
            arr?.forEach { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                if (name.startsWith(tag)) {
                    val id = obj["id"]?.jsonPrimitive?.content ?: obj["id"].toString()
                    request("DELETE", "$evUrl/$id")
                    cleared++
                }
            }
            log("Удалено ранее загруженных событий этого плана: $cleared")
        }

        var ok = 0
        for (e in events) {
            request("POST", evUrl, e.toJson())
            ok++
            if (ok % 10 == 0) log("  создано $ok/${events.size}…")
        }
        log("Готово: создано $ok запланированных тренировок в intervals.icu ($d0 … $d1).")
        return Result(ok = ok, cleared = cleared)
    }
}
