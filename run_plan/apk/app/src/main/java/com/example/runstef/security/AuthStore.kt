package com.example.runstef.security

import android.content.Context
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
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash.toHex())
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expectedHex = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val actualHex = hash(pin, saltHex.fromHex()).toHex()
        // Сравнение постоянного времени, чтобы не давать утечки по таймингу.
        return MessageDigest.isEqual(actualHex.toByteArray(), expectedHex.toByteArray())
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply()
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
