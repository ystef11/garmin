package com.example.runstef.service

import android.app.Application
import com.example.runstef.data.AnalyticsDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun appendLog(line: String) { _log.value = _log.value + line }
    fun clearLog() { _log.value = emptyList() }
    fun setRunning(running: Boolean) { _isRunning.value = running }
    fun setReportPath(path: String?) { _reportPath.value = path }
    fun setProgress(percent: Int?) { _progress.value = percent }

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
