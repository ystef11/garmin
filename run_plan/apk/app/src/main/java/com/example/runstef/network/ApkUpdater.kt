package com.example.runstef.network

import android.content.Context
import android.content.Intent
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun updateDir(context: Context): File =
        File(context.cacheDir, "update").apply { mkdirs() }

    /**
     * Скачивает apk по [apkUrl] в кэш приложения (перезаписывая предыдущую закачку) и
     * возвращает файл. Выполняет блокирующий сетевой запрос — вызывать из фонового
     * потока/корутины (Dispatchers.IO).
     */
    fun downloadApk(context: Context, apkUrl: String): File {
        val response = client.newCall(Request.Builder().url(apkUrl).build()).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
        }
        val bytes = response.body?.bytes() ?: throw IllegalStateException("пустой ответ")
        val file = File(updateDir(context), "runstef_update.apk")
        file.writeBytes(bytes)
        return file
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
