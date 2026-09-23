package com.example.runstef.ui.export

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.RunPlan
import com.example.runstef.data.SettingsStore
import com.example.runstef.network.IntervalsApi
import com.example.runstef.network.garmin.GarminApi
import com.example.runstef.network.garmin.GarminAuth
import com.example.runstef.network.garmin.GarminTokenStore
import com.example.runstef.network.ImportCancelledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val planRepo = PlanRepository(application)
    private val settings = SettingsStore(application)
    private val garminTokenStore = GarminTokenStore(application)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Кнопка «Стоп» на вкладке «Экспорт» - тот же кооперативный подход, что и в аналитике
    // (см. AnalyticsImportBus.cancelRequested): GarminApi.upload/IntervalsApi.upload не
    // suspend-функции, поэтому обычная отмена корутины (job.cancel()) их блокирующие HTTP-
    // вызовы не прервёт - вместо этого они сами опрашивают этот флаг между запросами.
    private val _cancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()
    fun cancelExport() { _cancelRequested.value = true }

    fun savedGarminAccounts(): List<String> = garminTokenStore.savedAccounts()

    fun hasSavedGarminToken(account: String): Boolean =
        account.isNotBlank() && garminTokenStore.savedAccounts().contains(account.trim().lowercase())

    fun appendLog(line: String) {
        _log.value = _log.value + line
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    suspend fun loadIntervalsCreds(): Pair<String, String> =
        settings.getIntervalsApiKey() to settings.getIntervalsAthlete()

    fun exportToIntervals(
        plan: RunPlan,
        apiKey: String,
        athleteId: String,
        skipCross: Set<String>,
        dryRun: Boolean
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        _cancelRequested.value = false
        clearLog()
        viewModelScope.launch {
            try {
                settings.saveIntervalsCreds(apiKey, athleteId)
                val api = IntervalsApi(apiKey, athleteId, log = ::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(plan, skipCross, dryRun, isCancelled = { _cancelRequested.value })
                }
            } catch (e: ImportCancelledException) {
                appendLog("Отменено пользователем.")
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun exportToGarmin(
        plan: RunPlan,
        account: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        testFirstWeek: Boolean,
        allDates: Boolean = false,
        fromDate: LocalDate? = null
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        _cancelRequested.value = false
        clearLog()
        viewModelScope.launch {
            try {
                val auth = GarminAuth(log = ::appendLog)
                // Вход по паролю теперь делается только во всплывающем окне добавления
                // аккаунта (AddGarminAccountDialog) — здесь только загрузка/обновление
                // уже сохранённого токена.
                var tokens = garminTokenStore.load(account)
                if (tokens != null && auth.isExpired(tokens)) {
                    appendLog("Токен истёк, обновляю…")
                    tokens = try {
                        withContext(Dispatchers.IO) { auth.refresh(tokens!!) }
                    } catch (e: Exception) {
                        appendLog("Не удалось обновить токен (${e.message}), нужен повторный вход.")
                        null
                    }
                    tokens?.let { garminTokenStore.save(account, it) }
                }
                if (tokens == null) {
                    throw RuntimeException("Нет действующего токена для аккаунта $account: добавь/перелогинь его через «＋» у выбора аккаунта")
                }
                settings.saveLastGarminAccount(account)
                val api = GarminApi(auth, log = ::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(
                        plan, tokens!!, skipCross, dryRun, testFirstWeek, allDates, fromDate,
                        isCancelled = { _cancelRequested.value }
                    )
                }
            } catch (e: ImportCancelledException) {
                appendLog("Отменено пользователем.")
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }
}
