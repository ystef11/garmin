package com.example.runstef.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
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
 */
fun iconForKey(key: String): ImageVector = when (key) {
    "run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "bolt" -> Icons.Filled.Bolt
    "speed" -> Icons.Filled.Speed
    "heart" -> Icons.Filled.MonitorHeart
    else -> Icons.Filled.Extension
}

fun HomeItem.toCalculatorTool(): CalculatorTool = CalculatorTool(
    id = id,
    title = title,
    subtitle = description,
    url = url,
    icon = iconForKey(icon)
)
