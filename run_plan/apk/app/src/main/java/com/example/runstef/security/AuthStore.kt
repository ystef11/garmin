package com.example.runstef.security

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хранит вход в приложение: ПИН-код никогда не сохраняется в открытом виде — только
 * PBKDF2-хэш со случайной солью в [CryptoManager.securePrefs] (EncryptedSharedPreferences,
 * ключ — в Android Keystore). ПИН обязателен и работает как запасной вариант, даже если
 * биометрия включена, недоступна или отключена пользователем в системе.
 */
class AuthStore(context: Context) {
    private val prefs = CryptoManager.securePrefs(context)

    private companion object {
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
        const val KEY_LOCKED_UNTIL_MS = "pin_locked_until_ms"
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256

        // Защита от подбора ПИН при физическом доступе к устройству: после
        // LOCKOUT_THRESHOLD неверных попыток подряд включается экспоненциальный backoff
        // (30с, 60с, 120с, ... до получаса), вместо неограниченного числа локальных попыток.
        const val LOCKOUT_THRESHOLD = 5
        const val LOCKOUT_BASE_SECONDS = 30L
        const val LOCKOUT_MAX_SECONDS = 1800L
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash.toHex())
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LOCKED_UNTIL_MS)
            .apply()
    }

    // ИСПРАВЛЕНО (ревью п.16, таблица "PIN lockout должен использовать SystemClock.
    // elapsedRealtime()"): раньше "until" считался и сравнивался через
    // System.currentTimeMillis() — это ОБЫЧНЫЕ настенные часы устройства, которые пользователь
    // (в т.ч. атакующий с физическим доступом) может просто перевести вперёд в «Дата и время»
    // системных настроек, БЕЗ root — и блокировка по подбору ПИН обходится за несколько
    // секунд. SystemClock.elapsedRealtime() — монотонное время с последней загрузки, на него
    // изменение настенных часов не влияет. Обратная сторона: elapsedRealtime() сбрасывается в
    // ~0 при перезагрузке устройства, из-за чего старое сохранённое "until" (посчитанное от
    // аптайма ДО перезагрузки) после ребута может стать бессмысленно огромным относительно
    // нового elapsedRealtime() и блокировка выглядела бы вечной — поэтому remain дополнительно
    // клампится сверху по LOCKOUT_MAX_SECONDS: если "until" завышен настолько, что оставшееся
    // время превышает максимально возможный по логике backoff интервал, сохранённое значение
    // считается устаревшим (пережившим перезагрузку) и сбрасывается, а не трактуется как
    // блокировка на неопределённый срок.
    /** Сколько секунд ещё действует блокировка после серии неверных ПИН — 0, если блокировки нет. */
    fun remainingLockoutSeconds(): Long {
        val until = prefs.getLong(KEY_LOCKED_UNTIL_MS, 0L)
        if (until == 0L) return 0L
        val remain = (until - SystemClock.elapsedRealtime()) / 1000L
        if (remain > LOCKOUT_MAX_SECONDS) {
            prefs.edit().remove(KEY_LOCKED_UNTIL_MS).apply()
            return 0L
        }
        return if (remain > 0) remain else 0L
    }

    fun verifyPin(pin: String): Boolean {
        if (remainingLockoutSeconds() > 0) return false
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expectedHex = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val actualHex = hash(pin, saltHex.fromHex()).toHex()
        // Сравнение постоянного времени, чтобы не давать утечки по таймингу.
        val ok = MessageDigest.isEqual(actualHex.toByteArray(), expectedHex.toByteArray())
        if (ok) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).remove(KEY_LOCKED_UNTIL_MS).apply()
        } else {
            registerFailedAttempt()
        }
        return ok
    }

    private fun registerFailedAttempt() {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= LOCKOUT_THRESHOLD) {
            val extraSteps = attempts - LOCKOUT_THRESHOLD
            val backoffSeconds = (LOCKOUT_BASE_SECONDS shl minOf(extraSteps, 6)).coerceAtMost(LOCKOUT_MAX_SECONDS)
            editor.putLong(KEY_LOCKED_UNTIL_MS, SystemClock.elapsedRealtime() + backoffSeconds * 1000L)
        }
        editor.apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKED_UNTIL_MS)
            .apply()
    }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()
        }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
