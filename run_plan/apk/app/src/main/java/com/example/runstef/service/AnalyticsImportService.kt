package com.example.runstef.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.runstef.MainActivity
import com.example.runstef.R
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PythonReportBuilder
import com.example.runstef.network.IntervalsApi
import com.example.runstef.network.garmin.GarminActivitiesApi
import com.example.runstef.network.garmin.GarminApi
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
 * авто-догрузка, сборка HTML-отчёта) И, с правки 2026-09-24, экспорта плана в Garmin/
 * intervals.icu (см. ревью п.2 "Экспорт не переживает ухода с вкладки") - раньше экспорт жил в
 * ExportViewModel.viewModelScope и при переходе на другую вкладку ViewModel уничтожался
 * (нижняя навигация делает popUpTo без saveState), блокирующий upload() в withContext(IO) при
 * этом продолжал работать в фоне, но кнопки «Стоп» для него уже не было, а повторное открытие
 * экрана могло запустить вторую параллельную загрузку. Постоянное уведомление в шторке
 * (startForeground) говорит системе, что приложение выполняет важную для пользователя работу,
 * и она не прерывает её так агрессивно при сворачивании/блокировке экрана.
 *
 * Результат работы публикуется в [AnalyticsImportBus] - ViewModel'и (аналитики и экспорта)
 * только слушают эти StateFlow и не выполняют саму работу.
 */
class AnalyticsImportService : Service() {

