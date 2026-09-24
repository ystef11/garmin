package com.example.runstef.network.garmin

import com.example.runstef.data.ActivityRow
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.CrossActivityRow
import com.example.runstef.data.IntervalRow
import com.example.runstef.data.WellnessRow
import com.example.runstef.network.ImportCancelledException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Упрощённый Kotlin-порт garmin_activities_export.py: тренировки (только running-типы) +
 * пара wellness-показателей в сутки (RHR, HRV, качество сна). Полный набор (лапы, кросс-
 * тренировки, история ПАНО, десяток конфаундов EF) остаётся за десктопным скриптом — здесь
 * ровно то, что нужно для аналитики прямо на телефоне без Python.
 *
 * Использует тот же GarminAuth.connectApi (Bearer OAuth2), что и GarminApi (загрузка плана) —
 * один и тот же сохранённый токен/аккаунт обслуживает обе вкладки.
 */
/**
 * Правка 2026-09-24 (ревью п.6 "Одно пустое поле от Garmin обрывает весь импорт"): в
 * kotlinx.serialization `obj["x"]` для `"x": null` возвращает JsonNull (а не Kotlin-null) - это
 * НЕ JsonObject/JsonArray, поэтому обычный `.obj()`/`.arr()` на таком значении не
 * тихо даёт null, а бросает IllegalArgumentException ("Element class
 * kotlinx.serialization.json.JsonNull is not a JsonObject"). Эти хелперы вместо расширений
 * `.jsonObject`/`.jsonArray` возвращают null для ЛЮБОГО значения не того типа (включая
 * JsonNull), не бросая исключение - используются везде в этом файле вместо `.obj()`/
 * `.arr()`.
 */
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

