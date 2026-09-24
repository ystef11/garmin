package com.example.runstef.ui.home

import android.content.Context
import com.example.runstef.data.AnalyticsDb
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.UserProfileStore
import com.example.runstef.data.VdotMath
import com.example.runstef.network.garmin.GarminTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Собирает для калькуляторов plan/hr_pace/weight/rank URL с query-параметрами, заполненными из
 * локального профиля пользователя (см. UserProfileStore) и локальной базы аналитики Гармин
 * ОСНОВНОГО аккаунта (см. AnalyticsDb, GarminTokenStore.primaryAccount()) — чтобы при открытии калькулятора из приложения не
 * приходилось вводить эти данные заново (см. project doc "Расположение артефактов" в claude.ai
 * /Бег — задача синхронизации калькуляторов run/ и аналитики garmin/run_plan/apk/).
 *
 * Параметры разбираются в JS каждого калькулятора (ищите функцию applyPrefill() в конце
 * <script>) и ничего не меняют при обычном открытии файла в браузере без параметров — поля
 * остаются обычными и редактируемыми, это ТОЛЬКО удобный дефолт.
 *
 * Ключи query-параметров синхронизированы вручную с HTML/JS-стороной калькуляторов в run/: age, sex,
 * height, weight, pano, hrmax, curvol, dist, t, res=dist:sec, dist:sec, ... Меняя один конец (эту
 * функцию или applyPrefill() в HTML), проверяйте оба — тесты этого не ловят.
 *
 * ВАЖНО про то, чего аналитика НЕ содержит (см. AnalyticsDb — в схеме таких колонок просто нет):
 * возраст, пол, рост и текущий вес Гармин через используемое API не отдаёт вообще — эти четыре
 * поля берутся ИСКЛЮЧИТЕЛЬНО из UserProfileStore (пользователь вводит их один раз сам). Всё
 * остальное (ПАНО, HRmax, недельный объём, лучшие результаты по дистанциям) — из локальной базы.
 */
object ToolUrlBuilder {

    private val PREFILLABLE_IDS = setOf("plan", "hr_pace", "weight", "rank", "gel")

