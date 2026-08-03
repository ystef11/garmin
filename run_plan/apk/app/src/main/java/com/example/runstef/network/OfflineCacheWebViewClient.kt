package com.example.runstef.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WebViewClient, кэширующий ответы сайта https://ystef11.github.io/run/ на диск приложения.
 * При наличии сети — грузит и обновляет кэш, при отсутствии — отдаёт из кэша (офлайн-режим).
 * Так калькуляторы остаются доступны без интернета после первого открытия.
 */
class OfflineCacheWebViewClient(
    private val context: Context,
    private val allowedHost: String = "ystef11.github.io",
    private val onPageFinished: ((WebView) -> Unit)? = null
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(view)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "webcache").apply { mkdirs() }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url
        if (url.host != allowedHost || request.method != "GET") {
            return super.shouldInterceptRequest(view, request)
        }

        val key = cacheKey(url.toString())
        val bodyFile = File(cacheDir, "$key.body")
        val metaFile = File(cacheDir, "$key.meta")

        if (isOnline()) {
            try {
                val response = client.newCall(Request.Builder().url(url.toString()).build()).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    val mime = response.header("Content-Type")?.substringBefore(";")?.trim()
                        ?: guessMime(url.toString())
                    val encoding = response.header("Content-Type")?.substringAfter("charset=", "utf-8")?.trim() ?: "utf-8"
                    if (bytes != null) {
                        bodyFile.writeBytes(bytes)
                        metaFile.writeText("$mime\n$encoding")
                        return WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
                    }
                }
            } catch (e: Exception) {
                // сеть есть, но запрос не удался — падаем на кэш ниже
            }
        }

        if (bodyFile.exists() && metaFile.exists()) {
            val meta = metaFile.readLines()
            val mime = meta.getOrNull(0) ?: guessMime(url.toString())
            val encoding = meta.getOrNull(1) ?: "utf-8"
            return WebResourceResponse(mime, encoding, ByteArrayInputStream(bodyFile.readBytes()))
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun guessMime(url: String): String = when {
        url.endsWith(".html") -> "text/html"
        url.endsWith(".js") -> "application/javascript"
        url.endsWith(".css") -> "text/css"
        url.endsWith(".json") -> "application/json"
        url.endsWith(".png") -> "image/png"
        url.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
