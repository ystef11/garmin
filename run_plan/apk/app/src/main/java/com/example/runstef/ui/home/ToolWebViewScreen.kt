package com.example.runstef.ui.home

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PlanRepository
import com.example.runstef.network.INSTALL_ANDROID_PRINT_JS
import com.example.runstef.network.OfflineCacheWebViewClient
import com.example.runstef.network.WebPrintBridge
import com.example.runstef.network.WebViewDownloads
import kotlinx.coroutines.launch

/**
 * Открывает калькулятор в WebView с офлайн-кэшем и перехватывает скачивание файлов с сайта:
 *  - HTML-план (кнопка «Скачать HTML плана», содержит window.__PLAN__) — сохраняется в «Мои планы»,
 *    машиночитаемый план для экспорта извлекается из него же (см. PlanHtmlParser);
 *  - все остальные скачивания (в т.ч. «Скачать plan.json для Garmin») — как обычно, уходят
 *    в системную папку «Загрузки», как при обычной браузерной загрузке файла.
 * Логика сохранения самого файла (декодирование data:-URI, запись в Загрузки) — в
 * network/WebViewDownloads, общая с ui/plans/PlanViewScreen (просмотр уже сохранённого плана,
 * там та же кнопка «Скачать plan.json» встречается внутри локального HTML). Мост «Печать / PDF»
 * (WebPrintBridge) — там же, общий с PlanViewScreen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ToolWebViewScreen(url: String) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repo = remember { PlanRepository(context) }

    // Callback от WebChromeClient.onShowFileChooser — сохраняем, чтобы передать результат
    // выбора файла (кнопка «Импортировать plan.json») после возврата из системного пикера.
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    Scaffold(
        // hideTopBar на этом маршруте всегда true (см. MainActivity/RunstefApp) — внешний Scaffold
        // там инсеты не резервирует вовсе (WindowInsets(0,0,0,0)), поэтому именно этот, внутренний,
        // Scaffold отвечает за отступ под статус-бар (иначе контент WebView заезжает под шторку).
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Свайп вниз от самого верха страницы обновляет её, как в обычном браузере.
                    // SwipeRefreshLayout сам определяет верх через WebView.canScrollVertically(-1),
                    // поэтому срабатывает только когда страница проскроллена до верха.
                    lateinit var swipeRefresh: SwipeRefreshLayout
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // Хост берём из самого URL — это позволяет использовать тот же офлайн-кэш
                        // как для встроенных калькуляторов, так и для произвольных ссылок «по URL»
                        // на вкладке «Мои планы» (см. PlansScreen).
                        val cacheHost = Uri.parse(url).host ?: "ystef11.github.io"
                        webViewClient = OfflineCacheWebViewClient(
                            ctx,
                            allowedHost = cacheHost,
                            // Стандартный WebView не поддерживает window.print() «из коробки» —
                            // после каждой загрузки страницы подменяем его в JS так, чтобы он
                            // вызывал нативный мост AndroidPrint (см. addJavascriptInterface ниже).
                            onPageFinished = { webView ->
                                webView.evaluateJavascript(INSTALL_ANDROID_PRINT_JS, null)
                                swipeRefresh.isRefreshing = false
                            }
                        )
                        // «Печать / PDF» (window.print() в run_plan_calculator.html) сама по себе
                        // ничего не делает в Android WebView — печать запускаем через этот мост
                        // и системный PrintManager.
                        addJavascriptInterface(WebPrintBridge(this, context), "AndroidPrint")
                        // «Импортировать plan.json» — <input type=file>: без onShowFileChooser
                        // клик по нему не открывает системный пикер файлов вообще.
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                view: WebView?,
                                callback: ValueCallback<Array<Uri>>,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = callback
                                val mimeType = fileChooserParams?.acceptTypes
                                    ?.firstOrNull { it.isNotBlank() && it != "*/*" }
                                    ?: "application/json"
                                filePickerLauncher.launch(mimeType)
                                return true
                            }
                        }
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
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "План «${saved.plan.meta.name}» сохранён в «Мои планы»"
                                        )
                                    }
                                } else {
                                    val ok = WebViewDownloads.saveToDownloads(
                                        context, fileName, mimeType.ifBlank { "application/octet-stream" }, bytes
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) "Файл «$fileName» сохранён в Загрузки"
                                            else "Не удалось сохранить «$fileName» в Загрузки"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Не удалось сохранить файл: ${e.message}")
                                }
                            }
                        }
                        loadUrl(url)
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
    }
}
