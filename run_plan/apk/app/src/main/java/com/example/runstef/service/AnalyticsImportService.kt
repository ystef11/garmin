package com.example.runstef.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.runstef.MainActivity
import com.example.runstef.R
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.PythonReportBuilder
import com.example.runstef.network.garmin.GarminActivitiesApi
import com.example.runstef.network.garmin.GarminAuth
import com.example.runstef.network.ImportCancelledException
import com.example.runstef.network.garmin.GarminTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Foreground-сервис для долгих операций аналитики (импорт тренировок из Garmin Connect,
 * авто-догрузка, сборка HTML-отчёта). Раньше этот код выполнялся в viewModelScope внутри
 * AnalyticsViewModel - при сворачивании приложения или блокировке экрана Android резко
 * ограничивает фоновую сеть/CPU (особенно Doze после блокировки экрана) и может убить процесс,
 * из-за чего загрузка базы прерывалась на середине (см. диалог 2026-08-23). Постоянное
 * уведомление в шторке (startForeground) говорит системе, что приложение выполняет важную для
 * пользователя работу, и системе не прерывает её так агрессивно.
 *
 * Результат работы публикуется в [AnalyticsImportBus] - ViewModel только слушает эти StateFlow
 * и не выполняет саму работу.
 */
class AnalyticsImportService : Service() {

