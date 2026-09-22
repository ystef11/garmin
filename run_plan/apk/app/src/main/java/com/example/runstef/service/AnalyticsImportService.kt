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
import com.example.runstef.data.ActivityRow
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.AnalyticsReportBuilder
import com.example.runstef.data.CrossActivityRow
import com.example.runstef.data.IntervalRow
import com.example.runstef.data.LactateThresholdRow
import com.example.runstef.data.WellnessRow
import com.example.runstef.network.garmin.GarminActivitiesApi
import com.example.runstef.network.garmin.GarminAuth
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
        serviceScope.launch {
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
                val db = AnalyticsDb.open(application, account)
                withContext(Dispatchers.IO) {
                    api.importRange(tokens, db, start, end, withWellness, forceRefresh) { done, total ->
                        val pct = if (total > 0) (done * 100 / total) else 0
                        AnalyticsImportBus.setProgress(pct)
                        updateNotification("Загружаю тренировки: $account - $pct%", pct)
                    }
                }
                db.close()
                AnalyticsImportBus.appendLog("\n[Готово]")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("ОШИБКА: ${e.message}")
            } finally {
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
                    api.importRange(effectiveTokens, db, start, end, withWellness = true, forceRefreshWellness = false) { done, total ->
                        val pct = if (total > 0) (done * 100 / total) else 0
                        AnalyticsImportBus.setProgress(pct)
                        updateNotification("Авто-обновление: $account - $pct%", pct)
                    }
                }
                AnalyticsImportBus.appendLog("[Авто-обновление] готово.")
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
                updateNotification("Собираю отчёт: $account")
                val db = AnalyticsDb.open(application, account)
                val results = withContext(Dispatchers.IO) {
                    val since = LocalDate.now().minusYears(2).toString()
                    val acts = db.activitiesSince(since)
                    val well = db.wellnessSince(since)
                    val cross = db.crossActivitiesSince(since)
                    val pano = db.panoFromDb()
                    var rhrMaxHr: Pair<Double, Int>? = null
                    val zones = pano?.let { p ->
                        val rhr = db.restHrFromDb()
                        val maxHr = db.maxHrFromDb(acts.mapNotNull { it.maxHr }.maxOrNull())
                        rhrMaxHr = rhr to maxHr
                        val hrr = maxHr - rhr
                        val z3LoHrr = Math.round(rhr + 0.70 * hrr).toInt()
                        val z2Hi = z3LoHrr - 1
                        val z3Lo = z2Hi + 1
                        val z4Lo = Math.round((z3Lo + p) / 2.0).toInt()
                        Triple(z2Hi, z4Lo, p)
                    }
                    val allIntervals = acts.filter { it.avgHr != null }
                        .associate { it.activityId to db.intervalsForActivity(it.activityId) }
                    val lactateHistory = db.lactateThresholdSince(since)
                    kotlin.collections.listOf(acts, well, zones, cross, allIntervals, lactateHistory, rhrMaxHr)
                }
                db.close()
                @Suppress("UNCHECKED_CAST")
                val html = AnalyticsReportBuilder.build(
                    account,
                    results[0] as List<ActivityRow>,
                    results[1] as List<WellnessRow>,
                    results[2] as Triple<Int, Int, Int>?,
                    results[3] as List<CrossActivityRow>,
                    results[4] as Map<Long, List<IntervalRow>>,
                    results[5] as List<LactateThresholdRow>,
                    results[6] as Pair<Double, Int>?
                )
                val dir = File(application.filesDir, "reports").apply { mkdirs() }
                val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
                val file = File(dir, "report_$safe.html")
                file.writeText(html)
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
