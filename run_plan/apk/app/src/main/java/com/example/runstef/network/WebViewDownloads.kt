package com.example.runstef.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Общая логика сохранения файлов, скачиваемых через WebView.setDownloadListener — используется
 * и в ui/home/ToolWebViewScreen (калькуляторы на сайте: «Скачать HTML плана», «Скачать plan.json
 * для Garmin»), и в ui/plans/PlanViewScreen (просмотр уже сохранённого плана — там та же кнопка
 * «Скачать plan.json» встречается внутри локального HTML). WebView отдаёт такие скачивания как
 * data:-URI (blob) — обычные ссылки на файлы система обрабатывает сама, минуя этот код (см.
 * setDownloadListener в обоих экранах: он просто выходит, если downloadUrl не начинается с "data:").
 */
object WebViewDownloads {

    /** Декодирует data:[mime];base64,XXXX или data:[mime],url-encoded-text в байты. */
    fun decodeDataUriBytes(dataUri: String): ByteArray {
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

    fun fileNameFromDisposition(contentDisposition: String): String? {
        val match = Regex("filename\\*?=\"?([^\";]+)\"?").find(contentDisposition) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun defaultFileName(mimeType: String): String {
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
    fun saveToDownloads(context: Context, fileName: String, mimeType: String, bytes: ByteArray): Boolean {
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
}

/**
 * JS-мост для кнопки «Печать / PDF» (window.print() в HTML калькулятора/плана).
 * Android WebView не реализует window.print() сам — печать нужно запускать явно через
 * PrintManager, используя webView.createPrintDocumentAdapter(). Вызов должен идти из UI-потока,
 * а метод интерфейса вызывается из JS-потока WebView, поэтому переключаемся через runOnUiThread.
 * Общий для ui/home/ToolWebViewScreen (калькуляторы на сайте) и ui/plans/PlanViewScreen (просмотр
 * уже сохранённого плана) — в обоих местах кнопка «Сохранить PDF» одинаково зовёт window.print().
 */
class WebPrintBridge(
    private val webView: android.webkit.WebView,
    private val context: Context
) {
    @android.webkit.JavascriptInterface
    fun requestPrint() {
        val activity = context as? android.app.Activity ?: return
        activity.runOnUiThread {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
                ?: return@runOnUiThread
            val jobName = "Runstef — план"
            val adapter = webView.createPrintDocumentAdapter(jobName)
            printManager.print(jobName, adapter, android.print.PrintAttributes.Builder().build())
        }
    }
}

/**
 * JS, подменяющий window.print() на вызов нативного моста AndroidPrint (см. WebPrintBridge) —
 * стандартный WebView не поддерживает window.print() «из коробки». Вызывать в onPageFinished
 * после каждой загрузки страницы (переопределения JS не переживают навигацию/перезагрузку).
 */
const val INSTALL_ANDROID_PRINT_JS =
    "window.print = function(){ if (window.AndroidPrint) { window.AndroidPrint.requestPrint(); } };"
