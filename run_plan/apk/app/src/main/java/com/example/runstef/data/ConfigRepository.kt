package com.example.runstef.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.Json
import com.example.runstef.network.NetworkModule
import com.example.runstef.network.ReleaseChecker
import com.example.runstef.network.ApkUpdater
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val configJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Жёстко закодированный fallback — состав «Главной» и версия приложения, который используется,
 * если по какой-то причине не удалось прочитать/разобрать assets/app_config.json (испорченный
 * файл, проблема при упаковке apk и т.п.). Совпадает с содержимым assets/app_config.json —
 * держите оба в синхронизации при добавлении нового инструмента вручную в код (обычно же новые
 * инструменты добавляются только через config.json на бэке, без пересборки — см. класс doc ниже).
 * Именно эта константа гарантирует, что [ConfigRepository.getEffectiveConfig] никогда не бросает
 * исключение — иначе сбой чтения assets на самом старте приложения (ещё до отрисовки экрана)
 * валил бы всё приложение в чёрный экран/краш.
 */
private val HARDCODED_FALLBACK = BundledConfig(
    version = "0.0.1",
    configUrl = "https://ystef11.github.io/run/android/config.json",
    home = HomeConfig(
        title = "Беговые инструменты",
        subtitle = "Работают офлайн после первого открытия",
        items = listOf(
            HomeItem(
                id = "plan",
                title = "Калькулятор беговых планов",
                description = "Прогноз цели и построение плана",
                icon = "run",
                url = "https://ystef11.github.io/run/run_plan_calculator.html"
            ),
            HomeItem(
                id = "gel",
                title = "Калькулятор гелей",
                description = "Питание на дистанции",
                icon = "bolt",
                url = "https://ystef11.github.io/run/gel_calculator.html"
            ),
            HomeItem(
                id = "pace",
                title = "Калькулятор темпа",
                description = "Пересчёт темпа и времени",
                icon = "speed",
                url = "https://ystef11.github.io/run/pace_calculator.html"
            ),
            HomeItem(
                id = "vo2max",
                title = "Тест Купера (VO2max)",
                description = "Оценка функциональной готовности",
                icon = "heart",
                url = "https://ystef11.github.io/run/vo2max_calculator.html"
            ),
            HomeItem(
                id = "hr_pace",
                title = "Темп по пульсовым зонам",
                description = "Соотношение зон пульса к диапазонам темпа",
                icon = "zones",
                url = "https://ystef11.github.io/run/hr_pace_calculator.html"
            ),
            HomeItem(
                id = "rank",
                title = "Калькулятор разряда по бегу",
                description = "Оценка VDOT и прогноз разряда по ЕВСК",
                icon = "rank",
                url = "https://ystef11.github.io/run/rank_calculator.html"
            ),
            HomeItem(
                id = "laps",
                title = "Калькулятор отсечек дистанции",
                description = "Промежуточные отметки, время и темп по дистанции",
                icon = "laps",
                url = "https://ystef11.github.io/run/laps_calculator.html"
            ),
            HomeItem(
                id = "weight",
                title = "Калькулятор веса и ИМТ",
                description = "ИМТ и влияние состава изменения веса на темп бега",
                icon = "weight",
                url = "https://ystef11.github.io/run/weight_calculator.html"
            )
        )
    )
)

/**
 * Источник конфигурации приложения: состав экрана «Главная» и данные для проверки обновлений.
 *
 * «Своя» версия приложения ([ownVersion]) всегда берётся из зашитого в apk assets/app_config.json
 * и никогда не перезаписывается тем, что приходит с бэка — обновляется вручную разработчиком при
 * новой сборке (см. BundledConfig.version). Если assets/app_config.json по любой причине не
 * прочитался/не разобрался — используется [HARDCODED_FALLBACK], а не исключение (см. его doc).
 *
 * Состав «Главной» и данные для обновления — из бэкенд-конфига (адрес — BundledConfig.configUrl):
 * при наличии интернета скачиваются и кэшируются в SharedPreferences; без интернета — берётся
 * последний кэш; если кэша ещё нет вовсе (самый первый запуск офлайн) — home из assets/app_config.json
 * (или из HARDCODED_FALLBACK), а проверка обновлений в этом случае не срабатывает до первого
 * выхода в сеть (update-блока нет).
 *
 * Отдельно — версия, которую пользователь явно «пропустил» в диалоге обновления ([getSkippedVersion]/
 * [setSkippedVersion]): в отличие от обычного отказа «Позже» (тот живёт только в памяти процесса,
 * см. HomeViewModel.updateDismissed), пропуск конкретной версии сохраняется на диск и переживает
 * перезапуск приложения — пока на бэке не появится версия новее пропущенной.
 */
class ConfigRepository(private val context: Context) {

    private val client = NetworkModule.baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cachePrefs get() = context.getSharedPreferences("app_config_cache", Context.MODE_PRIVATE)
    private val keyRemoteJson = "remote_config_json"
    private val keySkippedVersion = "skipped_version"

    // by lazy специально не используется здесь: если инициализация упадёт, обычный lazy будет
    // пытаться пересчитать значение при каждом обращении (и падать заново) — нам нужен ровно один
    // безопасный вызов при первом доступе с гарантированным результатом, без исключений.
    private var bundledCache: BundledConfig? = null

