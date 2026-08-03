package com.example.runstef.network.garmin

import android.content.Context
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
}
