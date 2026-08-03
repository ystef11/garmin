package com.example.runstef.data

import android.content.Context
import com.example.runstef.security.CryptoManager

/**
 * Пользовательские настройки экспорта — API-ключ intervals.icu, ID атлета, последний аккаунт Garmin.
 * Токены Garmin хранятся отдельно, см. GarminTokenStore.
 *
 * Хранятся в EncryptedSharedPreferences (см. [CryptoManager.securePrefs]) — ключ intervals.icu
 * даёт полный доступ к аккаунту пользователя, поэтому не должен лежать в открытом виде.
 * Методы оставлены suspend для совместимости с существующими вызовами (сама операция синхронная и быстрая).
 */
class SettingsStore(private val context: Context) {

    private val keyIntervalsApiKey = "intervals_api_key"
    private val keyIntervalsAthlete = "intervals_athlete_id"
    private val keyLastGarminAccount = "last_garmin_account"

    private val prefs get() = CryptoManager.securePrefs(context)

    suspend fun getIntervalsApiKey(): String = prefs.getString(keyIntervalsApiKey, "") ?: ""
    suspend fun getIntervalsAthlete(): String = prefs.getString(keyIntervalsAthlete, "") ?: ""
    suspend fun getLastGarminAccount(): String = prefs.getString(keyLastGarminAccount, "") ?: ""

    suspend fun saveIntervalsCreds(apiKey: String, athlete: String) {
        prefs.edit()
            .putString(keyIntervalsApiKey, apiKey)
            .putString(keyIntervalsAthlete, athlete)
            .apply()
    }

    suspend fun saveLastGarminAccount(account: String) {
        prefs.edit().putString(keyLastGarminAccount, account).apply()
    }
}
