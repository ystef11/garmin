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
import kotlinx.coroutines.CompletableDeferred
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

    private val _mfaRequested = MutableStateFlow(false)
    val mfaRequested: StateFlow<Boolean> = _mfaRequested.asStateFlow()
    private var mfaDeferred: CompletableDeferred<String>? = null

    fun savedGarminAccounts(): List<String> = garminTokenStore.savedAccounts()

    fun hasSavedGarminToken(account: String): Boolean =
        account.isNotBlank() && garminTokenStore.savedAccounts().contains(account.trim().lowercase())

    fun appendLog(line: String) {
        _log.value = _log.value + line
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun submitMfaCode(code: String) {
        mfaDeferred?.complete(code)
        _mfaRequested.value = false
    }

    fun cancelMfa() {
        mfaDeferred?.completeExceptionally(RuntimeException("Ввод кода 2FA отменён"))
        _mfaRequested.value = false
    }

    suspend fun loadIntervalsCreds(): Pair<String, String> =
        settings.getIntervalsApiKey() to settings.getIntervalsAthlete()

    fun exportToIntervals(
        plan: RunPlan,
        apiKey: String,
        athleteId: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        clear: Boolean
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        clearLog()
        viewModelScope.launch {
            try {
                settings.saveIntervalsCreds(apiKey, athleteId)
                val api = IntervalsApi(apiKey, athleteId, log = ::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(plan, skipCross, dryRun, clear)
                }
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
        password: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        testFirstWeek: Boolean,
        clearAll: Boolean,
        clearPast: Boolean,
        clearBefore: LocalDate?
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        clearLog()
        viewModelScope.launch {
            try {
                val auth = GarminAuth(log = ::appendLog)
                var tokens = garminTokenStore.load(account)
                if (password.isNotBlank()) {
                    // Пользователь вручную ввёл/изменил пароль — это явный запрос на
                    // переавторизацию, даже если для аккаунта уже есть валидный токен.
                    appendLog("Вход в Garmin Connect ($account)…")
                    tokens = withContext(Dispatchers.IO) {
                        auth.login(account, password, mfaPrompt = GarminAuth.MfaPrompt {
                            _mfaRequested.value = true
                            val deferred = CompletableDeferred<String>()
                            mfaDeferred = deferred
                            deferred.await()
                        })
                    }
                    garminTokenStore.save(account, tokens)
                    settings.saveLastGarminAccount(account)
                    appendLog("Токен сохранён для $account")
                } else {
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
                        throw RuntimeException("Нужен пароль для входа в аккаунт $account: сохранённого токена нет или его не удалось обновить")
                    }
                    settings.saveLastGarminAccount(account)
                }
                val api = GarminApi(auth, log = ::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(plan, tokens!!, skipCross, dryRun, testFirstWeek, clearAll, clearPast, clearBefore)
                }
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }
}
