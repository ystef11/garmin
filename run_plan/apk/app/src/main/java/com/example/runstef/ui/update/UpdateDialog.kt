package com.example.runstef.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.runstef.network.ApkUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    /** Файл скачан, но у приложения нет разрешения «Установка из неизвестных источников». */
    data class NeedsPermission(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Диалог «Доступно обновление» — показывается при запуске приложения на любом экране (см.
 * RunstefApp в MainActivity), если версия из конфига (UpdateInfo.latestVersion) новее
 * собственной версии приложения (см. VersionCompare), а также по кнопке «Проверить обновления»
 * в настройках.
 *
 * Три способа закрыть диалог, не устанавливая:
 *  - «Позже» ([onDismiss]) — только на текущий запуск процесса (см. HomeViewModel.updateDismissed);
 *  - «Пропустить эту версию» ([onSkip]) — запоминается на диск (см. HomeViewModel.skipVersion),
 *    диалог для этой конкретной версии больше не покажется сам при запуске, пока на бэке не
 *    появится версия новее;
 *  - системный back/тап вне диалога — эквивалентен «Позже».
 *
 * На Android 8+ системный установщик не запускается (без единой ошибки или диалога), если
 * пользователь не разрешил приложению установку из неизвестных источников (см. ApkUpdater) —
 * поэтому после скачивания при отсутствии разрешения диалог не закрывается, а предлагает
 * открыть системные настройки и вернуться сюда, чтобы попробовать install ещё раз (без
 * повторного скачивания — apk уже лежит в кэше).
 */
@Composable
fun UpdateAvailableDialog(
    latestVersion: String,
    apkUrl: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    val downloading = state == DownloadState.Downloading

    fun tryInstall(file: File) {
        if (ApkUpdater.canInstallPackages(context)) {
            try {
                ApkUpdater.installApk(context, file)
                onDismiss()
            } catch (e: Exception) {
                state = DownloadState.Error(e.message ?: "не удалось запустить установку")
            }
        } else {
            state = DownloadState.NeedsPermission(file)
        }
    }

    fun startUpdate() {
        state = DownloadState.Downloading
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ApkUpdater.downloadApk(context, apkUrl) }
                tryInstall(file)
            } catch (e: Exception) {
                state = DownloadState.Error(e.message ?: "неизвестная ошибка")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Доступно обновление") },
        text = {
            when (val s = state) {
                DownloadState.Downloading -> Text("Скачивание версии $latestVersion…")
                is DownloadState.Error -> Text("Не удалось скачать обновление: ${s.message}")
                is DownloadState.NeedsPermission -> Column {
                    Text(
                        "Файл обновления скачан, но для установки нужно разрешить приложению " +
                            "Runstef установку из неизвестных источников."
                    )
                    Text(
                        text = "Уже разрешили? Нажмите здесь, чтобы попробовать снова",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { tryInstall(s.file) }
                    )
                }
                DownloadState.Idle -> Column {
                    Text("Вышла новая версия приложения ($latestVersion). Рекомендуем обновиться.")
                    Text(
                        text = "Пропустить эту версию",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { onSkip() }
                    )
                }
            }
        },
        confirmButton = {
            val needsPermission = state as? DownloadState.NeedsPermission
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (needsPermission != null) {
                        context.startActivity(ApkUpdater.requestInstallPermissionIntent(context))
                    } else {
                        startUpdate()
                    }
                }
            ) { Text(if (needsPermission != null) "Открыть настройки" else "Обновить") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Позже") }
        }
    )
}
