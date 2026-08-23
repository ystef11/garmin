package com.example.runstef.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runstef.data.ActivityRow
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.AnalyticsReportBuilder
import com.example.runstef.data.CrossActivityRow
import com.example.runstef.data.IntervalRow
import com.example.runstef.data.LactateThresholdRow
import com.example.runstef.data.WellnessRow
import com.example.runstef.data.SettingsStore
import com.example.runstef.network.garmin.GarminActivitiesApi
import com.example.runstef.network.garmin.GarminAuth
import com.example.runstef.network.garmin.GarminTokenStore
import com.example.runstef.network.garmin.GarminTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Множество аккаунтов, для которых автокатч-ап уже выполнялся с момента запуска
        // процесса приложения. Обычный instance-флаг во ViewModel не подходит: экран
        // Аналитики в MainActivity открывается через Navigation-Compose БЕЗ saveState/
        // restoreState (см. NavigationBarItem.onClick), поэтому при каждом переключении
        // вкладок ViewModel этого экрана пересоздаётся заново. Companion-object живёт,
        // пока жив процесс приложения - то есть ровно "один раз за запуск приложения",
        // как и просил пользователь, а не один раз за каждое открытие вкладки.
        private val autoCatchUpDoneForAccount = mutableSetOf<String>()
    }

    private val app get() = getApplication<Application>()
    private val tokenStore = GarminTokenStore(app)
    private val settings = SettingsStore(app)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastActivityDate = MutableStateFlow<String?>(null)
    val lastActivityDate: StateFlow<String?> = _lastActivityDate.asStateFlow()

    private val _firstActivityDate = MutableStateFlow<String?>(null)
    val firstActivityDate: StateFlow<String?> = _firstActivityDate.asStateFlow()

    private val _activityCount = MutableStateFlow(0)
    val activityCount: StateFlow<Int> = _activityCount.asStateFlow()

    private val _reportPath = MutableStateFlow<String?>(null)
    val reportPath: StateFlow<String?> = _reportPath.asStateFlow()

    fun savedGarminAccounts(): List<String> = tokenStore.savedAccounts()

    fun hasSavedGarminToken(account: String): Boolean =
        account.isNotBlank() && tokenStore.savedAccounts().contains(account.trim().lowercase())

    suspend fun lastUsedAccount(): String = settings.getLastGarminAccount()

    /** Обновляет и дату последней тренировки, и статистику по базе (диапазон дат/количество) —
     * вызывается после каждого импорта и при выборе/открытии аккаунта (см. AnalyticsScreen). */
    fun refreshLastActivityDate(account: String) {
        if (account.isBlank()) {
            _lastActivityDate.value = null
            _firstActivityDate.value = null
            _activityCount.value = 0
            return
        }
        val db = AnalyticsDb.open(app, account)
        _lastActivityDate.value = db.lastActivityDate()
        _firstActivityDate.value = db.firstActivityDate()
        _activityCount.value = db.activityCount()
        db.close()
    }

    private fun appendLog(line: String) { _log.value = _log.value + line }
    private fun clearLog() { _log.value = emptyList() }

    /**
     * Импорт базы аналитики целиком из файла на диске (выбирается системным файловым
     * менеджером, см. AnalyticsScreen -- тот же UploadFile-паттерн, что и импорт HTML-плана в
     * PlansScreen). Полностью ЗАМЕНЯЕТ текущую локальную SQLite-базу этого аккаунта файлом,
     * выбранным пользователем -- например, скопированной с компьютера базой garmin_<account>.db
     * (та же схема таблиц, что и в apk, если экспортирована этим же приложением на другом
     * устройстве, или совместимая внешняя копия). Проверяет только заголовок SQLite (первые 16
     * байт файла) -- не валидирует схему таблиц: если файл не той структуры, следующий же
     * запрос к БД в приложении упадёт с понятной ошибкой SQLite, а не тихо покажет пустой отчёт.
     */
    fun importDbFromUri(account: String, uri: android.net.Uri) {
        if (account.isBlank()) {
            appendLog("Не выбран аккаунт - импорт базы отменён.")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val destFile = AnalyticsDb.dbFileForAccount(app, account)
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException("Не удалось открыть выбранный файл")
                    stream.use { input ->
                        val header = ByteArray(16)
                        var read = 0
                        while (read < 16) {
                            val n = input.read(header, read, 16 - read)
                            if (n < 0) break
                            read += n
                        }
                        val headerText = String(header, Charsets.ISO_8859_1)
                        if (read < 16 || !headerText.startsWith("SQLite format 3")) {
                            throw RuntimeException("Файл не похож на базу SQLite (неверный заголовок)")
                        }
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { out ->
                            out.write(header, 0, read)
                            input.copyTo(out)
                        }
                        // На случай, если у этой БД уже были открыты WAL/SHM-спутники от
                        // предыдущей версии файла -- удаляем, иначе SQLite может попытаться
                        // применить их к новому файлу и получить рассинхронизацию.
                        File(destFile.path + "-wal").delete()
                        File(destFile.path + "-shm").delete()
                        File(destFile.path + "-journal").delete()
                    }
                }
                appendLog("База аналитики импортирована из файла для аккаунта $account.")
                refreshLastActivityDate(account)
            } catch (e: Exception) {
                appendLog("Ошибка импорта базы: ${e.message}")
            }
        }
    }

    /** Вход по паролю теперь только во всплывающем окне (AddGarminAccountDialog, см.
     * ui/common) — здесь только загрузка/обновление уже сохранённого токена. */
    private suspend fun resolveTokens(account: String, auth: GarminAuth): GarminTokens {
        var tokens = tokenStore.load(account)
        if (tokens != null && auth.isExpired(tokens)) {
            appendLog("Токен истёк, обновляю…")
            tokens = try {
                withContext(Dispatchers.IO) { auth.refresh(tokens!!) }
            } catch (e: Exception) {
                appendLog("Не удалось обновить токен (${e.message}), нужен повторный вход.")
                null
            }
            tokens?.let { tokenStore.save(account, it) }
        }
        if (tokens == null) {
            throw RuntimeException("Нет действующего токена для аккаунта $account: добавь/перелогинь его через «＋» у выбора аккаунта")
        }
        settings.saveLastGarminAccount(account)
        return tokens
    }

    fun importActivities(
        account: String,
        startDate: LocalDate,
        endDate: LocalDate,
        withWellness: Boolean,
        forceRefreshWellness: Boolean
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        clearLog()
        viewModelScope.launch {
            try {
                val auth = GarminAuth(log = ::appendLog)
                val tokens = resolveTokens(account, auth)
                val api = GarminActivitiesApi(auth, log = ::appendLog)
                val db = AnalyticsDb.open(app, account)
                withContext(Dispatchers.IO) {
                    api.importRange(tokens, db, startDate, endDate, withWellness, forceRefreshWellness)
                }
                db.close()
                appendLog("\n[Готово]")
                refreshLastActivityDate(account)
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Тихое авто-обновление при открытии вкладки — только если для аккаунта уже есть непустая
     * база (см. AnalyticsScreen: если базы ещё нет, ждём ручной первой загрузки с явным периодом). */
    fun autoCatchUp(account: String) {
        if (_isRunning.value || account.isBlank()) return
        if (!autoCatchUpDoneForAccount.add(account)) return
        val db = AnalyticsDb.open(app, account)
        val last = db.lastActivityDate()
        if (last == null) { db.close(); return }
        val lastDate = runCatching { LocalDate.parse(last) }.getOrNull()
        if (lastDate == null) { db.close(); return }
        val start = lastDate.minusDays(2)
        val end = LocalDate.now()
        if (start.isAfter(end)) { db.close(); return }
        _isRunning.value = true
        clearLog()
        appendLog("[Авто-обновление] $account: последняя тренировка в базе - $lastDate, догружаю с $start по $end (2 дня внахлёст - Garmin иногда донасчитывает данные задним числом).")
        viewModelScope.launch {
            try {
                val auth = GarminAuth(log = ::appendLog)
                val tokens = tokenStore.load(account)
                if (tokens == null) {
                    appendLog("Нет сохранённого токена — авто-обновление пропущено.")
                    return@launch
                }
                val effectiveTokens = if (auth.isExpired(tokens)) {
                    val refreshed = withContext(Dispatchers.IO) { auth.refresh(tokens) }
                    tokenStore.save(account, refreshed)
                    refreshed
                } else tokens
                val api = GarminActivitiesApi(auth, log = ::appendLog)
                withContext(Dispatchers.IO) {
                    api.importRange(effectiveTokens, db, start, end, withWellness = true, forceRefreshWellness = false)
                }
                appendLog("[Авто-обновление] готово.")
                refreshLastActivityDate(account)
            } catch (e: Exception) {
                appendLog("[Авто-обновление] ошибка: ${e.message}")
            } finally {
                db.close()
                _isRunning.value = false
            }
        }
    }

    fun buildReport(account: String) {
        viewModelScope.launch {
            try {
                val db = AnalyticsDb.open(app, account)
                val results = withContext(Dispatchers.IO) {
                    val since = LocalDate.now().minusYears(2).toString()
                    val acts = db.activitiesSince(since)
                    val well = db.wellnessSince(since)
                    val cross = db.crossActivitiesSince(since)
                    // Zones (z2Hi/z4Lo/pano) — те же build_zones_for_classifier, что и в
                    // GarminActivitiesApi.buildZonesForClassifier/estimateRealHrZones (нужны
                    // импортёру для classifyByLaps); здесь просто повторно читаем их из БД,
                    // не пересоздавая GarminActivitiesApi (он привязан к GarminAuth/токену,
                    // а тут нужны только чистые данные для отображения в отчёте).
                    val pano = db.panoFromDb()
                    var rhrMaxHr: Pair<Double, Int>? = null
                    val zones = pano?.let { p ->
                        val rhr = db.restHrFromDb()
                        val maxHr = db.maxHrFromDb(acts.mapNotNull { it.maxHr }.maxOrNull())
                        rhrMaxHr = rhr to maxHr
                        val hrr = maxHr - rhr
                        val z3LoHrr = Math.round(rhr + 0.70 * hrr).toInt()
                        val z2Hi = z3LoHrr - 1
                        val z3Lo = z2Hi + 1
                        val z4Lo = Math.round((z3Lo + p) / 2.0).toInt()
                        Triple(z2Hi, z4Lo, p)
                    }
                    // Лапы — предзагружаем для ВСЕХ тренировок с известным avgHr (нужно и
                    // raceVdotPoints — гоночным кандидатам, и intervalThresholdVdotPoints —
                    // ищет рабочие отрезки в ЛЮБОЙ тренировке, не только гоночной/интервальной,
                    // см. докстринг в VdotAnalysis.kt). Это чтение из локальной SQLite, не сеть —
                    // приемлемо даже для многих тренировок за период.
                    val allIntervals = acts.filter { it.avgHr != null }
                        .associate { it.activityId to db.intervalsForActivity(it.activityId) }
                    val lactateHistory = db.lactateThresholdSince(since)
                    kotlin.collections.listOf(acts, well, zones, cross, allIntervals, lactateHistory, rhrMaxHr)
                }
                db.close()
                @Suppress("UNCHECKED_CAST")
                val html = AnalyticsReportBuilder.build(
                    account,
                    results[0] as List<ActivityRow>,
                    results[1] as List<WellnessRow>,
                    results[2] as Triple<Int, Int, Int>?,
                    results[3] as List<CrossActivityRow>,
                    results[4] as Map<Long, List<IntervalRow>>,
                    results[5] as List<LactateThresholdRow>,
                    results[6] as Pair<Double, Int>?
                )
                val dir = File(app.filesDir, "reports").apply { mkdirs() }
                val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
                val file = File(dir, "report_$safe.html")
                file.writeText(html)
                _reportPath.value = file.absolutePath
            } catch (e: Exception) {
                appendLog("Не удалось собрать отчёт: ${e.message}")
            }
        }
    }

    fun consumeReportPath() { _reportPath.value = null }
}
