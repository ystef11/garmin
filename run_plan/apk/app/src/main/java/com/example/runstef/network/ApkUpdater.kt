package com.example.runstef.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Скачивает apk по ссылке из конфига (см. data.UpdateInfo.apkUrl) и запускает системную
 * установку через ACTION_VIEW + FileProvider.
 *
 * На Android 8+ (API 26+) само по себе отправка такого Intent НИЧЕГО не покажет и не установит,
 * если пользователь не разрешил этому конкретному приложению «Установку из неизвестных источников»
 * (это разрешение выдаётся по-приложенчески, не глобальным тумблером, как было раньше) — установщик
 * в этом случае просто не запускается, без каких-либо диалогов и ошибок. Поэтому перед запуском
 * установки нужно явно проверить [canInstallPackages] и, если разрешения нет, отправить
 * пользователя в настройки через [requestInstallPermissionIntent] (см. UpdateDialog).
 */
object ApkUpdater {

    private val client = NetworkModule.baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun updateDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    /**
     * Скачивает apk по [apkUrl] в кэш приложения (перезаписывая предыдущую закачку) и
     * возвращает файл. Выполняет блокирующий сетевой запрос — вызывать из фонового
     * потока/корутины (Dispatchers.IO).
     *
     * Правка 2026-09-24 (ревью п.8 "Автообновление: APK на 100 МБ в памяти"): раньше
     * response.body?.bytes() грузил весь apk (~100 МБ) целиком в память ДО записи на диск —
     * на слабых устройствах/с другими открытыми приложениями это могло привести к вылету по
     * нехватке памяти; при неуспешном ответе тело тоже не закрывалось (утечка соединения).
     * Теперь пишем потоком (response.use{} гарантированно закрывает тело в любом случае), а
     * если сервер прислал [expectedSha256] — проверяем целостность скачанного файла перед тем,
     * как отдать его для установки (иначе оборванная/повреждённая закачка молча ставилась бы
     * как есть).
     */
    fun downloadApk(context: Context, apkUrl: String, expectedSha256: String? = null): File {
        val file = File(updateDir(context), "runstef_update.apk")
        client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("пустой ответ")
            body.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actual = sha256Hex(file)
        if (!expectedSha256.isNullOrBlank() && !actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("Скачанный файл повреждён (sha256 не совпадает) — попробуйте ещё раз")
        }
        // Скачанный файл побайтно тот же, что уже установлен, — обновлять нечего.
        val installed = installedSha256(context)
        if (installed != null && actual.equals(installed, ignoreCase = true)) {
            file.delete()
            throw NotNewerException("Опубликован тот же файл, что уже установлен")
        }
        try {
            verifyApk(context, file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** Опубликованный apk не новее установленного — ставить нечего (например, в релиз по ошибке
     * положили старую сборку). Диалог в этом случае скрывает предложение до следующей версии,
     * чтобы не зациклиться «скачал — поставил — снова предлагает». */
    class NotNewerException(message: String) : RuntimeException(message)

    /**
     * Проверяет скачанный apk ДО установки: это наш пакет, его versionCode больше
     * установленного и он подписан тем же ключом. Системный установщик и сам не даст
     * поставить apk с чужой подписью, но с непонятной ошибкой — здесь понятное сообщение.
     */
    fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw IllegalStateException("Скачанный файл не является корректным apk — попробуйте ещё раз")
        if (archive.packageName != context.packageName) {
            throw IllegalStateException("Скачанный apk от другого приложения (${archive.packageName})")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        // Новизна определяется по хэшу файла (см. installedSha256), а не по номеру версии.
        // Здесь отсекаем только понижение versionCode — такой apk Android всё равно не установит.
        if (archive.longVersionCode < installed.longVersionCode) {
            throw IllegalStateException(
                "Опубликованная сборка старее установленной (versionCode ${archive.longVersionCode} < ${installed.longVersionCode}) — Android не даст её установить"
            )
        }
        val newSigners = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val oldSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (!newSigners.isNullOrEmpty() && !oldSigners.isNullOrEmpty() && newSigners != oldSigners) {
            throw SecurityException("Скачанный apk подписан другим ключом — установка отменена")
        }
    }

    /**
     * SHA-256 установленного apk (applicationInfo.sourceDir — это побайтная копия того файла,
     * из которого приложение было установлено). Хэш ~100 МБ считается около секунды, поэтому
     * кэшируется и пересчитывается только после переустановки/обновления приложения
     * (меняется lastUpdateTime). Сравнивается с digest ассета релиза на GitHub: отличается —
     * значит опубликован другой файл и есть что предложить. Вызывать из фонового потока.
     */
    fun installedSha256(context: Context): String? {
        return try {
            val apk = File(context.applicationInfo.sourceDir)
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val key = "${pi.lastUpdateTime}:${apk.length()}"
            val prefs = context.getSharedPreferences("installed_apk_hash", Context.MODE_PRIVATE)
            if (prefs.getString("key", null) == key) {
                prefs.getString("sha256", null)?.let { return it }
            }
            val sha = sha256Hex(apk)
            prefs.edit().putString("key", key).putString("sha256", sha).apply()
            sha
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** true, если приложению разрешено устанавливать apk через системный установщик. */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Экран настроек «Установка из неизвестных источников» для этого приложения. */
    fun requestInstallPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Открывает системный установщик для уже скачанного [apkFile] — вызывать из UI-потока,
     * только после того, как [canInstallPackages] вернул true (иначе установщик не запустится
     * без единого сообщения об ошибке).
     */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