    companion object {
        const val ACTION_IMPORT = "com.example.runstef.action.IMPORT"
        const val ACTION_AUTO_CATCH_UP = "com.example.runstef.action.AUTO_CATCH_UP"
        const val ACTION_BUILD_REPORT = "com.example.runstef.action.BUILD_REPORT"
        const val ACTION_EXPORT_GARMIN = "com.example.runstef.action.EXPORT_GARMIN"
        const val ACTION_EXPORT_INTERVALS = "com.example.runstef.action.EXPORT_INTERVALS"
        // Действие кнопки «Стоп» в самом уведомлении сервиса (не с экрана приложения) - см.
        // ревью п.2: "Добавить действие «Стоп» прямо в уведомление сервиса". Обрабатывается ДО
        // общей проверки занятости в onStartCommand, иначе отменить текущую операцию было бы
        // невозможно, пока она идёт (шина как раз занята ею).
        const val ACTION_CANCEL = "com.example.runstef.action.CANCEL"

        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_WITH_WELLNESS = "withWellness"
        const val EXTRA_FORCE_REFRESH = "forceRefresh"

        // Экспорт: план передаётся путём к сохранённому HTML-файлу (PlanRepository), а не
        // самим объектом RunPlan - он заново парсится PlanHtmlParser'ом внутри сервиса. Так
        // Intent остаётся маленьким (Binder ограничивает размер extras) и переживает то, что
        // сервис/процесс мог быть создан заново.
        const val EXTRA_PLAN_FILE = "planFile"
        const val EXTRA_SKIP_CROSS = "skipCross"
        const val EXTRA_DRY_RUN = "dryRun"
        const val EXTRA_TEST_FIRST_WEEK = "testFirstWeek"
        const val EXTRA_ALL_DATES = "allDates"
        const val EXTRA_FROM_DATE = "fromDate"

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

        if (intent.action == ACTION_CANCEL) {
            // Не трогает activeJobs/шину - этот Intent ничего не занимал, он только просит
            // уже идущую операцию остановиться (кооперативный флаг, см. AnalyticsImportBus.
            // cancelRequested). stopSelf(startId) лишь снимает С ОЧЕРЕДИ этот конкретный
            // запуск, не касаясь foreground-статуса, который держит настоящая работа.
            AnalyticsImportBus.requestCancel()
            return START_NOT_STICKY
        }

        // Каждый startForegroundService() обязан закончиться startForeground() — иначе Android
        // уронит приложение. Если сервис уже работает, показываем тот же текст, что и сейчас
        // (а не «подготовка…»), чтобы не затирать прогресс идущей операции.
        startForeground(NOTIFICATION_ID, buildNotification(lastNotificationText, lastNotificationPercent))

        // Флаг занятости захватывает ViewModel (AnalyticsImportBus.tryStart) ДО отправки Intent,
        // поэтому второй Intent при двойном тапе сюда вообще не приходит. Если всё же пришёл
        // второй запуск, пока идёт работа (activeJobs > 0), — просто игнорируем его: НЕ вызываем
        // stopSelf(startId) (это последний startId, и он остановил бы весь сервис вместе с
        // идущей операцией) и не трогаем activeJobs.
        if (activeJobs > 0) {
            return START_NOT_STICKY
        }
        // Запуск не через ViewModel (занятость не захвачена) — захватываем здесь.
        if (!AnalyticsImportBus.isRunning.value) {
            val op = if (intent.action == ACTION_EXPORT_GARMIN || intent.action == ACTION_EXPORT_INTERVALS)
                AnalyticsImportBus.Operation.EXPORT else AnalyticsImportBus.Operation.ANALYTICS
            if (!AnalyticsImportBus.tryStart(op)) return START_NOT_STICKY
        }
        activeJobs++
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: ""
        when (intent.action) {
            ACTION_IMPORT -> {
                val start = intent.getStringExtra(EXTRA_START)?.let(LocalDate::parse)
                val end = intent.getStringExtra(EXTRA_END)?.let(LocalDate::parse)
                if (account.isBlank() || start == null || end == null) {
                    AnalyticsImportBus.appendLog("Некорректные параметры импорта.")
                    AnalyticsImportBus.finish(); finishJob(startId)
                } else {
                    val withWellness = intent.getBooleanExtra(EXTRA_WITH_WELLNESS, true)
                    val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
                    runImport(account, start, end, withWellness, forceRefresh, startId)
                }
            }
            ACTION_AUTO_CATCH_UP -> {
                val start = intent.getStringExtra(EXTRA_START)?.let(LocalDate::parse)
                val end = intent.getStringExtra(EXTRA_END)?.let(LocalDate::parse)
                if (account.isBlank() || start == null || end == null) {
                    AnalyticsImportBus.finish(); finishJob(startId)
                } else {
                    runAutoCatchUp(account, start, end, startId)
                }
            }
            ACTION_BUILD_REPORT -> {
                if (account.isBlank()) {
                    AnalyticsImportBus.finish(); finishJob(startId)
                } else {
                    runBuildReport(account, startId)
                }
            }
            ACTION_EXPORT_GARMIN -> {
                val planFile = intent.getStringExtra(EXTRA_PLAN_FILE)
                if (account.isBlank() || planFile.isNullOrBlank()) {
                    AnalyticsImportBus.appendLog("Некорректные параметры экспорта в Garmin.")
                    AnalyticsImportBus.finish(); finishJob(startId)
                } else {
                    runExportGarmin(
                        account, planFile,
                        skipCross = intent.getStringArrayExtra(EXTRA_SKIP_CROSS)?.toSet() ?: emptySet(),
                        dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false),
                        testFirstWeek = intent.getBooleanExtra(EXTRA_TEST_FIRST_WEEK, false),
                        allDates = intent.getBooleanExtra(EXTRA_ALL_DATES, false),
                        fromDate = intent.getStringExtra(EXTRA_FROM_DATE)?.let(LocalDate::parse),
                        startId = startId
                    )
                }
            }
            ACTION_EXPORT_INTERVALS -> {
                val planFile = intent.getStringExtra(EXTRA_PLAN_FILE)
                if (planFile.isNullOrBlank()) {
                    AnalyticsImportBus.appendLog("Некорректные параметры экспорта в intervals.icu.")
                    AnalyticsImportBus.finish(); finishJob(startId)
                } else {
                    runExportIntervals(
                        planFile,
                        skipCross = intent.getStringArrayExtra(EXTRA_SKIP_CROSS)?.toSet() ?: emptySet(),
                        dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false),
                        startId = startId
                    )
                }
            }
            else -> { AnalyticsImportBus.finish(); finishJob(startId) }
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
        AnalyticsImportBus.setProgress(null)
        AnalyticsImportBus.clearLog()
        AnalyticsImportBus.clearCancel()
        serviceScope.launch {
            val db = AnalyticsDb.open(application, account)
            try {
                updateNotification("Загружаю тренировки: $account")
                val auth = GarminAuth(context = application, log = AnalyticsImportBus::appendLog)
                // См. ревью п.7: авто-обновление токена, если он истёк ПОСРЕДИ операции (а не
                // только в начале) - без этого поздние запросы за 401 тихо превращались в
                // null-поля выше по стеку (GarminActivitiesApi/GarminApi).
                auth.onTokenExpired = { old ->
                    runCatching { auth.refresh(old) }.getOrNull()?.also { tokenStore.save(account, it) }
                }
                var tokens = tokenStore.load(account)
                if (tokens != null && auth.isExpired(tokens)) {
                    AnalyticsImportBus.appendLog("Токен истёк, обновляю...")
                    tokens = try {
                        withContext(Dispatchers.IO) { auth.refresh(tokens) }
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
            } catch (_: ImportCancelledException) {
                AnalyticsImportBus.appendLog("\n[Остановлено пользователем] сохранено то, что успели загрузить.")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("ОШИБКА: ${e.message}")
            } finally {
                db.close()
                AnalyticsImportBus.setProgress(null)
                AnalyticsImportBus.finish()
                finishJob(startId)
            }
        }
    }

    private fun runAutoCatchUp(account: String, start: LocalDate, end: LocalDate, startId: Int) {
        val tokenStore = GarminTokenStore(application)
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
                val auth = GarminAuth(context = application, log = AnalyticsImportBus::appendLog)
                // См. ревью п.7: авто-обновление токена, если он истёк ПОСРЕДИ операции (а не
                // только в начале) - без этого поздние запросы за 401 тихо превращались в
                // null-поля выше по стеку (GarminActivitiesApi/GarminApi).
                auth.onTokenExpired = { old ->
                    runCatching { auth.refresh(old) }.getOrNull()?.also { tokenStore.save(account, it) }
                }
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
            } catch (_: ImportCancelledException) {
                AnalyticsImportBus.appendLog("[Авто-обновление] остановлено пользователем.")
                AnalyticsImportBus.refreshStats(application, account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("[Авто-обновление] ошибка: ${e.message}")
            } finally {
                db.close()
                AnalyticsImportBus.setProgress(null)
                AnalyticsImportBus.finish()
                finishJob(startId)
            }
        }
    }

