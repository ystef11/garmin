package com.example.runstef.ui.analytics

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.runstef.network.enableZoom
import java.io.File

/**
 * Показывает HTML-отчёт аналитики (сгенерирован AnalyticsReportBuilder, лежит в
 * filesDir/reports/) во встроенном WebView — тот же приём с content://-URI через FileProvider,
 * что и в PlanViewScreen (рендер-процесс WebView не имеет прямого доступа к диску приложения,
 * а сетевые/виртуальные хосты на некоторых устройствах не резолвятся).
 *
 * Пинч-зум включён (см. network/WebViewZoom.enableZoom) — графики в отчёте нарисованы как
 * SVG с фиксированной шириной на точку и раскладкой в горизонтальный скролл, так что для
 * подробного разглядывания недельных значений его удобно ещё и увеличить жестом.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AnalyticsReportScreen(filePath: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    error?.let { Text(it) }

    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        factory = { ctx ->
            lateinit var swipeRefresh: SwipeRefreshLayout
            val webView = WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowContentAccess = true
                enableZoom()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        swipeRefresh.isRefreshing = false
                    }
                }
                try {
                    val file = File(filePath)
                    require(file.exists()) { "файл отчёта не найден" }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    loadUrl(uri.toString())
                } catch (e: Exception) {
                    error = "Не удалось открыть отчёт: ${e.message}"
                }
            }
            swipeRefresh = SwipeRefreshLayout(ctx).apply {
                addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                setOnRefreshListener { webView.reload() }
            }
            swipeRefresh
        }
    )
}
