package com.example.runstef.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.runstef.security.AuthStore
import com.example.runstef.security.BiometricAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние разблокировки ВСЕГО приложения (защита включается, только если есть хотя бы один
 * сохранённый аккаунт Garmin — см. GarminTokenStore.savedAccounts в MainActivity — там лежат
 * токены Garmin/intervals.icu). [unlocked] сохраняется, пока жив процесс приложения — уход в
 * фон (ON_STOP) больше не сбрасывает его (см. RunstefApp в MainActivity), поэтому ПИН/биометрия
 * запрашиваются один раз за сессию, а не при каждом возврате в приложение. Сбросить вручную
 * можно кнопкой "Заблокировать" в настройках безопасности ([lockNow]).
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun hasPin(): Boolean = authStore.isPinSet()

    fun isBiometricEnabled(): Boolean = authStore.biometricEnabled

    fun setBiometricEnabled(enabled: Boolean) {
        authStore.biometricEnabled = enabled
    }

    /**
     * Первичная настройка ПИН-кода (нет сохранённого ПИН) — сразу разблокирует приложение.
     * Если на устройстве доступна биометрия (сенсор есть и настроен), сразу включаем вход по
     * ней — это самый частый выбор, и до этой правки пользователь должен был отдельно находить
     * тумблер в «Настройках»; ПИН при этом всё равно остаётся обязательным запасным вариантом.
     */
    fun setupPin(pin: String) {
        authStore.setPin(pin)
        if (BiometricAuth.isAvailable(getApplication())) {
            authStore.biometricEnabled = true
        }
        _unlocked.value = true
    }

    fun tryUnlockWithPin(pin: String): Boolean {
        val ok = authStore.verifyPin(pin)
        if (ok) _unlocked.value = true
        return ok
    }

    /** Сколько секунд ещё действует блокировка после серии неверных ПИН (0 — блокировки нет). */
    fun lockoutSecondsRemaining(): Long = authStore.remainingLockoutSeconds()

    fun unlockWithBiometric() {
        _unlocked.value = true
    }

    /** Смена ПИН из экрана настроек безопасности — требует текущий ПИН. */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!authStore.verifyPin(oldPin)) return false
        authStore.setPin(newPin)
        return true
    }

    fun lockNow() {
        _unlocked.value = false
    }
}
