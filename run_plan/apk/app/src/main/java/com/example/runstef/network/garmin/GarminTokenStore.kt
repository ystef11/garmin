package com.example.runstef.network.garmin

import android.content.Context
import android.content.SharedPreferences
import com.example.runstef.security.CryptoManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GarminTokens(
    val oauth1Token: String,
    val oauth1TokenSecret: String,
    val oauth2AccessToken: String,
    val oauth2RefreshToken: String,
    val oauth2ExpiresAtEpochSec: Long
)

/**
 * Аналог ~/.garth/<логин>/ из garmin_plan_import.py — токены хранятся по-учётно
 * в files/garth/<логин>/tokens.json, что позволяет держать несколько аккаунтов.
 *
 * Файл шифруется через [CryptoManager] (EncryptedFile, AES256-GCM, ключ в Android Keystore) —
 * токены Garmin (по сути равносильны паролю от аккаунта) никогда не лежат на диске в открытом виде.
 */
class GarminTokenStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun baseDir(): File = File(context.filesDir, "garth").apply { mkdirs() }

    private fun accountDir(account: String): File =
        File(baseDir(), account.trim().lowercase()).apply { mkdirs() }

    fun savedAccounts(): List<String> =
        baseDir().listFiles { f -> f.isDirectory && File(f, "tokens.json").exists() }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun load(account: String): GarminTokens? {
        val f = File(accountDir(account), "tokens.json")
        val text = CryptoManager.readText(context, f) ?: return null
        return try {
            json.decodeFromString(GarminTokens.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }

    fun save(account: String, tokens: GarminTokens) {
        val f = File(accountDir(account), "tokens.json")
        CryptoManager.writeText(context, f, json.encodeToString(GarminTokens.serializer(), tokens))
    }

    fun clear(account: String) {
        CryptoManager.deleteFile(File(accountDir(account), "tokens.json"))
    }

    // Обычные (не зашифрованные) SharedPreferences — как и plan_order в PlanRepository, здесь
    // хранится не секрет, а только имя того из уже сохранённых аккаунтов, который считается
    // "основным". Токены самого аккаунта по-прежнему шифруются отдельно (см. save/load выше).
    private fun accountsPrefs(): SharedPreferences =
        context.getSharedPreferences("garmin_accounts", Context.MODE_PRIVATE)

    private val keyPrimaryAccount = "primary_account"

    /**
     * Основной аккаунт Garmin — именно из его локальной базы аналитики (AnalyticsDb) подставляются
     * данные в калькуляторы (pano/hrmax/curvol/res, см. ui/home/ToolUrlBuilder.kt), а также
     * подсвечивается в модалке управления аккаунтами (см. ui/common/AccountManagementDialog.kt).
     *
     * Тот же принцип, что и у "основного плана" (см. PlanRepository.getDefaultPlan()): явный выбор
     * пользователя, а если его нет (ещё не выбирали, либо выбранный аккаунт с тех пор удалён) —
     * единственный сохранённый аккаунт становится основным автоматически; при нескольких без
     * явного выбора берётся первый по алфавиту (savedAccounts() уже отсортирован) — детерминированно,
     * а не произвольно. Возвращает "" только если сохранённых аккаунтов вообще нет.
     */
    fun primaryAccount(): String {
        val saved = savedAccounts()
        if (saved.isEmpty()) return ""
        val stored = accountsPrefs().getString(keyPrimaryAccount, null)?.trim()?.lowercase()
        if (stored != null && saved.contains(stored)) return stored
        return saved.first()
    }

    fun setPrimaryAccount(account: String) {
        accountsPrefs().edit().putString(keyPrimaryAccount, account.trim().lowercase()).apply()
    }
}