class GarminActivitiesApi(
    private val auth: GarminAuth,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private val RUN_TYPE_KEYS = setOf(
            "running", "track_running", "trail_running", "treadmill_running",
            "street_running", "indoor_running", "virtual_run", "obstacle_run", "ultra_run"
        )
        private val LAP_ACTIVE_TYPES = setOf("INTERVAL_ACTIVE", "ACTIVE", "INTERVAL", "REPEAT", "WORK")
        private val LAP_REST_TYPES = setOf("INTERVAL_REST", "RECOVERY", "REST", "RECOVERY_ACTIVE")

        // Порт OUTDOOR_RUN_TYPE_KEYS из garmin_activities_export.py — только для этих типов
        // считается GAP (grade-adjusted pace): на дорожке/indoor/виртуальном беге уклона нет,
        // Garmin туда directGradeAdjustedSpeed не пишет (или он неинформативен).
        private val OUTDOOR_RUN_TYPE_KEYS = RUN_TYPE_KEYS - setOf(
            "treadmill_running", "indoor_running", "virtual_run"
        )
        // Порт п.13 ревью — типы, для которых температура с датчика часов (directAirTemperature/
        // directTemperature из посекундного потока /details) не запрашивается: в помещении/на
        // дорожке нет физического смысла в "уличной" температуре, это лишний сетевой запрос.
        private val INDOOR_RUN_TYPE_KEYS = setOf("treadmill_running", "indoor_running")
        private val GRADE_ADJUSTED_SPEED_KEY_CANDIDATES = listOf("directGradeAdjustedSpeed", "gradeAdjustedSpeed")
        private val GRADE_ADJUSTED_DISTANCE_KEY_CANDIDATES = listOf("sumDistance")

        // Порт CROSS_TYPE_GROUPS/CROSS_TYPE_KEY_TO_GROUP из garmin_activities_export.py —
        // только суммарная нагрузка (не беговые оси), нужна для контекста системной усталости.
        private val CROSS_TYPE_GROUPS = mapOf(
            "cycling" to setOf(
                "cycling", "road_biking", "indoor_cycling", "mountain_biking", "gravel_cycling",
                "virtual_ride", "cyclocross", "track_cycling", "recumbent_cycling", "e_bike_fitness"
            ),
            "skiing" to setOf(
                "resort_skiing_snowboarding_ws", "skate_skiing_ws", "classic_skiing_ws",
                "backcountry_skiing_ws", "cross_country_skiing_ws", "resort_skiing",
                "cross_country_skiing", "backcountry_skiing", "skate_skiing"
            ),
            "swimming" to setOf("lap_swimming", "open_water_swimming"),
            "strength_training" to setOf("strength_training")
        )
        private val CROSS_TYPE_KEY_TO_GROUP: Map<String, String> =
            CROSS_TYPE_GROUPS.entries.flatMap { (group, keys) -> keys.map { it to group } }.toMap()
    }

    /** Группа кросс-тренировки (cycling/skiing/swimming/strength_training) по ключу спорта
     * Garmin, или null, если это не кросс-тип (например, бег или что-то не размеченное). */
    fun crossGroup(sportKey: String?): String? = sportKey?.lowercase()?.let { CROSS_TYPE_KEY_TO_GROUP[it] }

    data class ImportResult(val fetched: Int, val wellnessDays: Int)

    private fun bodyOf(resp: okhttp3.Response): String =
        // .use{} гарантирует close() даже если .string() бросит исключение посреди чтения
        // (оборванный поток/кодировка) — раньше close() был отдельной строкой ПОСЛЕ .string()
        // и пропускался при таком исключении, оставляя соединение открытым до GC.
        resp.use { it.body?.string() ?: "{}" }

    /** displayName (не email!) — часть wellness-эндпоинтов требует его в пути URL, см. Python-докстринг
     * resolve_display_name в garmin_activities_export.py: с email часть эндпоинтов отвечает 403. */
    fun resolveDisplayName(tokens: GarminTokens): String? {
        return try {
            val resp = auth.connectApi(tokens, "/userprofile-service/socialProfile")
            val text = bodyOf(resp)
            Json.parseToJsonElement(text).jsonObject["displayName"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchActivitiesPage(tokens: GarminTokens, start: Int, limit: Int, startDate: String, endDate: String, activityType: String?): List<JsonObject> {
        val typeParam = activityType?.let { "&activityType=$it" } ?: ""
        val path = "/activitylist-service/activities/search/activities" +
            "?limit=$limit&start=$start&startDate=$startDate&endDate=$endDate$typeParam"
        val resp = auth.connectApi(tokens, path)
        val text = bodyOf(resp)
        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
        val arr = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return arr.map { it.jsonObject }
    }

    /** Без фильтра activityType — все виды спорта за период (бег + кросс: вело/лыжи/плавание/
     * силовая), как fetch_activities(..., activity_type=None) в garmin_activities_export.py.
     * Один проход достаточен и для беговых, и для кросс-тренировок — toActivityRow/
     * toCrossActivityRow сами отбирают "свои" типы, остальное молча пропускают. */
    fun fetchAllActivities(tokens: GarminTokens, startDate: String, endDate: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var start = 0
        val limitBatch = 100
        while (true) {
            val batch = fetchActivitiesPage(tokens, start, limitBatch, startDate, endDate, activityType = null)
            if (batch.isEmpty()) break
            out.addAll(batch)
            if (batch.size < limitBatch || out.size >= 2000) break
            start += limitBatch
        }
        return out
    }

    fun fetchRunningActivities(tokens: GarminTokens, startDate: String, endDate: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var start = 0
        val limitBatch = 100
        while (true) {
            val batch = fetchActivitiesPage(tokens, start, limitBatch, startDate, endDate, activityType = "running")
            if (batch.isEmpty()) break
            out.addAll(batch)
            if (batch.size < limitBatch || out.size >= 2000) break
            start += limitBatch
        }
        return out
    }

    private fun sPerKm(speedMs: Double?): Double? {
        if (speedMs == null || speedMs <= 0.0) return null
        // Порт s_per_km() из garmin_activities_export.py — round(1000.0/speed_m_s, 1).
        return Math.round(1000.0 / speedMs * 10) / 10.0
    }

    // ===== Порт _deep_find_key/_first_present/_EF_CONFOUND_FIELD_CANDIDATES/_extract_ef_confounds
    // из garmin_activities_export.py (~строки 628-750). Рекурсивный (глубина <=6, без учёта
    // регистра) поиск скалярного значения ключа по всему дереву JSON активности — нужен, т.к.
    // неофициальный API Garmin в разных версиях/эндпоинтах кладёт одно и то же поле то на
    // верхний уровень, то во вложенный summaryDTO. Все эти поля уже приходят в ТОМ ЖЕ bulk-
    // ответе списка активностей, который toActivityRow() и так получает — доп. сетевых
    // запросов НЕ требуется (кроме impact_load, который у десктопа тоже best-effort из этого
    // же bulk-ответа per _EF_CONFOUND_FIELD_CANDIDATES, а НЕ из отдельного detail-запроса, если
    // detail не запрашивается отдельно; апк тоже ограничивается bulk-ответом).
    private fun deepFindKey(element: JsonElement, keyName: String, depth: Int = 0): JsonPrimitive? {
        if (depth > 6) return null
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k.equals(keyName, ignoreCase = true) && v is JsonPrimitive) return v
                }
                for (v in element.values) {
                    val found = deepFindKey(v, keyName, depth + 1)
                    if (found != null) return found
                }
            }
            is JsonArray -> {
                for (item in element.take(3)) {
                    val found = deepFindKey(item, keyName, depth + 1)
                    if (found != null) return found
                }
            }
            else -> {}
        }
        return null
    }

    private fun firstPresent(act: JsonObject, keys: List<String>): JsonPrimitive? {
        for (k in keys) {
            val v = deepFindKey(act, k)
            if (v != null) return v
        }
        return null
    }

    private val EF_CONFOUND_FIELD_CANDIDATES: Map<String, List<String>> = mapOf(
        "elevation_gain_m" to listOf("elevationGain", "elevationGainInMeter", "elevationGainMeters"),
        "elevation_loss_m" to listOf("elevationLoss", "elevationLossInMeter", "elevationLossMeters"),
        "min_temperature" to listOf("minTemperature", "minTemp"),
        "max_temperature" to listOf("maxTemperature", "maxTemp"),
        "avg_cadence_spm" to listOf("averageRunningCadenceInStepsPerMinute", "avgRunCadence", "averageBikingCadenceInRevPerMinute"),
        "avg_stride_length_mm" to listOf("avgStrideLength", "averageStrideLength"),
        "calories" to listOf("calories"),
        "aerobic_training_effect" to listOf("aerobicTrainingEffect"),
        "anaerobic_training_effect" to listOf("anaerobicTrainingEffect"),
        "manual_activity" to listOf("manualActivity", "manual"),
        "elevation_corrected" to listOf("elevationCorrected"),
        "water_estimated_ml" to listOf("waterEstimated"),
        "impact_load" to listOf("impactLoad"),
        "activity_training_load" to listOf("activityTrainingLoad"),
        "difference_body_battery" to listOf("differenceBodyBattery"),
        "moderate_intensity_min" to listOf("moderateIntensityMinutes"),
        "vigorous_intensity_min" to listOf("vigorousIntensityMinutes"),
        "hr_time_in_zone_1" to listOf("hrTimeInZone_1"),
        "hr_time_in_zone_2" to listOf("hrTimeInZone_2"),
        "hr_time_in_zone_3" to listOf("hrTimeInZone_3"),
        "hr_time_in_zone_4" to listOf("hrTimeInZone_4"),
        "hr_time_in_zone_5" to listOf("hrTimeInZone_5")
    )

    /** Порт extract_ef_confounds() — набор confound-полей EF для одной активности из bulk-JSON. */
    private data class EfConfounds(
        val elevationGainM: Double?, val elevationLossM: Double?, val avgTemperatureC: Double?,
        val avgCadenceSpm: Double?, val avgStrideLengthM: Double?, val calories: Double?,
        val aerobicTrainingEffect: Double?, val anaerobicTrainingEffect: Double?,
        val manualActivity: Boolean?, val elevationCorrected: Boolean?,
        val waterEstimatedMl: Double?, val impactLoad: Double?, val activityTrainingLoad: Double?,
        val differenceBodyBattery: Int?, val moderateIntensityMin: Double?, val vigorousIntensityMin: Double?,
        val hrZone1: Double?, val hrZone2: Double?, val hrZone3: Double?, val hrZone4: Double?, val hrZone5: Double?
    )

    private fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    /** temperatureUnit — "c"/"f", порт --temperature-unit из garmin_activities_export.py:
     * Garmin не документирует единицу temperature в ответе, она зависит от настроек аккаунта
     * (см. _fahrenheit_to_celsius/_extract_ef_confounds, temperature_unit="c" по умолчанию). */
    private fun extractEfConfounds(act: JsonObject, temperatureUnit: String = "c"): EfConfounds {
        fun d(key: String): Double? = firstPresent(act, EF_CONFOUND_FIELD_CANDIDATES.getValue(key))?.doubleOrNull
        fun boolLike(key: String): Boolean? {
            val v = firstPresent(act, EF_CONFOUND_FIELD_CANDIDATES.getValue(key)) ?: return null
            return v.booleanOrNull ?: ((v.doubleOrNull ?: 0.0) != 0.0)
        }
        val elevationGainM = d("elevation_gain_m")?.let { Math.round(it * 10) / 10.0 }
        val elevationLossM = d("elevation_loss_m")?.let { Math.round(it * 10) / 10.0 }
        val temps = listOfNotNull(d("min_temperature"), d("max_temperature"))
        val avgTemperatureC = if (temps.isNotEmpty()) {
            val avgRaw = temps.average()
            val celsius = if (temperatureUnit == "f") fahrenheitToCelsius(avgRaw) else avgRaw
            Math.round(celsius * 10) / 10.0
        } else null
        val avgCadenceSpm = d("avg_cadence_spm")?.let { Math.round(it * 10) / 10.0 }
        val strideRaw = d("avg_stride_length_mm")
        val avgStrideLengthM = strideRaw?.let {
            if (it > 5) Math.round(it / 1000.0 * 1000) / 1000.0 else Math.round(it * 1000) / 1000.0
        }
        val calories = d("calories")?.let { Math.round(it).toDouble() }
        val differenceBodyBattery = d("difference_body_battery")?.let { Math.round(it).toInt() }
        return EfConfounds(
            elevationGainM = elevationGainM, elevationLossM = elevationLossM, avgTemperatureC = avgTemperatureC,
            avgCadenceSpm = avgCadenceSpm, avgStrideLengthM = avgStrideLengthM, calories = calories,
            aerobicTrainingEffect = d("aerobic_training_effect"), anaerobicTrainingEffect = d("anaerobic_training_effect"),
            manualActivity = boolLike("manual_activity"), elevationCorrected = boolLike("elevation_corrected"),
            waterEstimatedMl = d("water_estimated_ml"), impactLoad = d("impact_load"),
            activityTrainingLoad = d("activity_training_load"), differenceBodyBattery = differenceBodyBattery,
            moderateIntensityMin = d("moderate_intensity_min"), vigorousIntensityMin = d("vigorous_intensity_min"),
            hrZone1 = d("hr_time_in_zone_1"), hrZone2 = d("hr_time_in_zone_2"), hrZone3 = d("hr_time_in_zone_3"),
            hrZone4 = d("hr_time_in_zone_4"), hrZone5 = d("hr_time_in_zone_5")
        )
    }

    private fun toActivityRow(act: JsonObject): ActivityRow? {
        val activityId = act["activityId"]?.jsonPrimitive?.longOrNull ?: return null
        val startLocal = act["startTimeLocal"]?.jsonPrimitive?.contentOrNull ?: return null
        val date = startLocal.split(" ").firstOrNull() ?: return null
        val name = act["activityName"]?.jsonPrimitive?.contentOrNull ?: ""
        val sportKey = act["activityType"].obj()?.get("typeKey")?.jsonPrimitive?.contentOrNull
        if (sportKey == null || sportKey.lowercase() !in RUN_TYPE_KEYS) return null
        val duration = act["duration"]?.jsonPrimitive?.doubleOrNull
        val distance = act["distance"]?.jsonPrimitive?.doubleOrNull
        val avgHr = act["averageHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        val maxHr = act["maxHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        val avgSpeed = act["averageSpeed"]?.jsonPrimitive?.doubleOrNull
        val pace = sPerKm(avgSpeed) ?: if (distance != null && duration != null && distance > 0)
            duration / (distance / 1000.0) else null
        // Confound'ы здесь считаются ТОЛЬКО из bulk-ответа — предварительный проход (см.
        // importRange), чтобы отдать список беговых строк ДО сетевого детейл-запроса на каждую
        // активность. Финальные confound-поля (в т.ч. impact_load, который есть ТОЛЬКО в
        // detail-объекте) пересчитываются и перезаписываются в importRange() после мержа с
        // /activity-service/activity/{id} — см. extractEfConfounds(mergeActivityDetail(...)).
        val confounds = extractEfConfounds(act)
        return ActivityRow(
            activityId = activityId,
            date = date,
            startTime = startLocal,
            name = name,
            sport = sportKey,
            durationS = duration,
            distanceM = distance,
            avgHr = avgHr,
            maxHr = maxHr,
            avgPaceSPerKm = pace,
            typeGuess = null, // классификация — отдельным проходом, см. classifyAll() (нужен общий rest/max HR периода)
            elevationGainM = confounds.elevationGainM,
            elevationLossM = confounds.elevationLossM,
            avgTemperatureC = confounds.avgTemperatureC,
            avgCadenceSpm = confounds.avgCadenceSpm,
            avgStrideLengthM = confounds.avgStrideLengthM,
            calories = confounds.calories,
            aerobicTrainingEffect = confounds.aerobicTrainingEffect,
            anaerobicTrainingEffect = confounds.anaerobicTrainingEffect,
            manualActivity = confounds.manualActivity,
            elevationCorrected = confounds.elevationCorrected,
            waterEstimatedMl = confounds.waterEstimatedMl,
            impactLoad = confounds.impactLoad,
            activityTrainingLoad = confounds.activityTrainingLoad,
            differenceBodyBattery = confounds.differenceBodyBattery,
            moderateIntensityMin = confounds.moderateIntensityMin,
            vigorousIntensityMin = confounds.vigorousIntensityMin,
            hrTimeInZone1 = confounds.hrZone1,
            hrTimeInZone2 = confounds.hrZone2,
            hrTimeInZone3 = confounds.hrZone3,
            hrTimeInZone4 = confounds.hrZone4,
            hrTimeInZone5 = confounds.hrZone5
        )
    }

    /**
     * Порт upsert_cross_activity(): активность не-бег (кросс-тренировка), сгруппированная по
     * cross_group() — cycling/skiing/swimming/strength_training. Возвращает null, если тип
     * активности не входит ни в одну известную кросс-группу (десктоп в этом случае тоже
     * игнорирует активность для целей "суммарной нагрузки" по кросс-группам).
     */
    private fun toCrossActivityRow(act: JsonObject): CrossActivityRow? {
        val activityId = act["activityId"]?.jsonPrimitive?.longOrNull ?: return null
        val startLocal = act["startTimeLocal"]?.jsonPrimitive?.contentOrNull ?: return null
        val date = startLocal.split(" ").firstOrNull() ?: return null
        val sportKey = act["activityType"].obj()?.get("typeKey")?.jsonPrimitive?.contentOrNull
        val group = crossGroup(sportKey) ?: return null
        val name = act["activityName"]?.jsonPrimitive?.contentOrNull ?: ""
        val duration = act["duration"]?.jsonPrimitive?.doubleOrNull
        val distance = act["distance"]?.jsonPrimitive?.doubleOrNull
        val avgHr = act["averageHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        val maxHr = act["maxHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        return CrossActivityRow(
            activityId = activityId,
            date = date,
            name = name,
            sport = group,
            durationS = duration,
            distanceM = distance,
            avgHr = avgHr,
            maxHr = maxHr
        )
    }

    fun fetchDailySummary(tokens: GarminTokens, username: String, date: String): JsonObject? {
        return try {
            val resp = auth.connectApi(tokens, "/usersummary-service/usersummary/daily/$username?calendarDate=$date")
            if (!resp.isSuccessful) { resp.close(); return null }
            Json.parseToJsonElement(bodyOf(resp)).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    fun fetchHrv(tokens: GarminTokens, date: String): JsonObject? {
        return try {
            val resp = auth.connectApi(tokens, "/hrv-service/hrv/$date")
            if (!resp.isSuccessful) { resp.close(); return null }
            Json.parseToJsonElement(bodyOf(resp)).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    // ===== Порт fetch_sleep_day/fetch_stress_day/fetch_training_readiness_day/
    // fetch_body_battery_range/fetch_wellness_day из garmin_activities_export.py (~339-590) —
    // остальные wellness-конфаунды (сон по стадиям/SpO2/стресс/body battery/training readiness/
    // шаги/floors_ascended), которых раньше не было вообще ни в схеме, ни в коде. Каждый —
    // отдельный HTTP GET, как и в десктопе (Гармин не отдаёт их одним общим ответом).

    private fun safeGetJsonObject(tokens: GarminTokens, path: String): JsonObject? {
        return try {
            val resp = auth.connectApi(tokens, path)
            if (!resp.isSuccessful) { resp.close(); return null }
            val text = bodyOf(resp)
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Порт fetch_sleep_day(): стадии сна + независимые физиологические сигналы (SpO2 во сне,
     * стресс во сне, отклонение температуры кожи, RHR по данным сна, categorical training
     * feedback). Пустая карта, если сна за эту дату нет вообще (sleepTimeSeconds отсутствует). */
    private fun fetchSleepDay(tokens: GarminTokens, username: String, date: String): Map<String, Any?> {
        val data = safeGetJsonObject(tokens, "/wellness-service/wellness/dailySleepData/$username?date=$date&nonSleepBufferMinutes=60")
            ?: return emptyMap()
        val dto = data["dailySleepDTO"].obj() ?: return emptyMap()
        val sleepTimeS = dto["sleepTimeSeconds"]?.jsonPrimitive?.doubleOrNull
        if (sleepTimeS == null || sleepTimeS == 0.0) return emptyMap()
        val overall = dto["sleepScores"].obj()?.get("overall").obj()
        val sleepNeed = dto["sleepNeed"].obj()
        return mapOf(
            "sleep_score" to overall?.get("value")?.jsonPrimitive?.intOrNull,
            "sleep_duration_s" to sleepTimeS,
            "sleep_deep_s" to dto["deepSleepSeconds"]?.jsonPrimitive?.doubleOrNull,
            "sleep_light_s" to dto["lightSleepSeconds"]?.jsonPrimitive?.doubleOrNull,
            "sleep_rem_s" to dto["remSleepSeconds"]?.jsonPrimitive?.doubleOrNull,
            "sleep_awake_s" to dto["awakeSleepSeconds"]?.jsonPrimitive?.doubleOrNull,
            "sleep_avg_resp" to (data["avgSleepRespirationValue"] ?: dto["averageRespirationValue"])?.jsonPrimitive?.doubleOrNull,
            "avg_sleep_stress" to dto["avgSleepStress"]?.jsonPrimitive?.doubleOrNull,
            "sleep_spo2_avg" to dto["averageSpO2Value"]?.jsonPrimitive?.doubleOrNull,
            "sleep_spo2_min" to dto["lowestSpO2Value"]?.jsonPrimitive?.intOrNull,
            "sleep_rhr" to data["restingHeartRate"]?.jsonPrimitive?.intOrNull,
            "skin_temp_deviation_c" to data["avgSkinTempDeviationC"]?.jsonPrimitive?.doubleOrNull,
            "sleep_training_feedback" to sleepNeed?.get("trainingFeedback")?.jsonPrimitive?.contentOrNull,
            "sleep_start_local" to localMsToIso(dto["sleepStartTimestampLocal"]?.jsonPrimitive?.longOrNull),
            "sleep_end_local" to localMsToIso(dto["sleepEndTimestampLocal"]?.jsonPrimitive?.longOrNull)
        )
    }

    /** Порт _local_ms_to_iso(): sleep{Start,End}TimestampLocal — эпоха в миллисекундах, где
     * число уже закодировано как локальное время суток (Garmin представляет местное время так,
     * будто оно UTC, без реального смещения) — поэтому конвертируем через UTC-эпоху, а не через
     * системный часовой пояс устройства. */
    private fun localMsToIso(ms: Long?): String? {
        if (ms == null) return null
        return try {
            java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (e: Exception) {
            null
        }
    }

    /** Порт fetch_daily_summary_day(): RHR/шаги/калории + разбивка дневного стресса
     * (rest/activity/uncategorized, low/medium/high) + floorsAscended — один и тот же запрос,
     * что fetchDailySummary() уже делает для RHR/HRV в старом коде, здесь просто читаем
     * дополнительные поля из того же JsonObject. */
    private fun dailySummaryWellnessFields(daily: JsonObject?): Map<String, Any?> {
        if (daily == null) return emptyMap()
        return mapOf(
            "rhr" to daily["restingHeartRate"]?.jsonPrimitive?.intOrNull,
            "steps" to daily["totalSteps"]?.jsonPrimitive?.intOrNull,
            "active_calories" to daily["activeKilocalories"]?.jsonPrimitive?.intOrNull,
            "floors_ascended" to daily["floorsAscended"]?.jsonPrimitive?.doubleOrNull,
            "stress_rest_s" to daily["restStressDuration"]?.jsonPrimitive?.doubleOrNull,
            "stress_activity_s" to daily["activityStressDuration"]?.jsonPrimitive?.doubleOrNull,
            "stress_uncategorized_s" to daily["uncategorizedStressDuration"]?.jsonPrimitive?.doubleOrNull,
            "stress_low_s" to daily["lowStressDuration"]?.jsonPrimitive?.doubleOrNull,
            "stress_medium_s" to daily["mediumStressDuration"]?.jsonPrimitive?.doubleOrNull,
            "stress_high_s" to daily["highStressDuration"]?.jsonPrimitive?.doubleOrNull
        )
    }

    /** Порт fetch_stress_day(): среднее/макс. значение стресса за день (0..100, шкала Гармин). */
    private fun fetchStressDay(tokens: GarminTokens, date: String): Map<String, Any?> {
        val data = safeGetJsonObject(tokens, "/wellness-service/wellness/dailyStress/$date") ?: return emptyMap()
        return mapOf(
            "stress_avg" to data["avgStressLevel"]?.jsonPrimitive?.intOrNull,
            "stress_max" to data["maxStressLevel"]?.jsonPrimitive?.intOrNull
        )
    }

    /** Порт fetch_training_readiness_day(): ответ — список (обычно из одного элемента) или
     * объект напрямую, в зависимости от версии эндпоинта — берём первый элемент, если список. */
    private fun fetchTrainingReadinessDay(tokens: GarminTokens, date: String): Map<String, Any?> {
        return try {
            val resp = auth.connectApi(tokens, "/metrics-service/metrics/trainingreadiness/$date")
            if (!resp.isSuccessful) { resp.close(); return emptyMap() }
            val text = bodyOf(resp)
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyMap()
            val row = when {
                root is kotlinx.serialization.json.JsonArray -> root.firstOrNull().obj()
                root is JsonObject -> root
                else -> null
            } ?: return emptyMap()
            mapOf(
                "training_readiness_score" to row["score"]?.jsonPrimitive?.intOrNull,
                "training_readiness_level" to row["level"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private data class BodyBatteryDay(
        val min: Int?, val max: Int?, val charged: Int?, val drained: Int?
    )

    /** Порт fetch_body_battery_range(): в отличие от остальных wellness-метрик отдаётся
     * ДИАПАЗОНОМ за один запрос — бьём на кусочки по 28 дней (то же неофициальное ограничение
     * ширины окна, что и в десктопе). */
    private fun fetchBodyBatteryRange(tokens: GarminTokens, startDate: LocalDate, endDate: LocalDate): Map<String, BodyBatteryDay> {
        val out = mutableMapOf<String, BodyBatteryDay>()
        var cur = startDate
        while (!cur.isAfter(endDate)) {
            val chunkEnd = minOf(cur.plusDays(27), endDate)
            try {
                val resp = auth.connectApi(
                    tokens,
                    "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$cur&endDate=$chunkEnd"
                )
                if (resp.isSuccessful) {
                    val text = bodyOf(resp)
                    val arr = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull()
                    arr?.forEach { el ->
                        val row = el.jsonObject
                        val d = (row["date"] ?: row["calendarDate"])?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val values = row["bodyBatteryValuesArray"].arr()?.mapNotNull { pair ->
                            val p = pair.jsonArray
                            if (p.size > 1) p[1].jsonPrimitive.doubleOrNull else null
                        } ?: emptyList()
                        out[d] = BodyBatteryDay(
                            min = values.minOrNull()?.let { Math.round(it).toInt() },
                            max = values.maxOrNull()?.let { Math.round(it).toInt() },
                            charged = row["charged"]?.jsonPrimitive?.intOrNull,
                            drained = row["drained"]?.jsonPrimitive?.intOrNull
                        )
                    }
                } else {
                    resp.close()
                }
            } catch (e: Exception) {
                // best-effort — пропускаем кусок, остальные пробуем
            }
            cur = chunkEnd.plusDays(1)
        }
        return out
    }


    /**
     * Грубый фолбэк-классификатор — используется, только если лапы не пришли вообще (см.
     * classifyByLaps ниже, это основной путь) — например, тренировка без сети на дорожке без
     * структуры и Гармин не отдал ни typed-, ни plain-сплиты:
     *  - "long"    — длительность >= longThresholdMin;
     *  - "quality" — средний пульс тренировки заметно выше "лёгкого" диапазона (>=80% от
     *                размаха restHr..maxHr периода);
     *  - "easy"    — всё остальное.
     */
    fun classify(durationS: Double?, avgHr: Int?, restHr: Int?, maxHr: Int?, longThresholdMin: Double = 75.0): String {
        val minutes = (durationS ?: 0.0) / 60.0
        if (minutes >= longThresholdMin) return "long"
        if (avgHr != null && restHr != null && maxHr != null && maxHr > restHr) {
            val frac = (avgHr - restHr).toDouble() / (maxHr - restHr).toDouble()
            if (frac >= 0.80) return "quality"
        }
        return "easy"
    }

    /** z2Hi/z4Lo (уд/мин) для classifyByLaps — упрощённый Карвонен (%HRR) БЕЗ настоящего ПАНО
     * (в отличие от build_zones_for_classifier() в десктопном garmin_activities_export.py,
     * которому нужна история lactate_threshold — на apk она пока не выгружается, см. doc класса
     * и memory-файл: это одна из очередей на дальнейший перенос). z2Hi ≈ верх лёгкой зоны
     * (60% HRR), z4Lo ≈ низ порогового усилия (80% HRR) — достаточно грубо для гейта в
     * classifyByLaps, но не заменяет полноценные зоны из десктопного отчёта. */
    data class HrZones(val z2Hi: Int, val z4Lo: Int)

    fun estimateHrZones(restHr: Int?, maxHr: Int?): HrZones? {
        if (restHr == null || maxHr == null || maxHr <= restHr) return null
        val hrr = (maxHr - restHr).toDouble()
        val z2Hi = Math.round(restHr + 0.60 * hrr).toInt()
        val z4Lo = Math.round(restHr + 0.80 * hrr).toInt()
        return HrZones(z2Hi, z4Lo)
    }

    /** Порт build_zones_for_classifier() из build_report.py (раздел 3 "Пульсовые зоны и
     * актуальный темп") — та же методика Карвонена (%HRR) + НАСТОЯЩЕЕ ПАНО (из истории
     * lactate_threshold в БД, а не приближение) как верх Z4. Возвращает то же, что нужно
     * classifyByLaps: z2Hi (верх лёгкой/аэробной зоны) и z4Lo (низ порогового усилия). */
    fun buildZonesForClassifier(rhr: Double, pano: Int, maxHr: Int, z3Hrr: Double = 0.70): HrZones {
        val hrr = maxHr - rhr
        val z3LoHrr = Math.round(rhr + z3Hrr * hrr).toInt()
        val z4Hi = pano
        val z2Hi = z3LoHrr - 1
        val z3Lo = z2Hi + 1
        val z4Lo = Math.round((z3Lo + z4Hi) / 2.0).toInt()
        return HrZones(z2Hi, z4Lo)
    }

    /** Порт estimate_hr_zones() — настоящие зоны из уже накопленной в БД истории (ПАНО из
     * lactate_threshold, RHR из wellness, max_hr из фактических тренировок). null, если в БД
     * ещё нет истории ПАНО вообще (самый первый импорт) — тогда вызывающий код должен
     * откатиться на приближённый estimateHrZones(restHr, maxHr) (см. importRange). */
    fun estimateRealHrZones(db: AnalyticsDb, fallbackMaxHr: Int?): HrZones? {
        val pano = db.panoFromDb() ?: return null
        val rhr = db.restHrFromDb()
        val maxHr = db.maxHrFromDb(fallbackMaxHr)
        return buildZonesForClassifier(rhr, pano, maxHr)
    }

    /** Порт fetch_lactate_threshold_range() из garmin_activities_export.py: временной ряд ПАНО
     * (порознь пульс и темп, объединяются по дате) из biometric-service, с фолбэком на
     * "последнее известное значение" (latestLactateThreshold), если истории по датам нет вовсе.
     * Диапазон бьётся на куски <= 365 дней — /range/{start}/{end} отвечает 400 на более широких
     * диапазонах (см. докстринг оригинала). Возвращает map(date -> Triple(thresholdHr,
     * thresholdPaceSPerKm, source)); может быть пустой картой, если Гармин ни разу не считал
     * ПАНО для этого аккаунта/спорта — это не ошибка, просто нет данных. */
    fun fetchLactateThresholdRange(tokens: GarminTokens, startDate: LocalDate, endDate: LocalDate): Map<String, Triple<Int?, Double?, String>> {
        fun safeGet(path: String): kotlinx.serialization.json.JsonElement? {
            return try {
                val resp = auth.connectApi(tokens, path)
                val text = bodyOf(resp)
                if (!resp.isSuccessful) return null
                runCatching { Json.parseToJsonElement(text) }.getOrNull()
            } catch (e: Exception) {
                null
            }
        }

        fun chunks(): List<Pair<LocalDate, LocalDate>> {
            val out = mutableListOf<Pair<LocalDate, LocalDate>>()
            var cur = startDate
            while (!cur.isAfter(endDate)) {
                val chunkEnd = minOf(cur.plusDays(365), endDate)
                out.add(cur to chunkEnd)
                cur = chunkEnd.plusDays(1)
            }
            return out
        }

        fun rowsOf(el: kotlinx.serialization.json.JsonElement?): List<JsonObject> {
            if (el == null) return emptyList()
            return when (el) {
                is kotlinx.serialization.json.JsonArray -> el.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(el)
                else -> emptyList()
            }
        }

        fun rowDate(o: JsonObject): String? =
            o["until"]?.jsonPrimitive?.contentOrNull
                ?: o["updatedDate"]?.jsonPrimitive?.contentOrNull
                ?: o["from"]?.jsonPrimitive?.contentOrNull
                ?: o["calendarDate"]?.jsonPrimitive?.contentOrNull
                ?: o["date"]?.jsonPrimitive?.contentOrNull

        fun fetchRange(metricPath: String): List<JsonObject> {
            val merged = mutableListOf<JsonObject>()
            for ((cStart, cEnd) in chunks()) {
                val path = "/biometric-service/stats/$metricPath/range/$cStart/$cEnd" +
                    "?sport=RUNNING&aggregation=daily&aggregationStrategy=LATEST"
                merged.addAll(rowsOf(safeGet(path)))
            }
            return merged
        }

        val out = mutableMapOf<String, Pair<Int?, Double?>>()
        for (row in fetchRange("lactateThresholdHeartRate")) {
            val date = rowDate(row) ?: continue
            val hr = row["value"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
                ?: row["lactateThresholdHeartRate"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
                ?: row["heartRate"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
            if (hr != null) {
                val prev = out[date] ?: (null to null)
                out[date] = hr to prev.second
            }
        }
        for (row in fetchRange("lactateThresholdSpeed")) {
            val date = rowDate(row) ?: continue
            val speed = row["value"]?.jsonPrimitive?.doubleOrNull
                ?: row["lactateThresholdSpeed"]?.jsonPrimitive?.doubleOrNull
            if (speed != null && speed > 0) {
                val prev = out[date] ?: (null to null)
                out[date] = prev.first to sPerKm(speed)
            }
        }
        if (out.isNotEmpty()) {
            return out.mapValues { (_, v) -> Triple(v.first, v.second, "biometric-service/stats/lactateThreshold{HeartRate,Speed}/range") }
        }

        // Фолбэк — только последнее известное значение (не ряд по датам): элементы списка
        // относятся к разным видам спорта, беговой пульс лежит в поле "hearRate" (без опечатки
        // на нашей стороне — так исторически называется у Garmin), скорость — в "speed".
        val latest = safeGet("/biometric-service/biometric/latestLactateThreshold")
        val latestRows = rowsOf(latest)
        var hr: Int? = null
        var speed: Double? = null
        var dateStr: String? = null
        for (row in latestRows) {
            val rowDateVal = row["calendarDate"]?.jsonPrimitive?.contentOrNull
            val rowHr = (row["hearRate"] ?: row["heartRate"])?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
            val rowSpeed = row["speed"]?.jsonPrimitive?.doubleOrNull
            if (rowHr != null && (hr == null || (rowDateVal ?: "") > (dateStr ?: ""))) {
                hr = rowHr
                dateStr = rowDateVal ?: dateStr
            }
            if (rowSpeed != null && rowSpeed > 0 && (speed == null || (rowDateVal ?: "") >= (dateStr ?: ""))) {
                speed = rowSpeed
                dateStr = dateStr ?: rowDateVal
            }
        }
        val finalDate = dateStr ?: endDate.toString()
        if (hr != null || speed != null) {
            return mapOf(finalDate to Triple(hr, speed?.let { sPerKm(it) }, "biometric-service/biometric/latestLactateThreshold (только последнее значение)"))
        }
        return emptyMap()
    }

    /** Порт fetch_laps()+normalize_lap() из garmin_activities_export.py: сначала пробуем
     * типизированные лапы (typedsplits — тренировка была структурированной в часах), иначе
     * обычные авто/ручные (splits). Возвращает пустой список, если ни один эндпоинт не ответил
     * (например, старая/не-беговая активность) — classifyByLaps() в этом случае просто не
     * сработает и вызывающий код должен откатиться на грубый classify(). */
    fun fetchLaps(tokens: GarminTokens, activityId: Long): Pair<List<IntervalRow>, String> {
        fun tryEndpoint(path: String): List<IntervalRow>? {
            return try {
                val resp = auth.connectApi(tokens, path)
                val text = bodyOf(resp)
                if (!resp.isSuccessful) return null
                val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
                val laps = when {
                    root is kotlinx.serialization.json.JsonArray -> root
                    root is JsonObject -> root["lapDTOs"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    else -> null
                } ?: return null
                if (laps.isEmpty()) return null
                laps.mapIndexed { idx, el ->
                    val lap = el.jsonObject
                    val dur = lap["duration"]?.jsonPrimitive?.doubleOrNull
                        ?: lap["movingDuration"]?.jsonPrimitive?.doubleOrNull
                        ?: lap["elapsedDuration"]?.jsonPrimitive?.doubleOrNull
                    val dist = lap["distance"]?.jsonPrimitive?.doubleOrNull
                    val avgHr = (lap["averageHR"] ?: lap["avgHr"])?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
                    val maxHr = (lap["maxHR"] ?: lap["maxHr"])?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
                    val avgSpeed = (lap["averageSpeed"] ?: lap["avgSpeed"])?.jsonPrimitive?.doubleOrNull
                    val pace = sPerKm(avgSpeed) ?: if (dist != null && dur != null && dist > 0) dur / (dist / 1000.0) else null
                    // "type" может прийти и объектом ({"typeKey":"ACTIVE",...}), и просто строкой —
                    // безусловный .jsonObject уронил бы разбор на втором варианте (ClassCastException).
                    var typeKey: String? = lap["type"]?.let { el ->
                        (el as? JsonObject)?.let { o ->
                            o["typeKey"]?.jsonPrimitive?.contentOrNull ?: o["key"]?.jsonPrimitive?.contentOrNull
                        } ?: (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    }
                    if (typeKey.isNullOrBlank()) typeKey = lap["intensityType"]?.jsonPrimitive?.contentOrNull
                    // Беговая динамика по кругу — порт normalize_lap() из
                    // garmin_activities_export.py (~813-847): сырьё для compute_lap_drift(),
                    // те же поля уже присутствуют в этом же JSON-объекте лапа, доп. запросов не
                    // требуется.
                    val cadence = lap["averageRunCadence"]?.jsonPrimitive?.doubleOrNull
                    val gctMs = lap["groundContactTime"]?.jsonPrimitive?.doubleOrNull
                    val vertOscMm = lap["verticalOscillation"]?.jsonPrimitive?.doubleOrNull
                    val vertRatio = lap["verticalRatio"]?.jsonPrimitive?.doubleOrNull
                    val strideMm = lap["strideLength"]?.jsonPrimitive?.doubleOrNull
                    val avgResp = lap["avgRespirationRate"]?.jsonPrimitive?.doubleOrNull
                    val compliance = lap["directWorkoutComplianceScore"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
                    IntervalRow(
                        idx = idx,
                        lapType = typeKey?.uppercase(),
                        durationS = dur?.let { Math.round(it * 10) / 10.0 },
                        distanceM = dist?.let { Math.round(it * 10) / 10.0 },
                        avgHr = avgHr,
                        maxHr = maxHr,
                        avgPaceSPerKm = pace,
                        avgCadenceSpm = cadence?.let { Math.round(it * 10) / 10.0 },
                        groundContactTimeMs = gctMs?.let { Math.round(it * 10) / 10.0 },
                        verticalOscillationMm = vertOscMm?.let { Math.round(it * 10) / 10.0 },
                        verticalRatio = vertRatio?.let { Math.round(it * 100) / 100.0 },
                        strideLengthMm = strideMm?.let { Math.round(it * 10) / 10.0 },
                        avgRespirationRate = avgResp?.let { Math.round(it * 10) / 10.0 },
                        workoutComplianceScore = compliance
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
        tryEndpoint("/activity-service/activity/$activityId/typedsplits")?.let { return it to "typed" }
        tryEndpoint("/activity-service/activity/$activityId/splits")?.let { return it to "plain" }
        return emptyList<IntervalRow>() to "none"
    }

    /** Порт compute_lap_drift() из garmin_activities_export.py (~851-882) — внутритренировочный
     * дрейф беговой динамики: сравнивает первую и последнюю треть "рабочих" лапов (duration_s>0,
     * avg_cadence_spm известен) по каденсу/GCT/вертикальным колебаниям. null, если таких лапов
     * меньше 6 (слишком мало данных) — не ошибка, просто сигнал не считается для этой тренировки. */
    private data class LapDrift(val cadenceDriftPct: Double?, val gctDriftPct: Double?, val verticalOscDriftPct: Double?)

    private fun computeLapDrift(laps: List<IntervalRow>): LapDrift? {
        val active = laps.filter { (it.durationS ?: 0.0) > 0.0 }
        val withDynamics = active.filter { it.avgCadenceSpm != null }
        if (withDynamics.size < 6) return null
        val third = maxOf(2, withDynamics.size / 3)
        val first = withDynamics.take(third)
        val last = withDynamics.takeLast(third)
        fun pctChange(sel: (IntervalRow) -> Double?): Double? {
            val a = first.mapNotNull(sel)
            val b = last.mapNotNull(sel)
            if (a.isEmpty() || b.isEmpty()) return null
            val avgA = a.average(); val avgB = b.average()
            if (avgA == 0.0) return null
            return Math.round((avgB - avgA) / avgA * 100.0 * 10) / 10.0
        }
        return LapDrift(
            cadenceDriftPct = pctChange { it.avgCadenceSpm },
            gctDriftPct = pctChange { it.groundContactTimeMs },
            verticalOscDriftPct = pctChange { it.verticalOscillationMm }
        )
    }

    /** Порт detail-запроса из export() (garmin_activities_export.py:1678-1681): impactLoad (и
     * потенциально другие будущие confound-поля) приходит ТОЛЬКО в detail-объекте
     * /activity-service/activity/{id}, а не в bulk-списке активностей — см. комментарий у
     * _EF_CONFOUND_FIELD_CANDIDATES/impact_load. Best-effort: недоступен -> null, не падает. */
    private fun fetchActivityDetail(tokens: GarminTokens, activityId: Long): JsonObject? {
        return try {
            val resp = auth.connectApi(tokens, "/activity-service/activity/$activityId")
            if (!resp.isSuccessful) { resp.close(); return null }
            runCatching { Json.parseToJsonElement(bodyOf(resp)).jsonObject }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** confound_source = {**act, "_activity_detail": detail} из export() — detail НЕ
     * затирает поля bulk-ответа, а кладётся отдельным вложенным ключом; deepFindKey ищет
     * рекурсивно по всему дереву, поэтому порядок вложенности не важен для поиска. */
    private fun mergeActivityDetail(act: JsonObject, detail: JsonObject?): JsonObject {
        if (detail == null) return act
        val merged = LinkedHashMap<String, JsonElement>(act)
        merged["_activity_detail"] = detail
        return JsonObject(merged)
    }

    private val DEVICE_TEMPERATURE_KEY_CANDIDATES = listOf("directAirTemperature", "directTemperature")

    // ИСПРАВЛЕНО (ревью п.13 "Лишние сетевые запросы при импорте"): раньше fetchDeviceTemperature
    // и fetchGradeAdjustedPaceByLap каждая сама по себе ходили в один и тот же поточный
    // эндпоинт /activity-service/activity/{id}/details - на активность с заметным набором
    // высоты это был ДВОЙНОЙ запрос одного и того же (обычно самого тяжёлого - посекундного)
    // ответа. Теперь поток запрашивается один раз через fetchDetailStream(), а обе функции
    // (переименованы в parseDeviceTemperature/parseGradeAdjustedPaceByLap) только парсят уже
    // готовый JsonObject - сетевой поход остался только в fetchDetailStream().
    private fun fetchDetailStream(tokens: GarminTokens, activityId: Long, maxChartSize: Int = 2000): JsonObject? {
        return try {
            val resp = auth.connectApi(
                tokens,
                "/activity-service/activity/$activityId/details?maxChartSize=$maxChartSize&maxPolylineSize=$maxChartSize"
            )
            val text = bodyOf(resp)
            if (!resp.isSuccessful) return null
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Порт fetch_device_temperature() из garmin_activities_export.py (~2069-2107): средняя
     * температура с датчика часов (посекундный поток /details, отдельный от avg_temperature_c
     * в EF-конфаундах, который берётся из сводки min/maxTemperature активности). Best-effort:
     * дескриптора нет/точки пустые -> null, не падает. Принимает уже загруженный поток (см.
     * fetchDetailStream выше) - сам сеть больше не трогает. */
    fun parseDeviceTemperature(details: JsonObject?): Double? {
        if (details == null) return null
        val descriptors = details["metricDescriptors"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        var idxTemp: Int? = null
        for (key in DEVICE_TEMPERATURE_KEY_CANDIDATES) {
            for (d in descriptors) {
                val obj = d as? JsonObject ?: continue
                if (obj["key"]?.jsonPrimitive?.contentOrNull == key) {
                    idxTemp = obj["metricsIndex"]?.jsonPrimitive?.intOrNull
                    break
                }
            }
            if (idxTemp != null) break
        }
        val idx = idxTemp ?: return null
        val points = details["activityDetailMetrics"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: emptyList()
        val values = mutableListOf<Double>()
        for (p in points) {
            val obj = p as? JsonObject ?: continue
            val metrics = obj["metrics"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: continue
            if (idx >= metrics.size) continue
            metrics[idx].jsonPrimitive.doubleOrNull?.let { values.add(it) }
        }
        if (values.isEmpty()) return null
        return Math.round(values.average() * 10) / 10.0
    }

    /**
     * Порт fetch_grade_adjusted_pace_by_lap() из garmin_activities_export.py — темп с поправкой
     * на уклон (GAP), и в среднем за тренировку целиком, и по каждому лапу отдельно. Метрика
     * приходит ТОЛЬКО из поточного эндпоинта /activity-service/activity/{id}/details (по
     * секундам) — не из списка активностей и не из обычного detail-объекта: metricDescriptors
     * описывает колонки (key -> metricsIndex), activityDetailMetrics — сами точки.
     *
     * Разбивка по лапам — через КУМУЛЯТИВНУЮ ДИСТАНЦИЮ (sumDistance каждой точки потока
     * сравнивается с накопленной суммой distanceM лапов), не через время: у лапов нет
     * проверенного поля начала/конца в том же формате эпохи, что временная метка потока, а
     * distanceM у лапов есть всегда (тот же физический счётчик GPS-дистанции). Если у лапа
     * distanceM отсутствует — для него GAP не считается (пропускается), кумулятивная сумма для
     * последующих лапов при этом не сдвигается специально — просто такой лап выпадает из
     * разбивки.
     *
     * Best-effort: если эндпоинт недоступен, нужного дескриптора нет (старые часы/короткая
     * активность без потока) или все точки пустые — возвращает (null, emptyMap()), не бросает.
     * Возвращает (overallSPerKm, lapIdx -> sPerKm) — идексация та же, что в [laps] (порядковая,
     * см. [IntervalRow.idx] из [fetchLaps]).
     */
    fun parseGradeAdjustedPaceByLap(
        details: JsonObject?,
        laps: List<IntervalRow>
    ): Pair<Double?, Map<Int, Double>> {
        if (details == null) return null to emptyMap()

        val descriptors = details["metricDescriptors"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null to emptyMap()

        fun findIdx(candidates: List<String>): Int? {
            for (key in candidates) {
                for (d in descriptors) {
                    val obj = d as? JsonObject ?: continue
                    if (obj["key"]?.jsonPrimitive?.contentOrNull == key) {
                        return obj["metricsIndex"]?.jsonPrimitive?.intOrNull
                    }
                }
            }
            return null
        }

        val idxGap = findIdx(GRADE_ADJUSTED_SPEED_KEY_CANDIDATES) ?: return null to emptyMap()
        val idxDist = findIdx(GRADE_ADJUSTED_DISTANCE_KEY_CANDIDATES)

        val points = details["activityDetailMetrics"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: emptyList()
        val allValues = mutableListOf<Double>()
        val distGapPoints = mutableListOf<Pair<Double, Double>>()
        for (p in points) {
            val obj = p as? JsonObject ?: continue
            val metrics = obj["metrics"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: continue
            if (idxGap >= metrics.size) continue
            val gapV = metrics[idxGap].jsonPrimitive.doubleOrNull ?: continue
            allValues.add(gapV)
            if (idxDist != null && idxDist < metrics.size) {
                val distV = metrics[idxDist].jsonPrimitive.doubleOrNull
                if (distV != null) distGapPoints.add(distV to gapV)
            }
        }

        val overall = if (allValues.isNotEmpty()) sPerKm(allValues.average()) else null

        val perLap = mutableMapOf<Int, Double>()
        if (distGapPoints.isNotEmpty()) {
            val sorted = distGapPoints.sortedBy { it.first }
            var cum = 0.0
            for (lap in laps) {
                val dist = lap.distanceM
                if (dist == null || dist <= 0.0) continue
                val start = cum
                val end = cum + dist
                cum = end
                val bucket = sorted.filter { it.first in start..end }.map { it.second }
                if (bucket.isNotEmpty()) {
                    sPerKm(bucket.average())?.let { perLap[lap.idx] = it }
                }
            }
        }

        return overall to perLap
    }

    /** Порт classify() из garmin_activities_export.py — та же логика и в том же порядке
     * приоритетов: 1) типизированные Гармином work/rest-лапы (со склейкой подряд идущих
     * work-лапов без rest внутри в непрерывные блоки — см. докстринг оригинала про тренировку
     * "Порог 2x20'"); 2) длительность >= longThresholdS — РАНЬШЕ эвристики по разбросу темпа;
     * 3) разброс темпа между лапами (CV) + гейт по пульсу быстрых лапов относительно
     * hrZones.z4Lo; 4) устойчивый повышенный пульс без разброса — threshold; 5) лёгкая/mixed по
     * умолчанию. hrZones — см. estimateHrZones (упрощённый Карвонен, без ПАНО — см. её doc).
     * Возвращает "unknown", если нет лапов с ненулевой длительностью вообще (вызывающий код
     * должен в этом случае откатиться на грубый classify()). */
    fun classifyByLaps(totalDurationS: Double, laps: List<IntervalRow>, hrZones: HrZones?, longThresholdS: Double = 75.0 * 60): String {
        val active = laps.filter { (it.durationS ?: 0.0) > 0.0 }
        if (active.isEmpty()) return "unknown"

        val paces = active.mapNotNull { it.avgPaceSPerKm }
        val hrs = active.mapNotNull { it.avgHr }
        val typedActive = active.filter { it.lapType in LAP_ACTIVE_TYPES }
        val typedRest = active.filter { it.lapType in LAP_REST_TYPES }

        if (typedActive.size >= 2 && typedRest.isNotEmpty()) {
            val workBlocks = mutableListOf<Double>()
            var blockDur = 0.0
            for (l in active) {
                if (l.lapType in LAP_ACTIVE_TYPES) {
                    blockDur += l.durationS ?: 0.0
                } else {
                    if (blockDur > 0.0) workBlocks.add(blockDur)
                    blockDur = 0.0
                }
            }
            if (blockDur > 0.0) workBlocks.add(blockDur)
            val avgWorkDur = if (workBlocks.isNotEmpty()) workBlocks.average()
                else typedActive.mapNotNull { it.durationS }.average()
            return if (avgWorkDur <= 8 * 60) "interval" else "threshold"
        }

        if (totalDurationS >= longThresholdS) return "long"

        if (active.size >= 4 && paces.size >= 4) {
            val meanPace = paces.average()
            val cvPace = Math.sqrt(paces.sumOf { (it - meanPace) * (it - meanPace) } / paces.size) / meanPace
            val fastest = paces.min()
            val slowest = paces.max()
            if (cvPace > 0.12 && slowest / fastest > 1.25) {
                val fastLaps = active.filter { it.avgPaceSPerKm != null && it.avgPaceSPerKm <= fastest * 1.08 }
                if (fastLaps.size >= 3 && fastLaps.mapNotNull { it.durationS }.average() <= 8 * 60) {
                    val fastHrs = fastLaps.mapNotNull { it.avgHr }
                    val hrOk = if (fastHrs.isNotEmpty() && hrZones != null) fastHrs.average() >= hrZones.z4Lo else true
                    if (hrOk) return "interval"
                }
            }
        }

        if (hrs.isNotEmpty()) {
            val avgHrAll = hrs.average()
            val hi = hrZones?.z4Lo
            if (hi != null && avgHrAll >= hi && totalDurationS in (12.0 * 60)..(55.0 * 60)) return "threshold"
        }

        if (hrs.isNotEmpty()) {
            val avgHrAll = hrs.average()
            val lo = hrZones?.z2Hi
            return if (lo == null || avgHrAll <= lo) "easy" else "mixed"
        }
        return "easy"
    }

    /**
     * Полный импорт за период: тренировки + (опционально) wellness по дням без активности тоже
     * учитываются — RHR/HRV нужны на каждый день периода, не только на дни тренировок.
     */
    fun importRange(
        tokens: GarminTokens,
        db: AnalyticsDb,
        startDate: LocalDate,
        endDate: LocalDate,
        withWellness: Boolean,
        forceRefreshWellness: Boolean = false,
        wellnessDelayMs: Long = 150,
        lapsDelayMs: Long = 120,
        // Прогресс (0..100) для UI/уведомления foreground-сервиса - по умолчанию no-op, чтобы
        // не трогать другие вызовы этого метода. Считается по числу "единиц работы" (беговые
        // активности + дни самочувствия, если withWellness), а не по времени, т.к. время одного
        // запроса плавает.
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        // Кооперативная проверка кнопки «Стоп» (см. AnalyticsImportBus.cancelRequested) -
        // опрашивается между сетевыми запросами (по одной активности/дню самочувствия), а не
        // через обычную отмену корутины, т.к. этот метод не suspend и блокирующие HTTP-вызовы
        // внутри него job.cancel() всё равно не прервёт. По умолчанию no-op (не отменяемо) -
        // остальные вызыватели этого метода не завязаны на кнопку «Стоп».
        isCancelled: () -> Boolean = { false }
    ): ImportResult {
        log("Период: $startDate .. $endDate")
        val rawAll = fetchAllActivities(tokens, startDate.toString(), endDate.toString())
        // ИСПРАВЛЕНО (ревью п.6): раньше toActivityRow/toCrossActivityRow вызывались без try —
        // одна активность с неожиданным полем (например activityType: null, см. .obj()/.arr()
        // выше) бросала исключение прямо внутри mapNotNull и обрывала разбор ВСЕХ активностей
        // периода, а не только проблемной. runCatching изолирует каждую активность отдельно.
        val rows = rawAll.mapNotNull { act ->
            runCatching { toActivityRow(act) }
                .onFailure { log("Пропущена активность ${act["activityId"]?.jsonPrimitive?.contentOrNull} (ошибка разбора: ${it.message})") }
                .getOrNull()
        }
        val crossRows = rawAll.mapNotNull { act ->
            runCatching { toCrossActivityRow(act) }
                .onFailure { log("Пропущена кросс-активность ${act["activityId"]?.jsonPrimitive?.contentOrNull} (ошибка разбора: ${it.message})") }
                .getOrNull()
        }
        val rawById = rawAll.mapNotNull { act -> act["activityId"]?.jsonPrimitive?.longOrNull?.let { it to act } }.toMap()
        log("Найдено беговых активностей: ${rows.size}, кросс-тренировок: ${crossRows.size}")

        // Грубая оценка restHr/maxHr периода — фолбэк для classify(), когда лапы вообще не
        // пришли, и для estimateHrZones (приближённый Карвонен), если настоящего ПАНО в БД нет.
        // ИСПРАВЛЕНО (ревью п.11 "Неверный пульс покоя при первом импорте"): раньше при
        // отсутствии данных самочувствия (db.recentRestingHr == null, обычно при самом первом
        // импорте) за restHr брался минимальный СРЕДНИЙ пульс пробежек (restHrObs) - у бегуна
        // это ~120-130 уд/мин, а не настоящий пульс покоя (~50-65). С таким завышенным restHr
        // зоны Карвонена (estimateHrZones) сдвигались вверх, и интервальные/пороговые
        // тренировки ошибочно классифицировались как лёгкие или смешанные (classify() выше).
        // Теперь вместо restHrObs используется консервативная константа 55 уд/мин - ближе к
        // типичному пульсу покоя бегуна-любителя, чем минимальный пульс во время бега.
        val maxHrObs = rows.mapNotNull { it.maxHr }.maxOrNull()
        val restHr = db.recentRestingHr(startDate.minusDays(90).toString()) ?: 55

        val exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        // История ПАНО (lactate_threshold) — тянем и сохраняем ДО расчёта зон, чтобы
        // estimateRealHrZones ниже сразу видел и свежие данные этого запуска (как в десктопном
        // reclassify_activities — сначала выгрузка, потом расчёт по полностью обновлённой БД).
        try {
            val lt = fetchLactateThresholdRange(tokens, startDate, endDate)
            for ((date, triple) in lt) {
                db.upsertLactateThreshold(date, triple.first, triple.second, triple.third, exportedAt)
            }
            if (lt.isNotEmpty()) log("История ПАНО обновлена: ${lt.size} запис.")
        } catch (e: Exception) {
            log("Не удалось получить историю ПАНО (${e.message}) — зоны пульса приближённые (без ПАНО).")
        }

        // Настоящие зоны (build_zones_for_classifier, порт раздела 3 build_report.py) — если в
        // БД уже есть история ПАНО; иначе откат на приближённый Карвонен (estimateHrZones).
        val hrZones = estimateRealHrZones(db, maxHrObs) ?: estimateHrZones(restHr, maxHrObs)
        var lapsFetched = 0
        var gapFetched = 0
        val wellnessDaysTotal = if (withWellness) (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt() else 0
        val progressTotal = rows.size + wellnessDaysTotal
        var progressDone = 0
        for (r in rows) {
            if (isCancelled()) throw ImportCancelledException()
            // ИСПРАВЛЕНО (ревью п.6): весь разбор/дозагрузка одной активности (лапы, detail,
            // GAP, температура, классификация, запись в БД) теперь в try - раньше исключение в
            // любом из этих шагов для ОДНОЙ активности (например неожиданный формат ответа
            // Garmin) обрывало импорт всех оставшихся активностей периода; прогресс/задержка
            // ниже выполняются в любом случае, чтобы процент и троттлинг запросов не сбивались.
            try {
            // Лапы — как в garmin_activities_export.py: тянем ПО КАЖДОЙ активности отдельным
            // запросом (typedsplits → splits), поэтому классификация точнее грубой эвристики
            // (classifyByLaps умеет отличать интервалы/порог/длинную/лёгкую по структуре
            // тренировки, а не только по среднему пульсу за всю тренировку целиком).
            var (laps, lapSource) = fetchLaps(tokens, r.activityId)

            // Detail-объект (/activity-service/activity/{id}) — порт export() (строки 1673-1682
            // garmin_activities_export.py): impact_load и потенциально другие confound-поля
            // приходят ТОЛЬКО оттуда, не из bulk-списка активностей. Confound'ы пересчитываются
            // здесь (а не в toActivityRow) из смёрженного bulk+detail объекта, тем же значением
            // elevation_gain_m, которое пишется в БД, — используется и как GAP-гейт ниже (в
            // Python это одно и то же значение confounds["elevation_gain_m"] для обеих целей).
            val act = rawById[r.activityId]
            val detail = act?.let { fetchActivityDetail(tokens, r.activityId) }
            val confoundSource = act?.let { mergeActivityDetail(it, detail) }
            val confounds = confoundSource?.let { extractEfConfounds(it) }

            // ИСПРАВЛЕНО (ревью п.13 "Лишние сетевые запросы при импорте"): GAP и температура
            // с датчика часов раньше каждая сама ходила в /details (fetchGradeAdjustedPaceByLap
            // и fetchDeviceTemperature) - на активность, которой нужны оба значения, это был
            // двойной запрос одного и того же посекундного потока. Теперь поток запрашивается
            // максимум один раз (fetchDetailStream) и передаётся в обе parse-функции. Также
            // температуру больше не запрашиваем безусловно: на дорожке/в помещении датчика
            // "уличной" температуры на часах физически нет (проверено вручную на нескольких
            // treadmill/indoor активностях — directAirTemperature/directTemperature там либо
            // отсутствуют, либо не имеют смысла) — это тоже был лишний запрос.
            var avgGap: Double? = null
            val elevationGainM = confounds?.elevationGainM
            val sportLower = r.sport?.lowercase()
            val needGap = laps.isNotEmpty() && sportLower in OUTDOOR_RUN_TYPE_KEYS &&
                elevationGainM != null && elevationGainM >= 30.0
            val needTemperature = sportLower !in INDOOR_RUN_TYPE_KEYS
            var avgDeviceTemperatureC: Double? = null
            if (needGap || needTemperature) {
                val detailStream = fetchDetailStream(tokens, r.activityId)
                if (needGap) {
                    val (overall, perLap) = parseGradeAdjustedPaceByLap(detailStream, laps)
                    if (overall != null) {
                        avgGap = overall
                        laps = laps.map { l -> l.copy(avgGapSPerKm = perLap[l.idx]) }
                        gapFetched++
                    }
                }
                if (needTemperature) {
                    avgDeviceTemperatureC = parseDeviceTemperature(detailStream)
                }
            }

            if (laps.isNotEmpty()) lapsFetched++
            // ИСПРАВЛЕНО (ревью п.7): раньше при пустых лапах (в т.ч. из-за СЕТЕВОЙ ошибки/401
            // в ЭТОМ прогоне, не обязательно потому что лапов правда нет) typeGuess безусловно
            // становился "unknown" и lapSource - "none"/фактическим значением fetchLaps, и это
            // писалось поверх уже сохранённой хорошей классификации. Теперь при отсутствии
            // лапов В ЭТОМ прогоне пишем null вместо "unknown"/значения по умолчанию -
            // upsertCoalesce (см. AnalyticsDb) тогда оставит прежнее значение колонки как есть,
            // а не затрёт его. Для АКТИВНОСТИ, у которой лапов реально никогда не было (первый
            // импорт), это просто оставляет колонку NULL - как и раньше.
            val typeGuess: String? = if (laps.isNotEmpty()) classifyByLaps(r.durationS ?: 0.0, laps, hrZones) else null
            val effectiveLapSource: String? = if (laps.isNotEmpty()) lapSource else null
            val drift = if (laps.isNotEmpty()) computeLapDrift(laps) else null
            db.upsertActivityWithLaps(
                r.copy(
                    lapSource = effectiveLapSource,
                    typeGuess = typeGuess,
                    elevationGainM = confounds?.elevationGainM ?: r.elevationGainM,
                    elevationLossM = confounds?.elevationLossM ?: r.elevationLossM,
                    avgTemperatureC = confounds?.avgTemperatureC ?: r.avgTemperatureC,
                    avgCadenceSpm = confounds?.avgCadenceSpm ?: r.avgCadenceSpm,
                    avgStrideLengthM = confounds?.avgStrideLengthM ?: r.avgStrideLengthM,
                    calories = confounds?.calories ?: r.calories,
                    aerobicTrainingEffect = confounds?.aerobicTrainingEffect ?: r.aerobicTrainingEffect,
                    anaerobicTrainingEffect = confounds?.anaerobicTrainingEffect ?: r.anaerobicTrainingEffect,
                    manualActivity = confounds?.manualActivity ?: r.manualActivity,
                    elevationCorrected = confounds?.elevationCorrected ?: r.elevationCorrected,
                    waterEstimatedMl = confounds?.waterEstimatedMl ?: r.waterEstimatedMl,
                    impactLoad = confounds?.impactLoad ?: r.impactLoad,
                    activityTrainingLoad = confounds?.activityTrainingLoad ?: r.activityTrainingLoad,
                    differenceBodyBattery = confounds?.differenceBodyBattery ?: r.differenceBodyBattery,
                    moderateIntensityMin = confounds?.moderateIntensityMin ?: r.moderateIntensityMin,
                    vigorousIntensityMin = confounds?.vigorousIntensityMin ?: r.vigorousIntensityMin,
                    hrTimeInZone1 = confounds?.hrZone1 ?: r.hrTimeInZone1,
                    hrTimeInZone2 = confounds?.hrZone2 ?: r.hrTimeInZone2,
                    hrTimeInZone3 = confounds?.hrZone3 ?: r.hrTimeInZone3,
                    hrTimeInZone4 = confounds?.hrZone4 ?: r.hrTimeInZone4,
                    hrTimeInZone5 = confounds?.hrZone5 ?: r.hrTimeInZone5,
                    avgGapSPerKm = avgGap,
                    avgDeviceTemperatureC = avgDeviceTemperatureC,
                    cadenceDriftPct = drift?.cadenceDriftPct,
                    gctDriftPct = drift?.gctDriftPct,
                    verticalOscDriftPct = drift?.verticalOscDriftPct
                ),
                laps,
                exportedAt
            )
            } catch (e: ImportCancelledException) {
                throw e
            } catch (e: Exception) {
                log("Пропущена активность ${r.activityId} (${r.name ?: ""}) при дозагрузке деталей: ${e.message}")
            }
            progressDone++
            onProgress(progressDone, progressTotal)
            if (lapsDelayMs > 0) Thread.sleep(lapsDelayMs)
        }
        log("Сохранено/обновлено в базе: ${rows.size} (лапы получены для $lapsFetched, GAP посчитан для $gapFetched)")

        for (c in crossRows) {
            db.upsertCrossActivity(c, exportedAt)
        }
        if (crossRows.isNotEmpty()) log("Кросс-тренировки сохранены/обновлены: ${crossRows.size}")

        var wellnessDays = 0
        if (withWellness) {
            val username = resolveDisplayName(tokens)
            if (username == null) {
                log("Не удалось определить displayName Garmin — самочувствие пропущено.")
            } else {
                // Дни, за которые самочувствие уже есть в базе, по умолчанию пропускаем (быстрее
                // при повторных запусках) — как --force-refresh-wellness в garmin_activities_export.py.
                val already = if (forceRefreshWellness) emptySet() else db.wellnessDatesSince(startDate.toString())
                // Body Battery отдаётся диапазоном за один запрос (как в десктопе) — тянем один
                // раз на весь период ДО дневного цикла, а не по дню.
                val bodyBatteryByDate = try {
                    fetchBodyBatteryRange(tokens, startDate, endDate)
                } catch (e: Exception) {
                    emptyMap()
                }
                var d = startDate
                while (!d.isAfter(endDate)) {
                    if (isCancelled()) throw ImportCancelledException()
                    val dateStr = d.toString()
                    if (dateStr !in already) {
                      // ИСПРАВЛЕНО (ревью п.6): разбор одного дня самочувствия — в try, чтобы
                      // один день с неожиданным полем (например день сна без sleepNeed) не
                      // обрывал догрузку самочувствия за весь остальной период.
                      try {
                        val sleep = fetchSleepDay(tokens, username, dateStr)
                        val hrv = fetchHrv(tokens, dateStr)
                        val hrvSummary = hrv?.get("hrvSummary").obj()
                        val daily = fetchDailySummary(tokens, username, dateStr)
                        val dailyFields = dailySummaryWellnessFields(daily)
                        val stress = fetchStressDay(tokens, dateStr)
                        val readiness = fetchTrainingReadinessDay(tokens, dateStr)
                        val bb = bodyBatteryByDate[dateStr]

                        val rhr = (dailyFields["rhr"] as? Int) ?: (sleep["sleep_rhr"] as? Int)
                        val hrvAvg = hrvSummary?.get("lastNightAvg")?.jsonPrimitive?.doubleOrNull
                        val anyData = rhr != null || hrvAvg != null || sleep.isNotEmpty() ||
                            stress.isNotEmpty() || readiness.isNotEmpty() || bb != null
                        if (anyData) {
                            db.upsertWellness(
                                WellnessRow(
                                    date = dateStr,
                                    sleepScore = sleep["sleep_score"] as? Int,
                                    sleepDurationS = sleep["sleep_duration_s"] as? Double,
                                    sleepDeepS = sleep["sleep_deep_s"] as? Double,
                                    sleepLightS = sleep["sleep_light_s"] as? Double,
                                    sleepRemS = sleep["sleep_rem_s"] as? Double,
                                    sleepAwakeS = sleep["sleep_awake_s"] as? Double,
                                    sleepAvgResp = sleep["sleep_avg_resp"] as? Double,
                                    hrvLastNightAvg = hrvAvg,
                                    hrvWeeklyAvg = hrvSummary?.get("weeklyAvg")?.jsonPrimitive?.doubleOrNull,
                                    hrvStatus = hrvSummary?.get("status")?.jsonPrimitive?.contentOrNull,
                                    rhr = rhr,
                                    bodyBatteryMin = bb?.min,
                                    bodyBatteryMax = bb?.max,
                                    bodyBatteryCharged = bb?.charged,
                                    bodyBatteryDrained = bb?.drained,
                                    stressAvg = stress["stress_avg"] as? Int,
                                    stressMax = stress["stress_max"] as? Int,
                                    trainingReadinessScore = readiness["training_readiness_score"] as? Int,
                                    trainingReadinessLevel = readiness["training_readiness_level"] as? String,
                                    steps = dailyFields["steps"] as? Int,
                                    activeCalories = dailyFields["active_calories"] as? Int,
                                    avgSleepStress = sleep["avg_sleep_stress"] as? Double,
                                    sleepSpo2Avg = sleep["sleep_spo2_avg"] as? Double,
                                    sleepSpo2Min = sleep["sleep_spo2_min"] as? Int,
                                    sleepRhr = sleep["sleep_rhr"] as? Int,
                                    skinTempDeviationC = sleep["skin_temp_deviation_c"] as? Double,
                                    sleepTrainingFeedback = sleep["sleep_training_feedback"] as? String,
                                    floorsAscended = dailyFields["floors_ascended"] as? Double,
                                    stressRestS = dailyFields["stress_rest_s"] as? Double,
                                    stressActivityS = dailyFields["stress_activity_s"] as? Double,
                                    stressUncategorizedS = dailyFields["stress_uncategorized_s"] as? Double,
                                    stressLowS = dailyFields["stress_low_s"] as? Double,
                                    stressMediumS = dailyFields["stress_medium_s"] as? Double,
                                    stressHighS = dailyFields["stress_high_s"] as? Double,
                                    sleepStartLocal = sleep["sleep_start_local"] as? String,
                                    sleepEndLocal = sleep["sleep_end_local"] as? String
                                ),
                                exportedAt
                            )
                            wellnessDays++
                        }
                        if (wellnessDelayMs > 0) Thread.sleep(wellnessDelayMs)
                      } catch (e: ImportCancelledException) {
                          throw e
                      } catch (e: Exception) {
                          log("Пропущено самочувствие за $dateStr: ${e.message}")
                      }
                    }
                    progressDone++
                    onProgress(progressDone, progressTotal)
                    d = d.plusDays(1)
                }
                log("Самочувствие сохранено/обновлено за $wellnessDays дн.")
            }
        }
        return ImportResult(rows.size, wellnessDays)
    }
}
