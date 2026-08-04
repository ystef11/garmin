package com.example.runstef.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runstef.data.ConfigRepository
import com.example.runstef.data.EffectiveConfig
import com.example.runstef.data.UpdateInfo
import com.example.runstef.data.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val title: String = "Беговые инструменты",
    val subtitle: String = "",
    val tools: List<CalculatorTool> = emptyList()
)

/**
 * Загружает конфигурацию приложения (см. ConfigRepository) один раз при создании и отдаёт
 * состав экрана «Главная» в виде готовых к отрисовке [CalculatorTool]. Тот же снимок конфига
 * (через [effectiveConfig] — версия приложения и данные для обновления) используется диалогом
 * автообновления в MainActivity.
 *
 * Два разных вида «не показывать диалог обновления»:
 *  - [updateDismissed] («Позже») — живёт только в памяти текущего процесса, при перезапуске
 *    приложения диалог покажется снова;
 *  - [skippedVersion] («Пропустить эту версию») — сохраняется на диск (см. ConfigRepository) и
 *    переживает перезапуск, пока на бэке не появится версия новее пропущенной.
 * [checkForUpdatesNow] — ручная проверка из настроек, игнорирует оба этих флага и всегда
 * обновляет [effectiveConfig] свежим запросом к бэку.
 *
 * [refreshConfig] специально обёрнута в try/catch: ConfigRepository.getEffectiveConfig() и сам
 * не бросает исключений (см. его doc), но это ViewModel-инициализация, выполняемая на самом
 * старте приложения, до отрисовки первого экрана — любая непредвиденная ошибка здесь не должна
 * ронять всё приложение, в худшем случае «Главная» просто останется на дефолтном HomeUiState().
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepository = ConfigRepository(app)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _effectiveConfig = MutableStateFlow<EffectiveConfig?>(null)
    val effectiveConfig: StateFlow<EffectiveConfig?> = _effectiveConfig.asStateFlow()

    private val _updateDismissed = MutableStateFlow(false)
    val updateDismissed: StateFlow<Boolean> = _updateDismissed.asStateFlow()

    private val _skippedVersion = MutableStateFlow<String?>(null)
    val skippedVersion: StateFlow<String?> = _skippedVersion.asStateFlow()

    init {
        _skippedVersion.value = runCatching { configRepository.getSkippedVersion() }.getOrNull()
        viewModelScope.launch {
            try {
                refreshConfig()
            } catch (e: Exception) {
                // Не даём непредвиденной ошибке загрузки конфига оставить пользователя без UI —
                // «Главная» в этом случае остаётся на дефолтном HomeUiState().
            }
        }
    }

    private suspend fun refreshConfig(): EffectiveConfig {
        // Сетевой запрос внутри getEffectiveConfig — уводим с главного потока.
        val config = withContext(Dispatchers.IO) { configRepository.getEffectiveConfig() }
        _effectiveConfig.value = config
        _uiState.value = HomeUiState(
            title = config.home.title,
            subtitle = config.home.subtitle,
            tools = config.home.items.map { it.toCalculatorTool() }
        )
        return config
    }

    fun dismissUpdate() {
        _updateDismissed.value = true
    }

    fun skipVersion(version: String) {
        runCatching { configRepository.setSkippedVersion(version) }
        _skippedVersion.value = version
    }

    /**
     * Принудительная проверка обновлений (пункт «Проверить обновления» в настройках) — всегда
     * дёргает бэк заново и возвращает найденное обновление независимо от [updateDismissed] и
     * [skippedVersion] (в отличие от диалога на старте, который эти флаги учитывает).
     * Возвращает null, если обновлений нет (текущая версия не старше latestVersion с бэка) или
     * если проверка не удалась по любой причине.
     */
    suspend fun checkForUpdatesNow(): UpdateInfo? {
        return try {
            val config = refreshConfig()
            val update = config.update
            if (update != null && VersionCompare.isNewer(update.latestVersion, config.ownVersion)) update else null
        } catch (e: Exception) {
            null
        }
    }
}
