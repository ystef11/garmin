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

    /**
     * Правка 2026-09-24 (ревью п.4 "Резервная копия Android: падение после восстановления"):
     * если файл runstef_secure_prefs был восстановлен из бэкапа, сделанного ДО того, как этот
     * файл исключили из бэкапа (см. res/xml/backup_rules.xml, data_extraction_rules.xml), ключ
     * из Android Keystore, которым он зашифрован, недоступен на новом устройстве (Keystore
     * привязан к конкретному устройству и бэкапом не переносится) - EncryptedSharedPreferences.
     * create() бросает исключение прямо здесь, а это вызывается из AuthStore при самом старте
     * приложения, так что без обработки приложение падало сразу после восстановления из бэкапа,
     * без единого шанса на самовосстановление. Теперь при ошибке чтения/расшифровки удаляем
     * повреждённый файл и создаём заново с чистого листа - пользователь просто увидит экран
     * первого входа (задать ПИН заново, перелогиниться в Garmin) вместо краша.
     */
    fun securePrefs(context: Context): SharedPreferences =
        try {
            createSecurePrefs(context)
        } catch (e: Exception) {
            context.deleteSharedPreferences(PREFS_NAME)
            // Вместе с ПИН-кодом сбрасываем и сохранённые входы Garmin: иначе после сброса префов
            // экран блокировки увидел бы «аккаунты есть, ПИН не задан» и предложил задать НОВЫЙ
            // ПИН — то есть доступ к аккаунтам без старого ПИН. Пользователь просто войдёт заново.
            File(context.filesDir, "garth").deleteRecursively()
            createSecurePrefs(context)
        }

    private fun createSecurePrefs(context: Context): SharedPreferences =
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