    private fun bundled(): BundledConfig {
        bundledCache?.let { return it }
        val loaded = runCatching {
            val text = context.assets.open("app_config.json").bufferedReader().use { it.readText() }
            configJson.decodeFromString(BundledConfig.serializer(), text)
        }.getOrDefault(HARDCODED_FALLBACK)
        bundledCache = loaded
        return loaded
    }

    // Своя версия — из сборки (app/build.gradle.kts, appVersionName), а не из app_config.json:
    // одно место, которое нельзя «забыть поднять» отдельно от самой сборки.
    val ownVersion: String get() = com.example.runstef.BuildConfig.VERSION_NAME

    fun getSkippedVersion(): String? = cachePrefs.getString(keySkippedVersion, null)

    fun setSkippedVersion(version: String) {
        cachePrefs.edit().putString(keySkippedVersion, version).apply()
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun cachedRemote(): RemoteConfig? {
        val text = cachePrefs.getString(keyRemoteJson, null) ?: return null
        return runCatching { configJson.decodeFromString(RemoteConfig.serializer(), text) }.getOrNull()
    }

    /**
     * Выполняет блокирующий сетевой запрос при наличии интернета — вызывать из фонового
     * потока/корутины (Dispatchers.IO), как и аналогичные loadHtml/shouldInterceptRequest
     * в network/PlanUrlLoader и network/OfflineCacheWebViewClient.
     *
     * Гарантированно не бросает исключений — любая ошибка на любом шаге (сеть, разбор JSON,
     * чтение assets) приводит к откату на следующий по надёжности источник данных, а не к краху.
     * Не учитывает [getSkippedVersion]/dismissed-состояние UI — это чисто вопрос данных
     * (что сейчас актуально на бэке), решение показывать ли диалог принимает вызывающий код
     * (см. MainActivity/HomeViewModel), в т.ч. ручная проверка «Проверить обновления» в настройках
     * специально дёргает этот же метод, чтобы бэк проверялся независимо от пропущенных версий.
     */
    fun getEffectiveConfig(): EffectiveConfig {
        val bundled = bundled()
        val online = isOnline()
        var home: HomeConfig? = null
        var legacyUpdate: UpdateInfo? = null
        if (online) {
            try {
                client.newCall(Request.Builder().url(bundled.configUrl).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val text = response.body?.string()
                        if (text != null) {
                            // Валидируем перед сохранением в кэш — битый ответ не должен затирать
                            // последний рабочий кэш.
                            val remote = configJson.decodeFromString(RemoteConfig.serializer(), text)
                            cachePrefs.edit().putString(keyRemoteJson, text).apply()
                            home = remote.home
                            legacyUpdate = remote.update
                        }
                    }
                }
            } catch (e: Exception) {
                // сеть есть, но запрос не удался или ответ битый — падаем на кэш/встроенный конфиг ниже
            }
        }
        if (home == null) {
            cachedRemote()?.let { home = it.home; legacyUpdate = it.update }
        }

        // Обновление — из GitHub Releases (заполняется само при публикации релиза). Последний
        // успешный ответ кэшируем, чтобы офлайн/при лимите API диалог работал по последним
        // известным данным. Блок update из config.json — только запасной вариант.
        val release = if (online) runCatching { ReleaseChecker.latest() }.getOrNull() else null
        if (release != null) {
            cachePrefs.edit().putString(keyRelease, configJson.encodeToString(UpdateInfo.serializer(), release)).apply()
        }
        val update = release ?: cachedRelease() ?: legacyUpdate

        return EffectiveConfig(
            ownVersion = ownVersion,
            home = home ?: bundled.home,
            update = update,
            updateAvailable = update != null && isUpdateAvailable(update)
        )
    }

    /**
     * Обновление есть, только если номер версии релиза строго больше установленного —
     * иначе, если установлена локальная сборка (например 0.0.6), а на GitHub всё ещё лежит
     * более старый релиз (0.0.5), sha256 неизбежно отличается и раньше это ошибочно
     * предлагалось как обновление. Сравнение по sha256 используется только как ДОПОЛНИТЕЛЬНАЯ
     * проверка внутри той же версии (например, релиз пересобрали с тем же tag_name) — само по
     * себе несовпадение хэша обновление не включает.
     */
    private fun isUpdateAvailable(update: UpdateInfo): Boolean {
        if (VersionCompare.isNewer(update.latestVersion, ownVersion)) return true
        if (VersionCompare.isNewer(ownVersion, update.latestVersion)) return false
        val remote = update.sha256?.trim()
        if (!remote.isNullOrEmpty()) {
            val local = ApkUpdater.installedSha256(context)
            if (local != null) return !remote.equals(local, ignoreCase = true)
        }
        return false
    }

    private val keyRelease = "latest_release_json"

    private fun cachedRelease(): UpdateInfo? {
        val text = cachePrefs.getString(keyRelease, null) ?: return null
        return runCatching { configJson.decodeFromString(UpdateInfo.serializer(), text) }.getOrNull()
    }
}
