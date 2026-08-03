package com.example.runstef.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PlanRepository
import com.example.runstef.network.OfflineCacheWebViewClient
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Открывает калькулятор в WebView с офлайн-кэшем и перехватывает скачивание файлов с сайта:
 *  - HTML-план (кнопка «Скачать HTML плана», содержит window.__PLAN__) — сохраняется в «Мои планы»,
 *    машиночитаемый план для экспорта извлекается из него же (см. PlanHtmlParser);
 *  - все остальные скачивания (в т.ч. «Скачать plan.json для Garmin») — как обычно, уходят
 *    в системную папку «Загрузки», как при обычной браузерной загрузке файла.
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
        // hideTopBar на этом маршруте всегда true (см. MainActivity/RunstefApp) — не резервируем
        // ещё раз отступ под статус-бар, иначе вместе с внешним Scaffold получится двойной инсет
        // и над WebView остаётся пустая полоса.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
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
                                webView.evaluateJavascript(
                                    "window.print = function(){ if (window.AndroidPrint) { window.AndroidPrint.requestPrint(); } };",
                                    null
                                )
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
                                val bytes = decodeDataUriBytes(downloadUrl)
                                val fileName = fileNameFromDisposition(contentDisposition)
                                    ?: defaultFileName(mimeType)
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
                                    val ok = saveToDownloads(context, fileName, mimeType.ifBlank { "application/octet-stream" }, bytes)
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
                }
            )
        }
    }
}

/**
 * JS-мост для кнопки «Печать / PDF» (window.print() в run_plan_calculator.html).
 * Android WebView не реализует window.print() сам — печать нужно запускать явно через
 * PrintManager, используя webView.createPrintDocumentAdapter(). Вызов должен идти из UI-потока,
 * а метод интерфейса вызывается из JS-потока WebView, поэтому переключаемся через runOnUiThread.
 */
private class WebPrintBridge(private val webView: WebView, private val context: Context) {
    @JavascriptInterface
    fun requestPrint() {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@runOnUiThread
            val jobName = "Runstef — план"
            val adapter = webView.createPrintDocumentAdapter(jobName)
            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        }
    }
}

/** Декодирует data:[mime];base64,XXXX или data:[mime],url-encoded-text в байты. */
private fun decodeDataUriBytes(dataUri: String): ByteArray {
    val comma = dataUri.indexOf(',')
    require(comma >= 0) { "Некорректный data:-URI" }
    val header = dataUri.substring(5, comma) // после "data:"
    val payload = dataUri.substring(comma + 1)
    return if (header.contains("base64")) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
    }
}

private fun fileNameFromDisposition(contentDisposition: String): String? {
    val match = Regex("filename\\*?=\"?([^\";]+)\"?").find(contentDisposition) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun defaultFileName(mimeType: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val ext = when {
        mimeType.contains("html", ignoreCase = true) -> "html"
        mimeType.contains("json", ignoreCase = true) -> "json"
        else -> "bin"
    }
    return "download_$stamp.$ext"
}

/**
 * Сохраняет файл в системную папку «Загрузки» — так же, как это делает браузер.
 * На Android 10+ (API 29) — через MediaStore, без запроса разрешений.
 * На более старых версиях — в общедоступную папку Download (файл виден другим приложениям).
 */
private fun saveToDownloads(context: Context, fileName: String, mimeType: String, bytes: ByteArray): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            File(downloadsDir, fileName).writeBytes(bytes)
            true
        }
    } catch (e: Exception) {
        false
    }
}
