package com.example.runstef.network

import android.webkit.WebView

/**
 * Включает пинч-зум для WebView — общее для всех экранов с WebView (калькуляторы в
 * ToolWebViewScreen, просмотр плана в PlanViewScreen, отчёт аналитики в AnalyticsReportScreen).
 *
 * builtInZoomControls нужен, чтобы масштаб вообще можно было менять (без него setSupportZoom
 * ничего не даёт — жест pinch не работает); displayZoomControls=false прячет старые +/- кнопки
 * в углу экрана (жест pinch их не требует, а на калькуляторах они перекрывали интерфейс).
 * useWideViewPort/loadWithOverviewMode — чтобы страницы, не подгоняющие вёрстку под мобильный
 * экран (например простые сгенерированные HTML-отчёты без <meta viewport>), сразу открывались
 * целиком, а не с одним огромным зумом на левый верхний угол.
 */
fun WebView.enableZoom() {
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}
