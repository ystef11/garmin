package com.example.runstef.network.garmin

import com.example.runstef.data.ActivityRow
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.CrossActivityRow
import com.example.runstef.data.IntervalRow
import com.example.runstef.data.WellnessRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Упрощённый Kotlin-порт garmin_activities_export.py: тренировки (только running-типы) +
 * пара wellness-показателей в сутки (RHR, HRV, качество сна). Полный набор (лапы, кросс-
 * тренировки, история ПАНО, десяток конфаундов EF) остаётся за десктопным скриптом — здесь
 * ровно то, что нужно для отчёта AnalyticsReportBuilder прямо на телефоне без Python.
 *
 * Использует тот же GarminAuth.connectApi (Bearer OAuth2), что и GarminApi (загрузка плана) —
 * один и тот же сохранённый токен/аккаунт обслуживает обе вкладки.
 */
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
        private val ELEVATION_GAIN_KEY_CANDIDATES = listOf(
            "elevationGain", "elevationGainInMeter", "elevationGainMeters"
        )
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

    private fun bodyOf(resp: okhttp3.Response): String {
        val text = resp.body?.string() ?: "{}"
        resp.close()
        return text
    }

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
        return 1000.0 / speedMs
    }

    private fun toActivityRow(act: JsonObject): ActivityRow? {
        val activityId = act["activityId"]?.jsonPrimitive?.longOrNull ?: return null
        val startLocal = act["startTimeLocal"]?.jsonPrimitive?.contentOrNull ?: return null
        val date = startLocal.split(" ").firstOrNull() ?: return null
        val name = act["activityName"]?.jsonPrimitive?.contentOrNull ?: ""
        val sportKey = act["activityType"]?.jsonObject?.get("typeKey")?.jsonPrimitive?.contentOrNull
        if (sportKey == null || sportKey.lowercase() !in RUN_TYPE_KEYS) return null
        val duration = act["duration"]?.jsonPrimitive?.doubleOrNull
        val distance = act["distance"]?.jsonPrimitive?.doubleOrNull
        val avgHr = act["averageHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        val maxHr = act["maxHR"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() }
        val avgSpeed = act["averageSpeed"]?.jsonPrimitive?.doubleOrNull
        val pace = sPerKm(avgSpeed) ?: if (distance != null && duration != null && distance > 0)
            duration / (distance / 1000.0) else null
        return ActivityRow(
            activityId = activityId,
            date = date,
            name = name,
            sport = sportKey,
            durationS = duration,
            distanceM = distance,
            avgHr = avgHr,
            maxHr = maxHr,
            avgPaceSPerKm = pace,
            typeGuess = null // классификация — отдельным проходом, см. classifyAll() (нужен общий rest/max HR периода)
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
        val sportKey = act["activityType"]?.jsonObject?.get("typeKey")?.jsonPrimitive?.contentOrNull
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
    fun fetchLaps(tokens: GarminTokens, activityId: Long): List<IntervalRow> {
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
                    IntervalRow(
                        idx = idx,
                        lapType = typeKey?.uppercase(),
                        durationS = dur?.let { Math.round(it * 10) / 10.0 },
                        distanceM = dist?.let { Math.round(it * 10) / 10.0 },
                        avgHr = avgHr,
                        maxHr = maxHr,
                        avgPaceSPerKm = pace
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
        return tryEndpoint("/activity-service/activity/$activityId/typedsplits")
            ?: tryEndpoint("/activity-service/activity/$activityId/splits")
            ?: emptyList()
    }

    /** Суммарный набор высоты активности (м) — прямо из bulk-списка активностей (поле
     * elevationGain/elevationGainInMeter/elevationGainMeters, в этом порядке приоритета — как
     * в garmin_activities_export.py). null, если поля нет вообще (Garmin не всегда его отдаёт). */
    private fun extractElevationGainM(act: JsonObject): Double? {
        for (key in ELEVATION_GAIN_KEY_CANDIDATES) {
            val v = act[key]?.jsonPrimitive?.doubleOrNull
            if (v != null) return v
        }
        return null
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
    fun fetchGradeAdjustedPaceByLap(
        tokens: GarminTokens,
        activityId: Long,
        laps: List<IntervalRow>,
        maxChartSize: Int = 2000
    ): Pair<Double?, Map<Int, Double>> {
        val details = try {
            val resp = auth.connectApi(
                tokens,
                "/activity-service/activity/$activityId/details?maxChartSize=$maxChartSize&maxPolylineSize=$maxChartSize"
            )
            val text = bodyOf(resp)
            if (!resp.isSuccessful) return null to emptyMap()
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null to emptyMap()
        } catch (e: Exception) {
            return null to emptyMap()
        }

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
        lapsDelayMs: Long = 120
    ): ImportResult {
        log("Период: $startDate .. $endDate")
        val rawAll = fetchAllActivities(tokens, startDate.toString(), endDate.toString())
        val rows = rawAll.mapNotNull { toActivityRow(it) }
        val crossRows = rawAll.mapNotNull { toCrossActivityRow(it) }
        val rawById = rawAll.mapNotNull { act -> act["activityId"]?.jsonPrimitive?.longOrNull?.let { it to act } }.toMap()
        log("Найдено беговых активностей: ${rows.size}, кросс-тренировок: ${crossRows.size}")

        // Грубая оценка restHr/maxHr периода — фолбэк для classify(), когда лапы вообще не
        // пришли, и для estimateHrZones (приближённый Карвонен), если настоящего ПАНО в БД нет.
        val maxHrObs = rows.mapNotNull { it.maxHr }.maxOrNull()
        val restHrObs = rows.mapNotNull { it.avgHr }.minOrNull()
        val restHr = db.recentRestingHr(startDate.minusDays(90).toString()) ?: restHrObs

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
        for (r in rows) {
            // Лапы — как в garmin_activities_export.py: тянем ПО КАЖДОЙ активности отдельным
            // запросом (typedsplits → splits), поэтому классификация точнее грубой эвристики
            // (classifyByLaps умеет отличать интервалы/порог/длинную/лёгкую по структуре
            // тренировки, а не только по среднему пульсу за всю тренировку целиком).
            var laps = fetchLaps(tokens, r.activityId)

            // GAP (grade-adjusted pace) — только для уличных беговых типов с заметным набором
            // высоты (порт условия из garmin_activities_export.py: OUTDOOR_RUN_TYPE_KEYS и
            // elevation_gain_m >= 30 м) — лишний поточный запрос на каждую активность иначе
            // тратился бы зря на дорожке/ровных пробежках.
            var avgGap: Double? = null
            val act = rawById[r.activityId]
            val elevationGainM = act?.let { extractElevationGainM(it) }
            if (laps.isNotEmpty() && act != null && r.sport?.lowercase() in OUTDOOR_RUN_TYPE_KEYS &&
                elevationGainM != null && elevationGainM >= 30.0
            ) {
                val (overall, perLap) = fetchGradeAdjustedPaceByLap(tokens, r.activityId, laps)
                if (overall != null) {
                    avgGap = overall
                    laps = laps.map { l -> l.copy(avgGapSPerKm = perLap[l.idx]) }
                    gapFetched++
                }
            }

            if (laps.isNotEmpty()) {
                db.replaceIntervals(r.activityId, laps)
                lapsFetched++
            }
            val byLaps = if (laps.isNotEmpty()) classifyByLaps(r.durationS ?: 0.0, laps, hrZones) else "unknown"
            // Категории те же, что и в десктопном classify(): long/interval/threshold/easy/mixed
            // (см. AnalyticsReportBuilder — теперь умеет их все, а не только грубые
            // long/quality/easy). "unknown" — лапов не было вовсе (старая тренировка без
            // сохранённых сплитов, старые часы и т.п.) — тогда откат на грубый classify() по
            // одному среднему пульсу за тренировку.
            val typeGuess = if (byLaps != "unknown") byLaps
                else classify(r.durationS, r.avgHr, restHrObs, maxHrObs)
            db.upsertActivity(r.copy(typeGuess = typeGuess, avgGapSPerKm = avgGap), exportedAt)
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
                log("Не удалось определить displayName Garmin — самочувствие (RHR/HRV) пропущено.")
            } else {
                // Дни, за которые самочувствие уже есть в базе, по умолчанию пропускаем (быстрее
                // при повторных запусках) — как --force-refresh-wellness в garmin_activities_export.py.
                val already = if (forceRefreshWellness) emptySet() else db.wellnessDatesSince(startDate.toString())
                var d = startDate
                while (!d.isAfter(endDate)) {
                    val dateStr = d.toString()
                    if (dateStr !in already) {
                        val daily = fetchDailySummary(tokens, username, dateStr)
                        val hrv = fetchHrv(tokens, dateStr)
                        val restingHr = daily?.get("restingHeartRate")?.jsonPrimitive?.intOrNull
                        val hrvAvg = hrv?.get("hrvSummary")?.jsonObject?.get("lastNightAvg")?.jsonPrimitive?.doubleOrNull
                        if (restingHr != null || hrvAvg != null) {
                            db.upsertWellness(WellnessRow(dateStr, restingHr, hrvAvg, null), exportedAt)
                            wellnessDays++
                        }
                        if (wellnessDelayMs > 0) Thread.sleep(wellnessDelayMs)
                    }
                    d = d.plusDays(1)
                }
                log("Самочувствие (RHR/HRV) сохранено/обновлено за $wellnessDays дн.")
            }
        }
        return ImportResult(rows.size, wellnessDays)
    }
}
