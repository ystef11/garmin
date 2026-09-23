package com.example.runstef.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDate

/**
 * activities — ПОЛНАЯ схема 1:1 с десктопной garmin_activities_export.py / garmin_running_ystef.db
 * (см. project memory, часть 27/28). Поля, которые apk пока не заполняет сетевым импортом,
 * присутствуют в схеме и в data-классе как null — это осознанно (полная схема нужна для
 * совместимости с "Импорт БД из файла" реального десктопного .db и для будущего дозаполнения).
 */
data class ActivityRow(
    val activityId: Long,
    val date: String,
    val startTime: String? = null,
    val name: String,
    val sport: String?,
    val durationS: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgPaceSPerKm: Double?,
    val lapSource: String? = null,
    val typeGuess: String?,
    val typeReason: String? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val avgTemperatureC: Double? = null,
    val avgCadenceSpm: Double? = null,
    val avgStrideLengthM: Double? = null,
    val calories: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val manualActivity: Boolean? = null,
    val elevationCorrected: Boolean? = null,
    val waterEstimatedMl: Double? = null,
    val impactLoad: Double? = null,
    val activityTrainingLoad: Double? = null,
    val differenceBodyBattery: Int? = null,
    val moderateIntensityMin: Double? = null,
    val vigorousIntensityMin: Double? = null,
    val hrTimeInZone1: Double? = null,
    val hrTimeInZone2: Double? = null,
    val hrTimeInZone3: Double? = null,
    val hrTimeInZone4: Double? = null,
    val hrTimeInZone5: Double? = null,
    val cadenceDriftPct: Double? = null,
    val gctDriftPct: Double? = null,
    val verticalOscDriftPct: Double? = null,
    val avgGapSPerKm: Double? = null,
    val avgDeviceTemperatureC: Double? = null
)

/**
 * wellness — ПОЛНАЯ схема 1:1 с десктопом. ВАЖНО: ключевая колонка называется `rhr`
 * (НЕ resting_hr, как было в старой упрощённой схеме apk) — переименована при миграции v6,
 * чтобы "Импорт БД из файла" реального десктопного .db работал без ошибок "no such column".
 */
data class WellnessRow(
    val date: String,
    val sleepScore: Int?,
    val sleepDurationS: Double? = null,
    val sleepDeepS: Double? = null,
    val sleepLightS: Double? = null,
    val sleepRemS: Double? = null,
    val sleepAwakeS: Double? = null,
    val sleepAvgResp: Double? = null,
    val hrvLastNightAvg: Double?,
    val hrvWeeklyAvg: Double? = null,
    val hrvStatus: String? = null,
    val rhr: Int?,
    val bodyBatteryMin: Int? = null,
    val bodyBatteryMax: Int? = null,
    val bodyBatteryCharged: Int? = null,
    val bodyBatteryDrained: Int? = null,
    val stressAvg: Int? = null,
    val stressMax: Int? = null,
    val trainingReadinessScore: Int? = null,
    val trainingReadinessLevel: String? = null,
    val steps: Int? = null,
    val activeCalories: Int? = null,
    val avgSleepStress: Double? = null,
    val sleepSpo2Avg: Double? = null,
    val sleepSpo2Min: Int? = null,
    val sleepRhr: Int? = null,
    val skinTempDeviationC: Double? = null,
    val sleepTrainingFeedback: String? = null,
    val floorsAscended: Double? = null,
    val stressRestS: Double? = null,
    val stressActivityS: Double? = null,
    val stressUncategorizedS: Double? = null,
    val stressLowS: Double? = null,
    val stressMediumS: Double? = null,
    val stressHighS: Double? = null,
    val sleepStartLocal: String? = null,
    val sleepEndLocal: String? = null
)

/** Кросс-тренировка (вело/лыжи/плавание/силовая) — порт cross_activities из
 * garmin_activities_export.py. Только суммарная нагрузка (не по осям, как беговые) — нужна
 * для контекста системной усталости (ACWR/HRV), не для беговых зон/темпа. sport — группа
 * (cycling/skiing/swimming/strength_training), см. GarminActivitiesApi.crossGroup. */
data class CrossActivityRow(
    val activityId: Long,
    val date: String,
    val name: String,
    val sport: String,
    val durationS: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?
)

data class LactateThresholdRow(
    val date: String,
    val thresholdHr: Int?,
    val thresholdPaceSPerKm: Double?
)

/** Лап/сплит тренировки — ПОЛНАЯ схема 1:1 с десктопной intervals (беговая динамика включена:
 * каденс/GCT/вертикальные колебания/соотношение/длина шага/дыхание/compliance score). */
data class IntervalRow(
    val idx: Int,
    val lapType: String?,
    val durationS: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgPaceSPerKm: Double?,
    val avgCadenceSpm: Double? = null,
    val groundContactTimeMs: Double? = null,
    val verticalOscillationMm: Double? = null,
    val verticalRatio: Double? = null,
    val strideLengthMm: Double? = null,
    val avgRespirationRate: Double? = null,
    val workoutComplianceScore: Int? = null,
    val avgGapSPerKm: Double? = null
)

