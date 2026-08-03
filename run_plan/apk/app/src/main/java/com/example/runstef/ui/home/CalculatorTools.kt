package com.example.runstef.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector

private const val BASE_URL = "https://ystef11.github.io/run/"

/** Только общедоступные инструменты сайта — раздел «Личный кабинет» намеренно не переносится в приложение. */
data class CalculatorTool(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector
)

val calculatorTools = listOf(
    CalculatorTool(
        id = "plan",
        title = "Калькулятор беговых планов",
        subtitle = "Прогноз цели и построение плана",
        url = BASE_URL + "run_plan_calculator.html",
        icon = Icons.AutoMirrored.Filled.DirectionsRun
    ),
    CalculatorTool(
        id = "gel",
        title = "Калькулятор гелей",
        subtitle = "Питание на дистанции",
        url = BASE_URL + "gel_calculator.html",
        icon = Icons.Filled.Bolt
    ),
    CalculatorTool(
        id = "pace",
        title = "Калькулятор темпа",
        subtitle = "Пересчёт темпа и времени",
        url = BASE_URL + "pace_calculator.html",
        icon = Icons.Filled.Speed
    ),
    CalculatorTool(
        id = "vo2max",
        title = "Тест Купера (VO2max)",
        subtitle = "Оценка функциональной готовности",
        url = BASE_URL + "vo2max_calculator.html",
        icon = Icons.Filled.MonitorHeart
    )
)
