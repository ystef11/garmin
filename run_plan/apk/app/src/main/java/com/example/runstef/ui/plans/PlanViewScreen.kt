package com.example.runstef.ui.plans

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PlanRepository
import com.example.runstef.network.INSTALL_ANDROID_PRINT_JS
import com.example.runstef.network.WebPrintBridge
import com.example.runstef.network.WebViewDownloads
import java.io.File

/**
 * Просмотр сохранённого плана («Мои планы» → значок просмотра) — открывает исходный HTML
 * (тот же файл, что был скачан из калькулятора) в обычном WebView, без сетевого офлайн-кэша
 * (ToolWebViewScreen/OfflineCacheWebViewClient не нужны — контент локальный, а не с сайта).
 *
 * Пробовали loadDataWithBaseURL(html) — большие планы (много недель, встроенный window.__PLAN__)
 * передаются в рендер-процесс через Binder IPC с лимитом ~1 МБ, хвост документа обрезался, терялся
 * закрывающий <script> с jumpToday() («К текущему дню» не работала). Пробовали loadUrl("file://…") —
 * рендер-процесс WebView в отдельной песочнице без доступа к context.filesDir → ERR_ACCESS_DENIED.
 * Пробовали виртуальный https-хост через WebViewAssetLoader — тоже не сработало (ERR_CONNECTION_REFUSED,
 * похоже что-то в сети/DNS устройства мешает даже зарезервированному domain'у appassets.androidx.webkit.net).
 * Вместо этого грузим через content://-URI уже настроенного FileProvider (тот же, что используется для
 * шаринга плана, см. PlanRepository.getShareUri / res/xml/file_paths.xml) — он резолвится через
 * ContentResolver в процессе самого приложения, без сети и без прямого доступа рендерера к диску.
 *
 * setDownloadListener — без него клик по кнопкам «Скачать plan.json для Garmin»/«Скачать HTML
 * плана» внутри просматриваемого плана ничего не делал: WebView без обработчика скачивания
 * молча игнорирует data:-URI (тот же баг, что чинили в ToolWebViewScreen; логика сохранения
 * файла общая — см. network/WebViewDownloads).
 *
 * webViewClient с onPageFinished + addJavascriptInterface(AndroidPrint) — без них кнопка
 * «Сохранить PDF» (window.print() внутри HTML плана) молча ничего не делала: стандартный WebView
 * не реализует window.print(), а без явного webViewClient здесь не было и подмены window.print
 * на вызов моста, как это сделано в ToolWebViewScreen (там подмена шла через OfflineCacheWebViewClient.
 * onPageFinished, который для просмотра локального плана не используется). Мост WebPrintBridge и
 * сам JS — общие с ToolWebViewScreen, см. network/WebViewDownloads.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlanViewScreen(filePath: String) {
    val context = LocalContext.current
    val repo = remember { PlanRepository(context) }
    var error by remember { mutableStateOf<String?>(null) }

    error?.let { Text(it) }

    AndroidView(
        // Верхний Scaffold (RunstefApp) для этого маршрута инсеты не резервирует — отступ под
        // статус-бар нужен здесь, иначе содержимое (в т.ч. кнопка «К текущему дню») заезжает под шторку.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        factory = { ctx ->
            // Свайп вниз от верха страницы перезагружает отображаемый HTML плана из файла,
            // как в обычном браузере (та же логика, что и в ToolWebViewScreen).
            lateinit var swipeRefresh: SwipeRefreshLayout
            val webView = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(INSTALL_ANDROID_PRINT_JS, null)
                        swipeRefresh.isRefreshing = false
                    }
                }
                // «Печать / PDF» (window.print() внутри HTML плана) сама по себе ничего не делает
                // в Android WebView — печать запускаем через этот мост и системный PrintManager.
                addJavascriptInterface(WebPrintBridge(this, context), "AndroidPrint")
                setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                    try {
                        if (!downloadUrl.startsWith("data:")) {
                            // Обычная ссылка (не blob/data-uri) — пусть система сама разберётся со скачиванием.
                            return@setDownloadListener
                        }
                        val bytes = WebViewDownloads.decodeDataUriBytes(downloadUrl)
                        val fileName = WebViewDownloads.fileNameFromDisposition(contentDisposition)
                            ?: WebViewDownloads.defaultFileName(mimeType)
                        val isHtml = mimeType.contains("html", ignoreCase = true) ||
                            fileName.endsWith(".html", ignoreCase = true)
                        val htmlText = if (isHtml) String(bytes, Charsets.UTF_8) else null

                        if (htmlText != null && PlanHtmlParser.looksLikePlanHtml(htmlText)) {
                            val saved = repo.savePlan(htmlText)
                            Toast.makeText(
                                context,
                                "План «${saved.plan.meta.name}» сохранён в «Мои планы»",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val ok = WebViewDownloads.saveToDownloads(
                                context, fileName, mimeType.ifBlank { "application/octet-stream" }, bytes
                            )
                            Toast.makeText(
                                context,
                                if (ok) "Файл «$fileName» сохранён в Загрузки" else "Не удалось сохранить «$fileName» в Загрузки",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Не удалось сохранить файл: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                try {
                    val file = File(filePath)
                    require(file.exists()) { "файл не найден" }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    loadUrl(uri.toString())
                } catch (e: Exception) {
                    error = "Не удалось открыть план: ${e.message}"
                }
            }
            swipeRefresh = SwipeRefreshLayout(ctx).apply {
                addView(
                    webView,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                setOnRefreshListener { webView.reload() }
            }
            swipeRefresh
        }
    )
}