/**
 * Локальная база аналитики Garmin — Kotlin-аналог таблиц activities/wellness/intervals/
 * cross_activities/lactate_threshold из garmin_activities_export.py. С v6 схема ПОЛНОСТЬЮ
 * приведена к десктопной (1:1 по колонкам/именам) по прямому требованию пользователя ("исправь
 * структуру базы и приведи к десктопной" / "переписывай все к десктопной версии, никакой
 * обратной совместимости не надо") — база ОДНА НА АККАУНТ Garmin (см. dbFileForAccount).
 */
class AnalyticsDb private constructor(context: Context, dbFile: File) :
    SQLiteOpenHelper(context, dbFile.absolutePath, null, DB_VERSION) {

    companion object {
        // v2: intervals. v3: lactate_threshold. v4: cross_activities.
        // v5: avg_grade_adjusted_pace_s_per_km в activities/intervals.
        // v6: ПОЛНЫЙ репаритет схемы с десктопом (activities/intervals/wellness) — добавлены все
        // confound-колонки, wellness.resting_hr переименован в rhr (пересоздание таблицы, т.к.
        // ALTER TABLE RENAME COLUMN ненадёжен на minSdk=28).
        // v7: activities.avg_device_temperature_c (fetch_device_temperature, отдельно от
        // avg_temperature_c), wellness.sleep_start_local/sleep_end_local — все три были в
        // десктопной схеме, но отсутствовали в apk.
        const val DB_VERSION = 7

        fun dirFor(context: Context): File = File(context.filesDir, "analytics").apply { mkdirs() }

        fun dbFileForAccount(context: Context, account: String): File {
            val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
            return File(dirFor(context), "garmin_$safe.db")
        }

        fun open(context: Context, account: String): AnalyticsDb =
            AnalyticsDb(context, dbFileForAccount(context, account))

        /** Открывает произвольный файл БД через тот же SQLiteOpenHelper, что и обычные базы
         * аккаунтов -- используется ТОЛЬКО для проверки импортируемого извне файла ВО ВРЕМЕННОМ
         * расположении, до того как он заменит рабочую базу (см. AnalyticsViewModel.
         * importDbFromUri). Если файл содержит таблицы устаревшей/чужой схемы без правильно
         * выставленного user_version, здесь и вылетит то же SQLiteException ("table ... already
         * exists" и т.п.), что раньше вылетало уже ПОСЛЕ замены рабочей базы. */
        fun openForImportCheck(context: Context, file: File): AnalyticsDb =
            AnalyticsDb(context, file)

        private const val ACTIVITIES_SCHEMA = """
            CREATE TABLE activities (
                activity_id INTEGER PRIMARY KEY,
                date TEXT NOT NULL,
                start_time TEXT,
                name TEXT,
                sport TEXT,
                duration_s REAL,
                distance_m REAL,
                avg_hr INTEGER,
                max_hr INTEGER,
                avg_pace_s_per_km REAL,
                lap_source TEXT,
                type_guess TEXT,
                type_reason TEXT,
                elevation_gain_m REAL,
                elevation_loss_m REAL,
                avg_temperature_c REAL,
                avg_cadence_spm REAL,
                avg_stride_length_m REAL,
                calories REAL,
                aerobic_training_effect REAL,
                anaerobic_training_effect REAL,
                manual_activity INTEGER,
                elevation_corrected INTEGER,
                water_estimated_ml REAL,
                impact_load REAL,
                activity_training_load REAL,
                difference_body_battery INTEGER,
                moderate_intensity_min REAL,
                vigorous_intensity_min REAL,
                hr_time_in_zone_1 REAL,
                hr_time_in_zone_2 REAL,
                hr_time_in_zone_3 REAL,
                hr_time_in_zone_4 REAL,
                hr_time_in_zone_5 REAL,
                cadence_drift_pct REAL,
                gct_drift_pct REAL,
                vertical_osc_drift_pct REAL,
                avg_grade_adjusted_pace_s_per_km REAL,
                avg_device_temperature_c REAL,
                exported_at TEXT
            )
        """

        private const val INTERVALS_SCHEMA = """
            CREATE TABLE intervals (
                activity_id INTEGER NOT NULL,
                idx INTEGER NOT NULL,
                lap_type TEXT,
                duration_s REAL,
                distance_m REAL,
                avg_hr INTEGER,
                max_hr INTEGER,
                avg_pace_s_per_km REAL,
                avg_cadence_spm REAL,
                ground_contact_time_ms REAL,
                vertical_oscillation_mm REAL,
                vertical_ratio REAL,
                stride_length_mm REAL,
                avg_respiration_rate REAL,
                workout_compliance_score INTEGER,
                avg_grade_adjusted_pace_s_per_km REAL,
                PRIMARY KEY (activity_id, idx)
            )
        """

        private const val WELLNESS_SCHEMA = """
            CREATE TABLE wellness (
                date TEXT PRIMARY KEY,
                sleep_score INTEGER,
                sleep_duration_s REAL,
                sleep_deep_s REAL,
                sleep_light_s REAL,
                sleep_rem_s REAL,
                sleep_awake_s REAL,
                sleep_avg_resp REAL,
                hrv_last_night_avg REAL,
                hrv_weekly_avg REAL,
                hrv_status TEXT,
                rhr INTEGER,
                body_battery_min INTEGER,
                body_battery_max INTEGER,
                body_battery_charged INTEGER,
                body_battery_drained INTEGER,
                stress_avg INTEGER,
                stress_max INTEGER,
                training_readiness_score INTEGER,
                training_readiness_level TEXT,
                steps INTEGER,
                active_calories INTEGER,
                avg_sleep_stress REAL,
                sleep_spo2_avg REAL,
                sleep_spo2_min INTEGER,
                sleep_rhr INTEGER,
                skin_temp_deviation_c REAL,
                sleep_training_feedback TEXT,
                floors_ascended REAL,
                stress_rest_s REAL,
                stress_activity_s REAL,
                stress_uncategorized_s REAL,
                stress_low_s REAL,
                stress_medium_s REAL,
                stress_high_s REAL,
                sleep_start_local TEXT,
                sleep_end_local TEXT,
                exported_at TEXT
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ACTIVITIES_SCHEMA.trimIndent())
        db.execSQL("CREATE INDEX idx_activities_date ON activities(date)")
        db.execSQL(WELLNESS_SCHEMA.trimIndent())
        db.execSQL(INTERVALS_SCHEMA.trimIndent())
        db.execSQL("CREATE INDEX idx_intervals_activity ON intervals(activity_id)")
        db.execSQL(
            """
            CREATE TABLE lactate_threshold (
                date TEXT PRIMARY KEY,
                threshold_hr INTEGER,
                threshold_pace_s_per_km REAL,
                source TEXT,
                exported_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE cross_activities (
                activity_id INTEGER PRIMARY KEY,
                date TEXT,
                start_time TEXT,
                name TEXT,
                sport TEXT,
                duration_s REAL,
                distance_m REAL,
                avg_hr INTEGER,
                max_hr INTEGER,
                exported_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_cross_activities_date ON cross_activities(date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS intervals (
                    activity_id INTEGER NOT NULL,
                    idx INTEGER NOT NULL,
                    lap_type TEXT,
                    duration_s REAL,
                    distance_m REAL,
                    avg_hr INTEGER,
                    max_hr INTEGER,
                    avg_pace_s_per_km REAL,
                    PRIMARY KEY (activity_id, idx)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_intervals_activity ON intervals(activity_id)")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS lactate_threshold (
                    date TEXT PRIMARY KEY,
                    threshold_hr INTEGER,
                    threshold_pace_s_per_km REAL,
                    source TEXT,
                    exported_at TEXT
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cross_activities (
                    activity_id INTEGER PRIMARY KEY,
                    date TEXT,
                    start_time TEXT,
                    name TEXT,
                    sport TEXT,
                    duration_s REAL,
                    distance_m REAL,
                    avg_hr INTEGER,
                    max_hr INTEGER,
                    exported_at TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_cross_activities_date ON cross_activities(date)")
        }
        if (oldVersion < 5) {
            runCatching { db.execSQL("ALTER TABLE activities ADD COLUMN avg_grade_adjusted_pace_s_per_km REAL") }
            runCatching { db.execSQL("ALTER TABLE intervals ADD COLUMN avg_grade_adjusted_pace_s_per_km REAL") }
        }
        if (oldVersion < 6) {
            val newActivityCols = listOf(
                "lap_source TEXT", "type_reason TEXT", "elevation_gain_m REAL", "elevation_loss_m REAL",
                "avg_temperature_c REAL", "avg_cadence_spm REAL", "avg_stride_length_m REAL", "calories REAL",
                "aerobic_training_effect REAL", "anaerobic_training_effect REAL", "manual_activity INTEGER",
                "elevation_corrected INTEGER", "water_estimated_ml REAL", "impact_load REAL",
                "activity_training_load REAL", "difference_body_battery INTEGER", "moderate_intensity_min REAL",
                "vigorous_intensity_min REAL", "hr_time_in_zone_1 REAL", "hr_time_in_zone_2 REAL",
                "hr_time_in_zone_3 REAL", "hr_time_in_zone_4 REAL", "hr_time_in_zone_5 REAL",
                "cadence_drift_pct REAL", "gct_drift_pct REAL", "vertical_osc_drift_pct REAL"
            )
            for (col in newActivityCols) {
                runCatching { db.execSQL("ALTER TABLE activities ADD COLUMN $col") }
            }
            val newIntervalCols = listOf(
                "avg_cadence_spm REAL", "ground_contact_time_ms REAL", "vertical_oscillation_mm REAL",
                "vertical_ratio REAL", "stride_length_mm REAL", "avg_respiration_rate REAL",
                "workout_compliance_score INTEGER"
            )
            for (col in newIntervalCols) {
                runCatching { db.execSQL("ALTER TABLE intervals ADD COLUMN $col") }
            }
            db.execSQL("ALTER TABLE wellness RENAME TO wellness_old_v6")
            db.execSQL(WELLNESS_SCHEMA.trimIndent())
            db.execSQL(
                """
                INSERT INTO wellness (date, sleep_score, hrv_last_night_avg, rhr, exported_at)
                SELECT date, sleep_score, hrv_last_night_avg, resting_hr, exported_at FROM wellness_old_v6
                """.trimIndent()
            )
            db.execSQL("DROP TABLE wellness_old_v6")
        }
        if (oldVersion < 7) {
            runCatching { db.execSQL("ALTER TABLE activities ADD COLUMN avg_device_temperature_c REAL") }
            runCatching { db.execSQL("ALTER TABLE wellness ADD COLUMN sleep_start_local TEXT") }
            runCatching { db.execSQL("ALTER TABLE wellness ADD COLUMN sleep_end_local TEXT") }
        }
    }

    fun upsertActivity(a: ActivityRow, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("activity_id", a.activityId)
            put("date", a.date)
            put("start_time", a.startTime)
            put("name", a.name)
            put("sport", a.sport)
            put("duration_s", a.durationS)
            put("distance_m", a.distanceM)
            a.avgHr?.let { put("avg_hr", it) } ?: putNull("avg_hr")
            a.maxHr?.let { put("max_hr", it) } ?: putNull("max_hr")
            a.avgPaceSPerKm?.let { put("avg_pace_s_per_km", it) } ?: putNull("avg_pace_s_per_km")
            put("lap_source", a.lapSource)
            put("type_guess", a.typeGuess)
            put("type_reason", a.typeReason)
            a.elevationGainM?.let { put("elevation_gain_m", it) } ?: putNull("elevation_gain_m")
            a.elevationLossM?.let { put("elevation_loss_m", it) } ?: putNull("elevation_loss_m")
            a.avgTemperatureC?.let { put("avg_temperature_c", it) } ?: putNull("avg_temperature_c")
            a.avgCadenceSpm?.let { put("avg_cadence_spm", it) } ?: putNull("avg_cadence_spm")
            a.avgStrideLengthM?.let { put("avg_stride_length_m", it) } ?: putNull("avg_stride_length_m")
            a.calories?.let { put("calories", it) } ?: putNull("calories")
            a.aerobicTrainingEffect?.let { put("aerobic_training_effect", it) } ?: putNull("aerobic_training_effect")
            a.anaerobicTrainingEffect?.let { put("anaerobic_training_effect", it) } ?: putNull("anaerobic_training_effect")
            a.manualActivity?.let { put("manual_activity", if (it) 1 else 0) } ?: putNull("manual_activity")
            a.elevationCorrected?.let { put("elevation_corrected", if (it) 1 else 0) } ?: putNull("elevation_corrected")
            a.waterEstimatedMl?.let { put("water_estimated_ml", it) } ?: putNull("water_estimated_ml")
            a.impactLoad?.let { put("impact_load", it) } ?: putNull("impact_load")
            a.activityTrainingLoad?.let { put("activity_training_load", it) } ?: putNull("activity_training_load")
            a.differenceBodyBattery?.let { put("difference_body_battery", it) } ?: putNull("difference_body_battery")
            a.moderateIntensityMin?.let { put("moderate_intensity_min", it) } ?: putNull("moderate_intensity_min")
            a.vigorousIntensityMin?.let { put("vigorous_intensity_min", it) } ?: putNull("vigorous_intensity_min")
            a.hrTimeInZone1?.let { put("hr_time_in_zone_1", it) } ?: putNull("hr_time_in_zone_1")
            a.hrTimeInZone2?.let { put("hr_time_in_zone_2", it) } ?: putNull("hr_time_in_zone_2")
            a.hrTimeInZone3?.let { put("hr_time_in_zone_3", it) } ?: putNull("hr_time_in_zone_3")
            a.hrTimeInZone4?.let { put("hr_time_in_zone_4", it) } ?: putNull("hr_time_in_zone_4")
            a.hrTimeInZone5?.let { put("hr_time_in_zone_5", it) } ?: putNull("hr_time_in_zone_5")
            a.cadenceDriftPct?.let { put("cadence_drift_pct", it) } ?: putNull("cadence_drift_pct")
            a.gctDriftPct?.let { put("gct_drift_pct", it) } ?: putNull("gct_drift_pct")
            a.verticalOscDriftPct?.let { put("vertical_osc_drift_pct", it) } ?: putNull("vertical_osc_drift_pct")
            a.avgGapSPerKm?.let { put("avg_grade_adjusted_pace_s_per_km", it) } ?: putNull("avg_grade_adjusted_pace_s_per_km")
            a.avgDeviceTemperatureC?.let { put("avg_device_temperature_c", it) } ?: putNull("avg_device_temperature_c")
            put("exported_at", exportedAtIso)
        }
        writableDatabase.insertWithOnConflict("activities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertWellness(w: WellnessRow, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("date", w.date)
            w.sleepScore?.let { put("sleep_score", it) } ?: putNull("sleep_score")
            w.sleepDurationS?.let { put("sleep_duration_s", it) } ?: putNull("sleep_duration_s")
            w.sleepDeepS?.let { put("sleep_deep_s", it) } ?: putNull("sleep_deep_s")
            w.sleepLightS?.let { put("sleep_light_s", it) } ?: putNull("sleep_light_s")
            w.sleepRemS?.let { put("sleep_rem_s", it) } ?: putNull("sleep_rem_s")
            w.sleepAwakeS?.let { put("sleep_awake_s", it) } ?: putNull("sleep_awake_s")
            w.sleepAvgResp?.let { put("sleep_avg_resp", it) } ?: putNull("sleep_avg_resp")
            w.hrvLastNightAvg?.let { put("hrv_last_night_avg", it) } ?: putNull("hrv_last_night_avg")
            w.hrvWeeklyAvg?.let { put("hrv_weekly_avg", it) } ?: putNull("hrv_weekly_avg")
            put("hrv_status", w.hrvStatus)
            w.rhr?.let { put("rhr", it) } ?: putNull("rhr")
            w.bodyBatteryMin?.let { put("body_battery_min", it) } ?: putNull("body_battery_min")
            w.bodyBatteryMax?.let { put("body_battery_max", it) } ?: putNull("body_battery_max")
            w.bodyBatteryCharged?.let { put("body_battery_charged", it) } ?: putNull("body_battery_charged")
            w.bodyBatteryDrained?.let { put("body_battery_drained", it) } ?: putNull("body_battery_drained")
            w.stressAvg?.let { put("stress_avg", it) } ?: putNull("stress_avg")
            w.stressMax?.let { put("stress_max", it) } ?: putNull("stress_max")
            w.trainingReadinessScore?.let { put("training_readiness_score", it) } ?: putNull("training_readiness_score")
            put("training_readiness_level", w.trainingReadinessLevel)
            w.steps?.let { put("steps", it) } ?: putNull("steps")
            w.activeCalories?.let { put("active_calories", it) } ?: putNull("active_calories")
            w.avgSleepStress?.let { put("avg_sleep_stress", it) } ?: putNull("avg_sleep_stress")
            w.sleepSpo2Avg?.let { put("sleep_spo2_avg", it) } ?: putNull("sleep_spo2_avg")
            w.sleepSpo2Min?.let { put("sleep_spo2_min", it) } ?: putNull("sleep_spo2_min")
            w.sleepRhr?.let { put("sleep_rhr", it) } ?: putNull("sleep_rhr")
            w.skinTempDeviationC?.let { put("skin_temp_deviation_c", it) } ?: putNull("skin_temp_deviation_c")
            put("sleep_training_feedback", w.sleepTrainingFeedback)
            w.floorsAscended?.let { put("floors_ascended", it) } ?: putNull("floors_ascended")
            w.stressRestS?.let { put("stress_rest_s", it) } ?: putNull("stress_rest_s")
            w.stressActivityS?.let { put("stress_activity_s", it) } ?: putNull("stress_activity_s")
            w.stressUncategorizedS?.let { put("stress_uncategorized_s", it) } ?: putNull("stress_uncategorized_s")
            w.stressLowS?.let { put("stress_low_s", it) } ?: putNull("stress_low_s")
            w.stressMediumS?.let { put("stress_medium_s", it) } ?: putNull("stress_medium_s")
            w.stressHighS?.let { put("stress_high_s", it) } ?: putNull("stress_high_s")
            put("sleep_start_local", w.sleepStartLocal)
            put("sleep_end_local", w.sleepEndLocal)
            put("exported_at", exportedAtIso)
        }
        writableDatabase.insertWithOnConflict("wellness", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun lastActivityDate(): String? {
        readableDatabase.rawQuery("SELECT MAX(date) FROM activities", null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun firstActivityDate(): String? {
        readableDatabase.rawQuery("SELECT MIN(date) FROM activities", null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun activityCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM activities", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun activitiesSince(sinceDate: String): List<ActivityRow> {
        val rows = mutableListOf<ActivityRow>()
        readableDatabase.rawQuery(
            """SELECT activity_id, date, name, sport, duration_s, distance_m, avg_hr, max_hr,
                      avg_pace_s_per_km, type_guess, avg_grade_adjusted_pace_s_per_km,
                      cadence_drift_pct, gct_drift_pct, vertical_osc_drift_pct, activity_training_load
               FROM activities WHERE date >= ? ORDER BY date ASC""",
            arrayOf(sinceDate)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    ActivityRow(
                        activityId = c.getLong(0),
                        date = c.getString(1),
                        name = c.getString(2) ?: "",
                        sport = c.getString(3),
                        durationS = if (c.isNull(4)) null else c.getDouble(4),
                        distanceM = if (c.isNull(5)) null else c.getDouble(5),
                        avgHr = if (c.isNull(6)) null else c.getInt(6),
                        maxHr = if (c.isNull(7)) null else c.getInt(7),
                        avgPaceSPerKm = if (c.isNull(8)) null else c.getDouble(8),
                        typeGuess = c.getString(9),
                        avgGapSPerKm = if (c.isNull(10)) null else c.getDouble(10),
                        cadenceDriftPct = if (c.isNull(11)) null else c.getDouble(11),
                        gctDriftPct = if (c.isNull(12)) null else c.getDouble(12),
                        verticalOscDriftPct = if (c.isNull(13)) null else c.getDouble(13),
                        activityTrainingLoad = if (c.isNull(14)) null else c.getDouble(14)
                    )
                )
            }
        }
        return rows
    }

    fun wellnessDatesSince(sinceDate: String): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT date FROM wellness WHERE date >= ?", arrayOf(sinceDate)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun wellnessSince(sinceDate: String): List<WellnessRow> {
        val rows = mutableListOf<WellnessRow>()
        readableDatabase.rawQuery(
            """SELECT date, rhr, hrv_last_night_avg, sleep_score, body_battery_min, body_battery_max,
                      body_battery_charged, body_battery_drained, stress_avg, stress_max,
                      training_readiness_score, training_readiness_level
               FROM wellness WHERE date >= ? ORDER BY date ASC""",
            arrayOf(sinceDate)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    WellnessRow(
                        date = c.getString(0),
                        rhr = if (c.isNull(1)) null else c.getInt(1),
                        hrvLastNightAvg = if (c.isNull(2)) null else c.getDouble(2),
                        sleepScore = if (c.isNull(3)) null else c.getInt(3),
                        bodyBatteryMin = if (c.isNull(4)) null else c.getInt(4),
                        bodyBatteryMax = if (c.isNull(5)) null else c.getInt(5),
                        bodyBatteryCharged = if (c.isNull(6)) null else c.getInt(6),
                        bodyBatteryDrained = if (c.isNull(7)) null else c.getInt(7),
                        stressAvg = if (c.isNull(8)) null else c.getInt(8),
                        stressMax = if (c.isNull(9)) null else c.getInt(9),
                        trainingReadinessScore = if (c.isNull(10)) null else c.getInt(10),
                        trainingReadinessLevel = c.getString(11)
                    )
                )
            }
        }
        return rows
    }

    fun replaceIntervals(activityId: Long, laps: List<IntervalRow>) {
        val db = writableDatabase
        db.delete("intervals", "activity_id=?", arrayOf(activityId.toString()))
        for (l in laps) {
            val cv = ContentValues().apply {
                put("activity_id", activityId)
                put("idx", l.idx)
                put("lap_type", l.lapType)
                l.durationS?.let { put("duration_s", it) } ?: putNull("duration_s")
                l.distanceM?.let { put("distance_m", it) } ?: putNull("distance_m")
                l.avgHr?.let { put("avg_hr", it) } ?: putNull("avg_hr")
                l.maxHr?.let { put("max_hr", it) } ?: putNull("max_hr")
                l.avgPaceSPerKm?.let { put("avg_pace_s_per_km", it) } ?: putNull("avg_pace_s_per_km")
                l.avgCadenceSpm?.let { put("avg_cadence_spm", it) } ?: putNull("avg_cadence_spm")
                l.groundContactTimeMs?.let { put("ground_contact_time_ms", it) } ?: putNull("ground_contact_time_ms")
                l.verticalOscillationMm?.let { put("vertical_oscillation_mm", it) } ?: putNull("vertical_oscillation_mm")
                l.verticalRatio?.let { put("vertical_ratio", it) } ?: putNull("vertical_ratio")
                l.strideLengthMm?.let { put("stride_length_mm", it) } ?: putNull("stride_length_mm")
                l.avgRespirationRate?.let { put("avg_respiration_rate", it) } ?: putNull("avg_respiration_rate")
                l.workoutComplianceScore?.let { put("workout_compliance_score", it) } ?: putNull("workout_compliance_score")
                l.avgGapSPerKm?.let { put("avg_grade_adjusted_pace_s_per_km", it) } ?: putNull("avg_grade_adjusted_pace_s_per_km")
            }
            db.insertWithOnConflict("intervals", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun recentRestingHr(sinceDate: String): Int? {
        readableDatabase.rawQuery(
            "SELECT AVG(rhr) FROM wellness WHERE date >= ? AND rhr IS NOT NULL",
            arrayOf(sinceDate)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return Math.round(c.getDouble(0)).toInt()
        }
        return null
    }

    fun upsertLactateThreshold(date: String, thresholdHr: Int?, thresholdPaceSPerKm: Double?, source: String?, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("date", date)
            thresholdHr?.let { put("threshold_hr", it) } ?: putNull("threshold_hr")
            thresholdPaceSPerKm?.let { put("threshold_pace_s_per_km", it) } ?: putNull("threshold_pace_s_per_km")
            put("source", source)
            put("exported_at", exportedAtIso)
        }
        writableDatabase.insertWithOnConflict("lactate_threshold", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun lactateThresholdSince(sinceDate: String): List<LactateThresholdRow> {
        val rows = mutableListOf<LactateThresholdRow>()
        readableDatabase.rawQuery(
            """SELECT date, threshold_hr, threshold_pace_s_per_km FROM lactate_threshold
               WHERE date >= ? ORDER BY date ASC""",
            arrayOf(sinceDate)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    LactateThresholdRow(
                        date = c.getString(0),
                        thresholdHr = if (c.isNull(1)) null else c.getInt(1),
                        thresholdPaceSPerKm = if (c.isNull(2)) null else c.getDouble(2)
                    )
                )
            }
        }
        return rows
    }

    fun panoFromDb(recentDays: Int = 120): Int? {
        val rows = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT date, threshold_hr FROM lactate_threshold WHERE threshold_hr IS NOT NULL", null
        ).use { c -> while (c.moveToNext()) rows.add(c.getString(0) to c.getInt(1)) }
        if (rows.isEmpty()) return null
        val lastDate = rows.maxOf { LocalDate.parse(it.first.take(10)) }
        val cutoff = lastDate.minusDays(recentDays.toLong())
        val recent = rows.filter { LocalDate.parse(it.first.take(10)) >= cutoff }.map { it.second }
        val use = recent.ifEmpty { rows.map { it.second } }
        return Math.round(use.average()).toInt()
    }

    fun restHrFromDb(recentDays: Int = 90): Double {
        val rows = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT date, rhr FROM wellness WHERE rhr IS NOT NULL", null
        ).use { c -> while (c.moveToNext()) rows.add(c.getString(0) to c.getInt(1)) }
        if (rows.isEmpty()) return 50.0
        val lastDate = rows.maxOf { LocalDate.parse(it.first.take(10)) }
        val cutoff = lastDate.minusDays(recentDays.toLong())
        val recent = rows.filter { LocalDate.parse(it.first.take(10)) >= cutoff }.map { it.second }
        val use = recent.ifEmpty { rows.map { it.second } }
        return use.average()
    }

    fun maxHrFromDb(fallbackMaxHr: Int? = null, absCeiling: Int = 215, maxSpread: Int = 60): Int {
        val clean = mutableListOf<Int>()
        readableDatabase.rawQuery(
            "SELECT max_hr, avg_hr FROM activities WHERE max_hr IS NOT NULL AND avg_hr IS NOT NULL", null
        ).use { c ->
            while (c.moveToNext()) {
                val mh = c.getInt(0); val ah = c.getInt(1)
                if (mh <= absCeiling && (mh - ah) <= maxSpread) clean.add(mh)
            }
        }
        if (clean.isNotEmpty()) return clean.max()
        return fallbackMaxHr ?: 195
    }

    /** Средний недельный беговой объём (км) за последние [weeks] недель — используется только
     * для подстановки в калькулятор беговых планов (см. ToolUrlBuilder.kt), НЕ отображается нигде
     * в самом приложении. sport ИЛИ NULL: часть ручных/старых активностей может быть без поля
     * sport — не исключаем их, иначе объём занижается. */
    fun recentWeeklyVolumeKm(weeks: Int = 4): Double? {
        val last = lastActivityDate() ?: return null
        val lastDate = runCatching { LocalDate.parse(last.take(10)) }.getOrNull() ?: return null
        val since = lastDate.minusDays(weeks.toLong() * 7)
        var totalM = 0.0
        readableDatabase.rawQuery(
            """SELECT distance_m FROM activities
               WHERE date >= ? AND distance_m IS NOT NULL AND (sport IS NULL OR sport = 'running')""",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0)) totalM += c.getDouble(0)
            }
        }
        if (totalM <= 0.0) return null
        return (totalM / 1000.0) / weeks
    }

    /** Лучший результат по каждой "табличной" дистанции калькуляторов (см. VdotMath.
     * ANCHOR_DISTANCES_M) за последние [sinceDays] дней — используется ТОЛЬКО для подстановки
     * в калькулятор беговых планов и калькулятор разряда (см. ToolUrlBuilder.kt). В отличие от
     * десктопного build_report.py (race_vdot_points), apk НЕ размечает тренировки как "гонка"
     * (см. GarminActivitiesApi.classifyByLaps — там только long/interval/threshold/easy/mixed),
     * поэтому здесь эвристика проще: среди активностей, чья дистанция в пределах ±[tolerance] от
     * табличной, берём ту, что даёт максимальный VDOT (после отсева слишком лёгких по пульсу,
     * если известно ПАНО) — то есть фактически лучший результат/прикидку на этой дистанции, а не
     * обязательно официальную гонку. Возвращает map(anchorDistM -> Pair(лучший VDOT, дата)).
     */
    fun bestEffortsByAnchor(sinceDays: Int = 545, tolerance: Double = 0.15): Map<Double, Pair<Double, String>> {
        val last = lastActivityDate() ?: return emptyMap()
        val lastDate = runCatching { LocalDate.parse(last.take(10)) }.getOrNull() ?: return emptyMap()
        val since = lastDate.minusDays(sinceDays.toLong())
        val pano = panoFromDb()
        val minHrFrac = 0.82 // отсев лёгких пробежек, похожих по дистанции на табличную, но не близких к усилию гонки

        val best = mutableMapOf<Double, Pair<Double, String>>() // anchor -> (vdot, date)
        readableDatabase.rawQuery(
            """SELECT date, distance_m, duration_s, avg_hr FROM activities
               WHERE date >= ? AND distance_m IS NOT NULL AND duration_s IS NOT NULL
                     AND distance_m >= 700 AND duration_s >= 150""",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val date = c.getString(0)
                val distM = c.getDouble(1)
                val durS = c.getDouble(2)
                val avgHr = if (c.isNull(3)) null else c.getInt(3)
                val anchor = VdotMath.ANCHOR_DISTANCES_M.firstOrNull { a ->
                    distM >= a * (1 - tolerance) && distM <= a * (1 + tolerance)
                } ?: continue
                if (pano != null && avgHr != null && avgHr < pano * minHrFrac) continue
                val vd = VdotMath.vdot(distM, durS)
                if (vd <= 0 || vd.isNaN() || vd.isInfinite()) continue
                val prev = best[anchor]
                if (prev == null || vd > prev.first) best[anchor] = vd to date
            }
        }
        return best
    }

    fun intervalsForActivity(activityId: Long): List<IntervalRow> {
        val rows = mutableListOf<IntervalRow>()
        readableDatabase.rawQuery(
            """SELECT idx, lap_type, duration_s, distance_m, avg_hr, max_hr, avg_pace_s_per_km,
                      avg_grade_adjusted_pace_s_per_km, avg_cadence_spm, ground_contact_time_ms,
                      vertical_oscillation_mm, vertical_ratio, stride_length_mm, avg_respiration_rate,
                      workout_compliance_score
               FROM intervals WHERE activity_id=? ORDER BY idx ASC""",
            arrayOf(activityId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    IntervalRow(
                        idx = c.getInt(0),
                        lapType = c.getString(1),
                        durationS = if (c.isNull(2)) null else c.getDouble(2),
                        distanceM = if (c.isNull(3)) null else c.getDouble(3),
                        avgHr = if (c.isNull(4)) null else c.getInt(4),
                        maxHr = if (c.isNull(5)) null else c.getInt(5),
                        avgPaceSPerKm = if (c.isNull(6)) null else c.getDouble(6),
                        avgGapSPerKm = if (c.isNull(7)) null else c.getDouble(7),
                        avgCadenceSpm = if (c.isNull(8)) null else c.getDouble(8),
                        groundContactTimeMs = if (c.isNull(9)) null else c.getDouble(9),
                        verticalOscillationMm = if (c.isNull(10)) null else c.getDouble(10),
                        verticalRatio = if (c.isNull(11)) null else c.getDouble(11),
                        strideLengthMm = if (c.isNull(12)) null else c.getDouble(12),
                        avgRespirationRate = if (c.isNull(13)) null else c.getDouble(13),
                        workoutComplianceScore = if (c.isNull(14)) null else c.getInt(14)
                    )
                )
            }
        }
        return rows
    }

    fun upsertCrossActivity(a: CrossActivityRow, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("activity_id", a.activityId)
            put("date", a.date)
            put("name", a.name)
            put("sport", a.sport)
            a.durationS?.let { put("duration_s", it) } ?: putNull("duration_s")
            a.distanceM?.let { put("distance_m", it) } ?: putNull("distance_m")
            a.avgHr?.let { put("avg_hr", it) } ?: putNull("avg_hr")
            a.maxHr?.let { put("max_hr", it) } ?: putNull("max_hr")
            put("exported_at", exportedAtIso)
        }
        writableDatabase.insertWithOnConflict("cross_activities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun crossActivitiesSince(sinceDate: String): List<CrossActivityRow> {
        val rows = mutableListOf<CrossActivityRow>()
        readableDatabase.rawQuery(
            """SELECT activity_id, date, name, sport, duration_s, distance_m, avg_hr, max_hr
               FROM cross_activities WHERE date >= ? ORDER BY date ASC""",
            arrayOf(sinceDate)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    CrossActivityRow(
                        activityId = c.getLong(0),
                        date = c.getString(1),
                        name = c.getString(2) ?: "",
                        sport = c.getString(3) ?: "",
                        durationS = if (c.isNull(4)) null else c.getDouble(4),
                        distanceM = if (c.isNull(5)) null else c.getDouble(5),
                        avgHr = if (c.isNull(6)) null else c.getInt(6),
                        maxHr = if (c.isNull(7)) null else c.getInt(7)
                    )
                )
            }
        }
        return rows
    }
}