    private fun runBuildReport(account: String, startId: Int) {
        serviceScope.launch {
            try {
                val dbFile = AnalyticsDb.dbFileForAccount(application, account)
                val dir = File(application.filesDir, "reports").apply { mkdirs() }
                val safe = account.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").ifBlank { "account" }
                // ИСПРАВЛЕНО (ревью п.16, таблица "имя файла кэша отчёта должно учитывать версию
                // приложения"): раньше имя файла кэша (report_$safe.html) не зависело от версии
                // приложения - кэш считался валидным, если он новее файла БД, независимо от
                // того, каким build_report.py (какой версией APK) он был собран. После
                // обновления приложения с изменённой логикой/вёрсткой отчёта (build_report.py)
                // пользователь видел СТАРЫЙ закэшированный HTML, пока БД не поменяется, хотя
                // сама логика сборки уже другая. Версия в имени файла делает кэш version-scoped:
                // после обновления APK предыдущий report_..._v<N>.html просто не совпадает по
                // имени - файл считается отсутствующим, и отчёт пересобирается автозаново.
                val file = File(dir, "report_${safe}_v${com.example.runstef.BuildConfig.VERSION_CODE}.html")
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
                AnalyticsImportBus.finish()
                finishJob(startId)
            }
        }
    }

    private fun runExportGarmin(
        account: String,
        planFile: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        testFirstWeek: Boolean,
        allDates: Boolean,
        fromDate: LocalDate?,
        startId: Int
    ) {
        val tokenStore = GarminTokenStore(application)
        AnalyticsImportBus.clearLog()
        AnalyticsImportBus.clearCancel()
        serviceScope.launch {
            try {
                updateNotification("Экспорт в Garmin: $account")
                val plan = withContext(Dispatchers.IO) {
                    PlanHtmlParser.extractPlan(File(planFile).readText())
                }
                val auth = GarminAuth(context = application, log = AnalyticsImportBus::appendLog)
                // См. ревью п.7: авто-обновление токена, если он истёк ПОСРЕДИ операции (а не
                // только в начале) - без этого поздние запросы за 401 тихо превращались в
                // null-поля выше по стеку (GarminActivitiesApi/GarminApi).
                auth.onTokenExpired = { old ->
                    runCatching { auth.refresh(old) }.getOrNull()?.also { tokenStore.save(account, it) }
                }
                var tokens = tokenStore.load(account)
                if (tokens != null && auth.isExpired(tokens)) {
                    AnalyticsImportBus.appendLog("Токен истёк, обновляю…")
                    tokens = try {
                        withContext(Dispatchers.IO) { auth.refresh(tokens) }
                    } catch (e: Exception) {
                        AnalyticsImportBus.appendLog("Не удалось обновить токен (${e.message}), нужен повторный вход.")
                        null
                    }
                    tokens?.let { tokenStore.save(account, it) }
                }
                if (tokens == null) {
                    throw RuntimeException("Нет действующего токена для аккаунта $account: добавь/перелогинь его через «+» у выбора аккаунта")
                }
                val api = GarminApi(auth, log = AnalyticsImportBus::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(
                        plan, tokens, skipCross, dryRun, testFirstWeek, allDates, fromDate,
                        isCancelled = { AnalyticsImportBus.cancelRequested.value },
                        onProgress = { done, total ->
                            val pct = if (total > 0) done * 100 / total else 0
                            AnalyticsImportBus.setProgress(pct)
                            updateNotification("Экспорт в Garmin: день $done из $total", pct)
                        }
                    )
                }
            } catch (_: ImportCancelledException) {
                AnalyticsImportBus.appendLog("Отменено пользователем.")
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("ОШИБКА: ${e.message}")
            } finally {
                AnalyticsImportBus.finish()
                finishJob(startId)
            }
        }
    }

