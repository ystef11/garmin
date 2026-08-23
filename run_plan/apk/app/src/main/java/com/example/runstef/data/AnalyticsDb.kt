package com.example.runstef.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDate

data class ActivityRow(
    val activityId: Long,
    val date: String,
    val name: String,
    val sport: String?,
    val durationS: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgPaceSPerKm: Double?,
    val typeGuess: String?,
    // Порт avg_grade_adjusted_pace_s_per_km (GAP) — темп с поправкой на уклон, только для
    // уличных пробежек с заметным набором высоты (см. GarminActivitiesApi.fetchGradeAdjustedPace
    // ByLap/OUTDOOR_RUN_TYPE_KEYS). null — либо не бег/дорожка, либо набор высоты незначительный,
    // либо Гармин не отдал поточные данные для этой тренировки.
    val avgGapSPerKm: Double? = null
)

data class WellnessRow(
    val date: String,
    val restingHr: Int?,
    val hrvLastNightAvg: Double?,
    val sleepScore: Int?
)

/** Один лап/сплит тренировки — упрощённый порт intervals из garmin_activities_export.py
 * (без беговой динамики/GCT/каденса — только то, что нужно классификатору classify(), см.
 * GarminActivitiesApi.classifyByLaps). lapType — сырое значение Garmin (ACTIVE/REST/WARMUP/...
 * из поля "type"/"intensityType"), null если тип не пришёл (обычные авто-лапы по километру). */
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

data class IntervalRow(
    val idx: Int,
    val lapType: String?,
    val durationS: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgPaceSPerKm: Double?,
    val avgGapSPerKm: Double? = null
)

/**
 * Локальная база аналитики Garmin — упрощённый Kotlin-аналог таблиц activities/wellness из
 * garmin_activities_export.py (та же роль: локальный кэш выгруженных тренировок и показателей
 * самочувствия, из которого строится отчёт). База — ОДНА НА АККАУНТ Garmin (см.
 * dbFileForAccount) — файлы лежат в filesDir/analytics/, рядом с токенами (files/garth/).
 *
 * Сознательно упрощено относительно десктопной версии (garmin_activities_export.py +
 * build_report.py): нет лапов/интервалов, кросс-тренировок, ПАНО-истории и десятка
 * wellness-конфаундов — только то, что нужно для отчёта в AnalyticsReportBuilder (объём,
 * темп, пульс, RHR/HRV, простая классификация лёгкая/качественная/длинная). Полный анализ
 * по-прежнему делает build_report.py на компьютере (см. plan_uploader_gui.py — вкладка
 * «Аналитика» там строит тот самый подробный HTML).
 */
