package com.example.runstef.data

import kotlinx.serialization.builtins.serializer

/**
 * Разбирает автономный HTML-файл плана (кнопка «Скачать HTML плана» в run_plan_calculator.html).
 *
 * Внутри такого HTML есть скрипт с одним из двух вариантов встраивания плана:
 *   window.__PLAN__=JSON.parse("{\"meta\":{...},\"workouts\":[...]}");           — текущий формат
 *   const PLAN_M315=JSON.parse("{\"meta\":{...},\"workouts\":[...]}");           — старый формат
 * (старые страницы, размещённые до перехода генератора на window.__PLAN__, например
 * marathon_315_plan.html/wife_10k_plan.html — они не будут перегенерированы, поэтому парсер
 * должен понимать оба варианта).
 * Содержимое в кавычках — валидный JS/JSON строковый литерал (JSON.stringify от JSON-текста
 * плана), т.е. его нужно раскодировать дважды: сначала как JSON-строку (снять экранирование),
 * затем полученный текст — как сам план (RunPlan).
 */
object PlanHtmlParser {

    // Совпадает "window.__PLAN__=JSON.parse(" ИЛИ "const PLAN_XXX=JSON.parse(" + строковый литерал
    // в кавычках + ");". Группа 1 — префикс объявления (сохраняется как есть при пересборке, чтобы
    // не поломать остальной JS страницы, который может ссылаться на старое имя переменной),
    // группа 2 — сам литерал. Литерал разбирается escape-aware: любой символ, кроме кавычки/бэкслэша,
    // либо экранированная пара (бэкслэш + любой символ) — так `\"` внутри не обрывает совпадение раньше времени.
    private val PLAN_MARKER_RE = Regex(
        "((?:window\\.__PLAN__|const\\s+PLAN_[A-Za-z0-9_]+)\\s*=\\s*JSON\\.parse\\(\")((?:[^\"\\\\]|\\\\.)*)(\"\\))",
        RegexOption.DOT_MATCHES_ALL
    )

    /** true, если это похоже на HTML-план, сгенерированный калькулятором (текущий или старый формат). */
    fun looksLikePlanHtml(html: String): Boolean = PLAN_MARKER_RE.containsMatchIn(html)

    /** Извлекает RunPlan из HTML-файла плана. Бросает исключение, если маркер не найден или JSON битый. */
    fun extractPlan(html: String): RunPlan {
        val match = PLAN_MARKER_RE.find(html)
            ?: throw IllegalArgumentException("В HTML не найден встроенный план (window.__PLAN__)")
        val escapedLiteral = match.groupValues[2]
        // Оборачиваем обратно в кавычки и декодируем как JSON-строку (снимаем экранирование \", < и т.п.)
        val planText = planJson.decodeFromString(String.serializer(), "\"$escapedLiteral\"")
        return planJson.decodeFromString(RunPlan.serializer(), planText)
    }

    /**
     * Возвращает HTML с заменённым встроенным планом на [plan] — используется для переименования
     * сохранённого плана («Мои планы»): остальная разметка страницы (заголовок, таблица
     * тренировок и т.п.) не трогается, меняются только машиночитаемые метаданные. Префикс
     * объявления (window.__PLAN__ или const PLAN_XXX) сохраняется таким, каким он был в исходном
     * файле — это важно для старых страниц, где остальной JS может ссылаться на имя переменной.
     */
    fun withPlan(html: String, plan: RunPlan): String {
        val match = PLAN_MARKER_RE.find(html)
            ?: throw IllegalArgumentException("В HTML не найден встроенный план (window.__PLAN__)")
        val prefix = match.groupValues[1]
        val suffix = match.groupValues[3]
        val planText = planJson.encodeToString(RunPlan.serializer(), plan)
        // Экранируем как JSON-строку (те же правила, что и при разборе) и убираем внешние кавычки,
        // которые PLAN_MARKER_RE добавляет сам.
        val encoded = planJson.encodeToString(String.serializer(), planText)
        // "<" экранируем в < — как в run_plan_calculator.html при первой генерации,
        // чтобы случайное "</script" внутри текста плана не оборвало встраивающий <script> раньше времени.
        val escapedLiteral = encoded.removePrefix("\"").removeSuffix("\"").replace("<", "\\u003c")
        // Собираем результат по индексам (а не через Regex.replaceFirst с шаблоном-строкой),
        // чтобы символы вроде "$" в тексте плана не интерпретировались как ссылки на группы.
        return html.substring(0, match.range.first) +
            prefix + escapedLiteral + suffix +
            html.substring(match.range.last + 1)
    }

    /** Возвращает HTML с обновлённым именем плана (meta.name) — обёртка над [withPlan]. */
    fun withPlanName(html: String, newName: String): String {
        val plan = extractPlan(html)
        return withPlan(html, plan.copy(meta = plan.meta.copy(name = newName)))
    }
}