    private fun runExportIntervals(
        planFile: String,
        skipCross: Set<String>,
        dryRun: Boolean,
        startId: Int
    ) {
        AnalyticsImportBus.clearLog()
        AnalyticsImportBus.clearCancel()
        serviceScope.launch {
            try {
                updateNotification("Экспорт в intervals.icu")
                val plan = withContext(Dispatchers.IO) {
                    PlanHtmlParser.extractPlan(File(planFile).readText())
                }
                // Ключ и ID атлета — из зашифрованного хранилища (ViewModel сохранил их перед запуском).
                val settings = com.example.runstef.data.SettingsStore(application)
                val apiKey = settings.getIntervalsApiKey()
                val athleteId = settings.getIntervalsAthlete()
                val api = IntervalsApi(apiKey, athleteId, log = AnalyticsImportBus::appendLog)
                withContext(Dispatchers.IO) {
                    api.upload(plan, skipCross, dryRun, isCancelled = { AnalyticsImportBus.cancelRequested.value })
                }
            } catch (_: ImportCancelledException) {
                AnalyticsImportBus.appendLog("Отменено пользователем.")
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("ОШИБКА: ${e.message}")
            } finally {
                AnalyticsImportBus.finish()
                finishJob(startId)
            }
        }
    }

    private fun finishJob(startId: Int) {
        activeJobs--
        if (activeJobs <= 0) {
            activeJobs = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            // stopSelf() без startId: после «Стоп» из уведомления или проигнорированного повторного
            // запуска последний startId уже не наш, и stopSelf(startId) не остановил бы сервис.
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        // minSdk = 28 (см. app/build.gradle.kts) > VERSION_CODES.O (26) — проверка версии здесь
        // всегда true, каналы уведомлений существуют на всех поддерживаемых версиях.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Импорт аналитики",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Загрузка тренировок из Garmin Connect, экспорт плана и сборка отчёта"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, percent: Int? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, AnalyticsImportService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (AnalyticsImportBus.operation.value == AnalyticsImportBus.Operation.EXPORT) "Экспорт плана" else "Аналитика Garmin"
            )
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel, "Стоп", cancelPendingIntent
                )
            )
        if (percent != null) {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    private var lastNotificationText = "Подготовка…"
    private var lastNotificationPercent: Int? = null

    private fun updateNotification(text: String, percent: Int? = null) {
        lastNotificationText = text
        lastNotificationPercent = percent
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, percent))
    }
}