class AnalyticsDb private constructor(context: Context, dbFile: File) :
    SQLiteOpenHelper(context, dbFile.absolutePath, null, DB_VERSION) {

    companion object {
        // v2: добавлена таблица intervals (лапы/сплиты — нужны classify() для честной
        // классификации тренировок, см. GarminActivitiesApi.classifyByLaps).
        // v3: добавлена таблица lactate_threshold (история ПАНО от Garmin/Firstbeat) — нужна
        // для настоящих пульсовых зон (buildZonesForClassifier), а не приближения Карвонена.
        // v4: добавлена таблица cross_activities (вело/лыжи/плавание/силовые) — контекст
        // системной нагрузки, не беговые оси.
        // v5: добавлена колонка avg_grade_adjusted_pace_s_per_km в activities и intervals —
        // GAP (grade-adjusted pace), порт apply_grade_adjustment()/fetch_grade_adjusted_pace_by_lap().
        private const val DB_VERSION = 5

        fun dirFor(context: Context): File = File(context.filesDir, "analytics").apply { mkdirs() }

        fun dbFileForAccount(context: Context, account: String): File {
            val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
            return File(dirFor(context), "garmin_$safe.db")
        }

        fun open(context: Context, account: String): AnalyticsDb =
            AnalyticsDb(context, dbFileForAccount(context, account))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
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
                type_guess TEXT,
                avg_grade_adjusted_pace_s_per_km REAL,
                exported_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_activities_date ON activities(date)")
        db.execSQL(
            """
            CREATE TABLE wellness (
                date TEXT PRIMARY KEY,
                resting_hr INTEGER,
                hrv_last_night_avg REAL,
                sleep_score INTEGER,
                exported_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE intervals (
                activity_id INTEGER NOT NULL,
                idx INTEGER NOT NULL,
                lap_type TEXT,
                duration_s REAL,
                distance_m REAL,
                avg_hr INTEGER,
                max_hr INTEGER,
                avg_pace_s_per_km REAL,
                avg_grade_adjusted_pace_s_per_km REAL,
                PRIMARY KEY (activity_id, idx)
            )
            """.trimIndent()
        )
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
            // ALTER TABLE ADD COLUMN — SQLite позволяет добавлять NULLABLE-колонку без пересоздания
            // таблицы; уже существующие строки получат NULL (пересчитается при следующем импорте).
            runCatching { db.execSQL("ALTER TABLE activities ADD COLUMN avg_grade_adjusted_pace_s_per_km REAL") }
            runCatching { db.execSQL("ALTER TABLE intervals ADD COLUMN avg_grade_adjusted_pace_s_per_km REAL") }
        }
    }

    fun upsertActivity(a: ActivityRow, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("activity_id", a.activityId)
            put("date", a.date)
            put("name", a.name)
            put("sport", a.sport)
            put("duration_s", a.durationS)
            put("distance_m", a.distanceM)
            a.avgHr?.let { put("avg_hr", it) } ?: putNull("avg_hr")
            a.maxHr?.let { put("max_hr", it) } ?: putNull("max_hr")
            a.avgPaceSPerKm?.let { put("avg_pace_s_per_km", it) } ?: putNull("avg_pace_s_per_km")
            put("type_guess", a.typeGuess)
            a.avgGapSPerKm?.let { put("avg_grade_adjusted_pace_s_per_km", it) } ?: putNull("avg_grade_adjusted_pace_s_per_km")
            put("exported_at", exportedAtIso)
        }
        writableDatabase.insertWithOnConflict("activities", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertWellness(w: WellnessRow, exportedAtIso: String) {
        val cv = ContentValues().apply {
            put("date", w.date)
            w.restingHr?.let { put("resting_hr", it) } ?: putNull("resting_hr")
            w.hrvLastNightAvg?.let { put("hrv_last_night_avg", it) } ?: putNull("hrv_last_night_avg")
            w.sleepScore?.let { put("sleep_score", it) } ?: putNull("sleep_score")
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

    /** Активности от [sinceDate] (включительно, ISO ГГГГ-ММ-ДД) до сегодня, по возрастанию даты. */
    fun activitiesSince(sinceDate: String): List<ActivityRow> {
        val rows = mutableListOf<ActivityRow>()
        readableDatabase.rawQuery(
            """SELECT activity_id, date, name, sport, duration_s, distance_m, avg_hr, max_hr,
                      avg_pace_s_per_km, type_guess, avg_grade_adjusted_pace_s_per_km
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
                        avgGapSPerKm = if (c.isNull(10)) null else c.getDouble(10)
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
            """SELECT date, resting_hr, hrv_last_night_avg, sleep_score
               FROM wellness WHERE date >= ? ORDER BY date ASC""",
            arrayOf(sinceDate)
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    WellnessRow(
                        date = c.getString(0),
                        restingHr = if (c.isNull(1)) null else c.getInt(1),
                        hrvLastNightAvg = if (c.isNull(2)) null else c.getDouble(2),
                        sleepScore = if (c.isNull(3)) null else c.getInt(3)
                    )
                )
            }
        }
        return rows
    }

    /** Заменяет лапы тренировки целиком (как upsert_activity в garmin_activities_export.py:
     * DELETE WHERE activity_id=? затем вставка заново) — лапы не апдейтятся точечно, тренировка
     * при повторной выгрузке могла быть переразмечена Гарминым (typed/plain), проще пересобрать. */
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
                l.avgGapSPerKm?.let { put("avg_grade_adjusted_pace_s_per_km", it) } ?: putNull("avg_grade_adjusted_pace_s_per_km")
            }
            db.insertWithOnConflict("intervals", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Средний resting_hr за последние 90 дней (для estimateHrZones в GarminActivitiesApi —
     * настоящий RHR из wellness точнее, чем грубая оценка по avgHr тренировок). null, если
     * записей ещё нет вообще (самый первый импорт без wellness). */
    fun recentRestingHr(sinceDate: String): Int? {
        readableDatabase.rawQuery(
            "SELECT AVG(resting_hr) FROM wellness WHERE date >= ? AND resting_hr IS NOT NULL",
            arrayOf(sinceDate)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return Math.round(c.getDouble(0)).toInt()
        }
        return null
    }

    /** Записывает/обновляет одну дату истории ПАНО (см. GarminActivitiesApi.fetchLactateThresholdRange —
     * порт fetch_lactate_threshold_range() из garmin_activities_export.py). */
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

    /** Порт _pano_from_db() из garmin_activities_export.py — среднее threshold_hr за последние
     * [recentDays] от последней записи в БД (не от "сегодня" — от максимальной даты в самой
     * таблице, чтобы работать одинаково и для свежей, и для давно не обновлявшейся истории).
     * null, если истории ПАНО в БД ещё нет вообще. */
    /** Все записи истории ПАНО от [sinceDate], где известны И threshold_hr, И
     * threshold_pace_s_per_km — нужны для VO2max-прокси (garminVo2maxProxy в VdotAnalysis.kt/
     * VolumeEfResponse.kt — см. вызывающий код), которому нужны обе величины разом. */
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

    /** Порт _rest_hr_from_db() — среднее wellness.resting_hr за последние [recentDays] от
     * последней записи с известным rhr. Фолбэк 50.0, если данных нет вообще (как в десктопе). */
    fun restHrFromDb(recentDays: Int = 90): Double {
        val rows = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT date, resting_hr FROM wellness WHERE resting_hr IS NOT NULL", null
        ).use { c -> while (c.moveToNext()) rows.add(c.getString(0) to c.getInt(1)) }
        if (rows.isEmpty()) return 50.0
        val lastDate = rows.maxOf { LocalDate.parse(it.first.take(10)) }
        val cutoff = lastDate.minusDays(recentDays.toLong())
        val recent = rows.filter { LocalDate.parse(it.first.take(10)) >= cutoff }.map { it.second }
        val use = recent.ifEmpty { rows.map { it.second } }
        return use.average()
    }

    /** Порт _max_hr_from_db() — max(max_hr) среди тренировок без выброса (разрыв max_hr-avg_hr
     * не больше [maxSpread], сам max_hr не выше [absCeiling] — защита от одиночных скачков
     * оптического пульсометра). [fallbackMaxHr] — если чистых строк нет; иначе 195. */
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

    fun intervalsForActivity(activityId: Long): List<IntervalRow> {
        val rows = mutableListOf<IntervalRow>()
        readableDatabase.rawQuery(
            """SELECT idx, lap_type, duration_s, distance_m, avg_hr, max_hr, avg_pace_s_per_km,
                      avg_grade_adjusted_pace_s_per_km
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
                        avgGapSPerKm = if (c.isNull(7)) null else c.getDouble(7)
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
