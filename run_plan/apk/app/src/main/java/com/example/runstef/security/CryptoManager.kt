package com.example.runstef.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Единая точка доступа к шифрованию на базе Android Keystore:
 * - [securePrefs] — EncryptedSharedPreferences для настроек/ключей (PIN-хэш, ключ intervals.icu и т.п.);
 * - [readText]/[writeText] — EncryptedFile для файлов токенов Garmin (tokens.json на аккаунт).
 *
 * Мастер-ключ (AES256-GCM) генерируется и хранится в Android Keystore и никогда не покидает
 * защищённое хранилище устройства.
 */
object CryptoManager {
    private const val PREFS_NAME = "runstef_secure_prefs"

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    fun securePrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey(context),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun encryptedFile(context: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    /** Читает и расшифровывает файл, ранее записанный через [writeText]. Возвращает null, если файла нет или он повреждён. */
    fun readText(context: Context, file: File): String? {
        if (!file.exists()) return null
        return try {
            encryptedFile(context, file).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    /** Шифрует и записывает текст в файл. EncryptedFile не умеет перезаписывать существующий файл, поэтому старый удаляется. */
    fun writeText(context: Context, file: File, text: String) {
        if (file.exists()) file.delete()
        encryptedFile(context, file).openFileOutput().use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun deleteFile(file: File) {
        file.delete()
    }
}
