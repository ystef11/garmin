package com.example.runstef.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Загружает произвольную HTML-страницу по URL для функции «По URL» на вкладке «Мои планы».
 *
 * Использует тот же дисковый кэш и схему ключей (sha256(url) в context.cacheDir/webcache),
 * что и [OfflineCacheWebViewClient] — поэтому, если страница не оказалась планом и открывается
 * как обычная веб-страница в WebView, повторного скачивания не требуется, и наоборот.
 * При наличии сети — качает и обновляет кэш, при отсутствии — отдаёт последнюю закэшированную копию.
 */
object PlanUrlLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "webcache").apply { mkdirs() }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Возвращает текст страницы (из сети либо из кэша), либо null, если недоступно ни то, ни другое.
     * Выполняет блокирующий сетевой запрос — вызывать из фонового потока/корутины (Dispatchers.IO).
     */
    fun loadHtml(context: Context, url: String): String? {
        val key = cacheKey(url)
        val bodyFile = File(cacheDir(context), "$key.body")
        val metaFile = File(cacheDir(context), "$key.meta")

        if (isOnline(context)) {
            try {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        val mime = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "text/html"
                        val encoding = response.header("Content-Type")
                            ?.substringAfter("charset=", "utf-8")?.trim() ?: "utf-8"
                        bodyFile.writeBytes(bytes)
                        metaFile.writeText("$mime\n$encoding")
                        return runCatching { String(bytes, charset(encoding)) }.getOrElse { String(bytes, Charsets.UTF_8) }
                    }
                }
            } catch (e: Exception) {
                // сеть есть, но запрос не удался — падаем на кэш ниже
            }
        }

        if (bodyFile.exists()) {
            val encoding = metaFile.takeIf { it.exists() }?.readLines()?.getOrNull(1) ?: "utf-8"
            return runCatching { String(bodyFile.readBytes(), charset(encoding)) }
                .getOrElse { bodyFile.readText() }
        }

        return null
    }
}
