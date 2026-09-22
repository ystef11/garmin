package com.example.runstef.ui.analytics

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.SettingsStore
import com.example.runstef.network.garmin.GarminTokenStore
import com.example.runstef.service.AnalyticsImportBus
import com.example.runstef.service.AnalyticsImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * ВАЖНО: с этой правки сама долгая работа (сеть Garmin + запись в БД + сборка отчёта) больше
 * НЕ выполняется в viewModelScope этого класса - она перенесена в foreground-сервис
 * [AnalyticsImportService], т.к. Android прерывал загрузку при сворачивании приложения или
 * блокировке экрана (см. диалог 2026-08-23). Этот ViewModel теперь только:
 * 1) запускает сервис нужным Intent'ом (importActivities/autoCatchUp/buildReport);
 * 2) отдаёт наружу StateFlow из [AnalyticsImportBus] - общего состояния, которое обновляет
 *    сервис и которое переживает пересоздание самого ViewModel.
 */
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Множество аккаунтов, для которых автокатч-ап уже выполнялся с момента запуска
        // процесса приложения. Обычный instance-флаг во ViewModel не подходит: экран
        // Аналитики в MainActivity открывается через Navigation-Compose БЕЗ saveState/
        // restoreState (см. NavigationBarItem.onClick), поэтому при каждом переключении
        // вкладок ViewModel этого экрана пересоздаётся заново. Companion-object живёт,
        // пока жив процесс приложения - то есть ровно "один раз за запуск приложения",
        // как и просил пользователь, а не один раз за каждое открытие вкладки.
        private val autoCatchUpDoneForAccount = mutableSetOf<String>()
    }

    private val app get() = getApplication<Application>()
    private val tokenStore = GarminTokenStore(app)
    private val settings = SettingsStore(app)

    val log: StateFlow<List<String>> = AnalyticsImportBus.log
    val isRunning: StateFlow<Boolean> = AnalyticsImportBus.isRunning
    /** Процент выполнения текущего импорта (0..100) - null, пока процент неизвестен (сборка
     * отчёта, самое начало импорта) или ничего не выполняется; тогда UI показывает обычный
     * неопределённый троббер вместо числа. */
    val progress: StateFlow<Int?> = AnalyticsImportBus.progress
    val lastActivityDate: StateFlow<String?> = AnalyticsImportBus.lastActivityDate
    val firstActivityDate: StateFlow<String?> = AnalyticsImportBus.firstActivityDate
    val activityCount: StateFlow<Int> = AnalyticsImportBus.activityCount
    val reportPath: StateFlow<String?> = AnalyticsImportBus.reportPath

    fun savedGarminAccounts(): List<String> = tokenStore.savedAccounts()

    fun hasSavedGarminToken(account: String): Boolean =
        account.isNotBlank() && tokenStore.savedAccounts().contains(account.trim().lowercase())

    suspend fun lastUsedAccount(): String = settings.getLastGarminAccount()

    /** Обновляет и дату последней тренировки, и статистику по базе (диапазон дат/количество) —
     * вызывается после каждого импорта и при выборе/открытии аккаунта (см. AnalyticsScreen). */
    fun refreshLastActivityDate(account: String) {
        // Явное переключение/открытие аккаунта на экране -- отмечаем его как "текущий" в шине
        // ДО чтения статистики, иначе завершение фонового импорта другого, уже покинутого
        // аккаунта могло бы затереть показанные здесь данные (см. AnalyticsImportBus.
        // setCurrentAccount / refreshStats).
        AnalyticsImportBus.setCurrentAccount(account)
        AnalyticsImportBus.refreshStats(app, account)
    }

    /**
     * Импорт базы аналитики целиком из файла на диске (выбирается системным файловым
     * менеджером, см. AnalyticsScreen -- тот же UploadFile-паттерн, что и импорт HTML-плана в
     * PlansScreen). Полностью ЗАМЕНЯЕТ текущую локальную SQLite-базу этого аккаунта файлом,
     * выбранным пользователем -- например, скопированной с компьютера базой garmin_<account>.db
     * (та же схема таблиц, что и в apk, если экспортирована этим же приложением на другом
     * устройстве, или совместимая внешняя копия). Проверяет только заголовок SQLite (первые 16
     * байт файла) -- не валидирует схему таблиц: если файл не той структуры, следующий же
     * запрос к БД в приложении упадёт с понятной ошибкой SQLite, а не тихо покажет пустой отчёт.
     * Это быстрая локальная копия файла (не сеть) -- отдельного foreground-сервиса не требует.
     */
    fun importDbFromUri(account: String, uri: android.net.Uri) {
        if (account.isBlank()) {
            AnalyticsImportBus.appendLog("Не выбран аккаунт - импорт базы отменён.")
            return
        }
        // ИСПРАВЛЕНО: раньше файл копировался СРАЗУ поверх рабочей базы аккаунта. Если
        // импортируемый файл был десктопной базой с user_version=0 (см. AnalyticsDb.DB_VERSION
        // -- десктопный экспортёр его не проставляет), SQLiteOpenHelper при следующем открытии
        // считал её "новой" и вызывал onCreate() поверх уже существующих таблиц -> "table
        // activities already exists", а прежняя рабочая база к этому моменту была уже
        // безвозвратно перезаписана. Теперь копируем во временный файл, проверяем и при
        // необходимости чиним его user_version, а затем пробуем ПОЛНОСТЬЮ открыть его через тот
        // же SQLiteOpenHelper, что и обычную базу -- и заменяем рабочий файл только если это
        // прошло без ошибок. Также блокируем операцию, пока идёт другой импорт/сборка отчёта
        // (см. AnalyticsImportBus.isRunning -- иначе можно подменить базу прямо во время её
        // чтения сервисом).
        if (AnalyticsImportBus.isRunning.value) {
            AnalyticsImportBus.appendLog("Уже выполняется другая операция аналитики - подождите её завершения.")
            return
        }
        AnalyticsImportBus.setRunning(true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val destFile = AnalyticsDb.dbFileForAccount(app, account)
                    destFile.parentFile?.mkdirs()
                    val tempFile = File(destFile.parentFile, destFile.name + ".importing")
                    File(tempFile.path + "-wal").delete()
                    File(tempFile.path + "-shm").delete()
                    File(tempFile.path + "-journal").delete()
                    try {
                        val stream = app.contentResolver.openInputStream(uri)
                            ?: throw RuntimeException("Не удалось открыть выбранный файл")
                        stream.use { input ->
                            val header = ByteArray(16)
                            var read = 0
                            while (read < 16) {
                                val n = input.read(header, read, 16 - read)
                                if (n < 0) break
                                read += n
                            }
                            val headerText = String(header, Charsets.ISO_8859_1)
                            if (read < 16 || !headerText.startsWith("SQLite format 3")) {
                                throw RuntimeException("Файл не похож на базу SQLite (неверный заголовок)")
                            }
                            tempFile.outputStream().use { out ->
                                out.write(header, 0, read)
                                input.copyTo(out)
                            }
                        }

                        // Если во временном файле уже есть таблица activities (обычный случай
                        // для десктопной базы или экспорта с другого устройства), но
                        // user_version не проставлен как у apk -- считаем схему уже актуальной
                        // (см. project memory: "с v6 схема ПОЛНОСТЬЮ приведена к десктопной") и
                        // просто выставляем правильный user_version, чтобы SQLiteOpenHelper НЕ
                        // вызывал onCreate/onUpgrade поверх существующих таблиц. Если таблиц нет
                        // (пустой/новый файл) -- оставляем как есть, onCreate отработает как
                        // обычно.
                        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                            tempFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                        )
                        try {
                            val hasActivitiesTable = raw.rawQuery(
                                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='activities'", null
                            ).use { it.moveToFirst() }
                            if (hasActivitiesTable && raw.version < AnalyticsDb.DB_VERSION) {
                                raw.version = AnalyticsDb.DB_VERSION
                            }
                        } finally {
                            raw.close()
                        }

                        // Пробное полное открытие через тот же путь, что использует остальное
                        // приложение (запускает onCreate/onUpgrade, если они всё-таки нужны, и
                        // сразу проявит любую несовместимость схемы -- здесь, а не после замены
                        // рабочей базы).
                        val check = AnalyticsDb.openForImportCheck(app, tempFile)
                        try {
                            check.lastActivityDate()
                        } finally {
                            check.close()
                        }

                        // Только теперь заменяем рабочий файл аккаунта.
                        File(destFile.path + "-wal").delete()
                        File(destFile.path + "-shm").delete()
                        File(destFile.path + "-journal").delete()
                        if (!tempFile.renameTo(destFile)) {
                            tempFile.copyTo(destFile, overwrite = true)
                        }
                    } finally {
                        tempFile.delete()
                        File(tempFile.path + "-wal").delete()
                        File(tempFile.path + "-shm").delete()
                        File(tempFile.path + "-journal").delete()
                    }
                }
                AnalyticsImportBus.appendLog("База аналитики импортирована из файла для аккаунта $account.")
                refreshLastActivityDate(account)
            } catch (e: Exception) {
                AnalyticsImportBus.appendLog("Ошибка импорта базы: ${e.message} (рабочая база аккаунта не изменена)")
            } finally {
                AnalyticsImportBus.setRunning(false)
            }
        }
    }

    /** Запускает foreground-сервис на импорт указанного периода (см. class doc выше). */
    fun importActivities(
        account: String,
        startDate: LocalDate,
        endDate: LocalDate,
        withWellness: Boolean,
        forceRefreshWellness: Boolean
    ) {
        if (AnalyticsImportBus.isRunning.value) return
        val intent = Intent(app, AnalyticsImportService::class.java).apply {
            action = AnalyticsImportService.ACTION_IMPORT
            putExtra(AnalyticsImportService.EXTRA_ACCOUNT, account)
            putExtra(AnalyticsImportService.EXTRA_START, startDate.toString())
            putExtra(AnalyticsImportService.EXTRA_END, endDate.toString())
            putExtra(AnalyticsImportService.EXTRA_WITH_WELLNESS, withWellness)
            putExtra(AnalyticsImportService.EXTRA_FORCE_REFRESH, forceRefreshWellness)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /** Тихое авто-обновление при открытии вкладки — только если для аккаунта уже есть непустая
     * база (см. AnalyticsScreen: если базы ещё нет, ждём ручной первой загрузки с явным периодом).
     * Быстрая проверка (есть ли уже данные, актуален ли диапазон) делается тут же синхронно —
     * сама сетевая догрузка идёт через foreground-сервис (см. class doc выше). */
    fun autoCatchUp(account: String) {
        if (AnalyticsImportBus.isRunning.value || account.isBlank()) return
        if (!autoCatchUpDoneForAccount.add(account)) return
        val db = AnalyticsDb.open(app, account)
        val last = db.lastActivityDate()
        db.close()
        if (last == null) return
        val lastDate = runCatching { LocalDate.parse(last) }.getOrNull() ?: return
        val start = lastDate.minusDays(2)
        val end = LocalDate.now()
        if (start.isAfter(end)) return
        val intent = Intent(app, AnalyticsImportService::class.java).apply {
            action = AnalyticsImportService.ACTION_AUTO_CATCH_UP
            putExtra(AnalyticsImportService.EXTRA_ACCOUNT, account)
            putExtra(AnalyticsImportService.EXTRA_START, start.toString())
            putExtra(AnalyticsImportService.EXTRA_END, end.toString())
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /** Запускает foreground-сервис на сборку HTML-отчёта (см. class doc выше). */
    fun buildReport(account: String) {
        val intent = Intent(app, AnalyticsImportService::class.java).apply {
            action = AnalyticsImportService.ACTION_BUILD_REPORT
            putExtra(AnalyticsImportService.EXTRA_ACCOUNT, account)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    fun consumeReportPath() { AnalyticsImportBus.setReportPath(null) }
}
