package com.example.runstef.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Модели соответствуют схеме plan.json (schema: 1), которую генерирует
 * run_plan_calculator.html и понимают garmin_plan_import.py / intervals_icu_import.py.
 */

val planJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

@Serializable
data class PlanMeta(
    val tag: String = "[GEN]",
    val name: String = "Беговой план",
    val marathon: String? = null,
    val schema: Int = 1
)

@Serializable
data class PlanTarget(
    val hr: Int? = null,
    val bpm: List<Int>? = null,
    val pace: List<String>? = null,
    val none: Int? = null
)

@Serializable
data class PlanStep(
    val t: String, // warmup|cooldown|interval|recovery|other|repeat
    val end: String? = null, // time|distance
    val v: Double? = null,
    val tg: PlanTarget? = null,
    val d: String? = null,
    val n: Int? = null, // повторы для t=repeat
    val steps: List<PlanStep>? = null
)

@Serializable
data class PlanWorkout(
    val date: String,
    val kind: String, // run|str|cross
    val name: String,
    val steps: List<PlanStep>? = null,
    val desc: String? = null,
    val mins: Double? = null,
    val gtype: String? = null, // для cross: cycling|lap_swimming|swimming|cardio_training|other|strength_training
    val note: String? = null
)

@Serializable
data class RunPlan(
    val meta: PlanMeta = PlanMeta(),
    val workouts: List<PlanWorkout> = emptyList()
)

/** Метаданные сохранённого файла плана в «Моих планах». */
data class SavedPlan(
    val fileName: String,
    val filePath: String,
    val savedAtMillis: Long,
    val plan: RunPlan
)
