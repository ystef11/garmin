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

// ИСПРАВЛЕНО (ревью п.19 "Несовпадение плана и калькулятора геля"): раньше углеводы на
// тренировку для автозаполнения гель-калькулятора (см. PlanHtmlParser.fuelTotalGramsFromNote
// НИЖЕ) вытаскивались регэкспом из текста note - но у ОБЫЧНЫХ, сгенерированных buildPlan()
// тренировок (не через модалку ручного добавления/правки) питание кладётся в ОТДЕЛЬНОЕ поле
// wo.fuel={gph,total,gels,portion} (см. run_plan_calculator.html, строка "if(x.fuel)wo.fuel=
// x.fuel"), а note у них - это техническая заметка по тренировке (или её вообще нет), а не текст
// про питание. Из-за этого автозаполнение калькулятора геля реально работало только для
// тренировок, добавленных/отредактированных вручную через модалку (там note ДЕЙСТВИТЕЛЬНО
// содержит фразу "Питание ~X г/ч..."), а для подавляющего большинства обычных тренировок плана -
// нет. Плюс даже когда note-регэксп совпадал, из него доставался только total (граммы), а не
// portion (20 или 40 г - размер ПОКУПНОЙ порции геля, от которого plan.json считает число гелей,
// см. fuelFor() в run_plan_calculator.html) - калькулятор геля показывал число порций по своему
// дефолтному размеру порции (25 г), которое расходилось с числом гелей в самом плане. Теперь
// питание читается напрямую из структурированного поля fuel (оно уже есть в реальном plan.json
// для обычных тренировок), включая portion - ToolUrlBuilder передаёт и carbs, и portion.
@Serializable
data class PlanFuel(
    val gph: Int? = null,
    val total: Int? = null,
    val gels: Int? = null,
    val portion: Int? = null // размер покупной порции геля в граммах (20 или 40, см. fuelFor())
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
    val note: String? = null,
    val fuel: PlanFuel? = null
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
