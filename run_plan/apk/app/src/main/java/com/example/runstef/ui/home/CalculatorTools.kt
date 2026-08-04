package com.example.runstef.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.runstef.data.HomeItem

/**
 * Fallback-URL «Калькулятора беговых планов» — используется в MainActivity для перехода
 * из «Мои планы» → «+» → «Создать план», если список инструментов из конфига (см.
 * [HomeViewModel]) ещё не загрузился или временно не содержит пункт "plan" (например,
 * конфиг с бэка недоступен и локального кэша ещё нет). Совпадает с url пункта "plan" в
 * assets/app_config.json — при обычной работе используется URL из конфига, не эта константа.
 */
const val DEFAULT_PLAN_URL = "https://ystef11.github.io/run/run_plan_calculator.html"

/** Инструмент экрана «Главная» — то же самое, что [HomeItem] с бэка, но с уже разрешённой иконкой. */
data class CalculatorTool(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector
)

/**
 * Иконку с бэка нельзя передать как есть — сервер шлёт строковый ключ (см. HomeItem.icon),
 * здесь он маппится на существующие Icons.*. Неизвестный ключ (например, конфиг с бэка выслал
 * иконку новее, чем умеет текущая версия приложения) — нейтральная заглушка, чтобы не крашиться.
 *
 * Ключи "run"/"bolt"/"speed"/"heart" — уже используемые инструменты (план/гели/темп/VO2max).
 * Ключи ниже ("zones", "route", "timer", "trophy", "nutrition", "terrain", "calendar",
 * "recovery", "weather") — задел на будущие инструменты сайта run/*.html: сервер сможет
 * указать один из них в поле "icon" конфига без правки кода приложения. Подбирайте ключ по
 * смыслу нового инструмента; если подходящего нет — сначала добавьте новую пару icon-key →
 * ImageVector сюда, а уже потом ссылайтесь на неё из конфига.
 */
fun iconForKey(key: String): ImageVector = when (key) {
    "run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "bolt" -> Icons.Filled.Bolt
    "speed" -> Icons.Filled.Speed
    "heart" -> Icons.Filled.MonitorHeart
    // Задел на будущее — новые типы инструментов сайта:
    "zones" -> Icons.Filled.Favorite // темп/усилие по пульсовым зонам (hr_pace)
    "route" -> Icons.Filled.Route // маршрут/дистанция забега
    "timer" -> Icons.Filled.Timer // интервальные тренировки
    "trophy" -> Icons.Filled.EmojiEvents // цель на старте, прогноз результата забега
    "nutrition" -> Icons.Filled.Restaurant // питание/нутришн, отдельно от гелей на дистанции
    "terrain" -> Icons.Filled.Terrain // трейл, набор высоты
    "calendar" -> Icons.Filled.CalendarMonth // календарь/расписание тренировок
    "recovery" -> Icons.Filled.Bedtime // восстановление, сон
    "weather" -> Icons.Filled.WbSunny // поправка темпа на погоду
    else -> Icons.Filled.Extension
}

fun HomeItem.toCalculatorTool(): CalculatorTool = CalculatorTool(
    id = id,
    title = title,
    subtitle = description,
    url = url,
    icon = iconForKey(icon)
)
