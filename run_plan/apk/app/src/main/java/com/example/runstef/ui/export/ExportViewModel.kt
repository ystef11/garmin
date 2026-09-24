package com.example.runstef.ui.export

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runstef.data.SettingsStore
import com.example.runstef.network.garmin.GarminTokenStore
import com.example.runstef.service.AnalyticsImportBus
import com.example.runstef.service.AnalyticsImportService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Правка 2026-09-24 (см. ревью п.2 "Экспорт не переживает ухода с вкладки"): раньше сама
 * загрузка (GarminApi.upload/IntervalsApi.upload - блокирующие вызовы) шла прямо в
 * viewModelScope этого ViewModel. Нижняя навигация делает popUpTo БЕЗ saveState, поэтому при
 * переходе на другую вкладку ViewModel уничтожался - блокирующий upload() в withContext(IO)
 * продолжал работать в фоне (viewModelScope не отменяет saveState) без кнопки «Стоп» и без
 * видимого прогресса, а повторное открытие экрана позволяло запустить вторую параллельную
 * загрузку. Теперь, как и импорт аналитики, экспорт выполняется в AnalyticsImportService
 * (foreground-сервис - переживает уход с экрана и сворачивание приложения), а этот ViewModel
 * только шлёт ему Intent и слушает общую шину [AnalyticsImportBus].
 */
class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val garminTokenStore = GarminTokenStore(application)

    // Свой лог и своя кнопка «Стоп»: шина общая с аналитикой, но во время импорта аналитики
    // здесь не показываем его лог и «Стоп» (он остановил бы импорт, а не экспорт).
    val log: StateFlow<List<String>> = AnalyticsImportBus.exportLog
    /** Идёт экспорт плана — показываем «Стоп». */
    val isRunning: StateFlow<Boolean> = AnalyticsImportBus.operation
        .map { it == AnalyticsImportBus.Operation.EXPORT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticsImportBus.operation.value == AnalyticsImportBus.Operation.EXPORT)
    /** Идёт операция аналитики — кнопки экспорта недоступны до её окончания. */
    val busyOther: StateFlow<Boolean> = AnalyticsImportBus.operation
        .map { it == AnalyticsImportBus.Operation.ANALYTICS }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnalyticsImportBus.operation.value == AnalyticsImportBus.Operation.ANALYTICS)
    val cancelRequested: StateFlow<Boolean> = AnalyticsImportBus.cancelRequested

    fun cancelExport() {
        if (AnalyticsImportBus.operation.value != AnalyticsImportBus.Operation.EXPORT) return
        // Дублирует нажатие «Стоп» в уведомлении сервиса (см. AnalyticsImportService.
        // ACTION_CANCEL) - с экрана мы можем и просто дёрнуть общий кооперативный флаг
        // напрямую, без похода в сервис, эффект тот же.
        AnalyticsImportBus.requestCancel()
    }

    fun savedGarminAccounts(): List<String> = garminTokenStore.savedAccounts()

    fun hasSavedGarminToken(account: String): Boolean =
        account.isNotBlank() && garminTokenStore.savedAccounts().contains(account.trim().lowercase())

    fun appendLog(line: String) = AnalyticsImportBus.appendLog(line)

    fun clearLog() = AnalyticsImportBus.clearLog()

    suspend fun loadIntervalsCreds(): Pair<String, String> =
        settings.getIntervalsApiKey() to settings.getIntervalsAthlete()

    fun exportToIntervals(
        planFilePath: String,
        apiKey: String,
        athleteId: String,
        skipCross: Set<String>,
        dryRun: Boolean
    ) {
        if (!AnalyticsImportBus.tryStart(AnalyticsImportBus.Operation.EXPORT)) {
            AnalyticsImportBus.logBusy(AnalyticsImportBus.Operation.EXPORT)
            return
        }
        val app = getApplication<Application>()
        // Ключ intervals.icu в Intent не кладём: сервис читает его из зашифрованного
        // SettingsStore. Поэтому сначала сохраняем, и только потом запускаем сервис.
        viewModelScope.launch {
            try {
                settings.saveIntervalsCreds(apiKey, athleteId)
                val intent = Intent(app, AnalyticsImportService::class.java).apply {
                    action = AnalyticsImportService.ACTION_EXPORT_INTERVALS
                    putExtra(AnalyticsImportService.EXTRA_PLAN_FILE, planFilePath)
                    putExtra(AnalyticsImportService.EXTRA_SKIP_CROSS, skipCross.toTypedArray())
                    putExtra(AnalyticsImportService.EXTRA_DRY_RUN, dryRun)
                }
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                AnalyticsImportBus.finish()
                AnalyticsImportBus.appendLog("Не удалось запустить фоновую задачу: ${e.message}")
            }
        }
    }

    fun exportToGarmin(
        planFilePath: String,
        account: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        testFirstWeek: Boolean,
        allDates: Boolean = false,
        fromDate: LocalDate? = null
    ) {
        viewModelScope.launch { settings.saveLastGarminAccount(account) }
        val app = getApplication<Application>()
        val intent = Intent(app, AnalyticsImportService::class.java).apply {
            action = AnalyticsImportService.ACTION_EXPORT_GARMIN
            putExtra(AnalyticsImportService.EXTRA_ACCOUNT, account)
            putExtra(AnalyticsImportService.EXTRA_PLAN_FILE, planFilePath)
            putExtra(AnalyticsImportService.EXTRA_SKIP_CROSS, skipCross.toTypedArray())
            putExtra(AnalyticsImportService.EXTRA_DRY_RUN, dryRun)
            putExtra(AnalyticsImportService.EXTRA_TEST_FIRST_WEEK, testFirstWeek)
            putExtra(AnalyticsImportService.EXTRA_ALL_DATES, allDates)
            fromDate?.let { putExtra(AnalyticsImportService.EXTRA_FROM_DATE, it.toString()) }
        }
        if (!AnalyticsImportBus.tryStart(AnalyticsImportBus.Operation.EXPORT)) {
            AnalyticsImportBus.logBusy(AnalyticsImportBus.Operation.EXPORT)
            return
        }
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            AnalyticsImportBus.finish()
            AnalyticsImportBus.appendLog("Не удалось запустить фоновую задачу: ${e.message}")
        }
    }
}