    suspend fun build(context: Context, tool: CalculatorTool): String {
        if (tool.id !in PREFILLABLE_IDS) return tool.url
        if (tool.id == "gel") {
            return withContext(Dispatchers.IO) {
                runCatching { buildGel(context, tool) }.getOrDefault(tool.url)
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching { buildInternal(context, tool) }.getOrDefault(tool.url)
        }
    }

    /**
     * URL калькулятора геля (run/gel_calculator.html) с параметрами carbs (суммарные углеводы,
     * г, на сегодняшнюю тренировку) и portion (размер покупной порции геля в граммах — 20 или
     * 40, см. fuelFor() в run_plan_calculator.html) — ТОЛЬКО когда в основном плане («Мои
     * планы» → сделан основным, см. PlanRepository.getDefaultPlan()/setDefaultPlan()) есть
     * тренировка на сегодня и у неё есть питание (см. PlanHtmlParser.fuelTotalGrams()). Ключи
     * carbs/portion — стабильные, не завязанные на текст кнопок/лейблов калькулятора: разбираются
     * в applyPrefill() в конце <script> в run/gel_calculator.html, меняйте оба конца вместе.
     *
     * ИСПРАВЛЕНО (ревью п.19 "Несовпадение плана и калькулятора геля"): раньше передавался
     * только carbs — калькулятор геля делил его на СВОЙ дефолтный размер порции (25 г), который
     * не совпадал с размером, из которого план на самом деле посчитал число гелей (20 или 40 г,
     * см. PlanFuel.portion в PlanModels.kt) — итоговое число порций в калькуляторе расходилось с
     * числом гелей, показанным в самом плане. Теперь передаётся и portion, а applyPrefill в
     * gel_calculator.html сначала выставляет размер порции (setCarb(portion)), и только потом
     * считает число порций от него — то же число, что и в плане.
     */
    private fun buildGel(context: Context, tool: CalculatorTool): String {
        val workout = PlanRepository(context).getTodayWorkout() ?: return tool.url
        val carbs = PlanHtmlParser.fuelTotalGrams(workout) ?: return tool.url
        val portion = PlanHtmlParser.fuelPortionGrams(workout)
        // ИСПРАВЛЕНО (ревью п.9 "Калькуляторы с автозаполнением не открываются офлайн, личные
        // данные уходят на GitHub"): параметр — после "#" (URL fragment), а не в query-строке.
        // Fragment НЕ уходит на сервер при загрузке страницы (значит не попадает в логи
        // GitHub Pages) и не входит в ключ офлайн-кэша WebView (см. OfflineCacheWebViewClient —
        // кэш ключуется по полному URL; с query каждое изменение прилетающих данных давало
        // новый ключ, и калькулятор переставал открываться офлайн).
        return if (portion != null) "${tool.url}#carbs=$carbs&portion=$portion" else "${tool.url}#carbs=$carbs"
    }

    private fun buildInternal(context: Context, tool: CalculatorTool): String {
        val profile = UserProfileStore(context)
        // Данные в калькуляторы подставляются из ОСНОВНОГО аккаунта Garmin (может быть сохранено
        // несколько аккаунтов — см. AccountManagementDialog/GarminTokenStore.primaryAccount()),
        // а не из того, что последним открывали на вкладке "Аналитика" (SettingsStore.
        // lastGarminAccount — отдельная настройка для вкладки "Экспорт", к этому отношения не имеет).
        val account = GarminTokenStore(context).primaryAccount()

        val params = LinkedHashMap<String, String>()
        profile.getAge()?.let { params["age"] = it.toString() }
        profile.getSex()?.let { params["sex"] = it }
        profile.getHeightCm()?.let { params["height"] = it.toString() }
        profile.getWeightKg()?.let { params["weight"] = trimNum(it) }

        var bestEfforts: Map<Double, Pair<Double, String>> = emptyMap()
        if (account.isNotBlank()) {
            val dbFile = AnalyticsDb.dbFileForAccount(context, account)
            if (dbFile.exists()) {
                AnalyticsDb.open(context, account).use { db ->
                    if (db.activityCount() > 0) {
                        db.panoFromDb()?.let { params["pano"] = it.toString() }
                        val hrmax = db.maxHrFromDb()
                        if (hrmax > 0) params["hrmax"] = hrmax.toString()
                        if (tool.id == "plan") {
                            db.recentWeeklyVolumeKm()?.let { params["curvol"] = trimNum(it) }
                        }
                        bestEfforts = db.bestEffortsByAnchor()
                    }
                }
            }
        }

        when (tool.id) {
            "plan" -> {
                val anchors = doubleArrayOf(5000.0, 10000.0, 21097.0, 42195.0)
                // .toList() — у примитивного DoubleArray нет mapNotNull() напрямую (в отличие
                // от Iterable/Array<T>), только map()/mapIndexed(), поэтому сначала приводим к List.
                val res = anchors.toList().mapNotNull { a ->
                    val src = bestEfforts[if (a == 21097.0) 21097.5 else a] ?: return@mapNotNull null
                    val sec = VdotMath.timeFor(a, src.first).toLong()
                    "${a.toLong()}:$sec"
                }
                if (res.isNotEmpty()) params["res"] = res.joinToString(",")
            }
            "rank" -> {
                val res = VdotMath.ANCHOR_DISTANCES_M.toList().mapNotNull { a ->
                    val src = bestEfforts[a] ?: return@mapNotNull null
                    val sec = VdotMath.timeFor(a, src.first).toLong()
                    "${trimNum(a)}:$sec"
                }
                if (res.isNotEmpty()) params["res"] = res.joinToString(",")
            }
            "hr_pace", "weight" -> {
                // одиночный результат — берём лучший (по VDOT) из всех найденных на любой дистанции
                val bestEntry = bestEfforts.entries.maxByOrNull { it.value.first }
                if (bestEntry != null) {
                    val distRounded = bestEntry.key.toLong()
                    params["dist"] = distRounded.toString()
                    params["t"] = VdotMath.timeFor(distRounded.toDouble(), bestEntry.value.first).toLong().toString()
                }
            }
        }

        if (params.isEmpty()) return tool.url
        // См. объяснение в buildGel() выше — те же причины (офлайн-кэш WebView, приватность
        // логов GitHub Pages), только параметров тут больше.
        val frag = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "${tool.url}#$frag"
    }

    private fun trimNum(v: Double): String {
        val r = kotlin.math.round(v * 10.0) / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
