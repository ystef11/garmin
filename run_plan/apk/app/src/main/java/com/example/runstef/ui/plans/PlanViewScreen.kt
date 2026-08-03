package com.example.runstef.ui.plans

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.runstef.data.PlanRepository

/**
 * Просмотр сохранённого плана («Мои планы» → значок просмотра) — открывает исходный HTML
 * (тот же файл, что был скачан из калькулятора) в обычном WebView, без сетевого офлайн-кэша
 * (ToolWebViewScreen/OfflineCacheWebViewClient не нужны — контент локальный, а не с сайта).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlanViewScreen(filePath: String) {
    val context = LocalContext.current
    val repo = remember { PlanRepository(context) }
    var error by remember { mutableStateOf<String?>(null) }

    error?.let { Text(it) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                try {
                    val html = repo.readRawHtml(filePath)
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                } catch (e: Exception) {
                    error = "Не удалось открыть план: ${e.message}"
                }
            }
        }
    )
}
