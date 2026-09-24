package com.example.runstef.service

import android.app.Application
import com.example.runstef.data.AnalyticsDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Общее состояние процесса импорта/автообновления/сборки отчёта аналитики. Раньше жило как
 * private-поля внутри AnalyticsViewModel, но с этой правки сама долгая работа (сеть+БД)
 * выполняется в AnalyticsImportService (foreground-сервис) - см. пояснение там: без
 * foreground-сервиса Android прерывал загрузку при сворачивании приложения или блокировке
 * экрана. AnalyticsViewModel может быть пересоздан (см. companion object
 * autoCatchUpDoneForAccount там же - экран открывается через Navigation-Compose без
 * saveState/restoreState), а сервис - нет, поэтому состояние живёт здесь, в отдельном
 * синглтоне, и ViewModel просто его слушает.
 */
object AnalyticsImportBus {

    /** Вид долгой операции. Шина одна (сервис одновременно выполняет только одну операцию), но
     * лог и кнопка «Стоп» у импорта аналитики и у экспорта плана — свои: иначе во время импорта
     * на вкладке «Экспорт» показывался бы лог импорта, а «Стоп» там останавливал бы импорт. */
    enum class Operation { ANALYTICS, EXPORT }

    private const val MAX_LOG_LINES = 3000

    @Volatile
    private var currentOp: Operation = Operation.ANALYTICS

    private val _operation = MutableStateFlow<Operation?>(null)
    /** Операция, которая выполняется сейчас (null — ничего не выполняется). */
    val operation: StateFlow<Operation?> = _operation.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Лог импорта/отчёта аналитики. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _exportLog = MutableStateFlow<List<String>>(emptyList())
    /** Лог экспорта плана (Garmin/intervals.icu). */
    val exportLog: StateFlow<List<String>> = _exportLog.asStateFlow()

    private fun logFlow(): MutableStateFlow<List<String>> =
        if (currentOp == Operation.EXPORT) _exportLog else _log

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

    // Аккаунт, статистику которого сейчас показывает экран (устанавливается при каждом
    // открытии/переключении аккаунта в AnalyticsScreen -- см. refreshStats ниже). Нужен, чтобы
    // завершение импорта ДРУГОГО, уже покинутого аккаунта не перезаписало статистику текущего:
    // например, запустили импорт аккаунта A, переключились на B, импорт A завершился и без этой
    // проверки затёр бы _lastActivityDate/_firstActivityDate/_activityCount данными A, хотя на
    // экране уже B.
    @Volatile
    private var currentStatsAccount: String? = null

    // Прогресс текущего импорта в процентах (0..100). null - выполняется, но точный процент
    // ещё неизвестен (сборка отчёта) или ничего не выполняется - тогда UI показывает обычный
    // неопределённый троббер вместо процента.
    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    // Кнопка "Стоп" на экране аналитики ставит этот флаг в true, пока идёт импорт/авто-
    // догрузка (см. AnalyticsScreen). Сама сетевая работа в GarminActivitiesApi.importRange
    // не suspend-функция и не проверяет обычную отмену корутины (job.cancel() не прервёт её
    // посреди Thread.sleep/блокирующего HTTP-вызова) -- поэтому это простой кооперативный
    // флаг, который importRange сам опрашивает между запросами (после каждой активности/дня
    // самочувствия) и прерывается ImportCancelledException. Сборку отчёта (runBuildReport)
    // не отменяем -- она короткая и локальная, кнопка "Стоп" на ней не показывается.
    private val _cancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()

