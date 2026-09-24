package com.example.runstef.network

import com.example.runstef.data.UpdateInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Узнаёт о новой версии приложения прямо из GitHub Releases — без ручного заполнения
 * config.json. Источник истины — последний (не черновик и не pre-release) релиз репозитория:
 *  - `tag_name` — версия (совпадает с versionName, см. app/build.gradle.kts);
 *  - ассет `*.apk` — ссылка на скачивание и `digest` ("sha256:…"), который GitHub вычисляет
 *    сам при загрузке файла в релиз (используется для проверки целостности, если есть).
 *
 * Лимит API GitHub без авторизации — 60 запросов в час с одного IP; проверка делается один раз
 * при запуске и по кнопке «Проверить обновления», этого достаточно.
 */
object ReleaseChecker {

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/ystef11/run/releases/latest"
    private const val PREFERRED_APK_NAME = "runstef-release.apk"

    private val client = NetworkModule.baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Последний релиз или null (нет сети, лимит API, в релизе нет apk). Блокирующий вызов. */
    fun latest(): UpdateInfo? {
        val text = client.newCall(
            Request.Builder().url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string() ?: return null
        }
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val tag = (root["tag_name"] as? JsonPrimitive)?.content?.trim()?.removePrefix("v") ?: return null
        val assets = (root["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val apk = assets.firstOrNull { (it["name"] as? JsonPrimitive)?.content == PREFERRED_APK_NAME }
            ?: assets.firstOrNull { (it["name"] as? JsonPrimitive)?.content?.endsWith(".apk") == true }
            ?: return null
        val url = (apk["browser_download_url"] as? JsonPrimitive)?.content ?: return null
        val sha256 = (apk["digest"] as? JsonPrimitive)?.content
            ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        return UpdateInfo(latestVersion = tag, apkUrl = url, sha256 = sha256)
    }
}