    companion object {
        const val ACTION_IMPORT = "com.example.runstef.action.IMPORT"
        const val ACTION_AUTO_CATCH_UP = "com.example.runstef.action.AUTO_CATCH_UP"
        const val ACTION_BUILD_REPORT = "com.example.runstef.action.BUILD_REPORT"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_WITH_WELLNESS = "withWellness"
        const val EXTRA_FORCE_REFRESH = "forceRefresh"

        private const val CHANNEL_ID = "analytics_import"
        private const val NOTIFICATION_ID = 4271
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJobs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification("Аналитика: подготовка..."))
        // Общий флаг занятости (AnalyticsImportBus.isRunning) раньше выставлялся только внутри
        // runImport()/runAutoCatchUp() -- сборка отчёта (runBuildReport) его не трогала вообще,
        // поэтому можно было одновременно тянуть новый импорт/менять базу (см. importDbFromUri
        // в AnalyticsViewModel), пока сервис читает эту же базу для отчёта, либо запустить
        // сборку отчёта дважды подряд и получить гонку за один и тот же HTML-файл. Теперь любая
        // из трёх операций считается занятостью и отклоняет остальные, пока не завершится.
        if (AnalyticsImportBus.isRunning.value) {
            AnalyticsImportBus.appendLog("Уже выполняется другая операция аналитики - подождите её завершения.")
            finishJob(startId)
            return START_NOT_STICKY
        }
        activeJobs++
        when (intent.action) {
            ACTION_IMPORT -> {
                val start = LocalDate.parse(intent.getStringExtra(EXTRA_START))
                val end = LocalDate.parse(intent.getStringExtra(EXTRA_END))
                val withWellness = intent.getBooleanExtra(EXTRA_WITH_WELLNESS, true)
                val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
                runImport(account, start, end, withWellness, forceRefresh, startId)
            }
            ACTION_AUTO_CATCH_UP -> {
                val start = LocalDate.parse(intent.getStringExtra(EXTRA_START))
                val end = LocalDate.parse(intent.getStringExtra(EXTRA_END))
                runAutoCatchUp(account, start, end, startId)
            }
            ACTION_BUILD_REPORT -> runBuildReport(account, startId)
            else -> finishJob(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun runImport(
        account: String,
        start: LocalDate,
        end: LocalDate,
        withWellness: Boolean,
        forceRefresh: Boolean,
        startId: Int
    ) {
        val tokenStore = GarminTokenStore(application)
        AnalyticsImportBus.setRunning(true)
        AnalyticsImportBus.setProgress(null)
        AnalyticsImportBus.clearLog()
        AnalyticsImportBus.clearCancel()
        serviceScope.launch {
            val db = AnalyticsDb.open(application, account)
            try {
                updateNotification("Загружаю тренировки: $account")
                val auth = GarminAuth(log = AnalyticsImportBus::appendLog)
                var tokens = tokenStore.load(account)
                if (tokens != null && auth.isExpired(tokens)) {
                    AnalyticsImportBus.appendLog("Токен истёк, обновляю...")
                    tokens = try {
                        withContext(Dispatchers.IO) { auth.refresh(tokens!!) }
                    } catch (e: Exception) {
                        AnalyticsImportBus.appendLog("Не удалось обновить токен (${e.message}), нужен повторный вход.")
                        null
                    }
                    tokens?.let { tokenStore.save(account, it) }
                }
                if (tokens == null) {
                    throw RuntimeException("Нет действующего токена для аккаунта $account: добавь/перелогинь его через «+» у выбора аккаунта")
                }
                val api = GarminActivitiesApi(auth, log = AnalyticsImportBus::appendLog)
                withContext(Dispatchers.IO) {
                    api.importRange(
                        tokens, db, start, end, withWellness, forceRefresh,
                        onProgress = { done, total ->
                            val pct = if (total > 0) (done * 100 / total) else 0
                            AnalyticsImportBus.setProgress(pct)
                            updateNotification("Загружаю тренировки: $account - $pct%", pct)
                        },
                        isCancelled = { AnalyticsImportBus.cancelRequested.value }
                    )
                }
                AnalyticsImportBus.appendLog("\n[Готово]")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: ImportCancelledException) {
                AnalyticsImportBus.appendLog("\n[Остановлено пользователем] сохранено то, что успели загрузить.")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("ОШИБКА: ${e.message}")
            } finally {
                db.close()
                AnalyticsImportBus.setProgress(null)
                AnalyticsImportBus.setRunning(false)
                finishJob(startId)
            }
        }
    }

    private fun runAutoCatchUp(account: String, start: LocalDate, end: LocalDate, startId: Int) {
        val tokenStore = GarminTokenStore(application)
        AnalyticsImportBus.setRunning(true)
        AnalyticsImportBus.setProgress(null)
        AnalyticsImportBus.clearLog()
        AnalyticsImportBus.clearCancel()
        AnalyticsImportBus.appendLog(
            "[Авто-обновление] $account: догружаю с $start по $end (2 дня внахлёст - Garmin иногда донасчитывает данные задним числом)."
        )
        serviceScope.launch {
            val db = AnalyticsDb.open(application, account)
            try {
                updateNotification("Авто-обновление: $account")
                val auth = GarminAuth(log = AnalyticsImportBus::appendLog)
                val tokens = tokenStore.load(account)
                if (tokens == null) {
                    AnalyticsImportBus.appendLog("Нет сохранённого токена - авто-обновление пропущено.")
                    return@launch
                }
                val effectiveTokens = if (auth.isExpired(tokens)) {
                    val refreshed = withContext(Dispatchers.IO) { auth.refresh(tokens) }
                    tokenStore.save(account, refreshed)
                    refreshed
                } else tokens
                val api = GarminActivitiesApi(auth, log = AnalyticsImportBus::appendLog)
                withContext(Dispatchers.IO) {
                    api.importRange(
                        effectiveTokens, db, start, end, withWellness = true, forceRefreshWellness = false,
                        onProgress = { done, total ->
                            val pct = if (total > 0) (done * 100 / total) else 0
                            AnalyticsImportBus.setProgress(pct)
                            updateNotification("Авто-обновление: $account - $pct%", pct)
                        },
                        isCancelled = { AnalyticsImportBus.cancelRequested.value }
                    )
                }
                AnalyticsImportBus.appendLog("[Авто-обновление] готово.")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: ImportCancelledException) {
                AnalyticsImportBus.appendLog("[Авто-обновление] остановлено пользователем.")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("[Авто-обновление] ошибка: ${e.message}")
            } finally {
                db.close()
                AnalyticsImportBus.setProgress(null)
                AnalyticsImportBus.setRunning(false)
                finishJob(startId)
            }
        }
    }

    private fun runBuildReport(account: String, startId: Int) {
        AnalyticsImportBus.setRunning(true)
        serviceScope.launch {
            try {
                // Отчёт строит ТОТ ЖЕ build_report.py, что и на десктопе (запускается на
                // устройстве через Chaquopy, см. PythonReportBuilder) -- схема on-device БД
                // полностью совместима (AnalyticsDb.DB_VERSION=6), поэтому ручному SELECT'у из
                // БД и Kotlin-рендеру HTML тут больше не место: путь к файлу БД аккаунта
                // передаётся в Python как есть.
                val dbFile = AnalyticsDb.dbFileForAccount(application, account)
                val dir = File(application.filesDir, "reports").apply { mkdirs() }
                val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
                val file = File(dir, "report_$safe.html")
                // Кэш: пересобирать HTML (секунды-десятки секунд на графики/расчёты, см.
                // PythonReportBuilder) есть смысл только если БД аккаунта изменилась с прошлой
                // сборки. Сравниваем mtime файла БД с mtime уже готового отчёта -- если отчёт
                // не старше БД, отдаём его как есть, ничего не запуская.
                val cacheValid = file.exists() && dbFile.exists() && file.lastModified() >= dbFile.lastModified()
                if (cacheValid) {
                    AnalyticsImportBus.appendLog("База не менялась - открываю уже готовый отчёт.")
                } else {
                    updateNotification("Собираю отчёт: $account")
                    withContext(Dispatchers.IO) {
                        PythonReportBuilder.build(application, dbFile, file)
                    }
                }
                AnalyticsImportBus.setReportPath(file.absolutePath)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("Не удалось собрать отчёт: ${e.message}")
            } finally {
                AnalyticsImportBus.setRunning(false)
                finishJob(startId)
            }
        }
    }

    private fun finishJob(startId: Int) {
        activeJobs--
        if (activeJobs <= 0) {
            activeJobs = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Импорт аналитики",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Загрузка тренировок из Garmin Connect и сборка отчёта"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, percent: Int? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Аналитика Garmin")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent != null) {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, percent: Int? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, percent))
    }
}