    // Атомарный флаг занятости (правка 2026-09-24, см. ревью п.5 "Сервис импорта может
    // остановить сам себя"): раньше занятость проверялась через `if (isRunning.value)`, а
    // выставлялась отдельным следующим вызовом `setRunning(true)` - между этими двумя шагами
    // мог проскочить второй параллельный запрос (двойной тап по «Импорт»), который тоже видел
    // isRunning=false и тоже считал себя вправе стартовать. tryStart() делает проверку и
    // захват одной атомарной операцией (AtomicBoolean.compareAndSet).
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    // ИСПРАВЛЕНО (ревью п.16, таблица): раньше _log.value = _log.value + line - это
    // НЕ атомарная операция (read-modify-write из двух отдельных шагов): appendLog
    // может звать и AnalyticsImportService (фоновый поток сервиса), и, теоретически,
    // UI - два конкурентных вызова могли оба прочитать одно и то же старое значение
    // _log.value и один из них тихо затирал бы добавленную другим строку. update{}
    // делает это атомарно (compareAndSet-цикл MutableStateFlow), как и tryStart()/
    // finish() выше для busy.
    fun appendLog(line: String) { logFlow().update { (it + line).takeLast(MAX_LOG_LINES) } }
    fun clearLog() { logFlow().value = emptyList() }
    fun setRunning(running: Boolean) { _isRunning.value = running; busy.set(running); if (!running) _operation.value = null }
    fun setReportPath(path: String?) { _reportPath.value = path }
    fun setProgress(percent: Int?) { _progress.value = percent }
    fun requestCancel() { _cancelRequested.value = true }
    fun clearCancel() { _cancelRequested.value = false }

    /** Атомарно занимает шину под новую операцию. Возвращает false, если уже что-то
     * выполняется - вызывающий в этом случае ничего не должен запускать (и, если он уже успел
     * что-то создать под эту попытку - например, отправить Intent сервису - должен это
     * аккуратно свернуть, не трогая уже идущую операцию). Предпочтительна вместо ручной пары
     * `if (isRunning.value) return; setRunning(true)`. */
    fun tryStart(op: Operation = Operation.ANALYTICS): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        currentOp = op
        _operation.value = op
        _cancelRequested.value = false
        _isRunning.value = true
        return true
    }

    /** Сообщение «занято» — в лог той операции, которую пытались запустить (а не текущей). */
    fun logBusy(op: Operation) {
        val flow = if (op == Operation.EXPORT) _exportLog else _log
        val running = if (_operation.value == Operation.EXPORT) "экспорт плана" else "операция аналитики"
        flow.update { (it + "Уже выполняется $running — дождитесь её завершения или нажмите «Стоп».").takeLast(MAX_LOG_LINES) }
    }

    /** Освобождает шину по завершении операции (успех/ошибка/отмена) - парная к tryStart(). */
    fun finish() {
        _isRunning.value = false
        _operation.value = null
        busy.set(false)
    }

    /** Помечает [account] как аккаунт, который сейчас выбран/показан на экране аналитики --
     * вызывается ТОЛЬКО из явного пользовательского переключения (AnalyticsViewModel.
     * refreshLastActivityDate, дергается из AnalyticsScreen при LaunchedEffect(account)), а НЕ
     * из завершения фонового импорта: иначе завершение импорта другого, уже покинутого
     * аккаунта, само бы "переключало" текущий аккаунт обратно на себя и отменяло защиту в
     * refreshStats() ниже. */
    fun setCurrentAccount(account: String) { currentStatsAccount = account }

    /** Синхронное чтение статистики базы (дата последней/первой тренировки, количество) -
     * дешёвый локальный SELECT, вызывается и из ViewModel сразу (открытие/смена аккаунта), и
     * из сервиса по завершении работы. */
    fun refreshStats(app: Application, account: String) {
        if (account.isBlank()) {
            currentStatsAccount = null
            _lastActivityDate.value = null
            _firstActivityDate.value = null
            _activityCount.value = 0
            return
        }
        val db = AnalyticsDb.open(app, account)
        val last = db.lastActivityDate()
        val first = db.firstActivityDate()
        val count = db.activityCount()
        db.close()
        if (currentStatsAccount != account) return
        _lastActivityDate.value = last
        _firstActivityDate.value = first
        _activityCount.value = count
    }
}
