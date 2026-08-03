package com.example.runstef.ui.plans

import android.annotation.SuppressLint
import android.webkit.WebView
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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlanViewScreen(filePath: String) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    error?.let { Text(it) }

    AndroidView(
        // Верхний Scaffold (RunstefApp) для этого маршрута инсеты не резервирует — отступ под
        // статус-бар нужен здесь, иначе содержимое (в т.ч. кнопка «К текущему дню») заезжает под шторку.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
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
        }
    )
}
